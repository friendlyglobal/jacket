package io.github.friendlyglobal.jacket.data

/**
 * A collection the reader made ("Book club", "Holiday", "Poetry"): like a folder for books, except
 * a book can be in as many as it belongs in. Books keep the ids of theirs ([Book.collections]).
 */
data class BookCollection(val id: String, val name: String, val createdAt: Long = 0L)

/** What the library screen can change about how books are kept: pins, collections, details. */
interface LibraryOrganizer {
    fun setPinned(id: String, pinned: Boolean)

    /** Corrects a book's title and author, when the file's own are wrong or missing. */
    fun editDetails(id: String, title: String, author: String)

    /** Puts the book in exactly these collections. */
    fun setCollections(id: String, collections: Set<String>)

    /** Makes a collection and returns its id. */
    fun createCollection(name: String): String

    fun renameCollection(id: String, name: String)

    /** Deletes the collection; its books stay in the library. */
    fun deleteCollection(id: String)

    /** Shows only the books in this collection, or every book (null). */
    fun showCollection(id: String?)

    /**
     * Gives the book [cover] as its own, tinting it with [seed] (or a color picked from the
     * cover); null puts back the cover it came with.
     */
    fun setCover(id: String, cover: android.graphics.Bitmap?, seed: Int? = null)
}

/**
 * Folds [incoming] collections (from a backup) into [current]: one with the same id, or else the
 * same name (ignoring case), is the same collection. Returns the collections, and what each
 * incoming id became.
 */
internal fun mergeCollections(current: List<BookCollection>, incoming: List<BookCollection>): Pair<List<BookCollection>, Map<String, String>> {
    val merged = current.toMutableList()
    val ids = HashMap<String, String>()
    for (c in incoming) {
        val same = merged.firstOrNull { it.id == c.id } ?: merged.firstOrNull { it.name.trim().equals(c.name.trim(), ignoreCase = true) }
        if (same != null) {
            ids[c.id] = same.id
        } else {
            merged += c
            ids[c.id] = c.id
        }
    }
    return merged to ids
}

/**
 * The order books are listed in: pinned ones first (the most recently pinned leading), then the
 * rest in [sort]'s order; narrowed to [collection] and to titles or authors matching [query].
 */
fun arrangeShelf(books: List<Book>, sort: LibrarySort, query: String, collection: String? = null): List<Book> {
    val q = query.trim()
    val matching = books
        .filter { collection == null || collection in it.collections }
        .filter { q.isEmpty() || it.title.contains(q, true) || it.author.contains(q, true) }
    fun titleKey(b: Book) = b.title.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")
    val sorted = when (sort) {
        LibrarySort.RECENT -> matching.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        LibrarySort.TITLE -> matching.sortedBy(::titleKey)
        LibrarySort.AUTHOR -> matching.sortedWith(compareBy({ it.author.ifEmpty { "￿" }.lowercase() }, ::titleKey))
    }
    val (pinned, rest) = sorted.partition { it.isPinned }
    return pinned.sortedByDescending { it.pinnedAt } + rest
}
