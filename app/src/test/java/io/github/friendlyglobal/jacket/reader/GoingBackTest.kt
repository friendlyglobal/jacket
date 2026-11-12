package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A book's progress only goes down when the reader went back through it themselves (see
 * Book.leftAt). This is how that is told: from the pages Readium reports, and from where the
 * listener sends the voice. Everything else that shows a place again (the book reopened, the
 * pages laid out anew, the voice handing the place to a reader) is not going back.
 *
 * The pages are chapter 5 of "The Time Machine" on a Pixel 9 Pro Fold: 13 spreads unfolded, 26
 * pages folded.
 */
class GoingBackTest {
    private val chapter5 = 5

    /** The spread, counting from 0, of the 13 the chapter has unfolded. */
    private fun spread(index: Int, file: Int = chapter5) = PageSeen(file, index / 13.0, 13)

    /** The page of the 26 it has folded. */
    private fun page(index: Int, file: Int = chapter5) = PageSeen(file, index / 26.0, 26)

    // ---- Which page comes before which ----------------------------------------------------------

    @Test
    fun `a page is before the ones after it in its file, and not before itself`() {
        assertTrue(spread(6).isBefore(spread(7)))
        assertTrue(spread(0).isBefore(spread(12)))
        assertFalse(spread(7).isBefore(spread(6)))
        assertFalse(spread(7).isBefore(spread(7)))
    }

    @Test
    fun `a page reported a few pixels off is the same page`() {
        // Readium tells where a page starts from the web view's scroll offset: 0.53846 one time,
        // 0.53848 the next.
        val again = PageSeen(chapter5, 0.5384814910883018, 13)
        assertFalse(again.isBefore(spread(7)))
        assertFalse(spread(7).isBefore(again))
        assertTrue(again.isSameAs(spread(7)))
        assertFalse(spread(6).isSameAs(spread(7)))
    }

    @Test
    fun `a page of an earlier chapter file is before, however either is laid out`() {
        assertTrue(spread(12, file = 4).isBefore(spread(0)))
        assertTrue(page(25, file = 4).isBefore(spread(0)))
        assertFalse(spread(0, file = 6).isBefore(spread(12)))
    }

    @Test
    fun `pages laid out differently say nothing of each other`() {
        // Folded page 14 of 26 starts at 0.5385, where unfolded spread 7 of 13 does; page 13
        // starts at 0.5, inside spread 6. Neither count says which words are where.
        assertFalse(page(13).isBefore(spread(7)))
        assertFalse(spread(6).isBefore(page(14)))
        // Nor does a page whose file hasn't said how many pages it has.
        assertFalse(PageSeen(chapter5, 0.2, null).isBefore(spread(7)))
    }

    @Test
    fun `a file the book doesn't have is before nothing`() {
        assertFalse(spread(0, file = -1).isBefore(spread(7)))
        assertFalse(spread(0).isBefore(spread(7, file = -1)))
    }

    @Test
    fun `scrolling, a place is before another when it is more than a hair above it`() {
        val here = PageSeen(chapter5, 0.540, null)
        assertTrue(PageSeen(chapter5, 0.520, null).isBefore(here))
        assertFalse(PageSeen(chapter5, 0.535, null).isBefore(here))
        assertFalse(PageSeen(chapter5, 0.560, null).isBefore(here))
    }

    // ---- The reader going back, and everything that isn't ----------------------------------------

    @Test
    fun `turning a page back is going back, and turning one forward isn't`() {
        val place = PagePlace()
        place.at(spread(7))
        assertFalse(place.wentBackTo(spread(8), now = 1_000))
        assertTrue(place.wentBackTo(spread(7), now = 2_000))
        assertTrue(place.wentBackTo(spread(6), now = 3_000))
        assertFalse(place.wentBackTo(spread(7), now = 4_000))
    }

    @Test
    fun `a book opened at its place has gone nowhere`() {
        // The reader opens on the page the saved place is on. That page is the place, shown again.
        val place = PagePlace()
        place.unsettled()
        place.at(spread(7))
        assertFalse(place.wentBackTo(spread(7), now = 1_000))
        // And with nothing said of where the place is yet, the first page seen is taken for it.
        val unknown = PagePlace()
        assertFalse(unknown.wentBackTo(spread(3), now = 1_000))
        assertTrue(unknown.wentBackTo(spread(2), now = 2_000))
    }

    @Test
    fun `unfolding puts the reader on a page that starts earlier, and that isn't going back`() {
        // Folded on page 15 of 26 (it starts 0.577 of the way down the file). Unfolded, those
        // words are on spread 7 of 13, which starts at 0.538.
        val place = PagePlace()
        place.at(page(15))
        place.unsettled()
        assertFalse(place.wentBackTo(spread(7), now = 1_000))
        // From there the pages tell again.
        assertTrue(place.wentBackTo(spread(6), now = 2_000))
    }

    @Test
    fun `a page it couldn't be put back to still takes nothing off, until the reader turns back`() {
        // The pages were laid out anew and Readium wouldn't go to the place: nothing says where it
        // is among them. Whatever page the reader then turns to is taken for it.
        val place = PagePlace()
        place.at(page(15))
        place.unsettled()
        assertFalse(place.wentBackTo(spread(5), now = 1_000))
        assertTrue(place.wentBackTo(spread(4), now = 2_000))
    }

    @Test
    fun `a jump back is going back, into an earlier chapter or up the same one`() {
        val place = PagePlace()
        place.at(spread(7))
        assertTrue(place.wentBackTo(spread(0, file = 2), now = 1_000))
        place.at(spread(7))
        assertTrue(place.wentBackTo(spread(1), now = 2_000))
        // A jump forward isn't.
        assertFalse(place.wentBackTo(spread(4, file = 9), now = 3_000))
    }

    @Test
    fun `a jump the reader asked for is theirs even before the pages are known`() {
        // The contents were used a moment after the phone was unfolded: nothing says yet which
        // page the place is on, but the reader did ask to go.
        val place = PagePlace()
        place.at(page(15))
        place.unsettled()
        place.jumped(now = 10_000)
        assertTrue(place.jumping(now = 10_500))
        assertTrue(place.wentBackTo(spread(0, file = 2), now = 11_000))
        // Said once: the page after it is told from the pages again.
        assertFalse(place.jumping(now = 11_500))
        assertFalse(place.wentBackTo(spread(1, file = 2), now = 12_000))
    }

    @Test
    fun `a jump that never moved the page is forgotten`() {
        // The scrubber let go where it was. Much later the phone is unfolded: that is no jump.
        val place = PagePlace()
        place.at(page(15))
        place.jumped(now = 10_000)
        place.unsettled()
        assertFalse(place.jumping(now = 60_000))
        assertFalse(place.wentBackTo(spread(7), now = 60_000))
    }

    @Test
    fun `the page a listener chose while the voice read on is theirs when the voice stops`() {
        // They turned ahead to look at a picture; the voice read on past it and was stopped.
        // The page in front of them is their place, wherever the voice had got to.
        val place = PagePlace()
        place.at(spread(7))
        place.choseAPage()
        assertTrue(place.wentBackTo(spread(9), now = 1_000))
        assertFalse(place.wentBackTo(spread(10), now = 2_000))
    }

    @Test
    fun `the page the voice is seen on is where turning back is told from`() {
        // The voice turned the pages from spread 7 to spread 9 and was paused there.
        val place = PagePlace()
        place.at(spread(7))
        place.at(spread(9))
        assertTrue(place.isOn(spread(9)))
        assertFalse(place.isOn(spread(8)))
        assertTrue(place.wentBackTo(spread(8), now = 1_000))
    }

    @Test
    fun `scrolling back a line at a time adds up to going back`() {
        val place = PagePlace()
        place.at(PageSeen(chapter5, 0.540, null))
        // Each step is too small to be told from the same place reported again...
        assertFalse(place.wentBackTo(PageSeen(chapter5, 0.536, null), now = 1_000))
        assertFalse(place.wentBackTo(PageSeen(chapter5, 0.532, null), now = 2_000))
        // ...but together they are more than that.
        assertTrue(place.wentBackTo(PageSeen(chapter5, 0.528, null), now = 3_000))
        // And scrolling on isn't going back.
        assertFalse(place.wentBackTo(PageSeen(chapter5, 0.6, null), now = 4_000))
    }

    // ---- The voice sent back ----------------------------------------------------------------------

    @Test
    fun `a sentence is before another by the letters before it, and by its chapter file`() {
        assertTrue(comesBefore(5, 0.5617, 5, 0.5633))
        assertFalse(comesBefore(5, 0.5633, 5, 0.5617))
        assertFalse(comesBefore(5, 0.5617, 5, 0.5617))
        assertTrue(comesBefore(4, 0.99, 5, 0.0))
        assertFalse(comesBefore(6, 0.0, 5, 0.99))
        // A sentence in a file the book doesn't have says nothing.
        assertFalse(comesBefore(-1, 0.1, 5, 0.5))
        assertFalse(comesBefore(5, 0.1, -1, 0.5))
    }

    // ---- A place from Jacket 1.0.0 -----------------------------------------------------------------

    @Test
    fun `a sentence 1_0_0 saved by its count of blocks is counted over, one saved since is not`() {
        // 1.0.0 said "a fifth of the blocks down"; by its words the sentence is on the first pages.
        assertTrue(savedTheOldWay(saved = 0.2, placed = 0.0067))
        // One saved since comes out as it went in.
        assertFalse(savedTheOldWay(saved = 0.5617446677393954, placed = 0.5617446677393954))
        // Nothing to compare: nothing to put right.
        assertFalse(savedTheOldWay(saved = null, placed = 0.5))
        assertFalse(savedTheOldWay(saved = 0.5, placed = null))
    }
}
