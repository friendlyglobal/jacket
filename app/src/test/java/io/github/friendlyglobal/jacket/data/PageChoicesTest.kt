package io.github.friendlyglobal.jacket.data

import io.github.friendlyglobal.jacket.importer.Comic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages side by side (a choice for every book) and right to left (one for a book), kept and restored. */
class PageChoicesTest {
    private fun book(id: String, name: String, opened: Long = 0) = Book(
        id = id, title = "T$id", author = "A", format = BookFormat.CBZ, fileName = "$id.cbz",
        coverFile = null, seedColor = null, sourceName = name, sizeBytes = 100, addedAt = 1, lastOpenedAt = opened,
    )

    @Test
    fun bothAreKept() {
        val state = LibraryState(
            books = listOf(book("a", "a.cbz").copy(rightToLeft = true), book("b", "b.cbz").copy(rightToLeft = false), book("c", "c.cbz")),
            settings = Settings(sideBySide = false),
        )
        val read = LibraryJson.decode(LibraryJson.encode(state))
        assertEquals(listOf(true, false, null), read.books.map { it.rightToLeft })
        assertFalse(read.settings.sideBySide)
        assertEquals(state.copy(currentId = null), Backup.read(Backup.write(state)))
    }

    @Test
    fun aLibraryFromBeforeShowsPagesSideBySideAndGoesByWhatEachBookSays() {
        val old = """{"version":1,"settings":{"textScale":1.0},"books":[{"id":"a","title":"A","format":"CBZ","fileName":"a.cbz"}]}"""
        val read = LibraryJson.decode(old)
        assertTrue(read.settings.sideBySide)
        assertNull(read.books.single().rightToLeft)
    }

    @Test
    fun aBackupGivesItsChoiceOnlyWhereNoneWasMadeHere() {
        val here = LibraryState(books = listOf(book("x", "manga.cbz"), book("y", "other.cbz").copy(rightToLeft = false)))
        val saved = LibraryState(books = listOf(book("1", "manga.cbz").copy(rightToLeft = true), book("2", "other.cbz").copy(rightToLeft = true)))
        val merged = Backup.merge(here, saved)
        assertEquals(listOf(true, false), merged.books.map { it.rightToLeft })
    }

    @Test
    fun onlyYesAndRightToLeftSaysAMangaReadsRightToLeft() {
        assertTrue(Comic.readsRightToLeft("YesAndRightToLeft"))
        assertTrue(Comic.readsRightToLeft(" yesandrighttoleft "))
        // Any manga is "Yes": that says nothing of which way its pages turn.
        assertFalse(Comic.readsRightToLeft("Yes"))
        assertFalse(Comic.readsRightToLeft("No"))
        assertFalse(Comic.readsRightToLeft("Unknown"))
        assertFalse(Comic.readsRightToLeft(null))
    }
}
