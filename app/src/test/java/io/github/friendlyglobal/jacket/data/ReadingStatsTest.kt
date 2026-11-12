package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class ReadingStatsTest {
    /** A Wednesday. */
    private val today = LocalDate.of(2026, 11, 11)
    private val minute = 60_000L

    private fun reading(vararg days: Pair<String, Int>) = days.associate { (day, minutes) -> day to minutes * minute }

    // ---- This week, this month, this year ----

    @Test
    fun `nothing read is nothing everywhere`() {
        assertEquals(ReadingTotals(0, 0, 0), readingTotals(emptyMap(), today, DayOfWeek.MONDAY))
    }

    @Test
    fun `the week runs from its first day to today`() {
        val read = reading(
            "2026-11-11" to 10, // today, Wednesday
            "2026-11-09" to 20, // Monday
            "2026-11-08" to 40, // Sunday
            "2026-11-07" to 80, // Saturday
        )
        assertEquals(30 * minute, readingTotals(read, today, DayOfWeek.MONDAY).week)
        assertEquals(70 * minute, readingTotals(read, today, DayOfWeek.SUNDAY).week)
        assertEquals(150 * minute, readingTotals(read, today, DayOfWeek.SATURDAY).week)
        // A week that starts today holds today alone.
        assertEquals(10 * minute, readingTotals(read, today, DayOfWeek.WEDNESDAY).week)
    }

    @Test
    fun `a week that began last month, or last year, is still this week`() {
        val monday = LocalDate.of(2026, 11, 2)
        val read = reading("2026-11-02" to 5, "2026-11-01" to 7, "2026-10-31" to 11)
        val totals = readingTotals(read, monday, DayOfWeek.SATURDAY)
        assertEquals(23 * minute, totals.week)
        assertEquals(12 * minute, totals.month)
        val newYear = LocalDate.of(2027, 1, 1) // a Friday
        val over = readingTotals(reading("2027-01-01" to 3, "2026-12-31" to 9, "2026-12-28" to 30), newYear, DayOfWeek.MONDAY)
        assertEquals(42 * minute, over.week)
        assertEquals(3 * minute, over.month)
        assertEquals(3 * minute, over.year)
    }

    @Test
    fun `the month and the year count their own days only`() {
        val read = reading(
            "2026-11-11" to 10, "2026-11-01" to 20,
            "2026-10-31" to 40, "2026-01-01" to 80,
            "2025-12-31" to 160, "2025-11-11" to 320,
        )
        val totals = readingTotals(read, today, DayOfWeek.MONDAY)
        assertEquals(10 * minute, totals.week)
        assertEquals(30 * minute, totals.month)
        assertEquals(150 * minute, totals.year)
    }

    @Test
    fun `seconds add up, and days that aren't days are passed over`() {
        val read = mapOf("2026-11-10" to 30_000L, "2026-11-11" to 45_000L, "yesterday" to 99 * minute, "2026-13-40" to 99 * minute, "" to 5L, "2026-11-09" to -4L)
        assertEquals(ReadingTotals(75_000L, 75_000L, 75_000L), readingTotals(read, today, DayOfWeek.MONDAY))
    }

    @Test
    fun `a day after today is not counted`() {
        // The phone's clock was set back after reading.
        val read = reading("2026-11-12" to 50, "2026-11-11" to 10, "2026-12-01" to 50)
        assertEquals(ReadingTotals(10 * minute, 10 * minute, 10 * minute), readingTotals(read, today, DayOfWeek.MONDAY))
    }

    // ---- The longest streak ----

    @Test
    fun `the longest streak is the longest run of days in a row`() {
        assertEquals(0, longestStreak(emptyMap()))
        assertEquals(1, longestStreak(reading("2026-11-11" to 5)))
        assertEquals(3, longestStreak(reading("2026-11-01" to 5, "2026-11-02" to 5, "2026-11-03" to 5, "2026-11-05" to 5, "2026-11-06" to 5)))
        // The later run is the longer one.
        assertEquals(4, longestStreak(reading("2026-10-01" to 5, "2026-10-02" to 5, "2026-11-01" to 5, "2026-11-02" to 5, "2026-11-03" to 5, "2026-11-04" to 5)))
    }

    @Test
    fun `a streak runs over the end of a month and of a year`() {
        assertEquals(4, longestStreak(reading("2026-02-27" to 5, "2026-02-28" to 5, "2026-03-01" to 5, "2026-03-02" to 5)))
        assertEquals(3, longestStreak(reading("2025-12-31" to 5, "2026-01-01" to 5, "2026-01-02" to 5)))
        // 2028 is a leap year: the 29th is a day like any other.
        assertEquals(3, longestStreak(reading("2028-02-28" to 5, "2028-02-29" to 5, "2028-03-01" to 5)))
        assertEquals(1, longestStreak(reading("2027-02-28" to 5, "2027-03-02" to 5)))
    }

    @Test
    fun `a few seconds don't make a day of a streak`() {
        val read = mapOf("2026-11-09" to 5 * minute, "2026-11-10" to 20_000L, "2026-11-11" to 5 * minute)
        assertEquals(1, longestStreak(read))
        // The streak the library shows can't be longer than the longest.
        val run = reading("2026-11-09" to 5, "2026-11-10" to 5, "2026-11-11" to 5)
        assertEquals(readingStreak(run, today), longestStreak(run))
    }

    // ---- The calendar ----

    @Test
    fun `a day's shade goes by the minutes read`() {
        assertEquals(0, readingShade(0))
        assertEquals(0, readingShade(59_999))
        assertEquals(1, readingShade(minute))
        assertEquals(1, readingShade(15 * minute - 1))
        assertEquals(2, readingShade(15 * minute))
        assertEquals(2, readingShade(30 * minute - 1))
        assertEquals(3, readingShade(30 * minute))
        assertEquals(3, readingShade(60 * minute - 1))
        assertEquals(4, readingShade(60 * minute))
        assertEquals(4, readingShade(24 * 60 * minute))
        // Never a shade the calendar hasn't got, and never lighter for more reading.
        val shades = (0..600).map { readingShade(it * minute) }
        assertTrue(shades.all { it in 0 until READING_SHADES })
        assertEquals(shades.sorted(), shades)
        assertEquals((0 until READING_SHADES).toList(), shades.distinct())
    }

    @Test
    fun `a month knows its days read and its time in all`() {
        val read = reading("2026-11-01" to 30, "2026-11-11" to 90, "2026-11-30" to 240, "2026-10-31" to 500, "2026-12-01" to 500) +
            ("2026-11-12" to 30_000L)
        val november = monthReading(read, YearMonth.of(2026, 11))
        assertEquals(30, november.days.size)
        assertEquals(30 * minute, november.days[0])
        assertEquals(90 * minute, november.days[10])
        assertEquals(240 * minute, november.days[29])
        // Half a minute is time read, but not a day read.
        assertEquals(3, november.daysRead)
        assertEquals(360 * minute + 30_000L, november.total)
        val empty = monthReading(read, YearMonth.of(2026, 2))
        assertEquals(28, empty.days.size)
        assertEquals(0, empty.daysRead)
        assertEquals(0L, empty.total)
        assertEquals(29, monthReading(emptyMap(), YearMonth.of(2028, 2)).days.size)
    }

    @Test
    fun `the calendar goes back to the first month read in, and no further than this one`() {
        val now = YearMonth.of(2026, 11)
        assertEquals(listOf(now), readingMonths(emptyMap(), today))
        assertEquals(listOf(now), readingMonths(reading("2026-11-03" to 5), today))
        assertEquals(
            listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), now),
            readingMonths(reading("2026-09-30" to 5, "2026-11-03" to 5), today),
        )
        // Over the turn of a year.
        assertEquals(13, readingMonths(reading("2025-11-20" to 5), today).size)
        // A day not read on, and one from a clock set wrong, add no months.
        assertEquals(listOf(now), readingMonths(mapOf("2026-06-01" to 10_000L), today))
        assertEquals(listOf(now), readingMonths(reading("2027-03-01" to 5), today))
    }

    @Test
    fun `a month is laid out in weeks from the country's first day`() {
        // November 2026 begins on a Sunday.
        val november = YearMonth.of(2026, 11)
        val fromMonday = calendarWeeks(november, DayOfWeek.MONDAY)
        assertEquals(listOf(null, null, null, null, null, null, 1), fromMonday.first())
        assertEquals(listOf<Int?>(30, null, null, null, null, null, null), fromMonday.last())
        assertEquals(6, fromMonday.size)
        val fromSunday = calendarWeeks(november, DayOfWeek.SUNDAY)
        assertEquals(listOf<Int?>(1, 2, 3, 4, 5, 6, 7), fromSunday.first())
        assertEquals(listOf(29, 30, null, null, null, null, null), fromSunday.last())
        assertEquals(5, fromSunday.size)
        // February 2026 begins on a Sunday and has four weeks exactly, from a Sunday.
        assertEquals(4, calendarWeeks(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size)
        for (start in DayOfWeek.entries) {
            for (month in 1..12) {
                val weeks = calendarWeeks(YearMonth.of(2026, month), start)
                assertTrue(weeks.all { it.size == 7 })
                assertEquals((1..YearMonth.of(2026, month).lengthOfMonth()).toList(), weeks.flatten().filterNotNull())
                // Every day sits under its own day of the week.
                val column = weeks.first { 1 in it }.indexOf(1)
                assertEquals(YearMonth.of(2026, month).atDay(1).dayOfWeek, weekDays(start)[column])
                assertNull(weeks.first().take(column).firstOrNull { it != null })
            }
        }
    }

    @Test
    fun `the week's days start where the country starts them`() {
        assertEquals(DayOfWeek.entries.toList(), weekDays(DayOfWeek.MONDAY))
        assertEquals(DayOfWeek.SUNDAY, weekDays(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, weekDays(DayOfWeek.SUNDAY).last())
        assertEquals(7, weekDays(DayOfWeek.SATURDAY).distinct().size)
    }

    // ---- Books finished ----

    @Test
    fun `finished books come latest first, and only the finished ones`() {
        fun book(id: String, finishedAt: Long) = Book(
            id = id, title = id, author = "", format = BookFormat.EPUB, fileName = "$id.epub", coverFile = null,
            seedColor = null, sourceName = "$id.epub", sizeBytes = 1, addedAt = 1, finishedAt = finishedAt,
        )
        val books = listOf(book("a", 100), book("b", 0), book("c", 300), book("d", 200))
        assertEquals(listOf("c", "d", "a"), finishedBooks(books).map { it.id })
        assertEquals(emptyList<Book>(), finishedBooks(listOf(book("b", 0))))
    }
}
