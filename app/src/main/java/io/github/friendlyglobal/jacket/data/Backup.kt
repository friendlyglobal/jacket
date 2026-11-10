package io.github.friendlyglobal.jacket.data

import org.json.JSONObject

/**
 * The backup file: settings, the list of books (title, author, where you are), bookmarks,
 * highlights and notes. Book files and covers stay out; adding a book's file again on the new
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
     * Folds a backup into the current library: books already here keep their files and take the
     * backup's place in them if it's further along; books not here come back as entries waiting
     * for their file. Bookmarks and highlights are merged by id; settings come from the backup.
     */
    fun merge(current: LibraryState, backup: LibraryState): LibraryState {
        fun sameBook(a: Book, b: Book) = a.id == b.id || (a.sourceName == b.sourceName && a.sizeBytes == b.sizeBytes)
        val ids = HashMap<String, String>()
        val books = current.books.map { here ->
            val saved = backup.books.firstOrNull { sameBook(it, here) } ?: return@map here
            ids[saved.id] = here.id
            if (saved.lastOpenedAt > here.lastOpenedAt) {
                here.copy(
                    position = saved.position, positionOffset = saved.positionOffset, progress = saved.progress,
                    locator = saved.locator, lastOpenedAt = saved.lastOpenedAt, finishedAt = saved.finishedAt,
                    sectionCount = if (here.sectionCount > 0) here.sectionCount else saved.sectionCount,
                )
            } else {
                here
            }
        } + backup.books.filter { saved -> current.books.none { sameBook(it, saved) } }.map { it.safe() }
        val known = current.annotations.mapTo(HashSet()) { it.id }
        val annotations = current.annotations + backup.annotations
            .filter { it.id !in known }
            .map { it.copy(bookId = ids[it.bookId] ?: it.bookId) }
            .filter { a -> books.any { it.id == a.bookId } }
        return current.copy(books = books, settings = backup.settings, annotations = annotations)
    }

    /** A backup's file names are only names: no folders, so nothing outside the books folder is touched. */
    private fun Book.safe(): Book = copy(
        fileName = java.io.File(fileName).name.takeIf { it.isNotBlank() && it != ".." } ?: "$id.${format.extension}",
        coverFile = coverFile?.let { java.io.File(it).name }?.takeIf { it.isNotBlank() && it != ".." },
    )
}
