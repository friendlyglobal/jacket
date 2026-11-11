package io.github.friendlyglobal.jacket.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.OutputStream

/**
 * Writing the whole of a file that another app holds (a backup in Drive, say), so that it ends up
 * with the new bytes and nothing left of what it held before.
 *
 * Opened "wt", a file is emptied before it is written. Some apps refuse that and only take "w",
 * and of those some leave the old bytes where the new ones don't reach: a backup written over a
 * longer one would then end with the tail of the old one, and couldn't be read back. So when
 * "wt" is refused, the file's length is taken first, and the new bytes are followed by spaces
 * up to it. JSON ignores them, and nothing of the old file is left whether or not the app
 * holding it cuts the file short.
 */
internal object WholeFile {
    /**
     * Writes [bytes] as the whole of a file. [open] opens it for writing in the mode it is given
     * ("wt" or "w"), and may throw or return null when the app holding it won't; [lengthNow] is
     * how many bytes the file has before anything is written, or less than 0 if that can't be
     * told. Returns how long the file is afterwards, or null, with the file untouched, when it
     * can't be opened or can't be written without the risk of leaving it unreadable (it won't be
     * emptied and nobody can say how long it is).
     */
    fun write(bytes: ByteArray, lengthNow: () -> Long, open: (mode: String) -> OutputStream?): Long? {
        runCatching { open("wt") }.getOrNull()?.let { out ->
            out.use { it.write(bytes) }
            return bytes.size.toLong()
        }
        // Before it is opened: opening it "w" is what empties it, where it does.
        val before = lengthNow()
        if (before < 0) return null
        val out = runCatching { open("w") }.getOrNull() ?: return null
        out.use {
            it.write(bytes)
            // In pieces: what was there may be far longer than a backup.
            var left = before - bytes.size
            val spaces = ByteArray(minOf(left, 8192L).coerceAtLeast(0L).toInt()) { ' '.code.toByte() }
            while (left > 0) {
                val n = minOf(left, spaces.size.toLong()).toInt()
                it.write(spaces, 0, n)
                left -= n
            }
        }
        return maxOf(bytes.size.toLong(), before)
    }
}

/**
 * How many bytes the file at [uri] has now: counted by reading it, which is what the app holding
 * it will hand back; else what that app says its size is; else -1.
 */
internal fun ContentResolver.lengthOf(uri: Uri): Long {
    runCatching {
        openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1 shl 16)
            var length = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                length += n
            }
            length
        }
    }.getOrNull()?.let { return it }
    return runCatching {
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull() ?: -1L
}

/**
 * Writes [bytes] as the whole of the file at [uri] (see [WholeFile.write]). [atLeast] is a length
 * the file is known to have had, for when it can't be measured. Null if it couldn't be written.
 */
internal fun ContentResolver.writeWhole(uri: Uri, bytes: ByteArray, atLeast: Long = -1L): Long? =
    WholeFile.write(bytes, lengthNow = { maxOf(lengthOf(uri), atLeast) }, open = { mode -> openOutputStream(uri, mode) })
