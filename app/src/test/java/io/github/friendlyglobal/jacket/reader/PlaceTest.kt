package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeping the reader's place: the numbers here are ones Readium reported on a Pixel 9 Pro Fold. */
class PlaceTest {
    // ---- Pages -------------------------------------------------------------------------------

    @Test
    fun aPageStartReportedAHairShortIsStillThatPage() {
        // Page 3 of 12 starts at 0.25; Readium said 0.24998. Put back in the same 12 pages it is
        // page 3 again, not page 2 (a page lost on every fold and unfold).
        assertEquals(3, Pages.holding(0.24998070541020298, 12))
        // Unfolded, the same file is 8 spreads: 0.25 is the start of the third.
        assertEquals(2, Pages.holding(0.24998070541020298, 8))
        // A hair over is the same page too.
        assertEquals(71, Pages.holding(0.6174062175414865, 115))
    }

    @Test
    fun aPlaceInsideAPageIsHeldByThatPage() {
        // 0.6435 of a file is page 74 of 115 folded, spread 48 of 75 unfolded, page 81 of 127 smaller.
        assertEquals(74, Pages.holding(0.643493804198169, 115))
        assertEquals(48, Pages.holding(0.643493804198169, 75))
        assertEquals(81, Pages.holding(0.643493804198169, 127))
        // The last few lines of a page are still that page, not the next.
        assertEquals(4, Pages.holding(4.9 / 12, 12))
        assertEquals(0, Pages.holding(0.0, 12))
        assertEquals(11, Pages.holding(1.0, 12))
        assertEquals(0, Pages.holding(0.7, 1))
    }

    @Test
    fun aimingOpensThePageThatHoldsThePlace() {
        // Readium opens the page nearest to a progression: 3.43 pages in would be page 3, but 3.6 would be page 4.
        for (pages in listOf(1, 7, 12, 115, 600)) {
            for (step in 0..200) {
                val place = step / 200.0
                val opened = Math.round(Pages.aim(place, pages) * pages).toInt()
                assertEquals("place $place of $pages pages", Pages.holding(place, pages), opened)
            }
        }
    }

    @Test
    fun readiumIsSeenOnThePageThatHoldsThePlaceAndNoOther() {
        // Kept: 0.6435. Back at page 74 of 115 (0.6379 as reported): there.
        assertTrue(Pages.shows(0.6379463109748796, 0.643493804198169, 116))
        assertTrue(Pages.shows(0.6400041104952504, 0.643493804198169, 75))
        // What Readium reported while the pages re-flowed: the old page number in the new pages.
        assertFalse(Pages.shows(1.0, 0.643493804198169, 60))
        assertFalse(Pages.shows(0.3350936956620104, 0.643493804198169, 143))
        assertFalse(Pages.shows(0.25554361240495244, 0.643493804198169, 116))
        // One page off is off.
        assertFalse(Pages.shows(75.0 / 115, 0.643493804198169, 115))
        assertFalse(Pages.shows(73.0 / 115, 0.643493804198169, 115))
        // Scrolling, there are no pages: near is near.
        assertTrue(Pages.near(0.6400, 0.6435))
        assertFalse(Pages.near(0.3351, 0.6435))
    }

    // ---- Whose place ---------------------------------------------------------------------------

    @Test
    fun thePlaceIsTheVoicesOnlyWhileItSpeaks() {
        assertTrue(voiceHoldsThePlace(listening = true, speaking = true))
        // Paused, the reader may read on by themselves: the page they turn to is their place.
        assertFalse(voiceHoldsThePlace(listening = true, speaking = false))
        assertFalse(voiceHoldsThePlace(listening = false, speaking = false))
        assertFalse(voiceHoldsThePlace(listening = false, speaking = true))
    }

    // ---- Opening where the book was left ---------------------------------------------------------

    @Test
    fun aSavedPlaceThatCanBeUsedIsUsed() {
        assertEquals(Reopen.Saved, reopen(hasLocator = true, usable = true, position = 46, positions = 169, progress = 0.27f, offset = 0f))
    }

    @Test
    fun aSavedPlaceThatCannotBeUsedDoesNotMeanPageOne() {
        // The locator won't parse, or names a file the book doesn't have: the progress saved beside it says where.
        assertEquals(Reopen.AtProgress(0.27000001072883606), reopen(true, usable = false, position = 46, positions = 169, progress = 0.27f, offset = 0f))
        // No progress either: the Readium position.
        assertEquals(Reopen.AtPosition(46), reopen(true, usable = false, position = 46, positions = 169, progress = 0f, offset = 0f))
        assertEquals(Reopen.AtPosition(46), reopen(true, usable = false, position = 46, positions = 169, progress = Float.NaN, offset = 0f))
        // A position the book doesn't have (another edition of the file): nothing left to go by.
        assertEquals(Reopen.Start, reopen(true, usable = false, position = 400, positions = 169, progress = 0f, offset = 0f))
        // Finished: the end, not the start.
        assertEquals(Reopen.AtProgress(1.0), reopen(true, usable = false, position = 168, positions = 169, progress = 1f, offset = 0f))
    }

    @Test
    fun aBookFromJacketZeroTwoOpensAtItsChapter() {
        assertEquals(Reopen.AtChapter(6, 0.41999998688697815), reopen(hasLocator = false, usable = false, position = 6, positions = 0, progress = 0.33f, offset = 0.42f))
        assertEquals(Reopen.AtChapter(0, 0.5), reopen(false, false, position = 0, positions = 0, progress = 0.02f, offset = 0.5f))
    }

    @Test
    fun aBookNeverOpenedStartsAtTheStart() {
        assertEquals(Reopen.Start, reopen(hasLocator = false, usable = false, position = 0, positions = 0, progress = 0f, offset = 0f))
    }

    // ---- Where read-aloud starts -------------------------------------------------------------------

    private val blocks = listOf("The Project Gutenberg eBook of Dom Casmurro", "I", "Do titulo.", "Uma noite destas, vindo da cidade", "— Sim.", "Vivo só, com um creado.", "— Sim.", "Agora que expliquei o titulo")
    private val starts = blocks.indices.map { it / blocks.size.toDouble() }

    @Test
    fun theVoiceStartsAtTheBlockOnThePageNotAtTheTopOfTheFile() {
        // The page names its first block by its words; line breaks and runs of spaces are the page's own.
        assertEquals(5, startBlock(blocks, starts, wanted = "Vivo só,\n   com um creado.", around = 0.6))
        // The same words twice in the file: the one nearest where the page is.
        assertEquals(4, startBlock(blocks, starts, wanted = "— Sim.", around = 0.5))
        assertEquals(6, startBlock(blocks, starts, wanted = "— Sim.", around = 0.8))
    }

    @Test
    fun aLongBlockIsKnownByItsStart() {
        val long = "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéo."
        val texts = listOf("I", long, "II")
        // The two readings of the file part company further on (a line break one of them reads as a space).
        assertEquals(1, startBlock(texts, listOf(0.0, 0.3, 0.6), wanted = long.replace("de chapéo", "de\nchapéo") + " More.", around = 0.3))
    }

    @Test
    fun withNoWordsToGoByItStartsWhereThePageIs() {
        // A page that is all picture, or words the file's reading doesn't have: the last block at or before the page.
        assertEquals(5, startBlock(blocks, starts, wanted = null, around = 0.7))
        assertEquals(5, startBlock(blocks, starts, wanted = "words that are nowhere", around = 0.7))
        assertEquals(0, startBlock(blocks, starts, wanted = null, around = null))
        assertEquals(7, startBlock(blocks, starts, wanted = "", around = 1.0))
        assertNull(startBlock(emptyList(), emptyList(), wanted = "anything", around = 0.5))
    }
}
