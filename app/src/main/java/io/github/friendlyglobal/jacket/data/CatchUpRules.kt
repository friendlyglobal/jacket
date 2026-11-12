package io.github.friendlyglobal.jacket.data

import kotlin.math.abs

/**
 * Catching up between the reader's devices, as rules apart from Android: what this device tells
 * the others of itself, what it takes from what they tell, and when it offers to go to the page
 * another device reached.
 *
 * What is merged without asking is what can't be lost by it: bookmarks, highlights and notes (by
 * their ids, the newer change winning, a deletion being a change like any other) and collections.
 * A place is never taken: it is only ever offered (see [offers]), and the reader's own stays
 * until they say so.
 *
 * Nothing here looks at a clock. A device that is an hour out, or a year, merges the same.
 */
internal object CatchUpRules {
    /** What the reader deleted on this device since it last told the others: the library only shows that they are gone, not why. */
    class Deleted(val marks: Set<String> = emptySet(), val collections: Set<String> = emptySet())

    /** A round of catching up: the library with what the other devices told folded in, and what this device tells them now. */
    class Round(val library: LibraryState, val mine: DeviceFile)

    /** Another device is further along in a book that is on this one too. */
    data class Offer(val bookId: String, val device: String, val deviceName: String, val place: SharedPlace)

    /**
     * Whether [book] and a book another device tells of are the same: made from the same file
     * (by its name and size, or its bytes where both kept their SHA-256), or the same entry of a
     * backup both devices were set up from.
     */
    fun sameBook(book: Book, shared: SharedBook): Boolean =
        book.id == shared.id ||
            (shared.size > 0 && book.sizeBytes == shared.size &&
                ((shared.file.isNotEmpty() && book.sourceName == shared.file) || (book.sha256 != null && book.sha256 == shared.sha256)))

    /**
     * One round. [here] is the library as it is, [mine] what this device told last time (its
     * memory of what it has told, kept on the device), [others] the newest file of each other
     * device (see [DeviceFiles.newest]), [deleted] what the reader deleted here since, and
     * [chapters] the chapter each book is in where a reader has said (by book id).
     *
     * - A bookmark, highlight, note or collection changed here counts one more change than it
     *   had; one a device changed more recently than this one is taken from it. Deleted is
     *   deleted everywhere, and stays so: it doesn't come back from a device that hasn't heard.
     * - Only what the reader deleted is a deletion. A book removed from this device takes its
     *   bookmarks out of what this device tells, and deletes them nowhere. One the library has
     *   lost some other way (it went back to an older copy of itself) is put back.
     * - A book another device has and this one doesn't brings nothing: its bookmarks wait in that
     *   device's file until the book is added here.
     * - A collection made on two devices under one name is one collection.
     */
    fun merge(
        here: LibraryState,
        mine: DeviceFile,
        others: List<DeviceFile>,
        deleted: Deleted = Deleted(),
        chapters: Map<String, String> = emptyMap(),
    ): Round {
        val me = mine.device
        var library = here

        // ---- Collections ----------------------------------------------------------------------
        val shelves = LinkedHashMap<String, SharedCollection>()
        mine.collections.forEach { shelves[it.id] = it }
        for (id in deleted.collections) {
            // (One made and deleted before the others heard of it was never theirs to delete.)
            val told = shelves[id]?.takeIf { !it.deleted } ?: continue
            shelves[id] = SharedCollection(id, told.version + 1, me, null)
        }
        for (collection in library.collections) {
            val told = shelves[collection.id]
            // New here, renamed here, or brought back by restoring a backup.
            if (told == null) shelves[collection.id] = SharedCollection(collection.id, 1, me, collection)
            else if (told.collection != collection) shelves[collection.id] = SharedCollection(collection.id, told.version + 1, me, collection)
        }
        // Another device's id for a collection that has a different one here.
        val alias = HashMap<String, String>()
        for (entry in newestOf(others.flatMap { it.collections }, { it.id }, { it.version }, { it.by })) {
            val told = shelves[entry.id]
            if (told != null) {
                if (!isNewerThan(entry.version, entry.by, told.version, told.by)) continue
                shelves[entry.id] = entry
                library = if (entry.collection == null) library.withoutCollection(entry.id) else library.withCollection(entry.collection)
                continue
            }
            // Deleted there, and never here: nothing to take away.
            val collection = entry.collection ?: continue
            // Made on both devices under the same name before they knew of each other: one
            // collection, under whichever of its two ids comes first, which both devices can tell.
            val twin = library.collections.firstOrNull { it.id != collection.id && sameName(it.name, collection.name) }
            when {
                twin == null -> {
                    library = library.withCollection(collection)
                    shelves[entry.id] = entry
                }
                collection.id < twin.id -> {
                    library = library.withCollectionKnownAs(twin.id, collection)
                    shelves.remove(twin.id)
                    shelves[entry.id] = entry
                }
                // Ours is the id to keep; theirs stands for it until that device says the same.
                else -> alias[collection.id] = twin.id
            }
        }
        for (entry in shelves.values) {
            val collection = entry.collection ?: continue
            if (library.collections.none { it.id == collection.id }) library = library.withCollection(collection)
        }

        // ---- Bookmarks, highlights and notes --------------------------------------------------
        val bookIds = library.books.mapTo(HashSet()) { it.id }
        val marks = LinkedHashMap<String, SharedMark>()
        mine.marks.forEach { marks[it.id] = it }
        for (id in deleted.marks) {
            val told = marks[id]?.takeIf { !it.deleted } ?: continue
            marks[id] = SharedMark(id, told.version + 1, me, null)
        }
        for (annotation in library.annotations) {
            val told = marks[annotation.id]
            if (told == null) marks[annotation.id] = SharedMark(annotation.id, 1, me, annotation)
            else if (told.annotation != annotation) marks[annotation.id] = SharedMark(annotation.id, told.version + 1, me, annotation)
        }
        // A book that left this device takes its bookmarks with it. Nobody deleted them.
        marks.values.removeAll { mark -> mark.annotation != null && mark.annotation.bookId !in bookIds }
        val annotations = LinkedHashMap<String, Annotation>()
        library.annotations.forEach { annotations[it.id] = it }
        // Each mark with the device that tells it: a mark names its book by that device's id for it.
        val tellers = HashMap<SharedMark, DeviceFile>()
        others.forEach { other -> other.marks.forEach { tellers.putIfAbsent(it, other) } }
        for (entry in newestOf(others.flatMap { it.marks }, { it.id }, { it.version }, { it.by })) {
            val told = marks[entry.id]
            if (told != null && !isNewerThan(entry.version, entry.by, told.version, told.by)) continue
            val theirs = entry.annotation
            if (theirs == null) {
                if (told == null) continue
                marks[entry.id] = entry
                annotations.remove(entry.id)
                continue
            }
            val shared = tellers[entry]?.books?.firstOrNull { it.id == theirs.bookId } ?: continue
            // A book this device doesn't have: its bookmarks have nowhere to go yet.
            val book = library.books.firstOrNull { sameBook(it, shared) } ?: continue
            val ours = theirs.copy(bookId = book.id)
            marks[entry.id] = SharedMark(entry.id, entry.version, entry.by, ours)
            annotations[ours.id] = ours
        }
        for (mark in marks.values) {
            val annotation = mark.annotation ?: continue
            if (annotation.id !in annotations) annotations[annotation.id] = annotation
        }
        if (annotations.values.toList() != library.annotations) library = library.copy(annotations = annotations.values.toList())

        // ---- The books: which collections each is in, and where this device is in it ----------
        val toldBooks = mine.books.associateBy { it.id }
        val kept = library.collections.mapTo(HashSet()) { it.id }
        val next = mine.rev + 1
        val shared = ArrayList<SharedBook>(library.books.size)
        val books = library.books.map { book ->
            val told = toldBooks[book.id]
            var set: Set<String> = book.collections.filterTo(HashSet()) { it in kept }
            var shelved = when {
                told == null -> if (set.isEmpty()) 0 else 1
                told.collections != set -> told.shelved + 1
                else -> told.shelved
            }
            for (other in others) {
                val theirs = other.books.firstOrNull { sameBook(book, it) } ?: continue
                if (theirs.shelved < shelved) continue
                val there = theirs.collections.mapNotNullTo(HashSet()) { id -> (alias[id] ?: id).takeIf { it in kept } }
                if (theirs.shelved > shelved) {
                    set = there
                    shelved = theirs.shelved
                } else if (there != set) {
                    // Changed on two devices at once: it is in every collection either put it in.
                    set = set + there
                }
            }
            shared += SharedBook(
                id = book.id, title = book.title, file = book.sourceName, size = book.sizeBytes, sha256 = book.sha256,
                place = placeOf(book, told?.place, chapters[book.id], next), collections = set, shelved = shelved,
            )
            if (set == book.collections) book else book.copy(collections = set)
        }
        if (books != library.books) library = library.copy(books = books)

        val now = mine.copy(books = shared, marks = marks.values.toList(), collections = shelves.values.toList())
        return Round(library, if (now.saysTheSameAs(mine)) mine else now.copy(rev = next))
    }

    /**
     * The places to offer the reader: for each book on this device, the furthest place another
     * device has reached in it, when that is further than this device's own by about a page or
     * more ([isFurther]) and the reader hasn't already answered for it. [answered] holds, by
     * device and by this device's book id, the [SharedPlace.rev] of the place last taken or
     * waved away: the same place is not offered twice, and a newer one from that device is.
     *
     * A book marked finished here, or whose file isn't on this device ([unreadable]), has none.
     */
    fun offers(
        here: LibraryState,
        others: List<DeviceFile>,
        answered: Map<String, Map<String, Long>> = emptyMap(),
        unreadable: Set<String> = emptySet(),
    ): Map<String, Offer> {
        val offers = HashMap<String, Offer>()
        for (book in here.books) {
            if (book.isFinished || book.id in unreadable) continue
            var best: Offer? = null
            for (other in others) {
                val last = answered[other.device]?.get(book.id) ?: Long.MIN_VALUE
                for (theirs in other.books) {
                    val place = theirs.place ?: continue
                    if (place.rev <= last || !sameBook(book, theirs) || !isFurther(place, book)) continue
                    if (best == null || place.progress > best.place.progress) best = Offer(book.id, other.device, other.name, place)
                }
            }
            best?.let { offers[book.id] = it }
        }
        return offers
    }

    /**
     * What to remember once the reader has answered the offer for [bookId] (gone there, or waved
     * it away): every place the other devices have for that book now. Waving away the furthest
     * doesn't bring up the next furthest.
     */
    fun answered(
        answered: Map<String, Map<String, Long>>,
        here: LibraryState,
        others: List<DeviceFile>,
        bookId: String,
    ): Map<String, Map<String, Long>> {
        val book = here.find(bookId) ?: return answered
        val now = HashMap(answered)
        for (other in others) {
            val rev = other.books.filter { sameBook(book, it) }.mapNotNull { it.place?.rev }.maxOrNull() ?: continue
            now[other.device] = now[other.device].orEmpty() + (bookId to maxOf(rev, now[other.device]?.get(bookId) ?: Long.MIN_VALUE))
        }
        return now
    }

    /**
     * Whether [place] on another device is further through [book] than this device is, by enough
     * to be worth saying: more than about a page. Less than that is the same place counted on
     * another screen (see [Book.leftAt]): offered, it would send the reader to the page they are on.
     *
     * A page is one of a PDF's or a comic's, about a screen and a half of an EPUB's text
     * (Readium's positions, which are the same on every device) or a paragraph and a half of a
     * text file; never more than a fiftieth of the book, for one that has few of them.
     */
    fun isFurther(place: SharedPlace, book: Book): Boolean {
        val theirs = place.progress.takeIf { it.isFinite() } ?: return false
        val ours = book.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val sections = maxOf(place.sections, book.sectionCount)
        val pictures = book.format == BookFormat.PDF || book.format == BookFormat.CBZ
        val page = if (sections > 0) (if (pictures) 0.75f else 1.5f) / sections else MOST
        return theirs - ours > minOf(page, MOST)
    }

    /** Where this device is in [book], as it tells the others; null for a book never opened here. */
    private fun placeOf(book: Book, told: SharedPlace?, chapter: String?, rev: Long): SharedPlace? {
        val progress = book.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (book.isNew && book.locator == null && book.position == 0 && progress == 0f) return null
        val moved = told == null || told.position != book.position || abs(told.progress - progress) > SAME_PLACE
        return SharedPlace(
            locator = book.locator,
            position = book.position.coerceAtLeast(0),
            offset = book.positionOffset.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            sections = book.sectionCount.coerceAtLeast(0),
            progress = progress,
            // The chapter a reader last said the book was in. A place that moved with nobody
            // saying (and nothing does that) has none rather than the one it left.
            chapter = chapter ?: if (moved) "" else told?.chapter.orEmpty(),
            rev = if (moved) rev else told?.rev ?: rev,
        )
    }

    /** Of several devices' entries for the same thing, the newest of each: every device picks the same one. */
    private fun <T> newestOf(entries: List<T>, id: (T) -> String, version: (T) -> Int, by: (T) -> String): List<T> {
        val newest = LinkedHashMap<String, T>()
        for (entry in entries) {
            val best = newest[id(entry)]
            if (best == null || isNewerThan(version(entry), by(entry), version(best), by(best))) newest[id(entry)] = entry
        }
        return newest.values.sortedBy(id)
    }

    private fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private fun LibraryState.withCollection(collection: BookCollection): LibraryState = copy(
        collections = if (collections.any { it.id == collection.id }) collections.map { if (it.id == collection.id) collection else it } else collections + collection,
    )

    private fun LibraryState.withoutCollection(id: String): LibraryState = copy(
        collections = collections.filterNot { it.id == id },
        books = books.map { if (id in it.collections) it.copy(collections = it.collections - id) else it },
        settings = if (settings.collection == id) settings.copy(collection = null) else settings,
    )

    /** The collection that was [old] here is [now], id and all: its books and the library's view of it follow. */
    private fun LibraryState.withCollectionKnownAs(old: String, now: BookCollection): LibraryState = copy(
        collections = collections.map { if (it.id == old) now else it }.distinctBy { it.id },
        books = books.map { if (old in it.collections) it.copy(collections = it.collections - old + now.id) else it },
        settings = if (settings.collection == old) settings.copy(collection = now.id) else settings,
    )

    /** The most of a book that "about a page" is ever taken to be. */
    private const val MOST = 0.02f

    /** A place counted this close to the one already told hasn't moved. */
    private const val SAME_PLACE = 0.0001f
}
