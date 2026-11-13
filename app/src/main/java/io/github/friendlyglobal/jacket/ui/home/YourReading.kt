package io.github.friendlyglobal.jacket.ui.home

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.MonthReading
import io.github.friendlyglobal.jacket.data.READING_SHADES
import io.github.friendlyglobal.jacket.data.READ_DAY_MS
import io.github.friendlyglobal.jacket.data.ReadingTotals
import io.github.friendlyglobal.jacket.data.calendarWeeks
import io.github.friendlyglobal.jacket.data.finishedBooks
import io.github.friendlyglobal.jacket.data.longestStreak
import io.github.friendlyglobal.jacket.data.monthReading
import io.github.friendlyglobal.jacket.data.readingMonths
import io.github.friendlyglobal.jacket.data.readingShade
import io.github.friendlyglobal.jacket.data.readingStreak
import io.github.friendlyglobal.jacket.data.readingTotals
import io.github.friendlyglobal.jacket.data.weekDays
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.READABLE
import io.github.friendlyglobal.jacket.ui.asHeading
import io.github.friendlyglobal.jacket.ui.contrast
import io.github.friendlyglobal.jacket.ui.decorative
import io.github.friendlyglobal.jacket.ui.fitOnOneLine
import io.github.friendlyglobal.jacket.ui.predictiveBack
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.spoken
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

/**
 * "Your reading": the time read this week, this month and this year, the longest run of days in a
 * row, a calendar of the days read (the more read on a day, the deeper its colour) and the books
 * finished, with the day each was. All of it from what the library already keeps; nothing leaves
 * the phone. Opened from the line of today's reading in the library, and from Settings.
 *
 * Listening is reading here: the time the voice read a book aloud is in every total, and each
 * says how much of it was listened to ("4 hr listened"), as each finished book says the time
 * spent with it.
 */
@Composable
fun YourReadingScreen(state: LibraryState, onBack: () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val today = remember(state.reading) { LocalDate.now() }
    val weekStart = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val totals = remember(state.reading, today, weekStart) { readingTotals(state.reading, today, weekStart) }
    // How much of each was listened to: counted the same way, from the days' listening.
    val listened = remember(state.listened, today, weekStart) { readingTotals(state.listened, today, weekStart) }
    val longest = remember(state.reading) { longestStreak(state.reading) }
    val streak = remember(state.reading, today) { readingStreak(state.reading, today) }
    val months = remember(state.reading, today) { readingMonths(state.reading, today) }
    val finished = remember(state.books) { finishedBooks(state.books) }
    val readToday = ((state.reading[today.toString()] ?: 0L) / 60_000L).toInt()
    // The line that led here, under the title: "24 min today · Day 5".
    val now = listOfNotNull(
        stringResource(R.string.stats_today, readToday).takeIf { readToday > 0 },
        stringResource(R.string.stats_streak, streak).takeIf { streak > 1 },
    ).joinToString(stringResource(R.string.separator))

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).predictiveBack(onBack),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.reading_title), modifier = Modifier.asHeading()) },
                subtitle = if (now.isNotEmpty()) { { Text(now) } } else null,
                navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, stringResource(R.string.common_back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()

            item { Totals(totals, listened, longest, locale, column.padding(top = 8.dp)) }

            item { SectionTitle(stringResource(R.string.reading_days_read), column) }
            item { ReadingCalendar(state.reading, months, today, locale, column) }

            item { SectionTitle(stringResource(R.string.reading_books_finished), column) }
            if (finished.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.reading_finished_none),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = column.padding(horizontal = 16.dp),
                    )
                }
            } else {
                itemsIndexed(finished, key = { _, book -> book.id }) { index, book ->
                    FinishedBook(book, index, finished.size, locale, column.padding(bottom = ListItemDefaults.SegmentedGap))
                }
            }
        }
    }
}

/** The name of a part of the page: a heading, so a screen reader can go from part to part. */
@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp).asHeading(),
    )
}

// ---------------------------------------------------------------------------------------------
// This week, this month, this year, and the longest streak
// ---------------------------------------------------------------------------------------------

@Composable
private fun Totals(totals: ReadingTotals, listened: ReadingTotals, longest: Int, locale: Locale, modifier: Modifier) {
    // Side by side while there's room for two; one under the other with the phone's font turned up.
    val stacked = LocalDensity.current.fontScale > 1.3f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(
            label = stringResource(R.string.reading_this_week),
            value = readingTime(totals.week, locale, MeasureFormat.FormatWidth.SHORT),
            said = readingTime(totals.week, locale, MeasureFormat.FormatWidth.WIDE),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            large = true,
            listened = listened.week,
            locale = locale,
            modifier = Modifier.fillMaxWidth(),
        )
        val monthTile = @Composable { tile: Modifier ->
            Tile(
                label = stringResource(R.string.reading_this_month),
                value = readingTime(totals.month, locale, MeasureFormat.FormatWidth.SHORT),
                said = readingTime(totals.month, locale, MeasureFormat.FormatWidth.WIDE),
                listened = listened.month,
                locale = locale,
                modifier = tile,
            )
        }
        val yearTile = @Composable { tile: Modifier ->
            Tile(
                label = stringResource(R.string.reading_this_year),
                value = readingTime(totals.year, locale, MeasureFormat.FormatWidth.SHORT),
                said = readingTime(totals.year, locale, MeasureFormat.FormatWidth.WIDE),
                listened = listened.year,
                locale = locale,
                modifier = tile,
            )
        }
        if (stacked) {
            monthTile(Modifier.fillMaxWidth())
            yearTile(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                monthTile(Modifier.weight(1f).fillMaxHeight())
                yearTile(Modifier.weight(1f).fillMaxHeight())
            }
        }
        val days = remember(longest, locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(longest, MeasureUnit.DAY)) }
        Tile(
            label = stringResource(R.string.reading_longest_streak),
            value = days,
            said = days,
            modifier = Modifier.fillMaxWidth(),
            badge = {
                MorphBadge(MaterialShapes.SoftBurst, MaterialTheme.colorScheme.tertiaryContainer, Modifier.size(44.dp)) {
                    Icon(JacketIcons.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            },
        )
    }
}

/**
 * One number of the page with its name over it. To a screen reader it is one thing, said in full:
 * "This week, 2 hours, 14 minutes", where the eye gets "2 hr 14 min". A time that was partly
 * listened to says how much under it, quietly ("1 hr 30 min listened"), from a minute of it.
 */
@Composable
private fun Tile(
    label: String,
    value: String,
    said: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    large: Boolean = false,
    /** How much of the time was listened to, in milliseconds. */
    listened: Long = 0L,
    locale: Locale = Locale.ROOT,
    badge: (@Composable () -> Unit)? = null,
) {
    val heard = listened.takeIf { it >= READ_DAY_MS }
    val heardShown = heard?.let { stringResource(R.string.reading_listened, readingTime(it, locale, MeasureFormat.FormatWidth.SHORT)) }
    val heardSaid = heard?.let { stringResource(R.string.reading_listened, readingTime(it, locale, MeasureFormat.FormatWidth.WIDE)) }
    val phrase = spoken(stringResource(R.string.list_separator), label, said, heardSaid)
    Surface(
        shape = if (large) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.large,
        color = container, contentColor = content,
        modifier = modifier.clearAndSetSemantics { contentDescription = phrase },
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = if (large) 20.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                badge()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (large) content else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                val style = if (large) MaterialTheme.typography.displaySmallEmphasized else MaterialTheme.typography.titleLargeEmphasized
                Text(value, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis, autoSize = if (large) fitOnOneLine(style) else null)
                if (heardShown != null) {
                    Text(
                        heardShown, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (large) content else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A length of time as the phone's language writes it: "45 min", "2 hr 14 min"; in full for a screen reader. Under a minute is no minutes. */
internal fun readingTime(millis: Long, locale: Locale, width: MeasureFormat.FormatWidth): String {
    val minutes = (millis / 60_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val format = MeasureFormat.getInstance(locale, width)
    val hours = Measure(minutes / 60, MeasureUnit.HOUR)
    val rest = Measure(minutes % 60, MeasureUnit.MINUTE)
    return when {
        minutes < 60 -> format.format(rest)
        minutes % 60 == 0 -> format.format(hours)
        else -> format.formatMeasures(hours, rest)
    }
}

// ---------------------------------------------------------------------------------------------
// The calendar
// ---------------------------------------------------------------------------------------------

/** A day's fill in the calendar, and the colour its number is written in on it. */
internal class ShadeColors(val fill: Color, val ink: Color)

/**
 * The calendar's shades, from a day without reading to one with an hour or more: the theme's
 * quiet surface deepening, step by step, into its primary colour. A day's number is written in
 * whichever ink reads best on its shade, and always readably (see [READABLE]): the theme's own
 * where one of them does, else plain black or white, one of which does on any colour.
 */
internal fun readingShadeColors(scheme: ColorScheme): List<ShadeColors> {
    val none = scheme.surfaceContainerHigh
    return (0 until READING_SHADES).map { shade ->
        val fill = if (shade == 0) none else lerp(none, scheme.primary, shade / (READING_SHADES - 1f))
        val paper = fill.toArgb()
        val inks = listOf(if (shade == 0) scheme.onSurfaceVariant else scheme.onSurface, scheme.onPrimary, Color.Black, Color.White)
        ShadeColors(fill, inks.firstOrNull { contrast(it.toArgb(), paper) >= READABLE } ?: inks.maxBy { contrast(it.toArgb(), paper) })
    }
}

/**
 * A month of days, each shaded by how long was read on it, with the months before it a tap away.
 * The picture is for the eye. What it shows is also said in words under it ("Read on 18 days in
 * November 2026, 6 hours in all"), which is what a screen reader reads, and reads again when the
 * month is changed.
 */
@Composable
private fun ReadingCalendar(reading: Map<String, Long>, months: List<YearMonth>, today: LocalDate, locale: Locale, modifier: Modifier) {
    val haptics = rememberHaptics()
    val motion = MaterialTheme.motionScheme
    // Kept as how many months back, so it survives a turn of the phone, and the turn of a month.
    var back by rememberSaveable { mutableIntStateOf(0) }
    val index = (months.lastIndex - back).coerceIn(0, months.lastIndex)
    val month = months[index]
    val shown = remember(reading, month) { monthReading(reading, month) }
    val title = remember(month, locale) { monthTitle(month, locale) }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { haptics.tick(); back = months.lastIndex - index + 1 },
                    enabled = index > 0,
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(JacketIcons.Previous, stringResource(R.string.reading_previous_month)) }
                Text(
                    title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).asHeading(),
                )
                FilledTonalIconButton(
                    onClick = { haptics.tick(); back = months.lastIndex - index - 1 },
                    enabled = index < months.lastIndex,
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(JacketIcons.Next, stringResource(R.string.reading_next_month)) }
            }
            Spacer(Modifier.height(8.dp))
            // An earlier month comes in from the side the past is on, a later one from the other.
            AnimatedContent(
                targetState = shown,
                transitionSpec = {
                    val forward = targetState.month > initialState.month
                    (slideInHorizontally(motion.defaultSpatialSpec()) { if (forward) it / 4 else -it / 4 } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { if (forward) -it / 4 else it / 4 } + fadeOut(motion.fastEffectsSpec()))
                        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
                },
                // The same month with a page more read is the same picture, a little deeper: nothing slides.
                contentKey = { it.month },
                modifier = Modifier.widthIn(max = 420.dp).decorative(),
                label = "month",
            ) { reading -> MonthGrid(reading, today, locale) }
            Spacer(Modifier.height(12.dp))
            val summary = if (shown.daysRead == 0) {
                stringResource(R.string.reading_month_none, title)
            } else {
                pluralStringResource(
                    R.plurals.reading_month_summary, shown.daysRead,
                    shown.daysRead, title, readingTime(shown.total, locale, MeasureFormat.FormatWidth.WIDE),
                )
            }
            Text(
                summary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.reading_calendar_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp),
            )
        }
    }
}

/** The month's days in weeks, under the days of the week, each on its shade; today wears a ring. */
@Composable
private fun MonthGrid(reading: MonthReading, today: LocalDate, locale: Locale) {
    val weekStart = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val weeks = remember(reading.month, weekStart) { calendarWeeks(reading.month, weekStart) }
    val scheme = MaterialTheme.colorScheme
    val shades = remember(scheme) { readingShadeColors(scheme) }
    val todayHere = if (YearMonth.from(today) == reading.month) today.dayOfMonth else -1
    val number = MaterialTheme.typography.labelLarge
    // The days in the language's own digits, as the totals above them are written.
    val numerals = remember(locale) { (0..31).map { String.format(locale, "%d", it) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in weekDays(weekStart)) {
                Text(
                    day.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (week in weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (day in week) {
                    if (day == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                        continue
                    }
                    val shade = shades[readingShade(reading.days[day - 1])]
                    Box(
                        Modifier
                            .weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(shade.fill)
                            .then(if (day == todayHere) Modifier.border(2.dp, scheme.onSurface, RoundedCornerShape(12.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(numerals[day], style = number, color = shade.ink, maxLines = 1, softWrap = false, autoSize = fitOnOneLine(number))
                    }
                }
            }
        }
    }
}

/** "November 2026", as the phone's language writes a month and its year, to stand as a title. */
private fun monthTitle(month: YearMonth, locale: Locale): String {
    val format = DateFormat.getInstanceForSkeleton("yMMMM", locale)
    format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
    return format.format(Date.from(month.atDay(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()))
}

// ---------------------------------------------------------------------------------------------
// Books finished
// ---------------------------------------------------------------------------------------------

/** A finish this early isn't a day anyone finished a book: Jacket 0.x kept none, and its books were given a stand-in. */
private val FIRST_REAL_FINISH = LocalDate.of(2020, 1, 1)

/**
 * A finished book: its cover, its title, who wrote it and the day it was finished, and the time
 * spent with it, read and listened ("6 hr 10 min · 4 hr listened"; none for a book finished before
 * Jacket kept it). One thing to a screen reader.
 */
@Composable
private fun FinishedBook(book: Book, index: Int, count: Int, locale: Locale, modifier: Modifier) {
    val day = remember(book.finishedAt, locale) {
        Instant.ofEpochMilli(book.finishedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            .takeIf { it >= FIRST_REAL_FINISH }
            ?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withDecimalStyle(DecimalStyle.of(locale)).format(it) }
    }
    val line = listOfNotNull(book.author.takeIf { it.isNotBlank() }, day)
    val separator = stringResource(R.string.separator)
    // (In a list of books, the time beside each is plainly the time with it: the eye isn't told so.)
    val time = bookTime(book, locale, MeasureFormat.FormatWidth.SHORT, named = false)?.joinToString(separator)
    val timeSaid = bookTime(book, locale, MeasureFormat.FormatWidth.WIDE, named = true)?.joinToString(stringResource(R.string.list_separator))
    val phrase = spoken(stringResource(R.string.list_separator), book.title, book.author, day, timeSaid)
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier.clearAndSetSemantics { contentDescription = phrase },
        leadingContent = { CoverImage(book, Modifier.size(width = 36.dp, height = 54.dp).clip(RoundedCornerShape(6.dp))) },
        supportingContent = if (line.isNotEmpty() || time != null) {
            {
                Column {
                    if (line.isNotEmpty()) Text(line.joinToString(separator))
                    if (time != null) Text(time, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            null
        },
    ) { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/**
 * The time spent with [book], read and listened, as the parts of a line: "6 hr 10 min" (or, [named],
 * "6 hours, 10 minutes with this book") and, where a minute or more of it was, "4 hr listened".
 * Null for a book with less than a minute of it, or one read before Jacket kept it.
 */
@Composable
internal fun bookTime(book: Book, locale: Locale, width: MeasureFormat.FormatWidth, named: Boolean): List<String>? {
    if (book.spentMs < READ_DAY_MS) return null
    val spent = readingTime(book.spentMs, locale, width)
    return listOfNotNull(
        if (named) stringResource(R.string.book_time_spent, spent) else spent,
        book.listenedMs.takeIf { it >= READ_DAY_MS }?.let { stringResource(R.string.reading_listened, readingTime(it, locale, width)) },
    )
}

// ---------------------------------------------------------------------------------------------
// The way in from the library
// ---------------------------------------------------------------------------------------------

/**
 * The line of today's reading in the library ("24 min today · Day 5"), which opens this page: as
 * tall as a finger needs, and a button to a screen reader, which says where it leads. With
 * nowhere to lead ([onClick] null) it is the plain line it used to be.
 */
@Composable
internal fun ReadingLine(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val opens = stringResource(R.string.reading_title)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(
                if (onClick == null) Modifier else Modifier.clickable(onClickLabel = opens, role = Role.Button) {
                    haptics.tick()
                    onClick()
                },
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(JacketIcons.Clock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(6.dp))
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) Icon(JacketIcons.Next, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
