package io.github.friendlyglobal.jacket.reader

import android.content.res.Resources
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.fitOnOneLine
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.rememberTurn
import io.github.friendlyglobal.jacket.ui.shrinkToFit
import io.github.friendlyglobal.jacket.ui.spoken
import io.github.friendlyglobal.jacket.ui.voices.VoiceChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** When read-aloud stops by itself. */
enum class SleepTimer(val minutes: Int, @StringRes val label: Int) {
    OFF(0, R.string.listen_sleep_off),
    MIN_15(15, R.string.listen_sleep_15_minutes),
    MIN_30(30, R.string.listen_sleep_30_minutes),
    MIN_60(60, R.string.listen_sleep_1_hour),
    CHAPTER(0, R.string.listen_sleep_end_of_chapter),

    /** A length of the listener's own choosing: how long is in [ListenState.sleepEndsAt]. */
    OTHER(0, R.string.listen_sleep_other),
    ;

    /** Runs for a length of time (rather than to the end of the chapter, or not at all). */
    val timed: Boolean get() = this != OFF && this != CHAPTER
}

/** Audiobook mode: whether it's on, and whether it's speaking right now. */
@Stable
class ListenState {
    /** The player is showing (playing or paused). */
    var active by mutableStateOf(false)
    var playing by mutableStateOf(false)
    /**
     * Play has been pressed and the voice is on its way: a natural voice takes a moment to load
     * and to make its first sentence. The player shows it is getting ready until the first word.
     */
    var preparing by mutableStateOf(false)
    var sleep by mutableStateOf(SleepTimer.OFF)
    /** When a timed sleep ends (uptime millis). */
    var sleepEndsAt by mutableLongStateOf(0L)
    /**
     * The whole minutes a timed sleep still has, counted down by the reader while it runs (see
     * ReaderScreen): for what shows the timer away from the reader's screen, the widget's moon.
     * 0 without a timed sleep.
     */
    var sleepLeft by mutableIntStateOf(0)

    /** Sets the sleep timer at [now] (uptime millis): off, to the end of the chapter, or ([SleepTimer.timed]) to stop after [minutes]. */
    fun startSleep(timer: SleepTimer, minutes: Int, now: Long = SystemClock.uptimeMillis()) {
        sleep = timer
        sleepEndsAt = now + minutes * 60_000L
        sleepLeft = if (timer.timed) minutes else 0
    }
    /** The chapter file the voice is in (EPUB), or -1: lets "end of chapter" stop on time. */
    var spokenFile by mutableIntStateOf(-1)
    /** The sentence being spoken, and how many of its characters have been: shown over the cover when the phone is shut. */
    var phrase by mutableStateOf("")
    var spoken by mutableIntStateOf(0)
    /**
     * How fast the voice is speaking, in letters a millisecond at the speed it is set to: timed as
     * it reads (see [VoicePace]), and 0 until a sentence or two have been heard. "Time left in
     * chapter" is told by it.
     */
    var lettersPerMs by mutableFloatStateOf(0f)
    /**
     * The listener has moved away from the sentence being read (turned pages, scrolled, jumped):
     * the page stops following the voice, and the player offers the way back to it.
     */
    var offPage by mutableStateOf(false)

    /** The sleep timer's menu is open over the player, or the choosing of its length. */
    var sleepMenu by mutableStateOf(false)
    /** The speeds are open over the player. */
    var rateMenu by mutableStateOf(false)
    /** How many times the listener has skipped to another chapter from the player: "end of chapter" then waits for the new one's end. */
    var chapterSkips by mutableIntStateOf(0)
    /** The voice is on its way to a chapter skipped to. */
    var skippingChapter by mutableStateOf(false)
    /** The player is saying that the phone can't keep up with the voice. */
    var warning by mutableStateOf(false)
    /** That has been said once for this voice: afterwards the dot on its icon stands for it. */
    var warned = false

    /** Something of the player's is open over it: the controls stay where they are meanwhile. */
    val held: Boolean get() = sleepMenu || rateMenu || warning
}

/**
 * The voice reading the book, for the player: its name, whether it's a natural one, and whether
 * the phone is keeping up with it. [downloading]: how far the natural voice that will read it has
 * downloaded (0 to 1), while the phone's own reads; null when none is on its way.
 */
class ListenVoice(val label: String, val natural: Boolean, val struggling: Boolean, val downloading: Float? = null, val onClick: () -> Unit)

/** Implemented by each format that can be read aloud (EPUB and plain text). */
interface ListenController {
    /** Labels for skipping back and forward by a sentence or a paragraph, e.g. "Previous sentence". */
    @get:StringRes val previousLabel: Int
    @get:StringRes val nextLabel: Int

    /** Starts reading from the top of what's on screen; explains itself if it can't. */
    fun start()

    fun toggle()

    /** Jumps a sentence or paragraph: -1 back, +1 forward. */
    fun skip(delta: Int)

    fun setRate(rate: Float)

    fun stop()

    /** Takes the page back to the sentence being read, and has it follow the voice again. */
    fun backToVoice() {}

    /**
     * Where on the screen the line being read is, in the window's pixels: the controls keep off
     * it. Null when that line isn't on screen, or can't be told.
     */
    suspend fun spokenLine(): Rect? = null
}

/**
 * Stepping by chapter from the player: the chapter being read, by name, and the way to the one
 * before or after it. (By page, with the labels saying so, in a book of pages without chapters.)
 */
class ChapterSteps(
    val title: String,
    val canStep: (Int) -> Boolean,
    val onStep: (Int) -> Unit,
    @param:StringRes val previousLabel: Int = R.string.reader_previous_chapter,
    @param:StringRes val nextLabel: Int = R.string.reader_next_chapter,
)

/** The player's parts follow one another in by this much. */
internal const val ARRIVAL_STAGGER_MS = 70L

/** The toolbar that's up drops away this far ahead of the other hopping in. */
private const val HOP_GAP_MS = 60L

/** The mini player's button: small enough for the footer line, with a full-size target around it. */
private val MiniButton = 28.dp
private val MiniTarget = 48.dp

fun rateLabel(resources: Resources, rate: Float): String =
    resources.getString(R.string.listen_rate, if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0'))

/**
 * The audiobook player by itself, for the screens that lay it out their own way (the desk of a
 * half-folded phone, the cover of a shut one): the reader's toolbar as the player, under its wavy
 * progress line.
 */
@Composable
fun ListenBar(
    listen: ListenState,
    controller: ListenController,
    rate: Float,
    onRate: (Float) -> Unit,
    haptics: Haptics,
    progress: Float,
    hasChapters: Boolean,
    modifier: Modifier = Modifier,
    /** The voice that is reading, shown with the player and tapped to change it; null to leave it off. */
    voice: ListenVoice? = null,
    /** Shows "Back to the voice" above the player; off where the screen puts it above its other controls. */
    backToVoice: Boolean = true,
    /** Skipping to the next or the previous chapter; null for a book without chapters. */
    chapters: ChapterSteps? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Moved away from where the voice is: the way back, unless the screen stacks it itself.
        if (backToVoice) BackToVoiceButton(listen, controller, haptics)
        PlayerLine(listen, progress, voice, haptics, visible = true, chapters = chapters)
        ReaderToolbar(
            listening = true, reading = null, listen = listen, controller = controller,
            rate = rate, onRate = onRate, haptics = haptics, hasChapters = hasChapters, voice = voice, chapters = chapters,
        )
    }
}

/**
 * What rides on top of the toolbar while it is the player: the wavy progress line, which goes flat
 * when paused and runs by itself while the voice gets ready; and, on a window too narrow for the
 * voice's icon on the bar, the voice by name; and, on one too narrow for two more buttons, the
 * steps to the next and the previous chapter. They arrive one after the other, from the toolbar
 * up, on the theme's springs, and fold away together when listening ends.
 *
 * Without the [line] where the player floats over the page: there it would run across the text,
 * and the ring around the mini player's button tells the place instead.
 */
@Composable
internal fun PlayerLine(
    listen: ListenState,
    progress: Float,
    voice: ListenVoice?,
    haptics: Haptics,
    visible: Boolean,
    line: Boolean = true,
    /** The chapter steps, for a window too narrow for them on the bar; null where the screen has them in a control of its own (see ChapterControl). */
    chapters: ChapterSteps? = null,
) {
    val playing = listen.playing
    val preparing = listen.preparing && playing
    val motion = MaterialTheme.motionScheme
    val wave by animateFloatAsState(if (playing) 1f else 0f, motion.defaultEffectsSpec(), label = "wave")
    var arrived by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) {
        if (!visible) {
            arrived = 0
            return@LaunchedEffect
        }
        val stagger = if (motionRemoved()) 0L else ARRIVAL_STAGGER_MS
        delay(stagger)
        arrived = 1
        delay(stagger)
        arrived = 2
    }
    val leave = shrinkVertically(motion.fastSpatialSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.7f) + fadeOut(motion.fastEffectsSpec())
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The voice sits on the bar beside the moon, as an icon. A screen too narrow for one more icon
        // keeps it above the player, by name.
        if (voice != null && !voiceFitsOnBar()) {
            AnimatedVisibility(
                visible = arrived >= 2,
                enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.7f) +
                    slideInVertically(motion.fastSpatialSpec()) { it / 2 } + expandVertically(motion.defaultSpatialSpec()),
                exit = leave,
            ) {
                VoiceChip(
                    voice.label, voice.natural, voice.struggling,
                    downloading = voice.downloading,
                    onClick = { haptics.tick(); voice.onClick() },
                    modifier = Modifier.widthIn(max = 300.dp).padding(bottom = 10.dp),
                )
            }
        }
        // The chapter steps sit on the bar beside the sentence skips where there's room for two
        // more buttons. A narrower screen keeps them above the player, around the chapter's name.
        if (chapters != null && !chaptersFitOnBar()) {
            AnimatedVisibility(
                visible = arrived >= 2,
                enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.7f) +
                    slideInVertically(motion.fastSpatialSpec()) { it / 2 } + expandVertically(motion.defaultSpatialSpec()),
                exit = leave,
            ) {
                ChapterPill(chapters, haptics, Modifier.widthIn(max = 320.dp).padding(bottom = 10.dp))
            }
        }
        AnimatedVisibility(
            visible = line && arrived >= 1,
            enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()) +
                scaleIn(motion.defaultSpatialSpec(), initialScale = 0.4f),
            exit = leave,
        ) {
            // The line runs on its own while the voice gets ready, then shows the place in the book.
            // For the eye only: the play button says when the voice is getting ready, and a bare
            // "42 percent" read out here would be of nothing in particular.
            Crossfade(preparing, animationSpec = motion.defaultEffectsSpec(), modifier = Modifier.clearAndSetSemantics {}, label = "line") { busy ->
                if (busy) {
                    LinearWavyProgressIndicator(Modifier.width(240.dp).padding(bottom = 10.dp))
                } else {
                    LinearWavyProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        amplitude = { wave },
                        modifier = Modifier.width(240.dp).padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

/** What the reader's toolbar does while you read: its buttons' names, and what each asks for. */
class ReadingActions(
    val contentsLabel: String,
    val onContents: () -> Unit,
    /** Starts read-aloud; null for a format that can't be read aloud. */
    val onListen: (() -> Unit)?,
    val previousLabel: String,
    val nextLabel: String,
    val canStep: (Int) -> Boolean,
    val onStep: (Int) -> Unit,
    /** The action button: the text settings. */
    val onText: () -> Unit,
)

/** What the player's action button shows. */
private enum class Face { PLAY, PAUSE, PREPARING }

/** The bars hop from, and back to, their own bottom edge. */
internal val BottomMiddle = TransformOrigin(0.5f, 1f)

/**
 * The reader's floating toolbar. While you read it holds the contents, read-aloud and the chapter
 * steps, with the text settings on its action button; while the book is read to you it is the
 * player. Starting and stopping, the bar that's up drops away and the other hops in where it
 * stood, on the theme's bouncy spring. Only where the bars sit, their size and how see-through they
 * are change as they go, never their layout, so the hop runs at the phone's full frame rate.
 *
 * With no [reading] actions (the desk of a half-folded phone, the cover of a shut one) it is only
 * ever the player.
 */
@Composable
internal fun ReaderToolbar(
    listening: Boolean,
    reading: ReadingActions?,
    listen: ListenState,
    controller: ListenController?,
    rate: Float,
    onRate: (Float) -> Unit,
    haptics: Haptics,
    hasChapters: Boolean,
    voice: ListenVoice?,
    modifier: Modifier = Modifier,
    chapters: ChapterSteps? = null,
) {
    if (reading == null) {
        if (controller != null) PlayerToolbar(listen, controller, rate, onRate, haptics, hasChapters, voice, modifier, chapters)
        return
    }
    val player = listening && controller != null
    // The bar that's up leaves first and the other follows it in a moment later, so the two hop
    // past each other rather than blur into one.
    val readingBar = remember { MutableTransitionState(!player) }
    val playerBar = remember { MutableTransitionState(player) }
    LaunchedEffect(player) {
        val (leaving, arriving) = if (player) readingBar to playerBar else playerBar to readingBar
        if (arriving.targetState && !leaving.targetState) return@LaunchedEffect
        leaving.targetState = false
        if (!motionRemoved()) delay(HOP_GAP_MS)
        arriving.targetState = true
    }
    val motion = MaterialTheme.motionScheme
    val hopIn = slideInVertically(motion.fastSpatialSpec()) { it } +
        scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f, transformOrigin = BottomMiddle) +
        fadeIn(motion.fastEffectsSpec())
    val dropOut = slideOutVertically(motion.fastSpatialSpec()) { it } +
        scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f, transformOrigin = BottomMiddle) +
        fadeOut(motion.defaultEffectsSpec())
    BarStand(up = if (player) Bar.PLAYER else Bar.READING, modifier = modifier) {
        AnimatedVisibility(readingBar, Modifier.layoutId(Bar.READING), enter = hopIn, exit = dropOut, label = "reading-bar") {
            ReadingToolbar(reading, haptics)
        }
        if (controller != null) {
            AnimatedVisibility(playerBar, Modifier.layoutId(Bar.PLAYER), enter = hopIn, exit = dropOut, label = "player-bar") {
                PlayerToolbar(listen, controller, rate, onRate, haptics, hasChapters, voice, chapters = chapters)
            }
        }
    }
}

/** The two bars the reader's toolbar is. */
private enum class Bar { READING, PLAYER }

/**
 * Where the two bars stand, one over the other on the same foot: as tall as the one that is [up].
 * The two aren't always the same height (on a narrow screen the player's six buttons are drawn a
 * little smaller to fit). Whatever rides above the toolbar settles onto the new bar on the spring
 * it hops in on, instead of dropping the difference at the moment the old one has gone.
 */
@Composable
private fun BarStand(up: Bar, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val scope = rememberCoroutineScope()
    val stand = remember { Stand() }
    Layout(content, modifier) { measurables, constraints ->
        val bars = measurables.map { it.layoutId to it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        // Until the bar on its way in is there to be measured, the one on its way out is the height to keep.
        val standing = (bars.firstOrNull { it.first == up } ?: bars.firstOrNull())?.second?.height ?: 0
        val height = if (bars.isEmpty()) {
            0
        } else {
            val tall = stand.height ?: Animatable(standing.toFloat()).also { stand.height = it }
            if (tall.targetValue != standing.toFloat()) scope.launch { tall.animateTo(standing.toFloat(), spring) }
            tall.value.roundToInt().coerceAtLeast(0)
        }
        val width = (bars.maxOfOrNull { it.second.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            // On the same foot, in the middle: a taller bar on its way out stands a little proud of the slot.
            bars.forEach { (_, bar) -> bar.place((width - bar.width) / 2, height - bar.height) }
        }
    }
}

/** How tall the bars' stand is, on its way to the bar that is up; null until the first bar has been measured. */
private class Stand {
    var height: Animatable<Float, AnimationVector1D>? = null
}

/** The toolbar while you read: contents, read-aloud, the chapter steps, and the text settings. */
@Composable
private fun ReadingToolbar(reading: ReadingActions, haptics: Haptics) {
    // The square the player's button starts from (see PlayButton).
    val square = remember { MorphShape(Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided), 0f) }
    // The big button is Listen, where the player's play button will stand; the text settings,
    // set once and seldom changed after, wait on the bar. (Pages that can't be read aloud keep
    // the text settings there.)
    val onListen = reading.onListen
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = Modifier.shrinkToFit(),
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = {
            if (onListen != null) {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onListen, shape = square) {
                    Icon(JacketIcons.Headphones, stringResource(R.string.reader_listen))
                }
            } else {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { haptics.tick(); reading.onText() }, shape = square) {
                    Icon(JacketIcons.TextFormat, stringResource(R.string.reader_text_settings))
                }
            }
        },
    ) {
        IconButton(onClick = { haptics.tick(); reading.onContents() }) {
            Icon(JacketIcons.Contents, reading.contentsLabel)
        }
        if (onListen != null) {
            IconButton(onClick = { haptics.tick(); reading.onText() }) {
                Icon(JacketIcons.TextFormat, stringResource(R.string.reader_text_settings))
            }
        }
        IconButton(onClick = { haptics.tick(); reading.onStep(-1) }, enabled = reading.canStep(-1)) {
            Icon(JacketIcons.Previous, reading.previousLabel)
        }
        IconButton(onClick = { haptics.tick(); reading.onStep(1) }, enabled = reading.canStep(1)) {
            Icon(JacketIcons.Next, reading.nextLabel)
        }
    }
}

/** The toolbar as the player: skips by sentence (and by chapter, where they fit), speed, the voice, the sleep timer, stop, and play/pause. */
@Composable
private fun PlayerToolbar(
    listen: ListenState,
    controller: ListenController,
    rate: Float,
    onRate: (Float) -> Unit,
    haptics: Haptics,
    hasChapters: Boolean,
    voice: ListenVoice?,
    modifier: Modifier = Modifier,
    chapters: ChapterSteps? = null,
) {
    // The chapter steps flank the sentence skips, as on any player, where the bar has room for them.
    val chaptersOnBar = chapters?.takeIf { chaptersFitOnBar() }
    HorizontalFloatingToolbar(
        expanded = true,
        // (Six buttons across: on the narrowest screens they are drawn a little smaller rather than cut off at both ends.)
        modifier = modifier.shrinkToFit(),
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = { PlayButton(listen, controller, haptics) },
    ) {
        if (chaptersOnBar != null) {
            IconButton(onClick = { haptics.tick(); chaptersOnBar.onStep(-1) }, enabled = chaptersOnBar.canStep(-1)) {
                Icon(JacketIcons.ChapterBack, stringResource(chaptersOnBar.previousLabel))
            }
        }
        IconButton(onClick = { haptics.tick(); controller.skip(-1) }) {
            Icon(JacketIcons.SkipBack, stringResource(controller.previousLabel))
        }
        IconButton(onClick = { haptics.tick(); controller.skip(1) }) {
            Icon(JacketIcons.SkipForward, stringResource(controller.nextLabel))
        }
        if (chaptersOnBar != null) {
            IconButton(onClick = { haptics.tick(); chaptersOnBar.onStep(1) }, enabled = chaptersOnBar.canStep(1)) {
                Icon(JacketIcons.ChapterForward, stringResource(chaptersOnBar.nextLabel))
            }
        }
        RateButton(listen, rate, onRate, haptics)
        // The voice sits on the bar beside the moon where there's room for one more icon.
        if (voice != null && voiceFitsOnBar()) VoiceButton(voice, listen, haptics)
        SleepButton(listen, hasChapters, haptics)
        IconButton(onClick = { haptics.tick(); controller.stop() }) {
            Icon(JacketIcons.Close, stringResource(R.string.listen_stop))
        }
    }
}

/**
 * Play and pause. It arrives as the reading bar's square and, while the voice reads, turns into
 * the slowly spinning cookie; a loading shape shows while the voice gets ready. The button follows
 * its own shape, so only the button is redrawn as it changes, not the whole bar.
 */
@Composable
private fun PlayButton(listen: ListenState, controller: ListenController, haptics: Haptics) {
    val playing = listen.playing
    // Getting ready is neither playing nor paused: the button keeps its turning cookie, and shows
    // a loading shape where the pause bars will be.
    val preparing = playing && listen.preparing
    val motion = MaterialTheme.motionScheme
    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided) }
    val shapeSpec = motion.slowSpatialSpec<Float>()
    val shapeProgress = remember { Animatable(0f) }
    LaunchedEffect(playing) { shapeProgress.animateTo(if (playing) 1f else 0f, shapeSpec) }
    // Turning only while it reads, and only as a layer. Paused, it runs on to where a square looks
    // square and rests.
    val turn = rememberTurn(playing)
    val progress = shapeProgress.value
    val shape = remember(morph, progress) { MorphShape(morph, progress) }
    // One thing to a screen reader, whatever is drawn on it: a button that says what a press does,
    // or that the voice is on its way.
    val label = stringResource(if (preparing) R.string.listen_preparing else if (playing) R.string.listen_pause else R.string.listen_play)
    FloatingToolbarDefaults.VibrantFloatingActionButton(
        onClick = {
            haptics.toggle(!listen.playing)
            controller.toggle()
        },
        modifier = Modifier.graphicsLayer { rotationZ = turn.value }.semantics { contentDescription = label },
        shape = shape,
    ) {
        val effects = motion.fastEffectsSpec<Float>()
        val spatial = motion.fastSpatialSpec<Float>()
        AnimatedContent(
            targetState = when {
                preparing -> Face.PREPARING
                playing -> Face.PAUSE
                else -> Face.PLAY
            },
            transitionSpec = { (scaleIn(spatial, initialScale = 0.6f) + fadeIn(effects)) togetherWith (scaleOut(spatial, targetScale = 0.6f) + fadeOut(effects)) },
            // The icon stays upright inside the turning button.
            modifier = Modifier.graphicsLayer { rotationZ = -turn.value }.clearAndSetSemantics {},
            label = "action",
        ) { face ->
            when (face) {
                Face.PREPARING -> LoadingIndicator(Modifier.size(28.dp), color = LocalContentColor.current)
                Face.PAUSE -> Icon(JacketIcons.Pause, null)
                Face.PLAY -> Icon(JacketIcons.Play, null)
            }
        }
    }
}

/**
 * The speed: the bar shows the one in use, and a tap lists the others, from half the voice's own
 * pace to three times it.
 */
@Composable
private fun RateButton(listen: ListenState, rate: Float, onRate: (Float) -> Unit, haptics: Haptics) {
    var open by remember { mutableStateOf(false) }
    // The bar stays under its own menu.
    DisposableEffect(open) {
        listen.rateMenu = open
        onDispose { listen.rateMenu = false }
    }
    val resources = LocalResources.current
    val label = rateLabel(resources, rate)
    val described = stringResource(R.string.listen_speed, label)
    Box {
        TextButton(
            onClick = { haptics.tick(); open = true },
            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
            modifier = Modifier.semantics { contentDescription = described },
        ) {
            // Said by the button, as "Speed: 1.25×": not a second time as a bare number. With the
            // phone's font size turned up it stays the width of the buttons beside it.
            Text(
                label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                autoSize = fitOnOneLine(MaterialTheme.typography.labelLarge),
                modifier = Modifier.widthIn(max = 44.dp).clearAndSetSemantics {},
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listenRateChoices(rate).forEach { choice ->
                DropdownMenuItem(
                    text = { Text(rateLabel(resources, choice)) },
                    leadingIcon = if (choice == rate) { { Icon(JacketIcons.Check, null) } } else null,
                    onClick = {
                        haptics.tick()
                        onRate(choice)
                        open = false
                    },
                    // The tick is for the eye; a screen reader is told which speed is the one in use.
                    modifier = Modifier.semantics { selected = choice == rate },
                )
            }
        }
    }
}

/**
 * The room the reader's controls have across, where that is less than the window: one page, on a
 * phone held half-open like a book, whose controls keep off the fold. Unspecified everywhere else.
 */
internal val LocalControlsWidth = compositionLocalOf { Dp.Unspecified }

/** How wide a space the player's bar has: the window, or the one page the controls are on. */
@Composable
private fun controlsWidth(): Dp =
    LocalControlsWidth.current.takeIf { it != Dp.Unspecified } ?: with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** Windows at least this wide fit the chapter steps on the player's bar, beside the sentence skips. */
private val CHAPTERS_ON_BAR_FROM = 500.dp

/** The two chapter steps fit on the player's bar; on a narrower window they sit above the player. */
@Composable
internal fun chaptersFitOnBar(): Boolean = controlsWidth() >= CHAPTERS_ON_BAR_FROM

/**
 * The steps to the previous and the next chapter, around the name of the one being read: above the
 * player on a narrow window, where the player is laid out by itself (the desk, the cover). Over the
 * page the same pill is one form of the chapter control (see ChapterControl), which draws it alike.
 */
@Composable
private fun ChapterPill(chapters: ChapterSteps, haptics: Haptics, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { haptics.tick(); chapters.onStep(-1) }, enabled = chapters.canStep(-1)) {
                Icon(JacketIcons.ChapterBack, stringResource(chapters.previousLabel))
            }
            if (chapters.title.isNotBlank()) {
                Text(
                    chapters.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = { haptics.tick(); chapters.onStep(1) }, enabled = chapters.canStep(1)) {
                Icon(JacketIcons.ChapterForward, stringResource(chapters.nextLabel))
            }
        }
    }
}

/** Windows at least this wide fit the voice's icon on the player's bar. */
private val VOICE_ON_BAR_FROM = 380.dp

/** The voice's icon fits on the player's bar; on a narrower window its name sits above the player. */
@Composable
internal fun voiceFitsOnBar(): Boolean = controlsWidth() >= VOICE_ON_BAR_FROM

/**
 * "Back to the voice": after the listener moved away from the sentence being read, the way back,
 * springing up above the controls. Above everything else at the bottom of the screen, the chapter
 * slider included, as the one thing to do next.
 */
@Composable
internal fun BackToVoiceButton(listen: ListenState, controller: ListenController, haptics: Haptics, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = listen.active && listen.offPage,
        modifier = modifier,
        enter = expandVertically(motion.defaultSpatialSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.7f) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.7f) + fadeOut(motion.fastEffectsSpec()),
    ) {
        Button(
            onClick = { haptics.tick(); controller.backToVoice() },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Icon(JacketIcons.Headphones, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.listen_back_to_voice))
        }
    }
}

/**
 * The voice reading, as an icon on the bar: a tap opens the voices, a long press names this one.
 * When the phone can't keep up with it, the icon wears a red dot and says so once by itself.
 */
@Composable
private fun VoiceButton(voice: ListenVoice, listen: ListenState, haptics: Haptics) {
    val slow = stringResource(R.string.voice_slow)
    val change = stringResource(R.string.voice_change)
    key(voice.struggling) {
        // The warning stays until it's read and dismissed; the name only for a moment.
        val tooltip = rememberTooltipState(isPersistent = voice.struggling)
        // Once: the bar comes and goes while you listen, and the dot is reminder enough after that.
        LaunchedEffect(Unit) {
            if (voice.struggling && !listen.warned) {
                listen.warned = true
                tooltip.show()
            }
        }
        // The bar waits under the warning for it to be read.
        if (voice.struggling) {
            DisposableEffect(tooltip.isVisible) {
                listen.warning = tooltip.isVisible
                onDispose { listen.warning = false }
            }
        }
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(if (voice.struggling) slow else voice.label) } },
            state = tooltip,
        ) {
            IconButton(
                onClick = { haptics.tick(); voice.onClick() },
                modifier = Modifier.semantics {
                    contentDescription = if (voice.struggling) slow else "${voice.label}, $change"
                },
            ) {
                BadgedBox(badge = { if (voice.struggling) Badge() }) {
                    // A natural voice on its way: a ring round the icon fills as it arrives.
                    val downloading = voice.downloading
                    if (downloading != null) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { downloading }, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                            Icon(JacketIcons.Voice, null, Modifier.size(18.dp))
                        }
                    } else {
                        Icon(JacketIcons.Voice, null)
                    }
                }
            }
        }
    }
}

/** The minutes a timed sleep has left, counting down; null when none is running. */
@Composable
private fun rememberSleepMinutesLeft(listen: ListenState): Int? {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(listen.sleep, listen.sleepEndsAt) {
        while (listen.sleep.timed) {
            now = SystemClock.uptimeMillis()
            delay(15_000)
        }
    }
    return if (listen.sleep.timed) sleepMinutesLeft(listen.sleepEndsAt, now) else null
}

/** What the moon says of the timer: off, counting down, or waiting for the chapter to end. */
@Composable
private fun sleepLabel(listen: ListenState, minutesLeft: Int?): String = when {
    listen.sleep == SleepTimer.CHAPTER -> stringResource(R.string.listen_sleep_timer_end_of_chapter)
    minutesLeft != null -> pluralStringResource(R.plurals.listen_sleep_minutes_left, minutesLeft, minutesLeft)
    else -> stringResource(R.string.listen_sleep_timer)
}

/**
 * The moon: pick when reading stops by itself; shows the minutes left while counting down. The
 * usual lengths are a tap away, and "Other length" takes any number of minutes; the last one
 * chosen that way is kept beside the usual ones, for the listener who sets the same every night.
 */
@Composable
private fun SleepButton(listen: ListenState, hasChapters: Boolean, haptics: Haptics) {
    var open by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    // The bar stays under its own menu, and under the choosing of a length.
    DisposableEffect(open, choosing) {
        listen.sleepMenu = open || choosing
        onDispose { listen.sleepMenu = false }
    }
    val library = LocalContext.current.library
    val settings by library.state.collectAsStateWithLifecycle()
    // The listener's own length, unless it is one of the usual ones anyway.
    val own = settings.settings.sleepMinutes.takeIf { minutes -> minutes > 0 && SleepTimer.entries.none { it.minutes == minutes } }
    val start = { timer: SleepTimer, minutes: Int -> listen.startSleep(timer, minutes) }
    val minutesLeft = rememberSleepMinutesLeft(listen)
    Box {
        val label = sleepLabel(listen, minutesLeft)
        IconButton(onClick = { haptics.tick(); open = true }) {
            BadgedBox(badge = {
                if (minutesLeft != null) {
                    // The moon's own name says the minutes left: the number isn't read a second time.
                    Badge(Modifier.clearAndSetSemantics {}) { Text("$minutesLeft") }
                } else if (listen.sleep == SleepTimer.CHAPTER) {
                    Badge()
                }
            }) {
                Icon(JacketIcons.Bedtime, label)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // A length chosen by hand that isn't the one kept (it was changed since) still shows as running.
            val running = listen.sleep
            val preset = @Composable { option: SleepTimer ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = if (option == running) { { Icon(JacketIcons.Check, null) } } else null,
                    onClick = {
                        haptics.tick()
                        start(option, option.minutes)
                        open = false
                    },
                    modifier = Modifier.semantics { selected = option == running },
                )
            }
            SleepTimer.entries.filter { it == SleepTimer.OFF || it.minutes > 0 }.forEach { preset(it) }
            // The listener's own length, with the other lengths.
            if (own != null) {
                DropdownMenuItem(
                    text = { Text(sleepLengthLabel(own)) },
                    leadingIcon = if (running == SleepTimer.OTHER) { { Icon(JacketIcons.Check, null) } } else null,
                    onClick = {
                        haptics.tick()
                        start(SleepTimer.OTHER, own)
                        open = false
                    },
                    modifier = Modifier.semantics { selected = running == SleepTimer.OTHER },
                )
            }
            if (hasChapters) preset(SleepTimer.CHAPTER)
            DropdownMenuItem(
                text = { Text(stringResource(SleepTimer.OTHER.label)) },
                leadingIcon = if (running == SleepTimer.OTHER && own == null) { { Icon(JacketIcons.Check, null) } } else null,
                onClick = {
                    haptics.tick()
                    open = false
                    choosing = true
                },
                modifier = Modifier.semantics { selected = running == SleepTimer.OTHER && own == null },
            )
        }
    }
    if (choosing) {
        SleepLengthDialog(
            initial = settings.settings.sleepMinutes,
            onDismiss = { choosing = false },
            onStart = { minutes ->
                haptics.confirm()
                library.updateSettings { it.copy(sleepMinutes = minutes) }
                start(SleepTimer.OTHER, minutes)
                choosing = false
            },
        )
    }
}

/** A length of time in words, as the phone's language puts it: "45 minutes", "1 hour, 30 minutes". */
@Composable
private fun sleepLengthLabel(minutes: Int): String {
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    return remember(minutes, locale) {
        val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
        val hours = Measure(minutes / 60, MeasureUnit.HOUR)
        val rest = Measure(minutes % 60, MeasureUnit.MINUTE)
        when {
            minutes < 60 -> format.format(rest)
            minutes % 60 == 0 -> format.format(hours)
            else -> format.formatMeasures(hours, rest)
        }
    }
}

/** "Other length": a sleep timer of any number of minutes, typed in. It opens on the last one chosen. */
@Composable
private fun SleepLengthDialog(initial: Int, onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    val first = if (initial > 0) initial.toString() else ""
    // Opened with what's there selected, so typing replaces it.
    var typed by remember { mutableStateOf(TextFieldValue(first, TextRange(0, first.length))) }
    val minutes = sleepMinutesOf(typed.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(JacketIcons.Bedtime, null) },
        title = { Text(stringResource(R.string.listen_sleep_other_title)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.copy(text = sleepMinutesTyped(it.text)) },
                label = { Text(stringResource(R.string.listen_sleep_other_minutes)) },
                supportingText = if (minutes != null && minutes >= 60) { { Text(sleepLengthLabel(minutes)) } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { minutes?.let(onStart) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { minutes?.let(onStart) }, enabled = minutes != null) { Text(stringResource(R.string.listen_sleep_other_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

// ---------------------------------------------------------------------------------------------
// The mini player: the player at rest, in the reader's footer line
// ---------------------------------------------------------------------------------------------

/**
 * The player at rest. It takes the place of the footer's words and is no taller than they are, so
 * the page under it is the same page whether you read or listen: play and pause inside a ring that
 * is the chapter, who is reading, and how much of the chapter is left ([left], as the footer puts
 * it). The moon shows when a sleep timer is running.
 *
 * When the listener has gone to look elsewhere in the book, it offers the way back to the voice
 * instead. Tapping the line itself is the footer's business: it brings up the full controls.
 */
@Composable
internal fun MiniPlayer(
    listen: ListenState,
    controller: ListenController,
    voice: ListenVoice,
    left: String,
    /** How far through the chapter (or, without chapters, the book) the page is, 0..1. */
    progress: Float,
    colors: PageColors,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MiniPlayButton(listen, controller, progress, colors, haptics)
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = listen.offPage,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f),
            label = "mini-player",
        ) { elsewhere ->
            if (elsewhere) {
                Box(Modifier.height(MiniButton), contentAlignment = Alignment.CenterStart) {
                    BackToVoicePill(controller, colors, haptics)
                }
            } else {
                // One thing to a screen reader: who is reading and what is left ("Sofia, 4 pages left in chapter").
                val said = spoken(stringResource(R.string.list_separator), voice.label, left)
                Row(Modifier.clearAndSetSemantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
                    val style = MaterialTheme.typography.labelMedium
                    // The same voice icon for every voice; in red when the phone can't keep up with this one.
                    Icon(
                        JacketIcons.Voice, null, Modifier.size(14.dp),
                        tint = if (voice.struggling) MaterialTheme.colorScheme.error else colors.secondary,
                    )
                    Spacer(Modifier.width(5.dp))
                    // The name gives way before what's left does.
                    Text(
                        voice.label, style = style, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (left.isNotBlank()) {
                        Text(
                            stringResource(R.string.separator) + left, style = style, color = colors.secondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = fitOnOneLine(style),
                        )
                    }
                }
            }
        }
        if (listen.sleep != SleepTimer.OFF) {
            val minutesLeft = rememberSleepMinutesLeft(listen)
            val label = sleepLabel(listen, minutesLeft)
            Row(
                Modifier.padding(start = 8.dp).clearAndSetSemantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(JacketIcons.Bedtime, null, Modifier.size(12.dp), tint = colors.secondary)
                if (minutesLeft != null) {
                    Spacer(Modifier.width(3.dp))
                    Text("$minutesLeft", style = MaterialTheme.typography.labelMedium, color = colors.secondary, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Play and pause, small, inside a ring that fills as the chapter is read; a loading shape while
 * the voice gets ready. The button is drawn at the footer's scale, but it is a full-size target:
 * that reaches a little past the footer line, over the page's own margin.
 */
@Composable
private fun MiniPlayButton(listen: ListenState, controller: ListenController, progress: Float, colors: PageColors, haptics: Haptics) {
    val playing = listen.playing
    val preparing = listen.preparing && playing
    val motion = MaterialTheme.motionScheme
    val place = animateFloatAsState(progress.coerceIn(0f, 1f), WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "chapter")
    // One thing to a screen reader: a button that says what a tap on it does.
    val label = stringResource(if (preparing) R.string.listen_preparing else if (playing) R.string.listen_pause else R.string.listen_play)
    Box(Modifier.size(MiniButton), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(MiniTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button) {
                    haptics.toggle(!playing)
                    controller.toggle()
                }
                .clearAndSetSemantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { place.value },
                modifier = Modifier.size(MiniButton),
                color = colors.link,
                strokeWidth = 2.dp,
                trackColor = colors.secondary.copy(alpha = 0.28f),
                gapSize = 0.dp,
            )
            val effects = motion.fastEffectsSpec<Float>()
            val spatial = motion.fastSpatialSpec<Float>()
            AnimatedContent(
                targetState = if (preparing) Face.PREPARING else if (playing) Face.PAUSE else Face.PLAY,
                transitionSpec = { (scaleIn(spatial, initialScale = 0.6f) + fadeIn(effects)) togetherWith (scaleOut(spatial, targetScale = 0.6f) + fadeOut(effects)) },
                label = "mini-play",
            ) { face ->
                when (face) {
                    Face.PREPARING -> LoadingIndicator(Modifier.size(18.dp), color = colors.text)
                    Face.PAUSE -> Icon(JacketIcons.Pause, null, Modifier.size(16.dp), tint = colors.text)
                    else -> Icon(JacketIcons.Play, null, Modifier.size(16.dp), tint = colors.text)
                }
            }
        }
    }
}

/**
 * "Back to the voice" at the footer's scale, in the page's own colours: the one thing to do next
 * once the listener has wandered off, without a button sitting over the page they went to look at.
 * (With the full controls up, [BackToVoiceButton] heads them instead.)
 */
@Composable
private fun BackToVoicePill(controller: ListenController, colors: PageColors, haptics: Haptics) {
    Box(
        Modifier
            .requiredHeight(MiniTarget)
            .clickable(interactionSource = null, indication = null, role = Role.Button) {
                haptics.tick()
                controller.backToVoice()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(MiniButton).clip(CircleShape).background(colors.link).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(JacketIcons.Headphones, null, Modifier.size(14.dp), tint = colors.background)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.listen_back_to_voice),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = colors.background, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A [Morph] between two shapes as a Compose [Shape], at [progress]. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, android.graphics.Path())
        path.transform(android.graphics.Matrix().apply { setScale(size.width, size.height) })
        return Outline.Generic(path.asComposePath())
    }
}
