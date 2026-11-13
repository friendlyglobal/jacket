package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages side by side: which pages go together, when a window shows two, and where they lie. */
class PagePairsTest {
    private val upright = 0.707f
    private val doubleScan = 1.414f

    private fun spreads(rows: PageRows) = (0 until rows.count).map { rows.pages(it).toList() }

    private fun shapes(count: Int, vararg wide: Int) = FloatArray(count) { if (it in wide) doubleScan else upright }

    @Test
    fun theCoverIsAloneThenPagesGoInTwos() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5, 6)), spreads(PageRows.paired(shapes(7))))
    }

    @Test
    fun anEvenNumberOfPagesLeavesTheLastAlone() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), spreads(PageRows.paired(shapes(6))))
    }

    @Test
    fun aWidePageStandsAloneAndSoDoesThePageLeftBeforeIt() {
        // Page 4 (counting from 1) is a double-page scan: page 3 has nobody to go with.
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4), listOf(5, 6)),
            spreads(PageRows.paired(shapes(7, 4))),
        )
        // Straight after a pair it moves the pairs after it on by one.
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5), listOf(6)),
            spreads(PageRows.paired(shapes(7, 3))),
        )
    }

    @Test
    fun twoWidePagesRunningAreAloneEach() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), spreads(PageRows.paired(shapes(5, 1, 2))))
    }

    @Test
    fun aWideCoverIsAloneAsAnyCover() {
        assertEquals(listOf(listOf(0), listOf(1, 2)), spreads(PageRows.paired(shapes(3, 0))))
    }

    @Test
    fun aPageOfUnknownShapeIsTakenAsUpright() {
        assertEquals(listOf(listOf(0), listOf(1, 2)), spreads(PageRows.paired(floatArrayOf(0f, 0f, -1f))))
    }

    @Test
    fun tinyBooks() {
        assertEquals(0, PageRows.paired(FloatArray(0)).count)
        assertTrue(PageRows.paired(FloatArray(0)).pages(0).isEmpty())
        assertEquals(0, PageRows.paired(FloatArray(0)).rowOf(3))
        assertEquals(listOf(listOf(0)), spreads(PageRows.paired(shapes(1))))
        assertEquals(listOf(listOf(0), listOf(1)), spreads(PageRows.paired(shapes(2))))
    }

    @Test
    fun everyPageIsInTheSpreadItIsFoundIn() {
        for (rows in listOf(PageRows.paired(shapes(9)), PageRows.paired(shapes(10, 3, 7)), PageRows.single(6))) {
            for (page in 0 until rows.pageCount) {
                val row = rows.rowOf(page)
                assertTrue("page $page in spread $row", page in rows.pages(row))
            }
            // And every spread's pages are found in it, with nothing left out or counted twice.
            assertEquals((0 until rows.pageCount).toList(), (0 until rows.count).flatMap { rows.pages(it).toList() })
            (0 until rows.count).forEach { row -> assertEquals(row, rows.rowOf(rows.first(row))) }
        }
    }

    @Test
    fun pagesOutsideTheBookAreTheNearestSpread() {
        val rows = PageRows.paired(shapes(7))
        assertEquals(0, rows.rowOf(-4))
        assertEquals(rows.count - 1, rows.rowOf(99))
        assertEquals(listOf(5, 6), rows.pages(99).toList())
    }

    @Test
    fun onePageToASpreadIsAsOnANarrowScreen() {
        val rows = PageRows.single(4)
        assertEquals(4, rows.count)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), spreads(rows))
        assertEquals(2, rows.rowOf(2))
    }

    @Test
    fun thePlaceStaysOnTheSecondPageOfAPairItWasOn() {
        val rows = PageRows.paired(shapes(9))
        // Read to page 4 (index 3) on a narrow screen, then unfolded: pages 4 and 5 show, and the place is still page 4.
        assertEquals(3, rows.pageIn(rows.rowOf(3), kept = 3))
        // Page 3 (index 2), the second of its pair, likewise: folded again, it opens on page 3.
        assertEquals(2, rows.pageIn(rows.rowOf(2), kept = 2))
        // Turned to the next spread, the place is its first page.
        assertEquals(3, rows.pageIn(rows.rowOf(2) + 1, kept = 2))
        // And back again, the first page of that one.
        assertEquals(1, rows.pageIn(rows.rowOf(2), kept = 3))
    }

    @Test
    fun theUnfoldedInnerScreenOfAFoldShowsTwo() {
        // A Pixel 9 Pro Fold's inner screen at 2.625 pixels to the dp, held either way.
        assertTrue(SideBySide.fits(791f, 820f, upright))
        assertTrue(SideBySide.fits(820f, 791f, upright))
        // A comic's pages, and Letter paper.
        assertTrue(SideBySide.fits(791f, 820f, 0.66f))
        assertTrue(SideBySide.fits(791f, 820f, 0.773f))
        // Less the bars at the top and the foot of the screen.
        assertTrue(SideBySide.fits(791f, 740f, upright))
    }

    @Test
    fun phonesAndUprightTabletsShowOne() {
        // Folded, upright.
        assertFalse(SideBySide.fits(443f, 940f, upright))
        assertFalse(SideBySide.mayFit(443f))
        // Folded, on its side: wide enough, but two whole pages would be too small to read.
        assertFalse(SideBySide.fits(940f, 443f, upright))
        // A tablet held upright: one whole page is far larger than two.
        assertFalse(SideBySide.fits(800f, 1280f, upright))
    }

    @Test
    fun aTabletOnItsSideShowsTwo() {
        assertTrue(SideBySide.fits(1280f, 800f, upright))
    }

    @Test
    fun widePagesAreNeverPaired() {
        assertFalse(SideBySide.fits(1280f, 800f, 4f / 3f))
        assertFalse(SideBySide.fits(1280f, 800f, 0f))
        assertFalse(SideBySide.fits(0f, 0f, upright))
    }

    @Test
    fun theTypicalShapeIsTheMiddleOneOfThoseKnown() {
        assertEquals(upright, SideBySide.typical(floatArrayOf(upright, doubleScan, upright, 0f, upright)), 0f)
        assertEquals(0f, SideBySide.typical(floatArrayOf(0f, -1f)), 0f)
        assertEquals(0f, SideBySide.typical(FloatArray(0)), 0f)
    }

    // The inner screen, in pixels.
    private val width = 2076f
    private val height = 2152f
    private val density = 2.625f

    @Test
    fun aPairMeetsInTheMiddleFirstPageOnTheLeft() {
        val (first, second) = PairLayout(width, height, density, rightToLeft = false).row(1..2) { upright }
        assertEquals(1, first.page)
        assertEquals(width / 2f, first.right, 0.5f)
        assertEquals(width / 2f, second.left, 0.5f)
        // Both whole, between the top and the footer's line, the same size.
        assertTrue(first.left >= SideBySide.MARGIN * density - 0.5f && second.right <= width - SideBySide.MARGIN * density + 0.5f)
        assertTrue(first.top >= SideBySide.ABOVE * density - 0.5f && first.bottom <= height - SideBySide.BELOW * density + 0.5f)
        assertEquals(first.width, second.width, 0.01f)
        assertEquals(upright, first.width / first.height, 0.001f)
    }

    @Test
    fun rightToLeftTheFirstPageIsOnTheRight() {
        val (first, second) = PairLayout(width, height, density, rightToLeft = true).row(1..2) { upright }
        assertEquals(1, first.page)
        assertEquals(width / 2f, first.left, 0.5f)
        assertEquals(width / 2f, second.right, 0.5f)
    }

    @Test
    fun aLonePageIsTheSizeItWouldBeInAPairInTheMiddle() {
        val layout = PairLayout(width, height, density, rightToLeft = false)
        val pair = layout.row(1..2) { upright }.first()
        val cover = layout.row(0..0) { upright }.single()
        assertEquals(pair.width, cover.width, 0.01f)
        assertEquals(width / 2f, (cover.left + cover.right) / 2f, 0.5f)
    }

    @Test
    fun aWidePageTakesBothHalves() {
        val wide = PairLayout(width, height, density, rightToLeft = false).row(3..3) { doubleScan }.single()
        assertTrue(wide.left < width / 2f && wide.right > width / 2f)
        assertEquals(width / 2f, (wide.left + wide.right) / 2f, 0.5f)
        assertEquals(doubleScan, wide.width / wide.height, 0.001f)
    }

    @Test
    fun halfOpenEachPageKeepsToItsHalfClearOfTheFold() {
        val clear = SideBySide.CLEAR_OF_FOLD * density
        for (rightToLeft in listOf(false, true)) {
            val layout = PairLayout(width, height, density, rightToLeft, spineLeft = 1038f, spineRight = 1038f)
            val pages = layout.row(1..2) { upright }.sortedBy { it.left }
            assertTrue(pages[0].right <= 1038f - clear + 0.5f)
            assertTrue(pages[1].left >= 1038f + clear - 0.5f)
            assertEquals(if (rightToLeft) 1 else 2, pages[1].page)
        }
    }

    @Test
    fun halfOpenALonePageLiesWhereItWouldInTheBook() {
        val leftToRight = PairLayout(width, height, density, rightToLeft = false, spineLeft = 1038f, spineRight = 1038f)
        // The cover where a book opens: on the right.
        assertTrue(leftToRight.row(0..0) { upright }.single().left > 1038f)
        // The last page, alone, where the first of a pair goes: on the left.
        assertTrue(leftToRight.row(5..5) { upright }.single().right < 1038f)
        // A manga's the other way about.
        val rightToLeft = PairLayout(width, height, density, rightToLeft = true, spineLeft = 1038f, spineRight = 1038f)
        assertTrue(rightToLeft.row(0..0) { upright }.single().right < 1038f)
        assertTrue(rightToLeft.row(5..5) { upright }.single().left > 1038f)
    }

    @Test
    fun aFoldOffTheWindowIsNoSpine() {
        // (The window is beside another app: its fold, if any, isn't between the pages.)
        val layout = PairLayout(width, height, density, rightToLeft = false, spineLeft = -10f, spineRight = 0f)
        val (first, second) = layout.row(1..2) { upright }
        assertEquals(width / 2f, first.right, 0.5f)
        assertEquals(width / 2f, second.left, 0.5f)
    }

    @Test
    fun theKeptPlaceStaysWithinAPage() {
        val kept = KeptPage(0, 0f)
        kept.keep(4, 1.7f)
        assertEquals(4, kept.page)
        assertEquals(1f, kept.offset, 0f)
        kept.keep(2, Float.NaN)
        assertEquals(0f, kept.offset, 0f)
    }
}
