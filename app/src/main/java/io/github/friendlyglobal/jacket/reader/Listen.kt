package io.github.friendlyglobal.jacket.reader

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons

/** Audiobook mode: whether it's on, and whether it's speaking right now. */
@Stable
class ListenState {
    /** The player is showing (playing or paused). */
    var active by mutableStateOf(false)
    var playing by mutableStateOf(false)
}

/** Implemented by each format that can be read aloud (EPUB and plain text). */
interface ListenController {
    /** What a skip moves by, for labels: "sentence" or "paragraph". */
    val unit: String

    /** Starts reading from the top of what's on screen; explains itself if it can't. */
    fun start()

    fun toggle()

    /** Jumps by [unit]: -1 back, +1 forward. */
    fun skip(delta: Int)

    fun setRate(rate: Float)

    fun stop()
}

val ListenRates = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun rateLabel(rate: Float): String =
    (if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0')) + "×"

/** The audiobook player: a floating toolbar whose play/pause lives in the expressive FAB slot. */
@Composable
fun ListenBar(
    listen: ListenState,
    controller: ListenController,
    rate: Float,
    onRate: (Float) -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = {
            FloatingToolbarDefaults.VibrantFloatingActionButton(
                onClick = {
                    haptics.toggle(!listen.playing)
                    controller.toggle()
                },
            ) {
                AnimatedContent(
                    targetState = listen.playing,
                    transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                    label = "play",
                ) { playing ->
                    Icon(if (playing) JacketIcons.Pause else JacketIcons.Play, if (playing) "Pause" else "Play")
                }
            }
        },
    ) {
        IconButton(onClick = { haptics.tick(); controller.skip(-1) }) { Icon(JacketIcons.SkipBack, "Previous ${controller.unit}") }
        IconButton(onClick = { haptics.tick(); controller.skip(1) }) { Icon(JacketIcons.SkipForward, "Next ${controller.unit}") }
        TextButton(
            onClick = {
                haptics.tick()
                onRate(ListenRates[(ListenRates.indexOf(rate).coerceAtLeast(0) + 1) % ListenRates.size])
            },
            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
        ) {
            Text(rateLabel(rate), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { haptics.tick(); controller.stop() }) { Icon(JacketIcons.Close, "Stop listening") }
    }
}
