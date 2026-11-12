package io.github.friendlyglobal.jacket.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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

/** A title a screen reader can jump to, heading by heading. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/** A picture that is only there to be looked at: a screen reader passes over it, and whatever it is made of. */
fun Modifier.decorative(): Modifier = clearAndSetSemantics {}
