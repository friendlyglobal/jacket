package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The clock that runs while the voice is really speaking a book, and hands back each stretch once. */
class ListeningClockTest {
    private fun heard(vararg stretches: ListeningClock.Heard?) = stretches.filterNotNull().sumOf { it.millis }

    @Test
    fun `it counts while the voice speaks, each stretch once`() {
        val clock = ListeningClock()
        assertNull(clock.speaking("b1", 1_000L))
        val ticks = listOf(clock.tick(61_000L), clock.tick(121_000L), clock.speaking(null, 151_000L))
        assertEquals(listOf(60_000L, 60_000L, 30_000L), ticks.map { it?.millis })
        assertEquals(setOf("b1"), ticks.map { it?.bookId }.toSet())
        // Nothing more once it has stopped.
        assertNull(clock.tick(500_000L))
        assertNull(clock.speaking(null, 600_000L))
    }

    @Test
    fun `a pause is not counted`() {
        val clock = ListeningClock()
        clock.speaking("b1", 0L)
        val first = clock.speaking(null, 40_000L)
        // Paused for ten minutes (by the listener, the sleep timer or a call), then on again.
        val second = clock.speaking("b1", 640_000L)
        val third = clock.speaking(null, 660_000L)
        assertNull(second)
        assertEquals(60_000L, heard(first, third))
    }

    @Test
    fun `another book takes over from the moment it starts`() {
        val clock = ListeningClock()
        clock.speaking("b1", 0L)
        val first = clock.speaking("b2", 30_000L)
        val second = clock.speaking(null, 50_000L)
        assertEquals(ListeningClock.Heard("b1", 30_000L), first)
        assertEquals(ListeningClock.Heard("b2", 20_000L), second)
    }

    @Test
    fun `a clock that went back counts nothing, and carries on from there`() {
        val clock = ListeningClock()
        clock.speaking("b1", 100_000L)
        assertNull(clock.tick(40_000L))
        assertEquals(10_000L, clock.tick(50_000L)?.millis)
    }

    @Test
    fun `what has been spoken of a book includes the stretch still running`() {
        val clock = ListeningClock()
        clock.speaking("b1", 0L)
        clock.tick(60_000L)
        assertEquals(90_000L, clock.timeIn("b1", 90_000L))
        assertEquals(0L, clock.timeIn("b2", 90_000L))
        clock.speaking(null, 100_000L)
        // Stopped: it stays at what was spoken.
        assertEquals(100_000L, clock.timeIn("b1", 500_000L))
    }

    @Test
    fun `a page's time less the player's leaves no minute counted twice`() {
        // Two clocks, as Listening keeps them: one while the voice speaks, one while the player is up.
        val speaking = ListeningClock()
        val playerUp = ListeningClock()
        // A page comes up at 0 and is read in silence for 20 s. Then Listen: the voice gets ready
        // for 15 s, reads for 30 s, and turns to the next page.
        val before = playerUp.timeIn("b1", 0L)
        playerUp.speaking("b1", 20_000L)
        speaking.speaking("b1", 35_000L)
        val listened = speaking.tick(65_000L)!!.millis
        val aloud = playerUp.timeIn("b1", 65_000L) - before
        val read = io.github.friendlyglobal.jacket.data.pageReadingMs(onScreen = 65_000L, aloud = aloud)
        assertEquals(20_000L, read)
        assertEquals(30_000L, listened)
        // The voice getting ready was neither.
        assertEquals(50_000L, read + listened)
    }
}
