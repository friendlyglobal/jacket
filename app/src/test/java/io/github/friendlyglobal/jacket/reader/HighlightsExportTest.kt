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
    fun `a PDF's highlights say the page they are on, shared and saved`() {
        val words: (Annotation) -> String = { highlightPlace(it, separator = " · ", page = { number -> "Page $number" }, percent = { percent -> "$percent%" }) }
        val manual = book("p", "The Manual").copy(format = BookFormat.PDF)
        val marks = listOf(
            // Under a chapter of the PDF's outline, with a note; and in a PDF with no outline (pages count from 1).
            Annotation("h2", "p", AnnotationKind.HIGHLIGHT, locator = PdfAnchor(11, "before", "after").write(), section = 11, progress = 0.3f, chapter = "Setting up", text = "Hold the button for five seconds.", note = "Try this"),
            Annotation("h1", "p", AnnotationKind.HIGHLIGHT, locator = PdfAnchor(0).write(), section = 0, progress = 0.01f, text = "Read this first.\nAll of it."),
            // A bookmark has a page too, and is no highlight.
            Annotation("b", "p", AnnotationKind.BOOKMARK, section = 4, progress = 0.1f, text = "Page 5"),
        )
        assertEquals("Setting up · Page 12 · 30%", words(marks[0]))
        assertEquals("Page 1 · 1%", words(marks[1]))
        assertEquals("10%", words(marks[2]))
        // An EPUB's highlight says what it always did.
        assertEquals("Loomings · 50%", words(notes[0]))
        assertEquals(
            "# The Manual\n\n> Read this first.\nAll of it.\n(Page 1 · 1%)\n\n> Hold the button for five seconds.\n(Setting up · Page 12 · 30%)\nTry this",
            highlightsText(manual, highlightsOf(marks), place = words),
        )
        assertEquals(
            """
            # The Manual

            > Read this first.
            > All of it.

            (Page 1 · 1%)

            > Hold the button for five seconds.

            (Setting up · Page 12 · 30%)

            Try this

            """.trimIndent(),
            highlightsText(manual, highlightsOf(marks), asFile = true, place = words),
        )
        // In the whole library's file it stands under its own book, as any other book's do.
        val all = allHighlightsMarkdown(LibraryState(books = listOf(moby, manual), annotations = notes + marks), words)!!
        assertEquals(listOf("# Moby Dick — Herman Melville", "# The Manual"), all.lines().filter { it.startsWith("# ") })
        assertEquals(1, Regex("\\(Setting up · Page 12 · 30%\\)").findAll(all).count())
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
