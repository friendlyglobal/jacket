package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A book's progress is the number the library, the widgets, the cover and the reader show. Every
 * place an EPUB or a text book is left at is saved through [Book.leftAt], and there the number
 * never goes down by itself: only when the reader went back through the book.
 *
 * The numbers are ones from a Pixel 9 Pro Fold, unfolded: "The Time Machine" read aloud with the
 * book shut, then opened.
 */
class BookPlaceTest {
    private fun book(progress: Float, locator: String? = PAGE) = Book(
        id = "b1", title = "The Time Machine", author = "H. G. Wells", format = BookFormat.EPUB, fileName = "b1.epub",
        coverFile = null, seedColor = null, sourceName = "The Time Machine.epub", sizeBytes = 535571, addedAt = 1,
        position = 41, sectionCount = 96, progress = progress, locator = locator,
    )

    @Test
    fun `a place counted again a little lower doesn't take the progress down`() {
        // Paused with the book shut, the voice's sentence was counted as 43.051% from where it is
        // in its file. Opened, the reader counts it from where its page starts: 42.789%. It is
        // the same sentence, and the book goes on saying 43%.
        val heard = book(0.42789f).leftAt(SENTENCE, 0.43051f, 41, 96, null, back = false)
        assertEquals(43, heard.percent)
        val opened = heard.leftAt(SENTENCE, 0.42789f, 41, 96, null, back = false)
        assertEquals(0.43051f, opened.progress, 0f)
        assertEquals(43, opened.percent)
    }

    @Test
    fun `the place saved is always the one given, whatever is done about the number`() {
        // The page a book is put back to after a fold starts earlier than the page it was on (a
        // spread holds two of the folded phone's pages): the place is that page's now, and the
        // number stays.
        val folded = book(0.43051f, locator = SENTENCE)
        val unfolded = folded.leftAt(PAGE, 0.42789f, 40, 97, 0.25f, back = false)
        assertEquals(PAGE, unfolded.locator)
        assertEquals(40, unfolded.position)
        assertEquals(97, unfolded.sectionCount)
        assertEquals(0.25f, unfolded.positionOffset, 0f)
        assertEquals(0.43051f, unfolded.progress, 0f)
    }

    @Test
    fun `reading on takes the number up`() {
        val next = book(0.43051f).leftAt(PAGE, 0.43991f, 42, 96, null, back = false)
        assertEquals(0.43991f, next.progress, 0f)
    }

    @Test
    fun `going back takes it down, to exactly what is counted there`() {
        // A page turned back, a jump to an earlier chapter, an earlier sentence tapped.
        assertEquals(0.42789f, book(0.43051f).leftAt(PAGE, 0.42789f, 41, 96, null, back = true).progress, 0f)
        assertEquals(0.05f, book(0.43051f).leftAt(PAGE, 0.05f, 4, 96, null, back = true).progress, 0f)
        // All the way to the first page.
        assertEquals(0f, book(0.43051f).leftAt(PAGE, 0f, 0, 96, null, back = true).progress, 0f)
    }

    @Test
    fun `however far a count falls short, it takes nothing off by itself`() {
        // What bit the owner on 1.0.0: 42% become 20-something with nobody having gone anywhere.
        // Whatever miscounts a place like that now, the number stays where it was.
        assertEquals(0.42f, book(0.42f).leftAt(PAGE, 0.2f, 19, 96, null, back = false).progress, 0f)
    }

    @Test
    fun `the last page stays all of the book through a fold`() {
        // The last page counts as 100%, though it starts a little before the end; laid out anew
        // it is counted from where it starts.
        assertEquals(1f, book(1f).leftAt(PAGE, 0.97f, 95, 96, null, back = false).progress, 0f)
        // Turned back from, it is no longer all of the book.
        assertEquals(0.96f, book(1f).leftAt(PAGE, 0.96f, 94, 96, null, back = true).progress, 0f)
    }

    @Test
    fun `a book never opened takes its first count`() {
        assertEquals(0.012f, book(0f, locator = null).leftAt(PAGE, 0.012f, 1, 96, null, back = false).progress, 0f)
    }

    @Test
    fun `a count out of range is brought into it, and a missing offset leaves the old one`() {
        val kept = book(0.4f).copy(positionOffset = 0.5f)
        assertEquals(1f, kept.leftAt(PAGE, 1.2f, 95, 96, null, back = false).progress, 0f)
        assertEquals(0f, kept.leftAt(PAGE, -0.1f, 0, 96, null, back = true).progress, 0f)
        assertEquals(0.5f, kept.leftAt(PAGE, 0.41f, 40, 96, null, back = false).positionOffset, 0f)
        assertEquals(0, kept.leftAt(PAGE, 0.41f, -3, -1, null, back = false).position)
    }

    private companion object {
        const val PAGE = """{"href":"epub/text/chapter-5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5384814910883018},"text":{"highlight":"of the world had to be mastered. I had not, I said"}}"""
        const val SENTENCE = """{"href":"epub/text/chapter-5.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5617446677393954,"cssSelector":"#chapter-5 > p:nth-child(25)"},"text":{"highlight":"For, by merely seeming fond of me, and showing in "}}"""
    }
}
