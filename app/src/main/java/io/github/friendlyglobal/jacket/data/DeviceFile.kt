package io.github.friendlyglobal.jacket.data

import io.github.friendlyglobal.jacket.data.LibraryJson.toAnnotation
import io.github.friendlyglobal.jacket.data.LibraryJson.toJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one device tells the reader's other devices, so that each can catch up with the others:
 * where it is in each book, its bookmarks, highlights and notes, and its collections. Every
 * device keeps a file of its own in a place they share (see [CatchUp]) and only ever writes that
 * one; it reads the others'. So two devices writing at the same moment write two files.
 *
 * Nothing in it is told by a clock, which two devices rarely agree on. A device counts its own
 * writes ([rev]), and each thing that can be changed in two places counts its changes (a
 * [SharedMark]'s and a [SharedCollection]'s version). [writtenAt] is only ever shown.
 */
internal data class DeviceFile(
    /** The device's own id, made when catching up was first turned on there. */
    val device: String,
    /** What the reader calls it: "Tablet". */
    val name: String,
    /** How many times the device has changed what it tells. Of two copies of its file, the one with the higher is the newer. */
    val rev: Long = 0L,
    /** When it was written, by the device's own clock: shown ("2 hours ago"), never compared. */
    val writtenAt: Long = 0L,
    val books: List<SharedBook> = emptyList(),
    val marks: List<SharedMark> = emptyList(),
    val collections: List<SharedCollection> = emptyList(),
) {
    /** Whether this says the same as [other], whenever and however often each was written. */
    fun saysTheSameAs(other: DeviceFile): Boolean = copy(rev = 0L, writtenAt = 0L) == other.copy(rev = 0L, writtenAt = 0L)
}

/**
 * A book as a device has it. Its [id] is that device's own: another device knows the book by the
 * file it was made from ([file] and [size], or [sha256] and [size]), as a backup does.
 */
internal data class SharedBook(
    val id: String,
    val title: String,
    /** The name of the file it was added from. */
    val file: String,
    val size: Long,
    val sha256: String? = null,
    /** Where the device is in it; null for a book never opened there. */
    val place: SharedPlace? = null,
    /** The ids of the collections it is in, and how many times that has been changed ([shelved]). */
    val collections: Set<String> = emptySet(),
    val shelved: Int = 0,
)

/** Where a device is in a book: what [Book] keeps of a place, and the chapter it is in when that is known. */
internal data class SharedPlace(
    val locator: String? = null,
    val position: Int = 0,
    val offset: Float = 0f,
    val sections: Int = 0,
    val progress: Float = 0f,
    val chapter: String = "",
    /** The device's [DeviceFile.rev] when the place last moved: how another device tells a place it has already been offered from a new one. */
    val rev: Long = 0L,
)

/**
 * A bookmark, highlight or note, or (with no [annotation]) the fact that it was deleted. Each
 * change to it, on any device, makes a higher [version]; the device that made it is [by]. The
 * higher version is the newer; of two changes made at once on two devices (the same version),
 * every device keeps the same one (see [isNewerThan]).
 */
internal data class SharedMark(val id: String, val version: Int, val by: String, val annotation: Annotation?) {
    val deleted: Boolean get() = annotation == null
}

/** A collection, or (with no [collection]) the fact that it was deleted. Versions as for a [SharedMark]. */
internal data class SharedCollection(val id: String, val version: Int, val by: String, val collection: BookCollection?) {
    val deleted: Boolean get() = collection == null
}

/** Whether a change numbered [version] and made by [by] is to be kept over one numbered [otherVersion] made by [otherBy]. */
internal fun isNewerThan(version: Int, by: String, otherVersion: Int, otherBy: String): Boolean =
    version > otherVersion || (version == otherVersion && by > otherBy)

internal object DeviceFiles {
    private const val KIND = "jacket-catch-up"

    /** A file bigger than this isn't one of Jacket's: it isn't read. (A library of a thousand books makes about 1 MB.) */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** The name a device's file is given: "Jacket – Tablet.json". */
    fun nameFor(device: String): String = "Jacket \u2013 ${tidyName(device).ifEmpty { "device" }}.json"

    /** A device's name as it can be part of a file's: without what file systems refuse, and not too long. */
    fun tidyName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trim()

    /** Whether a file in the shared folder could be a device's: by its name alone, before it is read. Copies a sync app made ("… (1).json") are too. */
    fun mayBeOne(name: String): Boolean = name.endsWith(".json", ignoreCase = true)

    fun write(file: DeviceFile): String = JSONObject().apply {
        put("kind", KIND)
        put("version", 1)
        put("device", file.device)
        put("name", file.name)
        put("rev", file.rev)
        put("writtenAt", file.writtenAt)
        put("books", JSONArray().apply {
            file.books.forEach { book ->
                put(JSONObject().apply {
                    put("id", book.id); put("title", book.title); put("file", book.file); put("size", book.size)
                    put("sha256", book.sha256 ?: JSONObject.NULL)
                    put("place", book.place?.let { place ->
                        JSONObject().apply {
                            put("locator", place.locator ?: JSONObject.NULL)
                            put("position", place.position); put("offset", place.offset.toDouble())
                            put("sections", place.sections); put("progress", place.progress.toDouble())
                            put("chapter", place.chapter); put("rev", place.rev)
                        }
                    } ?: JSONObject.NULL)
                    put("collections", JSONArray(book.collections.sorted()))
                    put("shelved", book.shelved)
                })
            }
        })
        put("marks", JSONArray().apply {
            file.marks.forEach { mark ->
                put((mark.annotation?.toJson() ?: JSONObject().put("deleted", true)).apply {
                    put("id", mark.id); put("version", mark.version); put("by", mark.by)
                })
            }
        })
        put("collections", JSONArray().apply {
            file.collections.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id); put("version", entry.version); put("by", entry.by)
                    entry.collection?.let { put("name", it.name); put("createdAt", it.createdAt) } ?: put("deleted", true)
                })
            }
        })
    }.toString(2)

    /**
     * Reads a device's file. Null for anything that isn't one, whole: another app's file, an
     * empty one, one a sync app has only brought part of (it doesn't end, so it isn't read at
     * all). An entry of it that can't be read is left out, and the rest is used.
     */
    fun read(text: String): DeviceFile? = runCatching {
        val root = JSONObject(text)
        if (root.optString("kind") != KIND) return null
        val device = root.optString("device").takeIf { it.isNotBlank() } ?: return null
        fun <T : Any> entries(name: String, read: (JSONObject) -> T?): List<T> {
            val list = root.optJSONArray(name) ?: return emptyList()
            return (0 until list.length()).mapNotNull { i -> runCatching { read(list.getJSONObject(i)) }.getOrNull() }
        }
        DeviceFile(
            device = device,
            name = root.optString("name").trim().ifEmpty { device.take(8) },
            rev = root.optLong("rev"),
            writtenAt = root.optLong("writtenAt"),
            books = entries("books") { book ->
                SharedBook(
                    id = book.getString("id"),
                    title = book.optString("title"),
                    file = book.optString("file"),
                    size = book.optLong("size"),
                    sha256 = if (book.isNull("sha256")) null else book.optString("sha256").takeIf { it.isNotEmpty() },
                    place = book.optJSONObject("place")?.let { place ->
                        val progress = place.optDouble("progress", 0.0).toFloat()
                        val offset = place.optDouble("offset", 0.0).toFloat()
                        SharedPlace(
                            locator = if (place.isNull("locator")) null else place.optString("locator").takeIf { it.isNotEmpty() },
                            position = place.optInt("position").coerceAtLeast(0),
                            offset = if (offset.isFinite()) offset.coerceIn(0f, 1f) else 0f,
                            sections = place.optInt("sections").coerceAtLeast(0),
                            progress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f,
                            chapter = place.optString("chapter"),
                            rev = place.optLong("rev"),
                        )
                    },
                    collections = book.optJSONArray("collections")
                        ?.let { ids -> (0 until ids.length()).mapNotNullTo(HashSet()) { ids.optString(it).takeIf(String::isNotEmpty) } }.orEmpty(),
                    shelved = book.optInt("shelved").coerceAtLeast(0),
                )
            },
            marks = entries("marks") { mark ->
                SharedMark(
                    id = mark.getString("id"),
                    version = mark.optInt("version", 1).coerceAtLeast(1),
                    by = mark.optString("by"),
                    annotation = if (mark.optBoolean("deleted")) null else mark.toAnnotation(),
                )
            },
            collections = entries("collections") { entry ->
                val id = entry.getString("id").takeIf { it.isNotEmpty() } ?: return@entries null
                SharedCollection(
                    id = id,
                    version = entry.optInt("version", 1).coerceAtLeast(1),
                    by = entry.optString("by"),
                    collection = if (entry.optBoolean("deleted")) null else BookCollection(id, entry.optString("name"), entry.optLong("createdAt")),
                )
            },
        )
    }.getOrNull()

    /**
     * Of all the files found in the shared place, the one to go by for each device other than
     * [me]: the copy with the highest [DeviceFile.rev]. A sync app that couldn't decide between
     * two versions of a file keeps both ("Jacket – Tablet (1).json"), and an old copy can linger;
     * the device's own count says which is the newest, whatever the files are called and whatever
     * the clocks said. A copy older than one already seen from that device ([seen], its rev) is
     * the past coming round again, and isn't gone back to.
     */
    fun newest(files: List<DeviceFile>, me: String, seen: Map<String, Long> = emptyMap()): List<DeviceFile> =
        files.filter { it.device != me }
            .groupBy { it.device }
            .mapNotNull { (device, copies) -> copies.maxByOrNull { it.rev }?.takeIf { it.rev >= (seen[device] ?: Long.MIN_VALUE) } }
            .sortedBy { it.device }
}
