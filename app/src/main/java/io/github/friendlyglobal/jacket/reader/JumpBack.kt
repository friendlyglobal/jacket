package io.github.friendlyglobal.jacket.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.motionRemoved
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the way back from a jump stays on offer. */
private const val JUMP_BACK_MS = 12_000L

/** How the offer ended, which decides how the pill leaves. */
private enum class Leaving {
    /** Taken: you went back, and the pill shrinks away where it was. */
    TAKEN,

    /** Waved off with ✕ or a flick, or left to time out: it goes back up where it came from. */
    WAVED,
}

/**
 * "← Back to Chapter 3 | ✕": after a jump through the contents, a search or the scrubber, a split button
 * that takes you back to the page you left, or puts the offer away. It drops in under the top bar
 * with the theme's expressive spring, the ✕ half unfolding from behind it; a ring around the ✕ runs
 * down the time until it goes by itself. Flick it up to dismiss it too.
 */
@Composable
internal fun JumpBack(nav: ReaderNav, haptics: Haptics, visible: Boolean, modifier: Modifier = Modifier) {
    val origin = nav.cameFrom
    // Kept while the pill leaves, after the offer itself has gone.
    var shown by remember { mutableStateOf(origin) }
    if (origin != null) shown = origin
    var leaving by remember { mutableStateOf(Leaving.WAVED) }
    // What's left of the time on offer, 1 to 0, for the ring around the ✕.
    val left = remember { Animatable(1f) }
    // How far the pill has been pulled; up dismisses it, down only gives a little.
    var pulled by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(origin) {
        if (origin == null) return@LaunchedEffect
        leaving = Leaving.WAVED
        pulled = 0f
        left.snapTo(1f)
        // With animations removed the ring stays full, but the offer still times out.
        if (!motionRemoved()) launch { left.animateTo(0f, tween(JUMP_BACK_MS.toInt(), easing = LinearEasing)) }
        delay(JUMP_BACK_MS)
        if (nav.cameFrom === origin) nav.cameFrom = null
    }
    fun end(how: Leaving) {
        leaving = how
        nav.cameFrom = null
    }

    val motion = MaterialTheme.motionScheme
    val dismissAt = with(LocalDensity.current) { 28.dp.toPx() }
    AnimatedVisibility(
        visible = origin != null && visible,
        enter = slideInVertically(motion.defaultSpatialSpec()) { -it } +
            scaleIn(motion.defaultSpatialSpec(), initialScale = 0.7f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeIn(motion.fastEffectsSpec()),
        exit = if (leaving == Leaving.TAKEN) {
            scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f) + fadeOut(motion.fastEffectsSpec())
        } else {
            slideOutVertically(motion.fastSpatialSpec()) { -it * 2 } + fadeOut(motion.fastEffectsSpec())
        },
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 76.dp),
    ) {
        val colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
        val elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = {
                        haptics.tick()
                        shown?.back?.invoke()
                        end(Leaving.TAKEN)
                    },
                    colors = colors,
                    elevation = elevation,
                ) {
                    Icon(JacketIcons.Back, null, Modifier.size(SplitButtonDefaults.LeadingIconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    val from = shown
                    Text(
                        when {
                            from == null -> stringResource(R.string.common_back)
                            from.chapter.isNotEmpty() -> stringResource(R.string.jump_back_to, from.chapter)
                            from.page > 0 -> stringResource(R.string.jump_back_to_page, from.page)
                            from.percent == 0 -> stringResource(R.string.jump_back_to_start)
                            else -> stringResource(R.string.common_back) + stringResource(R.string.separator) + stringResource(R.string.percent, from.percent)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // A long chapter name gives way before the ✕ does.
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    onClick = {
                        haptics.tick()
                        end(Leaving.WAVED)
                    },
                    colors = colors,
                    elevation = elevation,
                    // Unfolds from behind the Back half as the pill lands.
                    modifier = Modifier.animateEnterExit(
                        enter = expandHorizontally(motion.defaultSpatialSpec(), expandFrom = Alignment.Start) +
                            fadeIn(motion.defaultEffectsSpec()),
                        exit = ExitTransition.None,
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { left.value },
                            modifier = Modifier.size(26.dp),
                            color = LocalContentColor.current.copy(alpha = 0.5f),
                            strokeWidth = 2.dp,
                            trackColor = Color.Transparent,
                            gapSize = 0.dp,
                        )
                        Icon(JacketIcons.Close, stringResource(R.string.common_dismiss), Modifier.size(16.dp))
                    }
                }
            },
            modifier = Modifier
                .graphicsLayer {
                    translationY = pulled
                    alpha = 1f - (-pulled / (dismissAt * 3f)).coerceIn(0f, 0.6f)
                }
                .draggable(
                    rememberDraggableState { dy ->
                        // Down is the page's way, not the pill's: it gives a little and springs back.
                        pulled = (pulled + if (pulled + dy > 0f) dy * 0.25f else dy).coerceAtLeast(-dismissAt * 3f)
                    },
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (pulled < -dismissAt || velocity < -1_000f) {
                            haptics.tick()
                            end(Leaving.WAVED)
                        } else {
                            animate(pulled, 0f, animationSpec = motion.fastSpatialSpec()) { value, _ -> pulled = value }
                        }
                    },
                ),
        )
    }
}
