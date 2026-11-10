package io.github.friendlyglobal.jacket.ui.theme

import android.os.Build
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme whose colors come from the current book's cover. With no book,
 * Android 12+ falls back to the wallpaper (dynamic color).
 *
 * A change of colors cross-fades when [animated]. A reader passes false: it has one book's colors
 * for as long as it is on screen.
 */
@Composable
fun JacketTheme(
    seed: Int?,
    darkTheme: Boolean = isSystemInDarkTheme(),
    animated: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val target = remember(seed, darkTheme) {
        when {
            seed != null -> if (darkTheme) SeedScheme.dark(seed) else SeedScheme.light(seed)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> if (darkTheme) SeedScheme.dark(DEFAULT_SEED) else SeedScheme.light(DEFAULT_SEED)
        }
    }
    MaterialExpressiveTheme(
        colorScheme = if (animated) animate(target) else target,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Cross-fades every role so switching books re-tints the UI smoothly. */
@Composable
private fun animate(target: ColorScheme): ColorScheme {
    val fade = remember { SchemeFade(target) }
    LaunchedEffect(target) { fade.fadeTo(target) }
    return fade.scheme
}

/**
 * One animation for the whole scheme. At rest it hands back the target scheme itself: the theme
 * provides colors through a static composition local, so a new (even if equal) scheme would
 * recompose everything on screen, every time anything above the theme recomposed.
 */
private class SchemeFade(initial: ColorScheme) {
    private var from = initial
    private var to by mutableStateOf(initial)
    private var fraction by mutableFloatStateOf(1f)

    val scheme: ColorScheme by derivedStateOf { if (fraction >= 1f) to else blend(from, to, fraction) }

    suspend fun fadeTo(target: ColorScheme) {
        if (target === to) return
        // From wherever a fade in progress has got to.
        val current = scheme
        Snapshot.withMutableSnapshot {
            from = current
            to = target
            fraction = 0f
        }
        animate(0f, 1f, animationSpec = tween(650)) { value, _ -> fraction = value }
    }
}

private fun blend(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme = b.copy(
    primary = lerp(a.primary, b.primary, t),
    onPrimary = lerp(a.onPrimary, b.onPrimary, t),
    primaryContainer = lerp(a.primaryContainer, b.primaryContainer, t),
    onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, t),
    inversePrimary = lerp(a.inversePrimary, b.inversePrimary, t),
    secondary = lerp(a.secondary, b.secondary, t),
    onSecondary = lerp(a.onSecondary, b.onSecondary, t),
    secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, t),
    onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, t),
    tertiary = lerp(a.tertiary, b.tertiary, t),
    onTertiary = lerp(a.onTertiary, b.onTertiary, t),
    tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, t),
    onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, t),
    background = lerp(a.background, b.background, t),
    onBackground = lerp(a.onBackground, b.onBackground, t),
    surface = lerp(a.surface, b.surface, t),
    onSurface = lerp(a.onSurface, b.onSurface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, t),
    surfaceTint = lerp(a.surfaceTint, b.surfaceTint, t),
    inverseSurface = lerp(a.inverseSurface, b.inverseSurface, t),
    inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, t),
    outline = lerp(a.outline, b.outline, t),
    outlineVariant = lerp(a.outlineVariant, b.outlineVariant, t),
    surfaceBright = lerp(a.surfaceBright, b.surfaceBright, t),
    surfaceDim = lerp(a.surfaceDim, b.surfaceDim, t),
    surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, t),
    surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, t),
    surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, t),
    surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, t),
)
