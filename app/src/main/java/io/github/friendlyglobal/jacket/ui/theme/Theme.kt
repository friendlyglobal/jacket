package io.github.friendlyglobal.jacket.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme whose colors come from the current book's cover. With no book,
 * Android 12+ falls back to the wallpaper (dynamic color).
 */
@Composable
fun JacketTheme(
    seed: Int?,
    darkTheme: Boolean = isSystemInDarkTheme(),
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
        colorScheme = animate(target),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Cross-fades every role so switching books re-tints the UI smoothly. */
@Composable
private fun animate(target: ColorScheme): ColorScheme {
    @Composable
    fun Color.anim(): Color = animateColorAsState(this, tween(650), label = "scheme").value
    return target.copy(
        primary = target.primary.anim(),
        onPrimary = target.onPrimary.anim(),
        primaryContainer = target.primaryContainer.anim(),
        onPrimaryContainer = target.onPrimaryContainer.anim(),
        inversePrimary = target.inversePrimary.anim(),
        secondary = target.secondary.anim(),
        onSecondary = target.onSecondary.anim(),
        secondaryContainer = target.secondaryContainer.anim(),
        onSecondaryContainer = target.onSecondaryContainer.anim(),
        tertiary = target.tertiary.anim(),
        onTertiary = target.onTertiary.anim(),
        tertiaryContainer = target.tertiaryContainer.anim(),
        onTertiaryContainer = target.onTertiaryContainer.anim(),
        background = target.background.anim(),
        onBackground = target.onBackground.anim(),
        surface = target.surface.anim(),
        onSurface = target.onSurface.anim(),
        surfaceVariant = target.surfaceVariant.anim(),
        onSurfaceVariant = target.onSurfaceVariant.anim(),
        surfaceTint = target.surfaceTint.anim(),
        inverseSurface = target.inverseSurface.anim(),
        inverseOnSurface = target.inverseOnSurface.anim(),
        outline = target.outline.anim(),
        outlineVariant = target.outlineVariant.anim(),
        surfaceBright = target.surfaceBright.anim(),
        surfaceDim = target.surfaceDim.anim(),
        surfaceContainerLowest = target.surfaceContainerLowest.anim(),
        surfaceContainerLow = target.surfaceContainerLow.anim(),
        surfaceContainer = target.surfaceContainer.anim(),
        surfaceContainerHigh = target.surfaceContainerHigh.anim(),
        surfaceContainerHighest = target.surfaceContainerHighest.anim(),
    )
}
