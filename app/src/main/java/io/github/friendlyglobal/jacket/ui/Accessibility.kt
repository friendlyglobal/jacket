package io.github.friendlyglobal.jacket.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

// ---------------------------------------------------------------------------------------------
// Reading the screen without seeing it, and working it without touching it: what the rest of the
// app asks about TalkBack, switches and the like.
// ---------------------------------------------------------------------------------------------

/**
 * A screen reader (TalkBack) is reading the screen out under a finger. Nothing should leave the
 * screen by itself then: what was just read out would be gone before it could be pressed.
 */
fun Context.screenReaderOn(): Boolean =
    getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

/** [screenReaderOn], following the screen reader as it is switched on and off. */
@Composable
fun rememberScreenReader(): State<Boolean> {
    val context = LocalContext.current
    val on = remember { mutableStateOf(context.screenReaderOn()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on.value = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        on.value = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * How long something that goes away by itself stays, given that it would stay [millis] for
 * someone in no hurry: longer for anyone who has asked Android for more "time to take action"
 * (Settings › Accessibility), which a switch or a screen reader needs.
 */
@Composable
fun rememberTimeToAct(): (Long) -> Long {
    val accessibility = LocalAccessibilityManager.current
    return remember(accessibility) {
        { millis ->
            accessibility?.calculateRecommendedTimeoutMillis(millis, containsIcons = true, containsText = true, containsControls = true)
                ?: millis
        }
    }
}

/**
 * A slider's handle a full 48dp tall. Material's is 44dp, which makes the whole slider 44dp to a
 * finger: under what Android asks of anything that is touched.
 */
@Composable
fun FullHeightThumb(interaction: MutableInteractionSource) {
    SliderDefaults.Thumb(interactionSource = interaction, thumbSize = DpSize(4.dp, 48.dp))
}

/**
 * For words that have one line's room and no second line to go to (a button in a row of buttons,
 * the line under the page). With the phone's font size turned up they are as large as fits, down
 * to the size they have with it left alone, rather than cut off mid-word. Null (nothing to do)
 * while the font size is left alone.
 */
@Composable
fun fitOnOneLine(style: TextStyle): TextAutoSize? {
    val density = LocalDensity.current
    if (density.fontScale <= 1f || style.fontSize.isUnspecified) return null
    // The size on screen these words have at the phone's own font size, in today's scaled units.
    val unscaled = with(density) { style.fontSize.value.dp.toSp() }
    return remember(unscaled, style.fontSize) {
        if (unscaled.value >= style.fontSize.value) null else TextAutoSize.StepBased(minFontSize = unscaled, maxFontSize = style.fontSize, stepSize = 0.5.sp)
    }
}

/**
 * Draws what it holds a little smaller when it is wider than the room there is, so that all of it
 * is on the screen: a row of buttons that can't wrap (the player's bar) on the narrowest screens,
 * at the largest display and font sizes, where it ran off both edges. Anywhere it fits, nothing
 * changes.
 */
fun Modifier.shrinkToFit(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val room = constraints.maxWidth
    if (room == Constraints.Infinity || placeable.width <= room) {
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        val scale = room.toFloat() / placeable.width
        layout(room, (placeable.height * scale).roundToInt()) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** A title a screen reader can jump to, heading by heading. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/** A picture that is only there to be looked at: a screen reader passes over it, and whatever it is made of. */
fun Modifier.decorative(): Modifier = clearAndSetSemantics {}
