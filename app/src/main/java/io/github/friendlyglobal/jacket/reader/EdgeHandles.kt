package io.github.friendlyglobal.jacket.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.motionRemoved
import kotlinx.coroutines.delay

/*
 * Where to swipe for brightness and warmth.
 *
 * A swipe up or down the page's left edge sets the brightness, the right edge the warmth (see
 * EdgeSwipes.kt). Nothing on the page said so, and the strips are narrow: readers didn't find
 * them. So each edge has a small handle: a slim pill hugging the edge, a sun on the left and a
 * thermometer on the right, with a little arrow above and below for "up and down". The handles
 * come and go with the reader's controls (when the page is being read nothing sits on it), and the
 * first few times a book is opened they come by themselves for a moment and give a little nudge
 * up and down, as a finger would.
 *
 * A finger landing on an edge brings its handle up, grown and brighter, on the theme's spring;
 * swiping, the level being set rises and falls in a pill that springs out of the handle beside
 * the finger. With animations turned off in the phone's settings they appear and go without
 * moving, and there is no nudge.
 *
 * They are drawn only, never touched themselves (the edges' own watching does the work), and
 * TalkBack passes over them: the reading settings have a slider for each level.
 */

/**
 * The two edges' handles and the level pill, while the swipes are [enabled]. They show while
 * [controlsUp]; the first few times a book opens ([hints] is how many times they have shown by
 * themselves so far) they show for a moment anyway, and [onHinted] is told when they have.
 */
@Composable
internal fun BoxScope.EdgeHandles(levels: EdgeLevels, enabled: Boolean, controlsUp: Boolean, hints: Int, onHinted: () -> Unit) {
    var hinting by remember { mutableStateOf(false) }
    val hinted by rememberUpdatedState(onHinted)
    // Once for each book opened, and only the first few times ever.
    LaunchedEffect(enabled) {
        if (!enabled || hints >= EDGE_HINTS) return@LaunchedEffect
        hinted()
        hinting = true
        delay(HINT_MS)
        hinting = false
    }
    if (!enabled) return
    EdgeHandle(
        side = -1, icon = SunIcon, levels = levels, controlsUp = controlsUp, hinting = hinting,
        modifier = Modifier.align(Alignment.CenterStart),
    )
    EdgeHandle(
        side = 1, icon = WarmthIcon, levels = levels, controlsUp = controlsUp, hinting = hinting,
        modifier = Modifier.align(Alignment.CenterEnd),
    )
    EdgeLevel(levels)
}

/** One edge's handle: [side] -1 the left (brightness), +1 the right (warmth). */
@Composable
private fun EdgeHandle(side: Int, icon: ImageVector, levels: EdgeLevels, controlsUp: Boolean, hinting: Boolean, modifier: Modifier) {
    val motion = MaterialTheme.motionScheme
    val touched = levels.touched == side || levels.active == side
    AnimatedVisibility(
        visible = controlsUp || hinting || touched,
        // In from the edge it hugs, and back out to it.
        enter = slideInHorizontally(motion.fastSpatialSpec()) { if (side < 0) -it else it } + fadeIn(motion.fastEffectsSpec()),
        exit = slideOutHorizontally(motion.defaultSpatialSpec()) { if (side < 0) -it else it } + fadeOut(motion.defaultEffectsSpec()),
        modifier = modifier.clearAndSetSemantics {},
    ) {
        val colors = MaterialTheme.colorScheme
        // Grown and brighter under a finger: the theme's spatial spring, which runs a little past and back.
        val grown by animateFloatAsState(if (touched) 1f else 0f, motion.fastSpatialSpec(), label = "edge-handle")
        val container by animateColorAsState(
            if (touched) colors.primaryContainer else colors.surfaceContainerHighest, motion.fastEffectsSpec(), label = "edge-handle-container",
        )
        val content by animateColorAsState(
            if (touched) colors.onPrimaryContainer else colors.onSurfaceVariant, motion.fastEffectsSpec(), label = "edge-handle-content",
        )
        // The nudge: up a little, down a little, and back, as a finger swiping would go.
        val nudge = remember { Animatable(0f) }
        val swing = motion.defaultSpatialSpec<Float>()
        LaunchedEffect(hinting) {
            if (!hinting || motionRemoved()) return@LaunchedEffect
            delay(NUDGE_DELAY_MS)
            repeat(NUDGES) {
                nudge.animateTo(-1f, swing)
                nudge.animateTo(1f, swing)
            }
            nudge.animateTo(0f, swing)
        }
        Box(
            Modifier
                .padding(horizontal = HandleInset)
                .graphicsLayer {
                    val g = grown.coerceAtLeast(0f)
                    transformOrigin = TransformOrigin(if (side < 0) 0f else 1f, 0.5f)
                    scaleX = 1f + GROWN_WIDER * g
                    scaleY = 1f + GROWN_TALLER * g
                    translationY = nudge.value * NudgeDistance.toPx()
                    alpha = (RESTING_ALPHA + (1f - RESTING_ALPHA) * g).coerceIn(0f, 1f)
                }
                .size(width = HandleWidth, height = HandleHeight)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((-4).dp)) {
                Icon(JacketIcons.ExpandLess, null, tint = content, modifier = Modifier.size(14.dp))
                Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
                Icon(JacketIcons.ExpandMore, null, tint = content, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The level being set, while it is being set: a pill beside the finger that fills from the foot
 * as the level rises, springing out of the edge's handle and folding back into it.
 */
@Composable
private fun BoxScope.EdgeLevel(levels: EdgeLevels) {
    val side = levels.active
    var shown by remember { mutableIntStateOf(-1) }
    if (side != 0) shown = side
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val fill = if (shown < 0) colors.primary else Warmest
    val onFill = if (shown < 0) colors.onPrimary else OnWarmest
    val origin = TransformOrigin(if (shown < 0) 0f else 1f, 0.5f)
    AnimatedVisibility(
        visible = side != 0,
        enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f, transformOrigin = origin),
        exit = fadeOut(motion.defaultEffectsSpec()) + scaleOut(motion.defaultSpatialSpec(), targetScale = 0.6f, transformOrigin = origin),
        modifier = Modifier.align(if (shown < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = LevelInset).clearAndSetSemantics {},
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 176.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHighest)
                .drawBehind {
                    val level = (if (shown < 0) levels.brightness else levels.warmth).coerceIn(0f, 1f)
                    drawRect(fill, Offset(0f, size.height * (1f - level)), Size(size.width, size.height * level))
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Its sign at the foot: on the fill once the level covers it, on the empty pill below that.
            val covered = (if (shown < 0) levels.brightness else levels.warmth) >= ICON_COVERED
            Icon(
                if (shown < 0) SunIcon else WarmthIcon, null,
                tint = if (covered) onFill else colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp).size(20.dp),
            )
        }
    }
}

/** How many times the handles show by themselves when a book opens, before they only come with the controls. */
internal const val EDGE_HINTS = 3

/** How long they stay, those times. */
private const val HINT_MS = 3_200L

/** How long they wait, having come, before they nudge; and how many times they go up and down. */
private const val NUDGE_DELAY_MS = 450L
private const val NUDGES = 2

private val HandleWidth = 20.dp
private val HandleHeight = 72.dp
private val HandleInset = 2.dp
private val NudgeDistance = 8.dp

/** The level pill stands clear of the handle, grown as it is under the finger. */
private val LevelInset = 48.dp

/** How much wider and taller a handle grows under a finger, and how see-through it is at rest. */
private const val GROWN_WIDER = 0.4f
private const val GROWN_TALLER = 0.15f
private const val RESTING_ALPHA = 0.88f

/** The level at which the fill reaches the sign at the foot of the level pill. */
private const val ICON_COVERED = 0.2f

/** A dark brown that reads on the warm amber. */
private val OnWarmest = Color(0xFF4A2A00)

/** A sun: the screen's brightness. */
private val SunIcon: ImageVector = JacketIcons.Brightness

/** A thermometer: how warm the page is tinted. */
private val WarmthIcon: ImageVector = JacketIcons.Warmth
