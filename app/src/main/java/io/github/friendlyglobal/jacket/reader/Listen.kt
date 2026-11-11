package io.github.friendlyglobal.jacket.reader

import android.content.res.Resources
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.PlainTooltip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.rememberTurn
import io.github.friendlyglobal.jacket.ui.voices.VoiceChip
import kotlinx.coroutines.delay

/** When read-aloud stops by itself. */
enum class SleepTimer(val minutes: Int, @StringRes val label: Int) {
    OFF(0, R.string.listen_sleep_off),
    MIN_15(15, R.string.listen_sleep_15_minutes),
    MIN_30(30, R.string.listen_sleep_30_minutes),
    MIN_60(60, R.string.listen_sleep_1_hour),
    CHAPTER(0, R.string.listen_sleep_end_of_chapter),
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
    /** The chapter file the voice is in (EPUB), or -1: lets "end of chapter" stop on time. */
    var spokenFile by mutableIntStateOf(-1)
    /** The sentence being spoken, and how many of its characters have been: shown over the cover when the phone is shut. */
    var phrase by mutableStateOf("")
    var spoken by mutableIntStateOf(0)
    /**
     * The listener has moved away from the sentence being read (turned pages, scrolled, jumped):
     * the page stops following the voice, and the player offers the way back to it.
     */
    var offPage by mutableStateOf(false)

    /** The sleep timer's menu is open over the player. */
    var sleepMenu by mutableStateOf(false)
    /** The player is saying that the phone can't keep up with the voice. */
    var warning by mutableStateOf(false)
    /** That has been said once for this voice: afterwards the dot on its icon stands for it. */
    var warned = false

    /** Something of the player's is open over it: the controls stay where they are meanwhile. */
    val held: Boolean get() = sleepMenu || warning
}

/** The voice reading the book, for the player: its name, whether it's a natural one, and whether the phone is keeping up with it. */
class ListenVoice(val label: String, val natural: Boolean, val struggling: Boolean, val onClick: () -> Unit)

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

val ListenRates = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * How fast the voice gets through a book, learnt from the pages it turns as it reads: the time
 * the whole book would take at this rate. The mini player tells the minutes left in the chapter by
 * it. Nothing is known until enough has been heard, and only an unbroken run of the voice's own
 * page turns counts: a pause, or the listener turning a page themselves, starts the run again.
 */
internal class ListeningPace {
    /** Milliseconds the whole book takes to be read aloud; 0 while unknown. */
    var wholeBook = 0f
        private set
    private var since = 0L
    private var from = -1f
    private var heard = 0L
    private var advanced = 0f

    /** The speed went from [old] to [new]: what was learnt carries over, scaled, until the new speed has been heard. */
    fun retimed(old: Float, new: Float) {
        if (old > 0f && new > 0f) wholeBook *= old / new
        from = -1f
        heard = 0L
        advanced = 0f
    }

    /**
     * The page on screen at [now] (milliseconds) is [progress] through the book (0..1).
     * [following] while the voice is speaking and this is the page it turned to by itself.
     */
    fun page(now: Long, progress: Float, following: Boolean) {
        if (!following) {
            from = -1f
            return
        }
        val step = progress - from
        if (from < 0f || step < 0f || step > LONGEST_STEP || now - since > LONGEST_PAGE_MS) {
            since = now
            from = progress
            return
        }
        // The same page, reported again.
        if (step == 0f) return
        heard += now - since
        advanced += step
        since = now
        from = progress
        if (heard >= AFTER_MS) wholeBook = heard / advanced
    }

    private companion object {
        /** The pace is told from at least this much listening... */
        const val AFTER_MS = 30_000L

        /** ...in steps of a page or two at most (this much of the book): more is a jump... */
        const val LONGEST_STEP = 0.05f

        /** ...and none taking longer than a full spread read slowly. */
        const val LONGEST_PAGE_MS = 900_000L
    }
}

/** The player's parts follow one another in by this much. */
private const val ARRIVAL_STAGGER_MS = 70L

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
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Moved away from where the voice is: the way back, unless the screen stacks it itself.
        if (backToVoice) BackToVoiceButton(listen, controller, haptics)
        PlayerLine(listen, progress, voice, haptics, visible = true)
        ReaderToolbar(
            listening = true, reading = null, listen = listen, controller = controller,
            rate = rate, onRate = onRate, haptics = haptics, hasChapters = hasChapters, voice = voice,
        )
    }
}

/**
 * What rides on top of the toolbar while it is the player: the wavy progress line, which goes flat
 * when paused and runs by itself while the voice gets ready; and, on a window too narrow for the
 * voice's icon on the bar, the voice by name. They arrive one after the other, from the toolbar
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
                    onClick = { haptics.tick(); voice.onClick() },
                    modifier = Modifier.widthIn(max = 300.dp).padding(bottom = 10.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = line && arrived >= 1,
            enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()) +
                scaleIn(motion.defaultSpatialSpec(), initialScale = 0.4f),
            exit = leave,
        ) {
            // The line runs on its own while the voice gets ready, then shows the place in the book.
            Crossfade(preparing, animationSpec = motion.defaultEffectsSpec(), label = "line") { busy ->
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
) {
    if (reading == null) {
        if (controller != null) PlayerToolbar(listen, controller, rate, onRate, haptics, hasChapters, voice, modifier)
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
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(readingBar, enter = hopIn, exit = dropOut, label = "reading-bar") {
            ReadingToolbar(reading, haptics)
        }
        if (controller != null) {
            AnimatedVisibility(playerBar, enter = hopIn, exit = dropOut, label = "player-bar") {
                PlayerToolbar(listen, controller, rate, onRate, haptics, hasChapters, voice)
            }
        }
    }
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

/** The toolbar as the player: skips, speed, the voice, the sleep timer, stop, and play/pause. */
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
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = { PlayButton(listen, controller, haptics) },
    ) {
        IconButton(onClick = { haptics.tick(); controller.skip(-1) }) {
            Icon(JacketIcons.SkipBack, stringResource(controller.previousLabel))
        }
        IconButton(onClick = { haptics.tick(); controller.skip(1) }) {
            Icon(JacketIcons.SkipForward, stringResource(controller.nextLabel))
        }
        TextButton(
            onClick = {
                haptics.tick()
                onRate(ListenRates[(ListenRates.indexOf(rate).coerceAtLeast(0) + 1) % ListenRates.size])
            },
            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
        ) {
            Text(rateLabel(LocalResources.current, rate), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
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
    FloatingToolbarDefaults.VibrantFloatingActionButton(
        onClick = {
            haptics.toggle(!listen.playing)
            controller.toggle()
        },
        modifier = Modifier.graphicsLayer { rotationZ = turn.value },
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
            modifier = Modifier.graphicsLayer { rotationZ = -turn.value },
            label = "action",
        ) { face ->
            when (face) {
                Face.PREPARING -> {
                    val label = stringResource(R.string.listen_preparing)
                    LoadingIndicator(
                        Modifier.size(28.dp).semantics { contentDescription = label },
                        color = LocalContentColor.current,
                    )
                }
                Face.PAUSE -> Icon(JacketIcons.Pause, stringResource(R.string.listen_pause))
                Face.PLAY -> Icon(JacketIcons.Play, stringResource(R.string.listen_play))
            }
        }
    }
}

/** Windows at least this wide fit the voice's icon on the player's bar. */
private val VOICE_ON_BAR_FROM = 380.dp

/** The voice's icon fits on the player's bar; on a narrower window its name sits above the player. */
@Composable
internal fun voiceFitsOnBar(): Boolean =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= VOICE_ON_BAR_FROM

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
                BadgedBox(badge = { if (voice.struggling) Badge() }) { Icon(JacketIcons.Voice, null) }
            }
        }
    }
}

/** The minutes a timed sleep has left, counting down; null when none is running. */
@Composable
private fun sleepMinutesLeft(listen: ListenState): Int? {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(listen.sleep, listen.sleepEndsAt) {
        while (listen.sleep.minutes > 0) {
            now = SystemClock.uptimeMillis()
            delay(15_000)
        }
    }
    return if (listen.sleep.minutes > 0) ((listen.sleepEndsAt - now) / 60_000 + 1).coerceAtLeast(1).toInt() else null
}

/** What the moon says of the timer: off, counting down, or waiting for the chapter to end. */
@Composable
private fun sleepLabel(listen: ListenState, minutesLeft: Int?): String = when {
    listen.sleep == SleepTimer.CHAPTER -> stringResource(R.string.listen_sleep_timer_end_of_chapter)
    minutesLeft != null -> pluralStringResource(R.plurals.listen_sleep_minutes_left, minutesLeft, minutesLeft)
    else -> stringResource(R.string.listen_sleep_timer)
}

/** The moon: pick when reading stops by itself; shows the minutes left while counting down. */
@Composable
private fun SleepButton(listen: ListenState, hasChapters: Boolean, haptics: Haptics) {
    var open by remember { mutableStateOf(false) }
    // The bar stays under its own menu.
    DisposableEffect(open) {
        listen.sleepMenu = open
        onDispose { listen.sleepMenu = false }
    }
    val minutesLeft = sleepMinutesLeft(listen)
    Box {
        val label = sleepLabel(listen, minutesLeft)
        IconButton(onClick = { haptics.tick(); open = true }) {
            BadgedBox(badge = {
                if (minutesLeft != null) {
                    Badge { Text("$minutesLeft") }
                } else if (listen.sleep == SleepTimer.CHAPTER) {
                    Badge()
                }
            }) {
                Icon(JacketIcons.Bedtime, label)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SleepTimer.entries.filter { it != SleepTimer.CHAPTER || hasChapters }.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = if (option == listen.sleep) { { Icon(JacketIcons.Check, null) } } else null,
                    onClick = {
                        haptics.tick()
                        listen.sleep = option
                        listen.sleepEndsAt = SystemClock.uptimeMillis() + option.minutes * 60_000L
                        open = false
                    },
                )
            }
        }
    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (listen.sleep != SleepTimer.OFF) {
            val minutesLeft = sleepMinutesLeft(listen)
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
