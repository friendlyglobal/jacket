package io.github.friendlyglobal.jacket.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/*
 * "Your reading": what the page of reading time shows, worked out from the minutes kept per day
 * ([LibraryState.reading], an ISO date to milliseconds) and from the books marked finished. Kept
 * apart from the page so it can be tested.
 */

/** A day counts as read from a minute of reading, as the streak has it (see [readingStreak]). */
const val READ_DAY_MS = 60_000L

/** The reading map with its days as dates; a key that isn't a date (a damaged file) is passed over. */
internal fun readingDays(reading: Map<String, Long>): Map<LocalDate, Long> {
    val days = HashMap<LocalDate, Long>(reading.size)
    for ((day, millis) in reading) {
        if (millis <= 0L) continue
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: continue
        days[date] = millis
    }
    return days
}

/** Time read, in milliseconds, so far this week, this month and this year. */
data class ReadingTotals(val week: Long, val month: Long, val year: Long)

/**
 * The time read this week, this month and this year, up to and including [today]. The week starts
 * on [weekStart], which differs from country to country (Monday, Sunday, Saturday).
 */
fun readingTotals(reading: Map<String, Long>, today: LocalDate, weekStart: DayOfWeek): ReadingTotals {
    val weekBegan = today.with(TemporalAdjusters.previousOrSame(weekStart))
    var week = 0L
    var month = 0L
    var year = 0L
    for ((day, millis) in readingDays(reading)) {
        // A day after today (the phone's clock was set back) belongs to no "so far".
        if (day > today) continue
        // A week can have begun last month, or last year.
        if (day >= weekBegan) week += millis
        if (day.year != today.year) continue
        year += millis
        if (day.month == today.month) month += millis
    }
    return ReadingTotals(week, month, year)
}

/** The longest run of days in a row with reading on each, in all the days kept. */
fun longestStreak(reading: Map<String, Long>): Int {
    val read = readingDays(reading).filterValues { it >= READ_DAY_MS }.keys.sorted()
    var longest = 0
    var run = 0
    var last: LocalDate? = null
    for (day in read) {
        run = if (last != null && ChronoUnit.DAYS.between(last, day) == 1L) run + 1 else 1
        if (run > longest) longest = run
        last = day
    }
    return longest
}

/** How many shades the calendar tells days apart by, the palest being a day without reading. */
const val READING_SHADES = 5

/**
 * The shade of a day in the calendar by the time read on it: 0 for none (under a minute), then
 * 1 for under a quarter of an hour, 2 for under half an hour, 3 for under an hour, 4 from an hour.
 */
fun readingShade(millis: Long): Int = when {
    millis < READ_DAY_MS -> 0
    millis < 15 * 60_000L -> 1
    millis < 30 * 60_000L -> 2
    millis < 60 * 60_000L -> 3
    else -> 4
}

/** One month of reading: the time read on each of its days (the first day first), in milliseconds. */
data class MonthReading(val month: YearMonth, val days: List<Long>) {
    /** The days with reading on them. */
    val daysRead: Int get() = days.count { it >= READ_DAY_MS }

    /** All the time read in the month. */
    val total: Long get() = days.sum()
}

fun monthReading(reading: Map<String, Long>, month: YearMonth): MonthReading {
    val days = LongArray(month.lengthOfMonth())
    for ((day, millis) in readingDays(reading)) {
        if (YearMonth.from(day) == month) days[day.dayOfMonth - 1] = millis
    }
    return MonthReading(month, days.toList())
}

/**
 * The months the calendar can be turned through: from the first with reading in it to the one
 * [today] is in, which is there even when nothing has been read yet.
 */
fun readingMonths(reading: Map<String, Long>, today: LocalDate): List<YearMonth> {
    val now = YearMonth.from(today)
    val first = readingDays(reading).filterValues { it >= READ_DAY_MS }.keys.minOrNull()?.let(YearMonth::from)?.takeIf { it < now } ?: now
    return generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= now }.toList()
}

/**
 * A month laid out as a calendar: its weeks from the top, each seven days across starting on
 * [weekStart], with the day of the month in its place and null where the week belongs to the
 * month before or after.
 */
fun calendarWeeks(month: YearMonth, weekStart: DayOfWeek): List<List<Int?>> {
    // How many places the first of the month sits in from the start of its week.
    val lead = (month.atDay(1).dayOfWeek.value - weekStart.value + 7) % 7
    val cells = List<Int?>(lead) { null } + (1..month.lengthOfMonth()).toList()
    return cells.chunked(7).map { week -> week + List<Int?>(7 - week.size) { null } }
}

/** The days of the week in the order a calendar starting on [weekStart] has them. */
fun weekDays(weekStart: DayOfWeek): List<DayOfWeek> = (0 until 7).map { weekStart.plus(it.toLong()) }

/** The books read to the end, the latest first. */
fun finishedBooks(books: List<Book>): List<Book> = books.filter { it.isFinished }.sortedByDescending { it.finishedAt }
