package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.reader.ListenRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

/**
 * The book you're reading with a big play button: from the home screen, start it reading aloud
 * from where you left it, and pause and resume the voice, all without opening Jacket. Books that can't be read aloud (PDFs,
 * comics) get an open button instead. One row high it's a bar; taller, the cover grows with it.
 *
 * While the book is being read aloud it is the player: a sentence back, a sentence on and the
 * sleep timer beside play and pause, as many of them as the size it's given has room for at a
 * full 48dp each. On a Galaxy Z Flip this is all of Jacket there is on the cover screen, so the
 * size that screen gives (about 300dp square) gets the buttons large and the timer in words.
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library = context.library
        val initial = PlayerModel(library.state.value, library.moment.value, context.resources)
        val initialCover = initial.book?.let { loadCover(library, it, 400) }
        provideContent {
            val model by remember {
                combine(library.state, library.moment) { state, moment -> PlayerModel(state, moment, context.resources) }
                    .distinctUntilChanged()
            }.collectAsState(initial)
            val cover by produceState(initialCover, model.book?.coverFile) {
                value = model.book?.let { loadCover(library, it, 400) }
            }
            Player(context, model, cover)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val library = context.library
        val model = PlayerModel(library.state.value, null, context.resources)
        val cover = model.book?.let { loadCover(library, it, 400) }
        provideContent { AsPreview { Player(context, model, cover) } }
    }
}

private class PlayerModel(state: LibraryState, moment: ReadingMoment?, resources: Resources) {
    val book: Book? = state.current
    val status: CoverStatus? = book?.let { CoverStatus.of(it, moment) }
    /** Read-aloud is on for this book: the button plays and pauses it in place. */
    val listening: Boolean = status?.phase == CoverPhase.LISTENING
    val playing: Boolean = listening && status?.playing == true
    /** The sleep timer, while this book is read aloud: the minutes it still has, or that it waits for the chapter's end. */
    private val timer: ReadingMoment? = moment?.takeIf { listening && it.bookId == book?.id }
    val sleepMinutes: Int = timer?.sleepMinutes?.coerceAtLeast(0) ?: 0
    val sleepAtChapterEnd: Boolean = timer?.sleepAtChapterEnd == true
    val sleepOn: Boolean get() = sleepMinutes > 0 || sleepAtChapterEnd
    private val key = listOf(
        book?.id, book?.title, book?.author, book?.coverFile, book?.seedColor, book?.percent, book?.statusLabel(resources),
        status?.phase, status?.chapter, playing, status?.marks?.size, sleepMinutes, sleepAtChapterEnd,
    )

    override fun equals(other: Any?) = other is PlayerModel && other.key == key
    override fun hashCode() = key.hashCode()
}

/** Every button is at least this big, whatever the widget's size: what a finger needs. */
private val TARGET = 48.dp

/** Between two buttons in a row. */
private val GAP = 4.dp

/** Under this height the widget is a bar: everything in one row. */
private val BAR_BELOW = 110.dp

@Composable
private fun Player(context: Context, model: PlayerModel, cover: Bitmap?) {
    val book = model.book
    val status = model.status
    WidgetTheme(book?.seedColor) {
        if (book == null || status == null) {
            Empty(context)
            return@WidgetTheme
        }
        when {
            // The skips and the sleep timer are a voice's. With none reading there is nothing for
            // them to move: the widget is the book and its play button.
            !model.listening -> Resting(context, book, model, status, cover)
            LocalSize.current.height < BAR_BELOW -> ListeningBar(context, book, model, status)
            else -> ListeningCard(context, book, model, status, cover)
        }
    }
}

/** The book, where you are in it, and one button: start it reading aloud (or open it, if it can't be). */
@Composable
private fun Resting(context: Context, book: Book, model: PlayerModel, status: CoverStatus, cover: Bitmap?) {
    val size = LocalSize.current
    val tall = size.height >= BAR_BELOW
    val button = if (tall) 64.dp else TARGET
    val coverHeight = (size.height - 24.dp).coerceIn(32.dp, 150.dp)
    val showCover = size.width >= 200.dp
    // A bar lower than its padding and a button: the button keeps its size, the padding gives.
    val above = ((size.height - button) / 2).coerceIn(0.dp, 12.dp)
    // Beside the button, too little room for a word of the title: the button has the widget to
    // itself. (A screen reader still says which book: the widget carries its name.)
    if (size.width - 24.dp - button - 12.dp < 40.dp) {
        Box(base(context, book).padding(horizontal = 12.dp, vertical = above), contentAlignment = Alignment.Center) {
            PlayButton(context, book, model, button)
        }
        return
    }
    Row(base(context, book).padding(horizontal = 12.dp, vertical = above), verticalAlignment = Alignment.CenterVertically) {
        if (showCover) {
            Cover(context, cover, book.title, coverHeight * 0.66f, coverHeight)
            Spacer(GlanceModifier.width(12.dp))
        }
        Column(GlanceModifier.defaultWeight()) {
            Title(book.title, lines = if (tall) 2 else 1, size = if (tall) 16 else 14)
            if (tall && book.author.isNotEmpty()) {
                Text(book.author, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            StageLine(context, book, model, status)
            // (The wavy line where there is the height for it under the two lines of words.)
            if (status.phase != CoverPhase.FINISHED && size.height >= 70.dp) {
                Spacer(GlanceModifier.height(if (tall) 8.dp else 4.dp))
                val used = 24.dp + 12.dp + button + if (showCover) coverHeight * 0.66f + 12.dp else 0.dp
                WavyProgress(status, size.width - used)
            }
        }
        Spacer(GlanceModifier.width(12.dp))
        PlayButton(context, book, model, button)
    }
}

/**
 * How many of the player's buttons fit side by side in [room], each [skip] wide but for play,
 * with [gap] between them: all four; or play with a sentence back and on; or play alone.
 */
internal fun playerButtonsIn(room: Dp, skip: Dp = TARGET, play: Dp = TARGET, gap: Dp = GAP): Int = when {
    room >= skip * 3 + play + gap * 3 -> 4
    room >= skip * 2 + play + gap * 2 -> 3
    else -> 1
}

/**
 * The player in one row. The buttons come first: every one that fits at full size. The book's
 * name and place sit beside them where a readable line of them is left, and step aside where it
 * isn't: who is listening knows what to.
 */
@Composable
private fun ListeningBar(context: Context, book: Book, model: PlayerModel, status: CoverStatus) {
    val size = LocalSize.current
    val side = 12.dp
    val room = size.width - side * 2
    val buttons = playerButtonsIn(room)
    val buttonsWidth = TARGET * buttons + GAP * (buttons - 1)
    val wordsRoom = room - buttonsWidth - 12.dp
    // (Beside a lone button a short word will do; beside a row of them it needs a line's worth.)
    val words = if (buttons == 1) wordsRoom >= 40.dp else wordsRoom >= 88.dp
    // A bar lower than its padding and a button: the button keeps its size, the padding gives.
    val above = ((size.height - TARGET) / 2).coerceIn(0.dp, 12.dp)
    Row(
        base(context, book).padding(horizontal = side, vertical = above),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (words) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (words) {
            Column(GlanceModifier.defaultWeight()) {
                Title(book.title, lines = 1, size = 14)
                StageLine(context, book, model, status)
                if (size.height >= 76.dp) {
                    Spacer(GlanceModifier.height(4.dp))
                    WavyProgress(status, wordsRoom)
                }
            }
            Spacer(GlanceModifier.width(12.dp))
        }
        Controls(context, book, model, buttons, skip = TARGET, play = TARGET, gap = GAP)
    }
}

/**
 * The player with room to stand: the book over its buttons, and the sleep timer in words on a row
 * of its own where the widget is tall enough for one. From about 260dp square (the cover screen of
 * a Galaxy Z Flip, a large home-screen widget) the buttons and the words are larger, and the
 * cover is there.
 */
@Composable
private fun ListeningCard(context: Context, book: Book, model: PlayerModel, status: CoverStatus, cover: Bitmap?) {
    val size = LocalSize.current
    val big = size.width >= 260.dp && size.height >= 250.dp
    val pad = if (big) 16.dp else 12.dp
    val w = size.width - pad * 2
    val h = size.height - pad * 2
    val skip = if (big) 56.dp else TARGET
    val play = if (big) 72.dp else if (h >= 130.dp) 56.dp else TARGET
    val gap = if (big) 12.dp else GAP
    val titleSize = if (big) 18 else 14
    // A line of the title, and of the place, as tall as they come at their sizes.
    val titleLine = if (big) 24.dp else 19.dp
    val stageLine = if (big) 18.dp else 16.dp
    val buttons = playerButtonsIn(w, skip, play, gap)
    // The sleep timer in words, on a row of its own under the others, where there's the height for it.
    val pillHeight = if (big) 52.dp else TARGET
    val pillFits = h >= titleLine + 6.dp + play + 8.dp + pillHeight
    val twoByTwoFits = w >= TARGET * 2 + GAP && h >= titleLine + 6.dp + TARGET * 2 + GAP
    if (buttons == 1 || (buttons == 3 && !pillFits && twoByTwoFits)) {
        // Too narrow for three across, or for the sleep timer after them and too low for it under
        // them: two by two, which has room for all four. Failing that, the book and its play button.
        if (twoByTwoFits) {
            val grid = TARGET * 2 + GAP
            Column(base(context, book).padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
                Title(book.title, lines = if (h >= titleLine * 2 + stageLine + 6.dp + grid) 2 else 1, size = titleSize, modifier = GlanceModifier.fillMaxWidth())
                if (h >= titleLine + stageLine + 6.dp + grid) StageLine(context, book, model, status, modifier = GlanceModifier.fillMaxWidth())
                Spacer(GlanceModifier.defaultWeight())
                Row {
                    SkipButton(context, forward = false, size = TARGET)
                    Spacer(GlanceModifier.width(GAP))
                    SkipButton(context, forward = true, size = TARGET)
                }
                Spacer(GlanceModifier.height(GAP))
                Row {
                    PlayButton(context, book, model, TARGET)
                    Spacer(GlanceModifier.width(GAP))
                    SleepButton(context, model, TARGET)
                }
            }
        } else {
            Resting(context, book, model, status, cover)
        }
        return
    }
    // The sleep timer: on its own row where that leaves the book its lines too, else the fourth
    // button of the row where there's the width, else (a low, narrow widget) left to the reader.
    val pill = pillFits && (buttons < 4 || h >= titleLine + stageLine + 24.dp + play + 8.dp + pillHeight)
    val inRow = if (pill) 3 else buttons
    // What is left over the buttons is the book's: its name, then its place, then the wavy line, then its cover.
    val rest = h - play - 6.dp - if (pill) pillHeight + 8.dp else 0.dp
    val stage = rest >= titleLine + stageLine
    val wave = status.phase != CoverPhase.FINISHED && rest >= titleLine + stageLine + 20.dp
    val coverHeight = if (big) (rest - 28.dp).coerceAtMost(120.dp) else 0.dp
    Column(base(context, book).padding(pad), horizontalAlignment = Alignment.CenterHorizontally) {
        if (coverHeight >= 56.dp) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Cover(context, cover, book.title, coverHeight * 0.66f, coverHeight)
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Title(book.title, lines = if (coverHeight >= titleLine * 2 + stageLine) 2 else 1, size = titleSize)
                    if (book.author.isNotEmpty() && coverHeight >= titleLine * 2 + stageLine * 2) {
                        Text(book.author, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                    }
                    StageLine(context, book, model, status, size = if (big) 13 else 12)
                }
            }
        } else {
            Title(
                book.title, lines = if (rest >= titleLine * 2 + stageLine + (if (wave) 20.dp else 0.dp)) 2 else 1, size = titleSize,
                modifier = GlanceModifier.fillMaxWidth(),
            )
            if (stage) StageLine(context, book, model, status, size = if (big) 13 else 12, modifier = GlanceModifier.fillMaxWidth())
        }
        if (wave) {
            Spacer(GlanceModifier.height(if (big) 10.dp else 6.dp))
            WavyProgress(status, w)
        }
        Spacer(GlanceModifier.defaultWeight())
        Controls(context, book, model, inRow, skip, play, gap)
        if (pill) {
            Spacer(GlanceModifier.height(8.dp))
            SleepPill(context, model, w, pillHeight, big)
        }
    }
}

@Composable
private fun Title(title: String, lines: Int, size: Int, modifier: GlanceModifier = GlanceModifier) = Text(
    title, maxLines = lines, modifier = modifier,
    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = size.sp, fontWeight = FontWeight.Bold),
)

/** Where the book is, in the widget's quiet line: the chapter being read aloud in the accent while it is. */
@Composable
private fun StageLine(context: Context, book: Book, model: PlayerModel, status: CoverStatus, size: Int = 12, modifier: GlanceModifier = GlanceModifier) = Text(
    stageLine(context, book, status), maxLines = 1, modifier = modifier,
    style = TextStyle(
        color = if (model.listening) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
        fontSize = size.sp,
        fontWeight = if (model.listening) FontWeight.Medium else FontWeight.Normal,
    ),
)

/**
 * The player's buttons in a row, as many as [count] says: play or pause alone; with a sentence
 * back before it and a sentence on after it; and with the sleep timer at the end.
 */
@Composable
private fun Controls(context: Context, book: Book, model: PlayerModel, count: Int, skip: Dp, play: Dp, gap: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (count >= 3) {
            SkipButton(context, forward = false, size = skip)
            Spacer(GlanceModifier.width(gap))
        }
        PlayButton(context, book, model, play)
        if (count >= 3) {
            Spacer(GlanceModifier.width(gap))
            SkipButton(context, forward = true, size = skip)
        }
        if (count >= 4) {
            Spacer(GlanceModifier.width(gap))
            SleepButton(context, model, skip)
        }
    }
}

/** A sentence back or on: the reader's own skip buttons, said the same way to a screen reader. */
@Composable
private fun SkipButton(context: Context, forward: Boolean, size: Dp) {
    SquareIconButton(
        imageProvider = ImageProvider(if (forward) R.drawable.ic_widget_skip_forward else R.drawable.ic_widget_skip_back),
        contentDescription = context.getString(if (forward) R.string.listen_next_sentence else R.string.listen_previous_sentence),
        onClick = actionRunCallback<SkipSentenceAction>(actionParametersOf(SkipSentenceAction.FORWARD to forward)),
        modifier = GlanceModifier.size(size),
        backgroundColor = GlanceTheme.colors.secondaryContainer,
        contentColor = GlanceTheme.colors.onSecondaryContainer,
    )
}

/** What the sleep timer is doing, as the reader's moon says it: "Sleep timer", "Sleep timer: 14 minutes left", "Sleep timer: end of chapter". */
private fun sleepSaid(context: Context, model: PlayerModel): String = when {
    model.sleepAtChapterEnd -> context.getString(R.string.listen_sleep_timer_end_of_chapter)
    model.sleepMinutes > 0 -> context.resources.getQuantityString(R.plurals.listen_sleep_minutes_left, model.sleepMinutes, model.sleepMinutes)
    else -> context.getString(R.string.listen_sleep_timer)
}

/**
 * The sleep timer as one button the size of the others: the moon, and under it the minutes left
 * while it counts down. A press steps it on: 15 minutes, 30, an hour, the listener's own length,
 * the end of the chapter, off. Set, it wears the accent; a screen reader is told what it is doing.
 */
@Composable
private fun SleepButton(context: Context, model: PlayerModel, size: Dp) {
    val ink = if (model.sleepOn) GlanceTheme.colors.onTertiaryContainer else GlanceTheme.colors.onSecondaryContainer
    Box(
        GlanceModifier.size(size).clickable(actionRunCallback<SleepTimerAction>()).semantics { contentDescription = sleepSaid(context, model) },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_button), contentDescription = null,
            colorFilter = ColorFilter.tint(if (model.sleepOn) GlanceTheme.colors.tertiaryContainer else GlanceTheme.colors.secondaryContainer),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val counting = model.sleepMinutes > 0
            Image(
                provider = ImageProvider(R.drawable.ic_widget_sleep), contentDescription = null,
                colorFilter = ColorFilter.tint(ink),
                modifier = GlanceModifier.size(if (counting) 18.dp else 24.dp),
            )
            if (counting) {
                Text(model.sleepMinutes.toString(), maxLines = 1, style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/**
 * Whether [text] at [fontSize] (sp) fits on one line [room] wide. A widget can't measure its
 * words, so this goes by how wide letters are on the whole: a little over half their height, and
 * the full height for Chinese, Japanese and Korean ones. It errs towards "doesn't fit".
 */
internal fun fitsOnOneLine(text: String, fontSize: Int, room: Dp, fontScale: Float = 1f): Boolean =
    text.sumOf { if (it.code >= 0x2E80) 1.0 else 0.58 } * fontSize * fontScale <= room.value

/**
 * The sleep timer on a row of its own, [width] wide: the moon and what it is doing, in words. A
 * press steps it on, as [SleepButton]'s does. Where the words don't fit on the row (a narrow
 * widget, a language of long words) it is the moon and the minutes left, as on the button: the
 * number is never the part that is cut off. A screen reader is told in full either way.
 */
@Composable
private fun SleepPill(context: Context, model: PlayerModel, width: Dp, height: Dp, big: Boolean) {
    val ink = if (model.sleepOn) GlanceTheme.colors.onTertiaryContainer else GlanceTheme.colors.onSecondaryContainer
    val said = sleepSaid(context, model)
    val fontSize = if (big) 14 else 13
    // The row less its padding, the moon and the gap after it.
    val shown = when {
        fitsOnOneLine(said, fontSize, width - 52.dp, context.resources.configuration.fontScale) -> said
        model.sleepMinutes > 0 -> model.sleepMinutes.toString()
        else -> ""
    }
    Box(
        GlanceModifier.fillMaxWidth().height(height).clickable(actionRunCallback<SleepTimerAction>()).semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_pill), contentDescription = null,
            colorFilter = ColorFilter.tint(if (model.sleepOn) GlanceTheme.colors.tertiaryContainer else GlanceTheme.colors.secondaryContainer),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Row(GlanceModifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_sleep), contentDescription = null,
                colorFilter = ColorFilter.tint(ink), modifier = GlanceModifier.size(20.dp),
            )
            if (shown.isNotEmpty()) {
                Spacer(GlanceModifier.width(8.dp))
                Text(shown, maxLines = 1, style = TextStyle(color = ink, fontSize = fontSize.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

/** A press on "previous sentence" or "next sentence": the voice goes back or on by one, as from the reader's own player. */
class SkipSentenceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val forward = parameters[FORWARD] ?: return
        val done = withContext(Dispatchers.Main.immediate) { ListenRemote.skip(if (forward) 1 else -1) }
        // No book is being read aloud any more (Jacket was closed since the widget was drawn): the
        // widget goes back to being the book and its play button.
        if (!done) PlayerWidget().updateAll(context)
    }

    companion object {
        val FORWARD = ActionParameters.Key<Boolean>("forward")
    }
}

/** A press on the sleep timer: it steps on to the next length (see [ListenRemote.nextSleep]). */
class SleepTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val done = withContext(Dispatchers.Main.immediate) { ListenRemote.nextSleep() }
        if (!done) PlayerWidget().updateAll(context)
    }
}

/**
 * Play and pause go straight to the read-aloud session, as a headphone button would, and the book
 * stays shut: while it isn't being read aloud, Play starts the voice in it at the place it was
 * left at, with nothing opened (see ListenService). Starting the voice needs the service in the
 * foreground; pausing it doesn't.
 */
@Composable
private fun PlayButton(context: Context, book: Book, model: PlayerModel, size: Dp) {
    val (icon, label, action) = when {
        // (A PDF with no words in it is opened, like a comic: there is nothing of it to play.
        // So is a locked PDF still waiting for its password, which only its reader can ask for.)
        !book.canListen || book.id in context.library.wordless.value || book.id in context.library.passwords.waiting.value ->
            Triple(R.drawable.ic_widget_open, R.string.library_menu_open, actionStartActivity(openIntent(context, book)))
        model.playing -> Triple(
            R.drawable.ic_widget_pause, R.string.listen_pause,
            actionStartService(mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PAUSE), isForegroundService = false),
        )
        model.listening -> Triple(
            R.drawable.ic_widget_play, R.string.listen_play,
            actionStartService(mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY), isForegroundService = true),
        )
        else -> Triple(
            R.drawable.ic_widget_play, R.string.reader_listen,
            actionStartService(mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY), isForegroundService = true),
        )
    }
    SquareIconButton(
        imageProvider = ImageProvider(icon),
        contentDescription = context.getString(label),
        onClick = action,
        modifier = GlanceModifier.size(size),
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary,
    )
}
