package io.github.friendlyglobal.jacket.data

import java.time.Duration
import java.time.LocalDateTime

/*
 * Time spent with books: what counts, and which day it belongs to. Apart from Android so it can
 * be tested.
 *
 * Two things count. Reading: a page that was on screen until the next was turned to. Listening:
 * the voice reading a book aloud, wherever the phone is (the book open, the screen off, Jacket
 * behind another app). Each is counted as it happens, and a minute is only ever counted once:
 * while listening is on, the page in view is not reading as well (see [pageReadingMs]).
 *
 * Both go into the same day totals ([LibraryState.reading]), which is what "Your reading", the
 * streak and the widget show, and into the time kept with each book ([Book.spentMs]). How much of
 * it was listening is kept beside them ([LibraryState.listened], [Book.listenedMs]).
 */

/** Reading time is kept per day for a little over a year. */
internal const val KEPT_DAYS = 400

/** A page up for less than this was flipped past, not read... */
internal const val SHORTEST_PAGE_MS = 3_000L

/** ...and one up for longer than this was left open. */
internal const val LONGEST_PAGE_MS = 180_000L

private const val DAY_MS = 86_400_000L

/**
 * How much of a page's time counts as reading. It was on screen for [onScreen] milliseconds before
 * the next one, and listening was on for [aloud] of them: the player up, the voice reading the
 * book aloud, paused in it, or getting ready.
 *
 * While the voice speaks, that is listening, and is counted as that already (see
 * [LibraryState.withTimeSpent]); while it is paused or getting ready, it is neither. Only the rest
 * of the page's time can be reading. And that rest is held to the rules a page always was: too
 * short, it was flipped past; too long, the page was left open. So a page the voice read from
 * start to end is no reading at all, and one it read half of, after the listener had read the
 * first half themselves, is that first half.
 */
internal fun pageReadingMs(onScreen: Long, aloud: Long): Long {
    val read = onScreen - aloud.coerceAtLeast(0L)
    return if (read in SHORTEST_PAGE_MS..LONGEST_PAGE_MS) read else 0L
}

/**
 * [millis] of time that ended at [end] by the phone's clock, shared out among the days it fell on
 * (ISO dates): listening that runs past midnight belongs to the day before up to midnight, and
 * to the new day after it.
 *
 * Only where the time ends is taken from the phone's clock; how long it lasted is measured apart
 * from it (see the callers). So a clock that is set back or forward in the middle neither loses
 * time nor makes any up: the time is simply counted on the days the clock now says.
 */
internal fun spreadOverDays(end: LocalDateTime, millis: Long): Map<String, Long> {
    if (millis <= 0L) return emptyMap()
    val days = LinkedHashMap<String, Long>()
    var left = millis
    var day = end.toLocalDate()
    // What of the last day had gone by when the time ended; every day before that is a whole one.
    var room = Duration.between(day.atStartOfDay(), end).toMillis().coerceIn(0L, DAY_MS)
    while (left > 0L) {
        val share = minOf(left, room)
        if (share > 0L) days[day.toString()] = share
        left -= share
        day = day.minusDays(1)
        room = DAY_MS
    }
    return days
}

/**
 * [this] library with [millis] more spent with the book [bookId], up to [end] by the phone's
 * clock: counted on the days it fell on and for the book. [listened] when it was the voice
 * reading the book aloud rather than a page being read.
 *
 * The days kept are the latest [KEPT_DAYS]; a day that has gone from the reading time takes its
 * listening with it.
 */
internal fun LibraryState.withTimeSpent(bookId: String, millis: Long, listened: Boolean, end: LocalDateTime): LibraryState {
    if (millis <= 0L) return this
    val shares = spreadOverDays(end, millis)
    val days = (reading.plusEach(shares)).let { all ->
        if (all.size <= KEPT_DAYS) all else all.toSortedMap().entries.toList().takeLast(KEPT_DAYS).associate { it.key to it.value }
    }
    val heard = (if (listened) this.listened.plusEach(shares) else this.listened).let { all ->
        if (all.keys.all(days::containsKey)) all else all.filterKeys(days::containsKey)
    }
    val books = books.map { book ->
        if (book.id != bookId) book else book.copy(spentMs = book.spentMs + millis, listenedMs = book.listenedMs + if (listened) millis else 0L)
    }
    return copy(books = books, reading = days, listened = heard)
}

/** [this] with each of [more]'s amounts added to the one it has for the same day. */
private fun Map<String, Long>.plusEach(more: Map<String, Long>): Map<String, Long> {
    if (more.isEmpty()) return this
    val sum = HashMap(this)
    for ((day, millis) in more) sum[day] = (sum[day] ?: 0L) + millis
    return sum
}

/**
 * The time spent with a book, from this phone and from a backup: the longer of the two, and the
 * longer of the two listening times. Added together, restoring this phone's own backup would
 * double them (as with the days: see [Backup.mergeReading]).
 */
internal fun Book.withTimeFrom(saved: Book): Book {
    val spent = maxOf(spentMs, saved.spentMs)
    val heard = maxOf(listenedMs, saved.listenedMs).coerceAtMost(spent)
    return if (spent == spentMs && heard == listenedMs) this else copy(spentMs = spent, listenedMs = heard)
}
