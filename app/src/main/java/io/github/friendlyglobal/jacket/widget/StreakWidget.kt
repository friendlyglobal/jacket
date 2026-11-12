package io.github.friendlyglobal.jacket.widget

import android.content.Context
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import io.github.friendlyglobal.jacket.ui.spoken
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.readingStreak
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}

/**
 * Your reading, counted: minutes today, the streak of days in a burst, and the week as bars once
 * there's room. A tap carries on with the book you're reading.
 */
class StreakWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library = context.library
        val initial = StreakModel(library.state.value)
        provideContent {
            val model by remember { library.state.map { StreakModel(it) }.distinctUntilChanged() }.collectAsState(initial)
            Streak(context, model)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val model = StreakModel(context.library.state.value)
        provideContent { Streak(context, model) }
    }
}

/** What the widget shows; equal while the numbers are, however far the book has moved on. */
private class StreakModel(state: LibraryState, now: LocalDate = LocalDate.now()) {
    val book: Book? = state.current
    val seed: Int? = book?.seedColor
    val today: Int = minutes(state, now)
    val streak: Int = readingStreak(state.reading, now)
    /** The last seven days, today last, and the minutes read on each. */
    val days: List<LocalDate> = (6 downTo 0).map { now.minusDays(it.toLong()) }
    val week: List<Int> = days.map { minutes(state, it) }
    private val key = listOf(book?.id, seed, today, streak, week, now)

    override fun equals(other: Any?) = other is StreakModel && other.key == key
    override fun hashCode() = key.hashCode()

    private companion object {
        fun minutes(state: LibraryState, day: LocalDate) = ((state.reading[day.toString()] ?: 0L) / 60_000L).toInt()
    }
}

@Composable
private fun Streak(context: Context, model: StreakModel) {
    WidgetTheme(model.seed) {
        val size = LocalSize.current
        val small = size.width < 110.dp
        // Said as what it shows, in a phrase: "24 min today, Day 5". Piece by piece it was a bare
        // number, "min today", "Day 5" twice and the week as seven single letters.
        val said = spoken(
            context.getString(R.string.list_separator),
            context.getString(R.string.stats_today, model.today),
            context.getString(R.string.stats_streak, model.streak).takeIf { model.streak > 0 },
        )
        Column(
            base(context, model.book, said).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = if (small || size.height < 140.dp) Alignment.CenterVertically else Alignment.Top,
            horizontalAlignment = if (small) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (small) {
                Minutes(context, model.today, big = 30.sp, centered = true)
                return@Column
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Minutes(context, model.today, big = if (size.height >= 110.dp) 40.sp else 32.sp, centered = false, modifier = GlanceModifier.defaultWeight())
                if (model.streak > 0) StreakBurst(context, model.streak, if (size.height >= 110.dp) 52.dp else 44.dp)
            }
            if (size.height >= 140.dp) {
                Spacer(GlanceModifier.defaultWeight())
                Week(model, labelled = size.height >= 180.dp, height = if (size.height >= 180.dp) 52.dp else 36.dp)
            }
        }
    }
}

@Composable
private fun Minutes(context: Context, minutes: Int, big: TextUnit, centered: Boolean, modifier: GlanceModifier = GlanceModifier) {
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            minutes.toString(),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = big, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.semantics { contentDescription = context.getString(R.string.stats_today, minutes) },
        )
        Text(
            context.getString(R.string.widget_streak_minutes), maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            ),
        )
    }
}

/** The streak's day count on a Material burst, as the finished badge wears its check. */
@Composable
private fun StreakBurst(context: Context, streak: Int, size: Dp) {
    val scheme = LocalWidgetScheme.current
    val burst = remember(scheme) { WidgetArt.shape(context, MaterialShapes.SoftBurst, scheme.tertiaryContainer.toArgb()) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            GlanceModifier.size(size).semantics { contentDescription = context.getString(R.string.stats_streak, streak) },
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(burst), contentDescription = null, modifier = GlanceModifier.size(size))
            Text(
                streak.toString(),
                style = TextStyle(color = GlanceTheme.colors.onTertiaryContainer, fontSize = if (size >= 52.dp) 18.sp else 15.sp, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            context.getString(R.string.stats_streak, streak), maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
        )
    }
}

/** The last seven days as bars, today's in the accent; a day not read is a small dot. */
@Composable
private fun Week(model: StreakModel, labelled: Boolean, height: Dp) {
    val most = maxOf(30, model.week.max())
    // The widget's own configuration: it follows a change of language with its next update.
    val locale = LocalContext.current.resources.configuration.locales[0]
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        model.week.forEachIndexed { index, minutes ->
            val today = index == model.week.lastIndex
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                val bar = if (minutes <= 0) 6.dp else (height * (minutes.toFloat() / most)).coerceIn(8.dp, height)
                Box(
                    GlanceModifier.width(14.dp).height(bar).cornerRadius(7.dp).background(
                        when {
                            today -> GlanceTheme.colors.primary
                            minutes > 0 -> GlanceTheme.colors.secondary
                            else -> GlanceTheme.colors.secondaryContainer
                        },
                    ),
                ) {}
                if (labelled) {
                    Text(
                        model.days[index].dayOfWeek.getDisplayName(DayStyle.NARROW, locale),
                        style = TextStyle(
                            color = if (today) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 10.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                        ),
                        modifier = GlanceModifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
