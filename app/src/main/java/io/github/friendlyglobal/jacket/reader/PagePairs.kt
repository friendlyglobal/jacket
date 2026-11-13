package io.github.friendlyglobal.jacket.reader

import kotlin.math.min

/*
 * Pages side by side: on a screen wide enough for two, the pages of a PDF or a comic lie as an
 * open book's do. The first page, its cover, is alone; then come pages 2 and 3 together, 4 and 5,
 * and so on. A page wider than it is tall (two pages scanned as one) is alone as well.
 *
 * Everything here is apart from Android: which pages go together ([PageRows]), when a window is
 * wide enough for it ([SideBySide]), and where each page lies ([PairLayout]). The pages are shown
 * in PageSpreads.kt.
 */

/**
 * The book's pages as the spreads they are shown in, in order: one page to a spread, or the pairs
 * of an open book. Pages and spreads both count from 0.
 */
internal class PageRows private constructor(
    /** The first page of each spread; null where every page is a spread of its own. */
    private val firsts: IntArray?,
    val pageCount: Int,
) {
    /** How many spreads there are. */
    val count: Int get() = firsts?.size ?: pageCount

    /** The first page of [row], in reading order. */
    fun first(row: Int): Int {
        if (pageCount == 0) return 0
        val at = row.coerceIn(0, count - 1)
        return firsts?.get(at) ?: at
    }

    /** The pages of [row]: one, or two that follow each other. */
    fun pages(row: Int): IntRange {
        if (pageCount == 0) return IntRange.EMPTY
        val at = row.coerceIn(0, count - 1)
        val next = if (at + 1 < count) first(at + 1) else pageCount
        return first(at) until next
    }

    /** The spread [page] is in. */
    fun rowOf(page: Int): Int {
        if (pageCount == 0) return 0
        val at = page.coerceIn(0, pageCount - 1)
        val rows = firsts ?: return at
        val found = rows.binarySearch(at)
        // Not the first of its spread: the spread before where it would go.
        return if (found >= 0) found else -found - 2
    }

    /**
     * The page the reader's place is on while [row] is the one in view: [kept], the page it was
     * on already, if that is one of the spread's (a place on the second page of a pair isn't moved
     * to the first by being shown beside it), and else the spread's first page.
     */
    fun pageIn(row: Int, kept: Int): Int = if (kept in pages(row)) kept else first(row)

    companion object {
        /** One page to a spread. */
        fun single(pageCount: Int) = PageRows(null, pageCount.coerceAtLeast(0))

        /**
         * The spreads of an open book, for pages of these shapes ([aspects]: each page's width for
         * its height; 0 or less where it isn't known, which is taken as upright). The cover is
         * alone. After it the pages go in twos, in order; a page that is [SideBySide.wide] is
         * alone, and so is the page left before it, or at the end, with no other to go with.
         */
        fun paired(aspects: FloatArray): PageRows {
            val count = aspects.size
            val firsts = ArrayList<Int>(count / 2 + 2)
            if (count > 0) firsts += 0
            var page = 1
            while (page < count) {
                firsts += page
                val together = !SideBySide.wide(aspects[page]) && page + 1 < count && !SideBySide.wide(aspects[page + 1])
                page += if (together) 2 else 1
            }
            return PageRows(firsts.toIntArray(), count)
        }
    }
}

/**
 * When pages are shown side by side. Lengths are in dp.
 *
 * Both pages of a pair are seen whole, a screenful at a time, so each is fitted to its half of the
 * window and to the window's height (less the room above, and the room below where the footer's
 * line is). Two things are asked of that. A page of the pair must be [READABLE]: about what a small
 * phone gives a page. And two must suit the window better than one: a page of the pair must be at
 * least [WORTH] of the width one page has when it is shown whole and alone.
 *
 * So the unfolded inner screen of a foldable shows two, held either way; so does a tablet on its
 * side. A tablet held upright is taller than two pages need, and shows one whole page as large as
 * it can be; a phone on its side is too low for two whole pages anyone could read, and scrolls
 * through one, as it always has. A book of pages wider than they are tall (slides, a landscape
 * scan) is never paired.
 */
internal object SideBySide {
    /** The least width a page of a pair may have. */
    const val READABLE = 300f

    /** The least a page of a pair may be, of the width of one page shown whole and alone. */
    const val WORTH = 0.55f

    /** The room kept either side of the pages, above them, and below them (where the reader's footer line is). */
    const val MARGIN = 8f
    const val ABOVE = 16f
    const val BELOW = 64f

    /**
     * How far a page keeps from the fold of a phone held half-open like a book: the screen curves
     * into it for a few millimetres either side. (A printed page's own margin keeps its words
     * further off.)
     */
    const val CLEAR_OF_FOLD = 16f

    /** A page wider than it is tall: two pages scanned as one, a fold-out, a slide. It is never put beside another. */
    fun wide(aspect: Float): Boolean = aspect > 1f

    /** The width each page of a pair has at most, in a window [width] wide. */
    fun half(width: Float): Float = (width - 2 * MARGIN) / 2f

    /** Whether a window [width] wide could show two pages of any shape: narrower, there is no need to know the pages' shapes. */
    fun mayFit(width: Float): Boolean = half(width) >= READABLE

    /** The shape the book's pages mostly are: the middle one of [aspects], those not known (0 or less) aside; 0 if none is known. */
    fun typical(aspects: FloatArray): Float {
        val known = aspects.filter { it > 0f && it.isFinite() }.sorted()
        return if (known.isEmpty()) 0f else known[known.size / 2]
    }

    /** Whether pages whose shape is mostly [typical] are shown side by side in a window [width] by [height]. */
    fun fits(width: Float, height: Float, typical: Float): Boolean {
        if (typical <= 0f || !typical.isFinite() || wide(typical) || width <= 0f || height <= 0f) return false
        val room = height - ABOVE - BELOW
        if (room <= 0f) return false
        val paired = min(half(width), room * typical)
        if (paired < READABLE) return false
        val alone = min(width - 2 * MARGIN, room * typical)
        return paired >= WORTH * alone
    }
}

/** Where a page lies in its spread: from the window's left and top, in pixels. */
internal class PageBox(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Where the pages of each spread lie, in a window [width] by [height] pixels at [density] pixels
 * to the dp. Every spread is a screenful, with both its pages whole: each is fitted to its half of
 * the window and to the room between the top and the footer's line, and set in the middle of
 * that room.
 *
 * The two pages of a pair lie against the middle of the window, as they do at a book's spine. In
 * a book read left to right the first page of a pair is the left-hand one; [rightToLeft] (manga),
 * it is the right-hand one. Held half-open like a book, the fold is the spine ([spineLeft] to
 * [spineRight], from the window's left), and each page keeps [SideBySide.CLEAR_OF_FOLD] from it on
 * its own half of the screen.
 *
 * A page alone in its spread (the cover, the last page, the one before a wide page) is the size it
 * would be in a pair, so the pages don't grow and shrink as they turn. It lies in the middle of
 * the window; held half-open, on the half it would be on in the book: the cover where the second
 * page of a pair goes, any other where the first does. A wide page takes both halves.
 */
internal class PairLayout(
    val width: Float,
    val height: Float,
    density: Float,
    private val rightToLeft: Boolean,
    spineLeft: Float? = null,
    spineRight: Float? = null,
) {
    private val margin = SideBySide.MARGIN * density
    private val above = SideBySide.ABOVE * density
    private val room = (height - (SideBySide.ABOVE + SideBySide.BELOW) * density).coerceAtLeast(1f)
    private val spine = spineLeft != null && spineRight != null && spineLeft > 0f && spineRight < width && spineLeft <= spineRight

    /** Where the left-hand page ends and the right-hand page begins. */
    private val leftEnd = if (spine) spineLeft!! - SideBySide.CLEAR_OF_FOLD * density else width / 2f
    private val rightStart = if (spine) spineRight!! + SideBySide.CLEAR_OF_FOLD * density else width / 2f

    /** The room a page has across, on its half of the window. */
    val half: Float = min(leftEnd - margin, width - margin - rightStart).coerceAtLeast(1f)

    /** Where the pages of [pages] (one, or two that follow each other) lie, where [aspect] tells a page's width for its height. In reading order. */
    fun row(pages: IntRange, aspect: (Int) -> Float): List<PageBox> {
        if (pages.isEmpty()) return emptyList()
        val first = pages.first
        if (pages.last > first) {
            val (firstWidth, firstHeight) = sized(aspect(first), half)
            val (secondWidth, secondHeight) = sized(aspect(first + 1), half)
            // Against the middle, each on its own side of it.
            fun box(page: Int, across: Float, tall: Float, onLeft: Boolean) = PageBox(page, if (onLeft) leftEnd - across else rightStart, top(tall), across, tall)
            return listOf(box(first, firstWidth, firstHeight, onLeft = !rightToLeft), box(first + 1, secondWidth, secondHeight, onLeft = rightToLeft))
        }
        val shape = aspect(first)
        if (SideBySide.wide(shape)) {
            val (across, tall) = sized(shape, (width - 2 * margin).coerceAtLeast(1f))
            return listOf(PageBox(first, (width - across) / 2f, top(tall), across, tall))
        }
        val (across, tall) = sized(shape, half)
        val left = when {
            !spine -> (width - across) / 2f
            // The cover is the page a book opens on: the second of a pair, had it a first.
            (first == 0) != rightToLeft -> rightStart
            else -> leftEnd - across
        }
        return listOf(PageBox(first, left, top(tall), across, tall))
    }

    /** A page of shape [aspect] fitted to [across] of width and to the room's height. */
    private fun sized(aspect: Float, across: Float): Pair<Float, Float> {
        val shape = if (aspect > 0f && aspect.isFinite()) aspect else UPRIGHT
        val wide = min(across, room * shape)
        return wide to wide / shape
    }

    /** How far down the window a page [tall] is put: in the middle of the room above the footer. */
    private fun top(tall: Float): Float = above + (room - tall) / 2f

    private companion object {
        /** The shape taken for a page whose shape isn't known: a sheet of paper's. */
        const val UPRIGHT = 0.7f
    }
}

/**
 * The reader's place among a book's pages, however they are laid out just now: the page, and how
 * far down it (0..1). Kept apart from what shows the pages, which is made anew when they are laid
 * out another way (the phone is unfolded, pages side by side are switched on or off): the new
 * one opens where this says.
 */
internal class KeptPage(var page: Int, var offset: Float) {
    fun keep(page: Int, offset: Float) {
        this.page = page
        this.offset = if (offset.isFinite()) offset.coerceIn(0f, 1f) else 0f
    }
}
