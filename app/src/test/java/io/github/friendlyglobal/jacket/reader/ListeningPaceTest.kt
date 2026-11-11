package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningPaceTest {
    /** A book the voice reads at a page (1% of it) every 40 seconds: 4,000 seconds in all. */
    private fun ListeningPace.hear(pages: Int, from: Int = 0, startAt: Long = 0L, pageMs: Long = 40_000L) {
        for (i in 0..pages) page(startAt + i * pageMs, (from + i) / 100f, following = true)
    }

    @Test
    fun `nothing is known before enough has been heard`() {
        val pace = ListeningPace()
        pace.page(0L, 0.10f, following = true)
        pace.page(20_000L, 0.11f, following = true)
        assertEquals(0f, pace.wholeBook)
    }

    @Test
    fun `the pace comes from the pages the voice turns`() {
        val pace = ListeningPace()
        pace.hear(pages = 3)
        assertEquals(4_000_000f, pace.wholeBook, 1f)
    }

    @Test
    fun `the same page reported again changes nothing`() {
        val pace = ListeningPace()
        pace.page(0L, 0.10f, following = true)
        pace.page(5_000L, 0.10f, following = true)
        pace.page(40_000L, 0.11f, following = true)
        assertEquals(4_000_000f, pace.wholeBook, 1f)
    }

    @Test
    fun `a pause or the listener's own page turn doesn't count`() {
        val pace = ListeningPace()
        pace.hear(pages = 2)
        // Paused for ten minutes on the same page, then on again.
        pace.page(80_000L, 0.02f, following = false)
        pace.page(680_000L, 0.02f, following = true)
        pace.page(720_000L, 0.03f, following = true)
        assertEquals(4_000_000f, pace.wholeBook, 1f)
    }

    @Test
    fun `a jump through the book starts the run again`() {
        val pace = ListeningPace()
        pace.hear(pages = 2)
        // Back to the voice after a jump: a tenth of the book in a second is not its pace.
        pace.page(81_000L, 0.12f, following = true)
        pace.page(121_000L, 0.13f, following = true)
        assertEquals(4_000_000f, pace.wholeBook, 1f)
    }

    @Test
    fun `a change of speed carries the pace over`() {
        val pace = ListeningPace()
        pace.hear(pages = 2)
        pace.retimed(old = 1f, new = 2f)
        assertEquals(2_000_000f, pace.wholeBook, 1f)
        // And what is heard at the new speed replaces it.
        pace.hear(pages = 2, from = 2, startAt = 100_000L, pageMs = 25_000L)
        assertEquals(2_500_000f, pace.wholeBook, 1f)
    }
}
