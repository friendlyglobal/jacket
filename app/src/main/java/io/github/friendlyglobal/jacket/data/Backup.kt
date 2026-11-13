package io.github.friendlyglobal.jacket.data

import io.github.friendlyglobal.jacket.voice.SayIt
import org.json.JSONObject

/**
 * The backup file: settings, the list of books (title, author, where you are, the time spent
 * with each), the time read and listened each day, bookmarks, highlights and notes, and the
 * words the voice says your way. Book files and covers stay out; adding a book's file again on the new
 * phone reconnects it to its entry.
 */
object Backup {
    const val MIME = "application/json"
    private const val KIND = "jacket-backup"

    fun write(state: LibraryState, exportedAt: Long = System.currentTimeMillis()): String =
        JSONObject(LibraryJson.encode(state)).apply {
            put("kind", KIND)
            put("exportedAt", exportedAt)
        }.toString(2)

    /** Parses a backup file; throws [IllegalArgumentException] if it isn't one. */
    fun read(text: String): LibraryState {
        val root = runCatching { JSONObject(text) }.getOrNull()
        require(root != null && root.optString("kind") == KIND) { "This isn't a Jacket backup file." }
        return LibraryJson.decode(text)
    }

    /**
     * Whether the backup that keeps itself up to date may write [state] over a file that now holds
     * [existing] (read only if it has to be: null when the file has nothing to give). Any library
     * with a book in it may. A library with none must not take the place of a backup that has
     * some: an empty library can be a library that couldn't be read, and that backup is then the
     * only copy of the reader's places and notes. An empty file, one that isn't a backup, and a
     * backup with no books are nothing to lose.
     */
    fun mayReplace(state: LibraryState, existing: () -> String?): Boolean =
        state.books.isNotEmpty() || existing()?.let { text -> runCatching { read(text).books.isEmpty() }.getOrDefault(true) } ?: true

    /**
     * Folds a backup into the current library: books already here keep their files and take the
     * backup's place in them if it's further along; books not here come back as entries waiting
     * for their file. Bookmarks and highlights are merged by id; settings come from the backup,
     * all but the folder this phone watches.
     * Collections are merged by id or name; a book here keeps its own and joins the backup's, and
     * stays pinned if it was pinned in either. Reading time comes back with them (see [mergeReading]),
     * and so does how much of it was listened to, and the time spent with each book (see [withTimeFrom]).
     * Words to say another way are merged like bookmarks; a word already fixed here keeps this
     * phone's way of saying it.
     */
    fun merge(current: LibraryState, backup: LibraryState): LibraryState {
        // The same book: the same entry, or one made from the same file (by its name and size, or
        // by its bytes when both kept their SHA-256, which is how "book (1).epub" is known).
        fun sameBook(a: Book, b: Book) = a.id == b.id || (a.sourceName == b.sourceName && a.sizeBytes == b.sizeBytes) ||
            (a.sha256 != null && a.sha256 == b.sha256 && a.sizeBytes == b.sizeBytes)
        val (collections, collectionIds) = mergeCollections(current.collections, backup.collections)
        // A backup's collection ids, as they are here; ones it never listed are dropped.
        fun Set<String>.here() = mapNotNullTo(HashSet()) { collectionIds[it] }
        val ids = HashMap<String, String>()
        val books = current.books.map { book ->
            val saved = backup.books.firstOrNull { sameBook(it, book) } ?: return@map book
            ids[saved.id] = book.id
            val here = book.copy(collections = book.collections + saved.collections.here(), pinnedAt = maxOf(book.pinnedAt, saved.pinnedAt))
                // The time spent with it: the longer of the two (see withTimeFrom).
                .withTimeFrom(saved)
            if (saved.lastOpenedAt > here.lastOpenedAt) {
                here.copy(
                    position = saved.position, positionOffset = saved.positionOffset, progress = saved.progress,
                    locator = saved.locator, lastOpenedAt = saved.lastOpenedAt, finishedAt = saved.finishedAt,
                    sectionCount = if (here.sectionCount > 0) here.sectionCount else saved.sectionCount,
                )
            } else {
                here
            }
        } + backup.books.filter { saved -> current.books.none { sameBook(it, saved) } }.map { it.safe().copy(collections = it.collections.here()) }
        val known = current.annotations.mapTo(HashSet()) { it.id }
        val annotations = current.annotations + backup.annotations
            .filter { it.id !in known }
            .map { it.copy(bookId = ids[it.bookId] ?: it.bookId) }
            .filter { a -> books.any { it.id == a.bookId } }
        val settings = backup.settings.copy(
            collection = backup.settings.collection?.let { collectionIds[it] },
            // The folder watched for new books stays this phone's own: the permission to read it
            // was given here, and the backup's (another phone's, or an older choice) has none.
            watchFolder = current.settings.watchFolder,
            watchSeen = current.settings.watchSeen,
        )
        val sayings = current.sayings.toMutableList()
        for (saved in backup.sayings) {
            val bookId = saved.bookId?.let { ids[it] ?: it }
            // One for a book that isn't in either library has nothing to be said in.
            if (bookId != null && books.none { it.id == bookId }) continue
            if (sayings.any { it.id == saved.id || (it.bookId == bookId && SayIt.sameWord(it.word, saved.word)) }) continue
            sayings += saved.copy(bookId = bookId)
        }
        return current.copy(
            books = books, settings = settings, annotations = annotations, collections = collections,
            reading = mergeReading(current.reading, backup.reading), listened = mergeReading(current.listened, backup.listened),
            sayings = sayings,
        )
    }

    /**
     * The time read each day, from here and from a backup, so that a new phone carries on with the
     * reading time and the streak the old one had. A day in both counts once, at the longer of the
     * two: added together, restoring this phone's own backup would double every day in it.
     *
     * How much of each day was listened to is merged the same way, by itself. A day's listening
     * is never more than its reading in either library, so the longer of the two listening times
     * is never more than the longer of the two reading times.
     */
    internal fun mergeReading(current: Map<String, Long>, backup: Map<String, Long>): Map<String, Long> =
        (current.keys + backup.keys).associateWith { day -> maxOf(current[day] ?: 0L, backup[day] ?: 0L) }

    /** A backup's file names are only names: no folders, so nothing outside the books folder is touched. */
    private fun Book.safe(): Book = copy(
        fileName = java.io.File(fileName).name.takeIf { it.isNotBlank() && it != ".." } ?: "$id.${format.extension}",
        coverFile = coverFile?.let { java.io.File(it).name }?.takeIf { it.isNotBlank() && it != ".." },
        originalCover = originalCover?.let { java.io.File(it).name }?.takeIf { it.isNotBlank() && it != ".." },
    )
}
