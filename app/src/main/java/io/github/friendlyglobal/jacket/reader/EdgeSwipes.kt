package io.github.friendlyglobal.jacket.reader

import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.findActivity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The reader's own brightness and warmth, set by swiping up and down the page's edges: the left
 * edge for brightness, the right for warmth. Read while drawing, so a swipe recomposes nothing.
 */
@Stable
internal class EdgeLevels(brightness: Float, warmth: Float) {
    /** 0..1, or negative to leave the screen to the phone. */
    var brightness by mutableFloatStateOf(brightness)
    var warmth by mutableFloatStateOf(warmth)

    /** The edge under the finger: -1 the left (brightness), +1 the right (warmth), 0 neither. */
    var active by mutableIntStateOf(0)

    /** The edge a finger has landed on and not yet left, swiping or not (its handle answers it, see EdgeHandles): -1, +1, or 0. */
    var touched by mutableIntStateOf(0)
}

/** The dimmest the reader goes: still readable in the dark, never a black screen. */
internal const val DIMMEST = 0.02f

/** How bright the phone itself has the screen (0..1): where the reader's own brightness starts from. */
internal fun phoneBrightness(context: android.content.Context): Float =
    android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

/**
 * The strip along each edge that listens for these swipes: about a fingertip wide, so it is easy
 * to land in. Nothing is taken from anything else by its width: only a move up or down, begun
 * soon after the finger lands, is a swipe; a move sideways there is still a page turn (or
 * Android's back gesture, which the phone itself watches for), a tap is still a tap, and a finger
 * held still is still a press and hold on the words.
 */
internal val EdgeStrip = 40.dp

/**
 * Watches for a finger moving up or down along either edge. A sideways move there is still a page
 * turn (or the system's back swipe), a tap is still a tap, a finger held still before it moves is
 * still a press and hold (choosing words, which the move after it stretches), and anywhere else is
 * left alone.
 */
internal suspend fun PointerInputScope.edgeSwipes(
    levels: EdgeLevels,
    enabled: () -> Boolean,
    phoneBrightness: () -> Float,
    haptics: Haptics,
    onSettled: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (!enabled()) return@awaitEachGesture
    val strip = EdgeStrip.toPx()
    val side = when {
        down.position.x <= strip -> -1
        down.position.x >= size.width - strip -> 1
        else -> return@awaitEachGesture
    }
    levels.touched = side
    try {
        val slop = viewConfiguration.touchSlop
        var moved = Offset.Zero
        // Before it is a swipe: a move up or down, begun before a press and hold would be.
        val swiping = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var decided: Boolean? = null
            while (decided == null) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id }
                if (change != null) moved += change.positionChange()
                decided = when {
                    change == null || !change.pressed || event.changes.size > 1 -> false
                    abs(moved.x) > slop && abs(moved.x) >= abs(moved.y) -> false
                    abs(moved.y) > slop && abs(moved.y) > abs(moved.x) * 1.5f -> true
                    else -> null
                }
            }
            decided
        } == true
        if (!swiping) return@awaitEachGesture
        levels.active = side
        // Starting from wherever the phone has it, so the first move isn't a jump.
        if (side < 0 && levels.brightness < 0f) levels.brightness = phoneBrightness()
        var step = -1
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
            if (!change.pressed || event.changes.size > 1) break
            val delta = change.positionChange()
            change.consume()
            val by = -delta.y / (size.height * 0.6f)
            if (side < 0) {
                levels.brightness = (levels.brightness + by).coerceIn(DIMMEST, 1f)
            } else {
                levels.warmth = (levels.warmth + by).coerceIn(0f, 1f)
            }
            val notch = ((if (side < 0) levels.brightness else levels.warmth) * 10).roundToInt()
            if (notch != step) {
                if (step >= 0) haptics.frequentTick()
                step = notch
            }
        }
        levels.active = 0
        onSettled()
    } finally {
        levels.touched = 0
        levels.active = 0
    }
}

/**
 * The brightness the reader holds the inner screen at (0..1), or negative while it leaves it to
 * the phone. The cover screen keeps to the same (see OuterDisplay).
 */
internal val ReaderHeldBrightness = MutableStateFlow(-1f)

/** Holds the window at the reader's brightness while a book is open, and hands it back after. */
@Composable
internal fun ReaderBrightness(levels: EdgeLevels, enabled: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    fun set(level: Float) {
        window ?: return
        window.attributes = window.attributes.apply {
            screenBrightness = if (level < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level
        }
        ReaderHeldBrightness.value = if (level < 0f) -1f else level
    }
    LaunchedEffect(window, enabled) {
        snapshotFlow { if (enabled) levels.brightness else -1f }.collect(::set)
    }
    DisposableEffect(window) { onDispose { set(-1f) } }
}

/** The warm tint over the page: white (nothing) to amber, multiplied in like a filter. */
@Composable
internal fun BoxScope.Warmth(levels: EdgeLevels, enabled: Boolean) {
    if (!enabled) return
    Box(
        Modifier.matchParentSize().drawBehind {
            val warmth = levels.warmth
            if (warmth > 0f) drawRect(lerp(Color.White, Warmest, warmth), blendMode = BlendMode.Multiply)
        },
    )
}

/** The warmest the page is tinted: an amber, multiplied in. */
internal val Warmest = Color(0xFFFFB061)
