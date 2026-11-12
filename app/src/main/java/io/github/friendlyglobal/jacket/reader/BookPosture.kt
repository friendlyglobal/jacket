package io.github.friendlyglobal.jacket.reader

import androidx.compose.ui.unit.IntRect
import kotlin.math.abs
import kotlin.math.max

/*
 * Book posture: a phone that folds like a book, held half-open, is a paperback, and its fold the
 * spine. The two pages of the spread sit one on each side of it, the text keeps clear of it, and
 * the reader's controls move off it onto one page.
 *
 * Readium already lays two columns out about the middle of the window, which on such a phone is
 * where the fold is. So little has to change, and for most readers nothing about the page does:
 * only where two pages wouldn't otherwise be shown (large text), or where the margins chosen
 * would bring the words up to the fold, are the pages laid out anew.
 */

/** How the pages are laid out about the spine: [twoPages] where Readium wouldn't show two by itself, and the least the side [margins] may be. */
internal data class BookSpread(val twoPages: Boolean, val margins: Double)

internal object BookPosture {
    /** The words keep this far from the fold's edge (dp): the screen curves into it for a few millimetres either side. */
    const val CLEAR_OF_FOLD = 28f

    /** A fold this far off the middle of the window (dp) is not between the two pages: the window isn't the whole screen. */
    const val OFF_CENTRE = 12f

    /** The width of a column Jacket asks Readium for, in the page's own units (see `readerConfiguration`): two are shown where two fit. */
    const val COLUMN_REM = 22f
    private const val REM = 16f

    /**
     * The fold as a spine, in a window [width] by [height] pixels: the bounds of a [fold] that
     * stands upright from the top of the window to the bottom, in the middle of it ([offCentre]
     * pixels at most to either side). Null for any other fold, or none: a window on one half of
     * the screen, or beside another app, has no spine.
     */
    fun spine(fold: IntRect?, width: Int, height: Int, offCentre: Float): IntRect? {
        if (fold == null || width <= 0 || height <= 0) return null
        if (fold.height < fold.width || fold.top > 0 || fold.bottom < height) return null
        if (fold.left <= 0 || fold.right >= width) return null
        val middle = (fold.left + fold.right) / 2f
        return fold.takeIf { abs(middle - width / 2f) <= offCentre }
    }

    /**
     * The margin Readium's own stylesheet puts beside each page (CSS pixels, which are dp), for a
     * window [widthDp] wide: the reader's choice of margins is a factor of this.
     */
    fun pageGutter(widthDp: Float): Float = when {
        widthDp >= 75 * REM -> 50f
        widthDp >= 45 * REM -> 40f
        widthDp >= 35 * REM -> 30f
        else -> 20f
    }

    /**
     * How the pages are laid out about a spine [foldDp] wide, in a window [widthDp] wide, for a
     * reader whose text is [textScale] of its usual size and whose margins are [margins] (a factor
     * of [pageGutter]). Two pages are asked for outright where the text is too large for Readium
     * to show two by itself; the margins are widened just as far as keeps the words
     * [CLEAR_OF_FOLD] from the fold. Asked for nothing new, the pages stay exactly as they were.
     */
    fun spread(widthDp: Float, foldDp: Float, textScale: Float, margins: Double): BookSpread {
        val fitsTwo = widthDp >= 2 * COLUMN_REM * REM * textScale
        val needed = (foldDp / 2f + CLEAR_OF_FOLD) / pageGutter(widthDp)
        return BookSpread(twoPages = !fitsTwo, margins = max(margins, needed.toDouble()))
    }
}
