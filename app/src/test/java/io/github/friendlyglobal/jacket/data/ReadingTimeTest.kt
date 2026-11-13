package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Listening counts as reading: what is counted, once, and on which day (see ReadingTime.kt). */
class ReadingTimeTest {
    private fun book(id: String) = Book(
        id = id, title = "T$id", author = "A", format = BookFormat.EPUB, fileName = "$id.epub",
        coverFile = null, seedColor = null, sourceName = "$id.epub", sizeBytes = 100, addedAt = 1,
    )

    private val library = LibraryState(books = listOf(book("b1"), book("b2")))
    private val evening = LocalDateTime.of(2026, 11, 13, 21, 0)

    // ---- A page's time ----------------------------------------------------------------------

    @Test
    fun `a page read in silence counts as it always did`() {
        assertEquals(40_000L, pageReadingMs(onScreen = 40_000L, aloud = 0L))
        // Flipped past, or left open: not read.
        assertEquals(0L, pageReadingMs(onScreen = 2_000L, aloud = 0L))
        assertEquals(0L, pageReadingMs(onScreen = 5 * 60_000L, aloud = 0L))
    }

    @Test
    fun `a page the voice read is listening, not reading as well`() {
        // The voice read it from top to bottom: all of it was listening, none of it reading.
        assertEquals(0L, pageReadingMs(onScreen = 60_000L, aloud = 60_000L))
        // A moment of it unspoken (the voice turning the page) is no reading either.
        assertEquals(0L, pageReadingMs(onScreen = 62_000L, aloud = 60_000L))
    }

    @Test
    fun `the part of a page read before the voice took over is reading`() {
        // Read for 30 s, then Listen: the voice read the rest of it in 50 s.
        assertEquals(30_000L, pageReadingMs(onScreen = 80_000L, aloud = 50_000L))
        // Listened to for ten minutes, the player closed, then read for a minute: a page up eleven
        // minutes was not left open, it was listened to and then read.
        assertEquals(60_000L, pageReadingMs(onScreen = 11 * 60_000L, aloud = 10 * 60_000L))
    }

    @Test
    fun `a pause, or the voice getting ready, is not reading either`() {
        // A minute of listening, then paused for twenty minutes on the page, the player up.
        assertEquals(0L, pageReadingMs(onScreen = 21 * 60_000L, aloud = 21 * 60_000L))
        // Thirty seconds of "Getting the voice ready", then a minute of it reading.
        assertEquals(0L, pageReadingMs(onScreen = 90_000L, aloud = 90_000L))
        // The player closed after a minute's listening, the page left open: not read.
        assertEquals(0L, pageReadingMs(onScreen = 21 * 60_000L, aloud = 60_000L))
    }

    @Test
    fun `more spoken than shown (two clocks a moment apart) is no reading`() {
        assertEquals(0L, pageReadingMs(onScreen = 59_000L, aloud = 60_000L))
    }

    // ---- Which day ----------------------------------------------------------------------------

    @Test
    fun `time within a day is that day's`() {
        assertEquals(mapOf("2026-11-13" to 600_000L), spreadOverDays(evening, 600_000L))
    }

    @Test
    fun `listening on past midnight is shared between the two days`() {
        // A minute's tick at 00:00:30: half of it was yesterday.
        val shares = spreadOverDays(LocalDateTime.of(2026, 11, 14, 0, 0, 30), 60_000L)
        assertEquals(mapOf("2026-11-13" to 30_000L, "2026-11-14" to 30_000L), shares)
    }

    @Test
    fun `time ending at midnight is all the day before's`() {
        assertEquals(mapOf("2026-11-13" to 60_000L), spreadOverDays(LocalDateTime.of(2026, 11, 14, 0, 0), 60_000L))
    }

    @Test
    fun `a stretch longer than a day runs back through whole days`() {
        val shares = spreadOverDays(LocalDateTime.of(2026, 11, 14, 1, 0), 26 * 3_600_000L)
        assertEquals(mapOf("2026-11-14" to 3_600_000L, "2026-11-13" to 86_400_000L, "2026-11-12" to 3_600_000L), shares)
        assertEquals(26 * 3_600_000L, shares.values.sum())
    }

    @Test
    fun `nothing has no day`() {
        assertTrue(spreadOverDays(evening, 0L).isEmpty())
        assertTrue(spreadOverDays(evening, -5L).isEmpty())
    }

    // ---- The library ---------------------------------------------------------------------------

    @Test
    fun `listening goes to the day, to the book, and is kept as listened`() {
        val after = library.withTimeSpent("b1", 600_000L, listened = true, end = evening)
        assertEquals(mapOf("2026-11-13" to 600_000L), after.reading)
        assertEquals(mapOf("2026-11-13" to 600_000L), after.listened)
        val b1 = after.books.first { it.id == "b1" }
        assertEquals(600_000L, b1.spentMs)
        assertEquals(600_000L, b1.listenedMs)
        // The other book is as it was.
        assertEquals(0L, after.books.first { it.id == "b2" }.spentMs)
    }

    @Test
    fun `reading and listening add up, and only listening is kept as listened`() {
        val after = library
            .withTimeSpent("b1", 300_000L, listened = false, end = evening)
            .withTimeSpent("b1", 600_000L, listened = true, end = evening.plusMinutes(10))
        assertEquals(900_000L, after.reading["2026-11-13"])
        assertEquals(600_000L, after.listened["2026-11-13"])
        val b1 = after.books.first { it.id == "b1" }
        assertEquals(900_000L, b1.spentMs)
        assertEquals(600_000L, b1.listenedMs)
    }

    @Test
    fun `listening through midnight counts on both days, once`() {
        val after = library.withTimeSpent("b1", 120_000L, listened = true, end = LocalDateTime.of(2026, 11, 14, 0, 1))
        assertEquals(mapOf("2026-11-13" to 60_000L, "2026-11-14" to 60_000L), after.reading)
        assertEquals(after.reading, after.listened)
        assertEquals(120_000L, after.books.first { it.id == "b1" }.spentMs)
    }

    @Test
    fun `a clock set back counts the time on the day it now says, and only once`() {
        // Ten minutes of listening; the phone's clock is put back a day in the middle of them.
        // How long it lasted isn't told by that clock: the ten minutes are counted, on the day
        // the clock says now, and not as a day's worth more or less.
        val first = library.withTimeSpent("b1", 300_000L, listened = true, end = evening)
        val after = first.withTimeSpent("b1", 300_000L, listened = true, end = evening.minusDays(1))
        assertEquals(mapOf("2026-11-13" to 300_000L, "2026-11-12" to 300_000L), after.reading)
        assertEquals(600_000L, after.reading.values.sum())
        assertEquals(600_000L, after.books.first { it.id == "b1" }.spentMs)
    }

    @Test
    fun `a book no longer in the library still counts towards the day`() {
        val after = library.withTimeSpent("gone", 60_000L, listened = true, end = evening)
        assertEquals(60_000L, after.reading["2026-11-13"])
        assertEquals(library.books, after.books)
    }

    @Test
    fun `days kept are the latest, and a day gone takes its listening with it`() {
        val start = LocalDateTime.of(2025, 1, 1, 12, 0)
        var state = library
        for (day in 0 until KEPT_DAYS + 2) state = state.withTimeSpent("b1", 60_000L, listened = true, end = start.plusDays(day.toLong()))
        assertEquals(KEPT_DAYS, state.reading.size)
        assertEquals(state.reading.keys, state.listened.keys)
        assertTrue("2025-01-01" !in state.reading)
    }

    @Test
    fun `a book's time from a backup is the longer of the two, never added`() {
        val here = book("b1").copy(spentMs = 3_600_000L, listenedMs = 600_000L)
        val saved = book("b1").copy(spentMs = 1_800_000L, listenedMs = 1_200_000L)
        val merged = here.withTimeFrom(saved)
        assertEquals(3_600_000L, merged.spentMs)
        assertEquals(1_200_000L, merged.listenedMs)
        // Restoring this phone's own backup changes nothing.
        assertEquals(here, here.withTimeFrom(here))
    }
}
