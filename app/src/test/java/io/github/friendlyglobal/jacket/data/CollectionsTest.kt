package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsTest {
    private fun book(id: String, title: String = "T$id", opened: Long = 0, name: String = "$id.epub") = Book(
        id = id, title = title, author = "A", format = BookFormat.EPUB, fileName = "$id.epub",
        coverFile = null, seedColor = null, sourceName = name, sizeBytes = 100, addedAt = 1, lastOpenedAt = opened,
    )

    @Test
    fun pinnedBooksLeadWhateverTheSort() {
        val books = listOf(
            book("a", "Alpha", opened = 30),
            book("b", "Beta", opened = 10).copy(pinnedAt = 100),
            book("c", "Gamma", opened = 20).copy(pinnedAt = 200),
        )
        // The most recently pinned first, then the rest in the sort's order.
        assertEquals(listOf("c", "b", "a"), arrangeShelf(books, LibrarySort.RECENT, "").map { it.id })
        assertEquals(listOf("c", "b", "a"), arrangeShelf(books, LibrarySort.TITLE, "").map { it.id })
    }

    @Test
    fun aCollectionShowsOnlyItsBooksAndSearchStillApplies() {
        val books = listOf(
            book("a", "Alpha").copy(collections = setOf("club")),
            book("b", "Beta"),
            book("c", "Gamma").copy(collections = setOf("club", "trip")),
        )
        assertEquals(listOf("a", "c"), arrangeShelf(books, LibrarySort.TITLE, "", "club").map { it.id })
        assertEquals(listOf("c"), arrangeShelf(books, LibrarySort.TITLE, "gam", "club").map { it.id })
        assertEquals(3, arrangeShelf(books, LibrarySort.TITLE, "", null).size)
    }

    @Test
    fun collectionsMergeByIdOrName() {
        val here = listOf(BookCollection("1", "Book club"))
        val incoming = listOf(BookCollection("1", "Renamed elsewhere"), BookCollection("2", "book CLUB "), BookCollection("3", "Poetry"))
        val (merged, ids) = mergeCollections(here, incoming)
        assertEquals(listOf("1", "3"), merged.map { it.id })
        assertEquals(mapOf("1" to "1", "2" to "1", "3" to "3"), ids)
    }

    @Test
    fun aBackupBringsItsCollectionsAndPins() {
        val here = LibraryState(
            books = listOf(book("new", name = "one.epub").copy(collections = setOf("mine"))),
            collections = listOf(BookCollection("mine", "Mine"), BookCollection("c1", "Book club")),
        )
        val saved = LibraryState(
            books = listOf(
                book("old", name = "one.epub").copy(collections = setOf("x", "gone"), pinnedAt = 9),
                book("other", name = "two.epub").copy(collections = setOf("x")),
            ),
            collections = listOf(BookCollection("x", "Book Club")),
            settings = Settings(collection = "x"),
        )
        val merged = Backup.merge(here, saved)
        val one = merged.books.first { it.id == "new" }
        // "Book Club" is the club already here; an id the backup never listed is dropped.
        assertEquals(setOf("mine", "c1"), one.collections)
        assertEquals(9L, one.pinnedAt)
        assertEquals(setOf("c1"), merged.books.first { it.id == "other" }.collections)
        assertEquals(2, merged.collections.size)
        assertEquals("c1", merged.settings.collection)
    }

    @Test
    fun newFieldsSurviveTheRoundTripAndOldFilesLoadWithout() {
        val state = LibraryState(
            books = listOf(
                book("a").copy(
                    pinnedAt = 5, collections = setOf("c"),
                    coverFile = "a-2.jpg", coverCustomized = true, originalCover = "a.jpg", originalSeed = 0x123456,
                ),
            ),
            collections = listOf(BookCollection("c", "Club", 3)),
            settings = Settings(collection = "c"),
        )
        assertEquals(state.copy(currentId = null), Backup.read(Backup.write(state)))

        val old = LibraryJson.decode("""{"version":1,"settings":{},"books":[{"id":"z","title":"Z","format":"EPUB","fileName":"z.epub"}]}""")
        val z = old.books.single()
        assertFalse(z.isPinned)
        assertTrue(z.collections.isEmpty())
        assertFalse(z.coverCustomized)
        assertNull(z.originalCover)
        assertTrue(old.collections.isEmpty())
        assertNull(old.settings.collection)
    }
}
