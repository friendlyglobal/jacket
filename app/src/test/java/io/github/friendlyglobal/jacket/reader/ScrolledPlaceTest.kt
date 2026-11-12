package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.leftOnPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page a PDF or a comic is scrolled to is saved like every other place: the place is the one
 * given, and the book's progress only goes down when the reader went back through the pages
 * themselves (see Book.leftOnPage and [ScrolledPlace]).
 *
 * The book is a PDF of 400 pages, left on page 212 with the top of the screen 40% of the way
 * down it.
 */
class ScrolledPlaceTest {
    private val pages = 400

    private fun counted(page: Int, offset: Float) = (page + offset) / pages

    private fun book(page: Int = 211, offset: Float = 0.4f, progress: Float = counted(211, 0.4f), locator: String? = null) = Book(
        id = "p1", title = "A long PDF", author = "", format = BookFormat.PDF, fileName = "p1.pdf", coverFile = null, seedColor = null,
        sourceName = "long.pdf", sizeBytes = 4_000_000, addedAt = 1, position = page, positionOffset = offset, sectionCount = pages,
        progress = progress, locator = locator,
    )

    /** The reader's place as the pages report it, saved the way the reader screen saves it. */
    private fun Book.scrolledTo(place: ScrolledPlace, page: Int, offset: Float): Book =
        leftOnPage(page, offset, counted(page, offset), pages, back = place.wentBackTo(page, offset))

    // ---- Whether the reader went back --------------------------------------------------------

    @Test
    fun `the page a book opens on is its place shown again, not a step back`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        // Put back to the nearest pixel of a page that is another height on this screen.
        assertFalse(place.wentBackTo(211, 0.3996f))
        assertFalse(place.wentBackTo(211, 0.4f))
    }

    @Test
    fun `scrolling on is not going back, and scrolling up is`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        assertFalse(place.wentBackTo(211, 0.9f))
        assertFalse(place.wentBackTo(213, 0.1f))
        assertTrue(place.wentBackTo(212, 0.8f))
        // And from there on is on again.
        assertFalse(place.wentBackTo(212, 0.9f))
    }

    @Test
    fun `a jump to an earlier page is going back`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        assertTrue(place.wentBackTo(11, 0f))
        assertFalse(place.wentBackTo(300, 0f))
        assertTrue(place.wentBackTo(0, 0f))
    }

    @Test
    fun `pages laid out anew show the same place again`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        // Unfolded, the page is wider and taller, and the same top of the screen is less of the
        // way down it: counted from where it is now, nobody went anywhere.
        place.at(211, 0.31f)
        assertFalse(place.wentBackTo(211, 0.31f))
        // Turning back from there is still turning back.
        assertTrue(place.wentBackTo(210, 0.9f))
    }

    @Test
    fun `the first place seen after the voice had the pages is where the place is now`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        // The pages followed the voice somewhere (back a chapter, say) and its place was the
        // voice's to save: where they are when it stops is not the reader going back.
        place.shownAgain()
        assertFalse(place.wentBackTo(180, 0.2f))
        assertTrue(place.wentBackTo(179, 0f))
    }

    @Test
    fun `many small steps back add up to one`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        // Each less than a hundredth of a page: a line of pixels at a time.
        assertFalse(place.wentBackTo(211, 0.395f))
        assertFalse(place.wentBackTo(211, 0.392f))
        assertTrue(place.wentBackTo(211, 0.385f))
    }

    @Test
    fun `a place that isn't a number is the top of its page, and one out of bounds is kept within them`() {
        val place = ScrolledPlace()
        place.at(5, Float.NaN)
        assertFalse(place.wentBackTo(5, 0f))
        // Page -3 is the first page, and 200% of the way down a page is its foot.
        place.at(0, 1f)
        assertFalse(place.wentBackTo(-3, 2f))
    }

    // ---- What is saved of it -------------------------------------------------------------------

    @Test
    fun `reopened on another screen, the percent stays and the place is the one given`() {
        // 211.4 pages of 400 is 52.85%. Opened on a screen where the page is another height, it
        // is put back 40% of the way down to the nearest pixel, and counted a hair lower.
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        val opened = book().scrolledTo(place, 211, 0.3996f)
        assertEquals(counted(211, 0.4f), opened.progress, 0f)
        assertEquals(211, opened.position)
        assertEquals(0.3996f, opened.positionOffset, 0f)
        assertEquals(pages, opened.sectionCount)
    }

    @Test
    fun `unfolded with the book open, the percent stays though the page is counted lower`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        // The list keeps the same pixels above the screen's top, which is less of a taller page:
        // 31% of the way down it. The pages say where the place is now, and the next scroll is
        // counted from there.
        place.at(211, 0.31f)
        val unfolded = book().scrolledTo(place, 211, 0.33f)
        assertEquals(counted(211, 0.4f), unfolded.progress, 0f)
        assertEquals(0.33f, unfolded.positionOffset, 0f)
    }

    @Test
    fun `left by the voice on a line below the top of the screen, a first scroll takes nothing off`() {
        // The voice counted its sentence's own line, 60% of the way down page 212; the pages show
        // that line a quarter of the way down the screen, so the screen's top is 45% down the page.
        val heard = book(offset = 0.6f, progress = counted(211, 0.6f), locator = SENTENCE)
        val place = ScrolledPlace()
        place.shownAgain()
        val scrolled = heard.scrolledTo(place, 211, 0.45f)
        assertEquals(counted(211, 0.6f), scrolled.progress, 0f)
        assertEquals(0.45f, scrolled.positionOffset, 0f)
        // The sentence the voice stopped on is still the one it takes up again.
        assertEquals(SENTENCE, scrolled.locator)
    }

    @Test
    fun `reading on takes the percent up`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        val on = book().scrolledTo(place, 215, 0.5f)
        assertEquals(counted(215, 0.5f), on.progress, 0f)
        assertEquals(53, on.percent)
    }

    @Test
    fun `turning back takes it down, to exactly what is counted there`() {
        val place = ScrolledPlace()
        place.at(211, 0.4f)
        val back = book().scrolledTo(place, 199, 0f)
        assertEquals(counted(199, 0f), back.progress, 0f)
        assertEquals(199, back.position)
        // And a deliberate jump to the first page takes it all the way.
        assertEquals(0f, back.scrolledTo(place, 0, 0f).progress, 0f)
    }

    @Test
    fun `the last page is the end, and going back from it is going back`() {
        val place = ScrolledPlace()
        place.at(398, 0f)
        // The list can scroll no further: the reader screen counts that as the whole book.
        val end = book(398, 0f, counted(398, 0f)).leftOnPage(399, 0.2f, 1f, pages, back = place.wentBackTo(399, 0.2f))
        assertEquals(1f, end.progress, 0f)
        val back = end.scrolledTo(place, 397, 0.5f)
        assertEquals(counted(397, 0.5f), back.progress, 0f)
    }

    @Test
    fun `a place that can't be right is kept within bounds`() {
        val kept = book()
        assertEquals(1f, kept.leftOnPage(399, 0f, 1.4f, pages, back = false).progress, 0f)
        assertEquals(0f, kept.leftOnPage(0, 0f, -0.2f, pages, back = true).progress, 0f)
        assertEquals(0, kept.leftOnPage(-2, 0f, 0.1f, -1, back = false).position)
        assertEquals(0, kept.leftOnPage(-2, 0f, 0.1f, -1, back = false).sectionCount)
        assertEquals(1f, kept.leftOnPage(3, 7f, 0.1f, pages, back = false).positionOffset, 0f)
        // An offset that isn't a number leaves the one the book had.
        assertEquals(0.4f, kept.leftOnPage(3, Float.NaN, 0.1f, pages, back = false).positionOffset, 0f)
    }

    private companion object {
        const val SENTENCE = """{"href":"text/part0003.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""
    }
}
