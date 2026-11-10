package io.github.friendlyglobal.jacket.reader

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
}

/** The dimmest the reader goes: still readable in the dark, never a black screen. */
private const val DIMMEST = 0.02f

/** The strip along each edge that listens for these swipes. */
private val EdgeStrip = 32.dp

/**
 * Watches for a finger moving up or down along either edge. A sideways move there is still a page
 * turn (or the system's back swipe), a tap is still a tap, and anywhere else is left alone.
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
    val slop = viewConfiguration.touchSlop
    var moved = Offset.Zero
    var swiping = false
    var step = -1
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
        if (!change.pressed || event.changes.size > 1) break
        val delta = change.positionChange()
        moved += delta
        if (!swiping) {
            if (abs(moved.x) > slop && abs(moved.x) >= abs(moved.y)) break
            if (abs(moved.y) > slop && abs(moved.y) > abs(moved.x) * 1.5f) {
                swiping = true
                levels.active = side
                // Starting from wherever the phone has it, so the first move isn't a jump.
                if (side < 0 && levels.brightness < 0f) levels.brightness = phoneBrightness()
            }
        }
        if (swiping) {
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
    }
    if (swiping) {
        levels.active = 0
        onSettled()
    }
}

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

private val Warmest = Color(0xFFFFB061)

/** A pill beside the finger showing the level being set, while it's being set. */
@Composable
internal fun BoxScope.EdgeLevel(levels: EdgeLevels) {
    val side = levels.active
    var shown by remember { mutableIntStateOf(-1) }
    if (side != 0) shown = side
    val fill = if (shown < 0) MaterialTheme.colorScheme.primary else Warmest
    AnimatedVisibility(
        visible = side != 0,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = 0.8f),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = Modifier.align(if (shown < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 48.dp),
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 176.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .drawBehind {
                    val level = (if (shown < 0) levels.brightness else levels.warmth).coerceIn(0f, 1f)
                    drawRect(fill, Offset(0f, size.height * (1f - level)), Size(size.width, size.height * level))
                },
        )
    }
}
