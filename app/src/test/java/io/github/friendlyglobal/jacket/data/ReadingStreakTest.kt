package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReadingStreakTest {
    private val today = LocalDate.of(2026, 11, 10)
    private fun days(vararg ago: Int) = ago.associate { today.minusDays(it.toLong()).toString() to 120_000L }

    @Test
    fun countsDaysInARow() {
        assertEquals(0, readingStreak(emptyMap(), today))
        assertEquals(1, readingStreak(days(0), today))
        assertEquals(3, readingStreak(days(0, 1, 2, 4), today))
    }

    @Test
    fun aDayNotYetReadInKeepsYesterdaysRun() {
        assertEquals(2, readingStreak(days(1, 2), today))
        assertEquals(0, readingStreak(days(2, 3), today))
    }

    @Test
    fun aFewSecondsIsNotADay() {
        assertEquals(0, readingStreak(mapOf(today.toString() to 20_000L), today))
    }

    @Test
    fun readingTimeSurvivesSaving() {
        val state = LibraryState(reading = days(0, 1), settings = Settings(watchSeen = setOf("a.epub|12"), watchFolder = "content://tree/x"))
        assertEquals(state, LibraryJson.decode(LibraryJson.encode(state)))
    }
}
