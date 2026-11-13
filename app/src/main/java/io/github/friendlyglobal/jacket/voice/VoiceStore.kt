package io.github.friendlyglobal.jacket.voice

import java.io.File
import java.security.MessageDigest

/**
 * The natural voices downloaded to this phone, one folder per pack under [root]. On the phone
 * [root] is in Android's no-backup folder: a voice is 30 to 150 MB that can always be downloaded
 * again, so it goes into neither Android's backups nor Jacket's own backup files.
 *
 * A file is only ever used once it is whole and its SHA-256 is the catalog's. Until then it is a
 * partial file ("vocoder.int8.onnx.e923d60f53f9.part", named for what it is meant to become), which
 * a download carries on from after a failure or after Android has closed Jacket. Once it checks
 * out, a note of its checksum is written beside it and it is moved into place in one step, so a
 * file under its own name is always a checked one.
 *
 * Only plain files: no network here (see [VoiceDownload]), and nothing Android-specific, so it can
 * be tried on a computer.
 */
internal class VoiceStore(private val root: File) {

    fun folder(pack: VoicePack): File = File(root, pack.id)

    /** Where [file] is kept once it has checked out. */
    fun placeOf(pack: VoicePack, file: VoiceFile): File = File(folder(pack), file.name)

    /** Where [file] is downloaded to until it is whole and checked. */
    fun partOf(pack: VoicePack, file: VoiceFile): File = File(folder(pack), "${file.name}.${file.sha256.take(12)}.part")

    /** The note that [file] checked out: its SHA-256. */
    private fun noteOf(pack: VoicePack, file: VoiceFile): File = File(folder(pack), "${file.name}.sha256")

    /** Whether [file] is in place and checked. (Only its size is looked at again: its checksum was, when it arrived.) */
    fun isChecked(pack: VoicePack, file: VoiceFile): Boolean {
        val place = placeOf(pack, file)
        val note = noteOf(pack, file)
        return place.isFile && place.length() == file.bytes && note.isFile &&
            runCatching { note.readText().trim() }.getOrNull() == file.sha256
    }

    /** Whether all of [pack]'s files are here and checked: it can speak. */
    fun isReady(pack: VoicePack): Boolean = pack.downloadable && pack.downloads.all { isChecked(pack, it) }

    /** How many of [pack]'s bytes are here already, checked or still arriving. */
    fun bytesHere(pack: VoicePack): Long = pack.downloads.sumOf { file ->
        if (isChecked(pack, file)) file.bytes else partOf(pack, file).length().coerceAtMost(file.bytes)
    }

    /** How many bytes are still to come for [pack]. */
    fun bytesToGo(pack: VoicePack): Long = (pack.bytes - bytesHere(pack)).coerceAtLeast(0L)

    /** Free room where the voices are kept, in bytes. */
    fun freeBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    /**
     * Gets [pack]'s folder ready for a download: made if need be, and cleared of anything that
     * isn't one of its files, a partial one or a note (what an older catalog left, say).
     */
    fun prepare(pack: VoicePack) {
        val folder = folder(pack)
        folder.mkdirs()
        val wanted = pack.downloads.flatMap { listOf(it.name, partOf(pack, it).name, noteOf(pack, it).name) }.toSet()
        folder.listFiles()?.filter { it.name !in wanted }?.forEach { it.deleteRecursively() }
        // A file in place that no longer checks out (the catalog has a new one by that name) goes too.
        for (file in pack.downloads) {
            if (!isChecked(pack, file)) {
                placeOf(pack, file).delete()
                noteOf(pack, file).delete()
            }
        }
    }

    /**
     * Takes the downloaded [file] into use, if it is whole and its SHA-256 is the catalog's: its
     * note is written and it is moved into place. False if it isn't, and then it is deleted: a
     * damaged file is never kept, and the next try starts it afresh.
     */
    fun accept(pack: VoicePack, file: VoiceFile): Boolean {
        val part = partOf(pack, file)
        if (!part.isFile || part.length() != file.bytes || sha256(part) != file.sha256) {
            part.delete()
            return false
        }
        // The note first, then the move: a file under its own name has always been checked.
        val place = placeOf(pack, file)
        place.delete()
        val note = noteOf(pack, file)
        val writing = File(note.parentFile, note.name + ".new")
        writing.writeText(file.sha256)
        check(writing.renameTo(note)) { "Couldn't keep the note for ${file.name}" }
        check(part.renameTo(place)) { "Couldn't move ${file.name} into place" }
        return true
    }

    /** Deletes [pack]: its files, partial ones and notes. */
    fun remove(pack: VoicePack) {
        folder(pack).deleteRecursively()
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
