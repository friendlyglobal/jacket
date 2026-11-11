package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.LibraryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightsExportTest {
    private fun book(id: String, title: String, author: String = "") =
        Book(id = id, title = title, author = author, format = BookFormat.EPUB, fileName = "$id.epub", coverFile = null, seedColor = null, sourceName = "", sizeBytes = 0, addedAt = 0)

    private fun highlight(book: String, at: Float, text: String, note: String = "", chapter: String = "") =
        Annotation(id = "$book-$at", bookId = book, kind = AnnotationKind.HIGHLIGHT, progress = at, chapter = chapter, text = text, note = note)

    private val place: (Annotation) -> String = { listOf(it.chapter, "${(it.progress * 100).toInt()}%").filter(String::isNotBlank).joinToString(" · ") }

    private val moby = book("m", "Moby Dick", "Herman Melville")
    private val notes = listOf(
        highlight("m", 0.5f, " Call me Ishmael. ", note = " The famous opening. ", chapter = "Loomings"),
        highlight("m", 0.1f, "First line\nsecond line"),
        Annotation(id = "b", bookId = "m", kind = AnnotationKind.BOOKMARK, progress = 0.2f, text = "A bookmarked page"),
        highlight("m", 0.3f, "   "),
    )

    @Test
    fun `highlights come in the order of the book, without bookmarks or empty ones`() {
        assertEquals(listOf(0.1f, 0.5f), highlightsOf(notes).map { it.progress })
    }

    @Test
    fun `shared with another app, the text is what it always was`() {
        assertEquals(
            "# Moby Dick — Herman Melville\n\n> First line\nsecond line\n(10%)\n\n> Call me Ishmael.\n(Loomings · 50%)\nThe famous opening.",
            highlightsText(moby, highlightsOf(notes), place = place),
        )
    }

    @Test
    fun `saved as a file, the quote, its place and its note are paragraphs of their own`() {
        assertEquals(
            """
            # Moby Dick — Herman Melville

            > First line
            > second line

            (10%)

            > Call me Ishmael.

            (Loomings · 50%)

            The famous opening.

            """.trimIndent(),
            highlightsText(moby, highlightsOf(notes), asFile = true, place = place),
        )
    }

    @Test
    fun `a book without an author is headed by its title alone`() {
        assertEquals("# Notes\n\n> A line\n(0%)", highlightsText(book("n", " Notes "), listOf(highlight("n", 0f, "A line")), place = place))
    }

    @Test
    fun `all books are saved in one file, by title, those with highlights only`() {
        val state = LibraryState(
            books = listOf(moby, book("e", "emma", "Jane Austen"), book("z", "Zadig")),
            annotations = notes + highlight("e", 0.9f, "Handsome, clever, and rich"),
        )
        assertEquals(
            """
            # emma — Jane Austen

            > Handsome, clever, and rich

            (90%)

            # Moby Dick — Herman Melville

            > First line
            > second line

            (10%)

            > Call me Ishmael.

            (Loomings · 50%)

            The famous opening.

            """.trimIndent(),
            allHighlightsMarkdown(state, place),
        )
    }

    @Test
    fun `with nothing highlighted there is nothing to save`() {
        assertNull(allHighlightsMarkdown(LibraryState(books = listOf(moby)), place))
        assertNull(allHighlightsMarkdown(LibraryState(books = listOf(moby), annotations = listOf(notes[2], notes[3])), place))
        // A highlight whose book has been removed has no book to be saved under.
        assertNull(allHighlightsMarkdown(LibraryState(annotations = listOf(notes[0])), place))
    }

    @Test
    fun `the file is named for what it holds, in letters a file name can have`() {
        assertEquals("Moby Dick Highlights.md", highlightsFileName("Moby Dick", "Highlights"))
        assertEquals("Jacket Highlights 2026-11-11.md", highlightsFileName("Jacket", "Highlights", "2026-11-11"))
        assertEquals("Either Or A Fragment Markierungen.md", highlightsFileName("Either/Or: A \"Fragment\"?", "Markierungen"))
        assertEquals("吶喊 畫線.md", highlightsFileName("吶喊", "畫線"))
        assertEquals(120 + 3, highlightsFileName("x".repeat(500), "Highlights").length)
    }
}
