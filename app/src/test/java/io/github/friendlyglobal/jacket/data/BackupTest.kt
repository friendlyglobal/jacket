package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private fun book(id: String, name: String, opened: Long = 0, progress: Float = 0f) = Book(
        id = id, title = "T$id", author = "A", format = BookFormat.EPUB, fileName = "$id.epub",
        coverFile = null, seedColor = null, sourceName = name, sizeBytes = 100, addedAt = 1,
        progress = progress, lastOpenedAt = opened,
    )

    private val highlight = Annotation(
        id = "h1", bookId = "b1", kind = AnnotationKind.HIGHLIGHT, locator = "{\"href\":\"x\"}",
        progress = 0.4f, chapter = "II", text = "a quote", note = "nice", color = HighlightColor.BLUE, createdAt = 5,
    )

    @Test
    fun roundTripKeepsEverything() {
        val state = LibraryState(
            books = listOf(book("b1", "one.epub", opened = 10, progress = 0.5f).copy(finishedAt = 7, locator = "{\"href\":\"c\"}")),
            settings = Settings(volumeKeys = true, lineSpacing = LineSpacing.LOOSE, margins = PageMargins.WIDE, alignment = TextAlignment.JUSTIFY),
            annotations = listOf(highlight),
        )
        assertEquals(state.copy(currentId = null), Backup.read(Backup.write(state)))
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backup.read("{\"books\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { Backup.read("not json") }
    }

    @Test
    fun mergeMatchesBooksByFileAndKeepsTheFurthestPlace() {
        // Same file, imported again on a new phone under a new id.
        val here = LibraryState(books = listOf(book("new", "one.epub", opened = 5, progress = 0.1f)))
        val saved = LibraryState(
            books = listOf(book("b1", "one.epub", opened = 50, progress = 0.6f), book("b2", "two.epub")),
            annotations = listOf(highlight),
        )
        val merged = Backup.merge(here, saved)
        assertEquals(listOf("new", "b2"), merged.books.map { it.id })
        assertEquals(0.6f, merged.books[0].progress)
        // The highlight follows its book to the new id.
        assertEquals("new", merged.annotations.single().bookId)
    }

    @Test
    fun backupFileNamesCannotLeaveTheBooksFolder() {
        val evil = book("x", "evil.epub").copy(fileName = "../library.json", coverFile = "../../shared_prefs/a.xml")
        val merged = Backup.merge(LibraryState(), LibraryState(books = listOf(evil)))
        assertEquals("library.json", merged.books.single().fileName)
        assertEquals("a.xml", merged.books.single().coverFile)
    }

    @Test
    fun oldLibrariesKeepBooksReadToTheEndAsFinished() {
        val old = """{"books":[{"id":"b","title":"T","format":"EPUB","fileName":"b.epub","progress":1.0,"lastOpenedAt":42}]}"""
        assertEquals(42L, LibraryJson.decode(old).books.single().finishedAt)
    }

    @Test
    fun mergeDoesNotDuplicateAnnotations() {
        val state = LibraryState(books = listOf(book("b1", "one.epub")), annotations = listOf(highlight))
        val merged = Backup.merge(state, state)
        assertEquals(1, merged.annotations.size)
        assertTrue(merged.books.size == 1)
    }
}
