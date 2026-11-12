package io.github.friendlyglobal.jacket.reader

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book posture: when a fold is a spine, and how the pages are laid out about it. */
class BookPostureTest {
    // A Pixel 9 Pro Fold's inner screen: 2076 by 2152 pixels, 2.625 pixels to the dp.
    private val width = 2076
    private val height = 2152
    private val slop = BookPosture.OFF_CENTRE * 2.625f

    @Test
    fun anUprightFoldDownTheMiddleIsASpine() {
        val fold = IntRect(1038, 0, 1038, 2152)
        assertEquals(fold, BookPosture.spine(fold, width, height, slop))
        // A hinge with some width to it (two screens side by side) is one too.
        val hinge = IntRect(1000, 0, 1076, 2152)
        assertEquals(hinge, BookPosture.spine(hinge, width, height, slop))
    }

    @Test
    fun aFoldLyingFlatIsNoSpine() {
        // Tabletop: the same phone turned on its side.
        assertNull(BookPosture.spine(IntRect(0, 1038, 2152, 1038), 2152, 2076, slop))
        assertNull(BookPosture.spine(null, width, height, slop))
    }

    @Test
    fun aFoldOffTheMiddleOfTheWindowIsNoSpine() {
        // Beside another app, the window is a part of the screen and the fold is near its edge.
        assertNull(BookPosture.spine(IntRect(300, 0, 300, 2152), 1400, height, slop))
        assertNull(BookPosture.spine(IntRect(0, 0, 0, 2152), 1038, height, slop))
        // One that stops short of the window's height doesn't part it in two.
        assertNull(BookPosture.spine(IntRect(1038, 0, 1038, 1000), width, height, slop))
        // A few pixels out is still the middle.
        assertEquals(IntRect(1050, 0, 1050, 2152), BookPosture.spine(IntRect(1050, 0, 1050, 2152), width, height, slop))
    }

    @Test
    fun readiumsOwnMarginGoesByTheWindowsWidth() {
        assertEquals(20f, BookPosture.pageGutter(400f))
        assertEquals(30f, BookPosture.pageGutter(600f))
        assertEquals(40f, BookPosture.pageGutter(791f))
        assertEquals(50f, BookPosture.pageGutter(1280f))
    }

    @Test
    fun aReaderWithUsualTextAndMarginsKeepsThePagesAsTheyAre() {
        // 791dp wide, no width to the fold, text at its usual size, "Normal" margins.
        val spread = BookPosture.spread(widthDp = 791f, foldDp = 0f, textScale = 1f, margins = 1.0)
        assertFalse(spread.twoPages)
        assertEquals(1.0, spread.margins, 0.0)
        // Wide margins stay wide.
        assertEquals(1.6, BookPosture.spread(791f, 0f, 1f, 1.6).margins, 0.0)
    }

    @Test
    fun narrowMarginsWidenJustEnoughToKeepClearOfTheFold() {
        val spread = BookPosture.spread(widthDp = 791f, foldDp = 0f, textScale = 1f, margins = 0.5)
        // 0.5 of 40dp would leave the words 20dp from the fold: 28dp is kept.
        assertEquals(0.7, spread.margins, 1e-6)
        assertEquals(BookPosture.CLEAR_OF_FOLD, (spread.margins * BookPosture.pageGutter(791f)).toFloat(), 1e-3f)
    }

    @Test
    fun aHingeWithWidthPushesTheWordsFurtherOut() {
        // A hinge 32dp wide hides 16dp of each page: the words start 28dp beyond that.
        val spread = BookPosture.spread(widthDp = 1100f, foldDp = 32f, textScale = 1f, margins = 1.0)
        assertEquals(44.0 / 40.0, spread.margins, 1e-6)
    }

    @Test
    fun largeTextIsGivenTwoPagesWhereReadiumWouldShowOne() {
        // Two columns of 22rem fit 791dp up to about 112% text.
        assertFalse(BookPosture.spread(791f, 0f, 1.1f, 1.0).twoPages)
        assertTrue(BookPosture.spread(791f, 0f, 1.25f, 1.0).twoPages)
        assertTrue(BookPosture.spread(791f, 0f, 3f, 1.0).twoPages)
    }
}
