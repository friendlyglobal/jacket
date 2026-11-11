package io.github.friendlyglobal.jacket.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.WindowSdkExtensions
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Whether this phone folds. Jacket began as a way to put a book's cover on a foldable's outer
 * screen, but most phones don't fold, and they shouldn't be told about screens they don't have:
 * the welcome and the empty library speak differently to each.
 *
 * No single signal covers every foldable, so any of these counts:
 *  - the phone has a hinge-angle sensor (book-style foldables and most flip phones);
 *  - Jetpack WindowManager says the device can stand half-open (a posture only foldables have);
 *  - the window reports a fold running through it (only while the phone is open);
 *  - the outer screen is on offer for showing the cover ([outerAvailable], from WindowAreaController).
 */
object Foldables {
    /**
     * For trying both welcomes on one device: true or false overrides what the phone says of
     * itself. Only honoured in debug builds (see MainActivity).
     */
    val override = MutableStateFlow<Boolean?>(null)

    fun debuggable(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * What can be told without watching the window: a hinge to measure, or a posture only a
     * folding phone can take. (A third way, the system resource that names a device's "folded"
     * states, is no use: emulator images, for one, fill it in on phones that don't fold at all.)
     */
    fun builtToFold(context: Context): Boolean = context.hasHinge() || hasFoldingPosture(context)

    /**
     * Jetpack WindowManager knows, on recent Android, whether the device can stand half-open.
     * (The version is checked just before the call; lint doesn't follow the check, hence the
     * suppression. Anything unexpected from a manufacturer's extension counts as "no".)
     */
    @SuppressLint("RequiresWindowSdk")
    private fun hasFoldingPosture(context: Context): Boolean = try {
        WindowSdkExtensions.getInstance().extensionVersion >= 6 &&
            WindowInfoTracker.getOrCreate(context).supportedPostures.isNotEmpty()
    } catch (_: Throwable) {
        false
    }
}

/** True on a phone that folds (see [Foldables]). */
@Composable
fun rememberFoldable(outerAvailable: Boolean): State<Boolean> {
    val context = LocalContext.current
    val activity = context.findActivity()
    return produceState(Foldables.override.value ?: (outerAvailable || Foldables.builtToFold(context)), outerAvailable, activity) {
        val known = outerAvailable || Foldables.builtToFold(context)
        var fold = false
        fun settle(forced: Boolean?) {
            value = forced ?: (known || fold)
        }
        settle(Foldables.override.value)
        launch { Foldables.override.collect { settle(it) } }
        if (activity != null && !known) {
            launch {
                WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layout ->
                    // Once seen, a fold doesn't go away when the phone is shut.
                    if (layout.displayFeatures.any { it is FoldingFeature }) fold = true
                    settle(Foldables.override.value)
                }
            }
        }
    }
}
