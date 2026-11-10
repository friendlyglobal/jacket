package io.github.friendlyglobal.jacket.reader

import android.content.res.Resources
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons

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
    var sleep by mutableStateOf(SleepTimer.OFF)
    /** When a timed sleep ends (uptime millis). */
    var sleepEndsAt by mutableLongStateOf(0L)
    /** The chapter file the voice is in (EPUB), or -1: lets "end of chapter" stop on time. */
    var spokenFile by mutableIntStateOf(-1)
}

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
}

val ListenRates = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun rateLabel(resources: Resources, rate: Float): String =
    resources.getString(R.string.listen_rate, if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0'))

/**
 * The audiobook player: a floating toolbar whose play/pause lives in the expressive FAB slot. The
 * button morphs into a turning cookie while it reads, and the wavy progress line above it goes
 * flat when paused.
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
) {
    val playing = listen.playing
    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided) }
    val shapeProgress by animateFloatAsState(
        if (playing) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "play-shape",
    )
    val spin = rememberInfiniteTransition(label = "play-spin")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "turn")
    val wave by animateFloatAsState(if (playing) 1f else 0f, label = "wave")

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        LinearWavyProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            amplitude = { wave },
            modifier = Modifier.width(240.dp).padding(bottom = 10.dp),
        )
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = {
                        haptics.toggle(!playing)
                        controller.toggle()
                    },
                    shape = MorphShape(morph, shapeProgress, if (playing) turn else 0f),
                ) {
                    AnimatedContent(
                        targetState = playing,
                        transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                        label = "play",
                    ) { on ->
                        Icon(
                            if (on) JacketIcons.Pause else JacketIcons.Play,
                            if (on) stringResource(R.string.listen_pause) else stringResource(R.string.listen_play),
                        )
                    }
                }
            },
        ) {
            IconButton(onClick = { haptics.tick(); controller.skip(-1) }) { Icon(JacketIcons.SkipBack, stringResource(controller.previousLabel)) }
            IconButton(onClick = { haptics.tick(); controller.skip(1) }) { Icon(JacketIcons.SkipForward, stringResource(controller.nextLabel)) }
            TextButton(
                onClick = {
                    haptics.tick()
                    onRate(ListenRates[(ListenRates.indexOf(rate).coerceAtLeast(0) + 1) % ListenRates.size])
                },
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
            ) {
                Text(rateLabel(LocalResources.current, rate), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            SleepButton(listen, hasChapters, haptics)
            IconButton(onClick = { haptics.tick(); controller.stop() }) { Icon(JacketIcons.Close, stringResource(R.string.listen_stop)) }
        }
    }
}

/** The moon: pick when reading stops by itself; shows the minutes left while counting down. */
@Composable
private fun SleepButton(listen: ListenState, hasChapters: Boolean, haptics: Haptics) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(listen.sleep, listen.sleepEndsAt) {
        while (listen.sleep.minutes > 0) {
            now = SystemClock.uptimeMillis()
            delay(15_000)
        }
    }
    Box {
        val active = listen.sleep != SleepTimer.OFF
        val label = when {
            listen.sleep == SleepTimer.CHAPTER -> stringResource(R.string.listen_sleep_timer_end_of_chapter)
            active -> {
                val left = ((listen.sleepEndsAt - now) / 60_000 + 1).coerceAtLeast(1).toInt()
                pluralStringResource(R.plurals.listen_sleep_minutes_left, left, left)
            }
            else -> stringResource(R.string.listen_sleep_timer)
        }
        IconButton(onClick = { haptics.tick(); open = true }) {
            BadgedBox(badge = {
                if (listen.sleep.minutes > 0) {
                    Badge { Text("${((listen.sleepEndsAt - now) / 60_000 + 1).coerceAtLeast(1)}") }
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

/** A [Morph] between two shapes as a Compose [Shape], at [progress], turned by [rotation] degrees. */
private class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, android.graphics.Path())
        val matrix = android.graphics.Matrix().apply {
            setScale(size.width, size.height)
            postRotate(rotation, size.width / 2, size.height / 2)
        }
        path.transform(matrix)
        return Outline.Generic(path.asComposePath())
    }
}
