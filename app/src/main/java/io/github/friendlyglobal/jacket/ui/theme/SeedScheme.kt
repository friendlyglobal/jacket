package io.github.friendlyglobal.jacket.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Teal-ish fallback used before any book is imported (and on devices without wallpaper colors). */
const val DEFAULT_SEED: Int = 0xFF2E7D8C.toInt()

/**
 * Builds a full Material 3 color scheme from one seed color (picked from a book cover).
 *
 * Tones are placed in OKLCH, a perceptual color space, so every book gets the same contrast
 * structure as a stock Material scheme while keeping the cover's hue.
 */
object SeedScheme {
    fun light(seed: Int): ColorScheme {
        val k = Keys(seed)
        return lightColorScheme(
            primary = k.p(.48, 1.0), onPrimary = k.p(.99, .08),
            primaryContainer = k.p(.89, .55), onPrimaryContainer = k.p(.29, .7),
            inversePrimary = k.p(.83, .75),
            secondary = k.s(.50), onSecondary = k.s(.99, .2),
            secondaryContainer = k.s(.90), onSecondaryContainer = k.s(.29),
            tertiary = k.t(.50, 1.0), onTertiary = k.t(.99, .08),
            tertiaryContainer = k.t(.89, .55), onTertiaryContainer = k.t(.29, .7),
            background = k.n(.985), onBackground = k.n(.22),
            surface = k.n(.985), onSurface = k.n(.22),
            surfaceVariant = k.nv(.91), onSurfaceVariant = k.nv(.43),
            surfaceTint = k.p(.48, 1.0),
            inverseSurface = k.n(.30), inverseOnSurface = k.n(.95),
            error = Oklch.color(.52, .19, 27.0), onError = Color.White,
            errorContainer = Oklch.color(.91, .06, 27.0), onErrorContainer = Oklch.color(.32, .12, 27.0),
            outline = k.nv(.62), outlineVariant = k.nv(.85),
            scrim = Color.Black,
            surfaceBright = k.n(.985), surfaceDim = k.n(.90),
            surfaceContainerLowest = Color.White, surfaceContainerLow = k.n(.965),
            surfaceContainer = k.n(.948), surfaceContainerHigh = k.n(.928), surfaceContainerHighest = k.n(.908),
        )
    }

    fun dark(seed: Int): ColorScheme {
        val k = Keys(seed)
        return darkColorScheme(
            primary = k.p(.83, .75), onPrimary = k.p(.29, .6),
            primaryContainer = k.p(.38, .75), onPrimaryContainer = k.p(.91, .45),
            inversePrimary = k.p(.48, 1.0),
            secondary = k.s(.82), onSecondary = k.s(.29),
            secondaryContainer = k.s(.34), onSecondaryContainer = k.s(.90),
            tertiary = k.t(.83, .75), onTertiary = k.t(.29, .6),
            tertiaryContainer = k.t(.38, .75), onTertiaryContainer = k.t(.91, .45),
            background = k.n(.165), onBackground = k.n(.92),
            surface = k.n(.165), onSurface = k.n(.92),
            surfaceVariant = k.nv(.32), onSurfaceVariant = k.nv(.79),
            surfaceTint = k.p(.83, .75),
            inverseSurface = k.n(.90), inverseOnSurface = k.n(.25),
            error = Oklch.color(.80, .10, 27.0), onError = Oklch.color(.30, .10, 27.0),
            errorContainer = Oklch.color(.40, .13, 27.0), onErrorContainer = Oklch.color(.92, .05, 27.0),
            outline = k.nv(.60), outlineVariant = k.nv(.36),
            scrim = Color.Black,
            surfaceBright = k.n(.30), surfaceDim = k.n(.165),
            surfaceContainerLowest = k.n(.13), surfaceContainerLow = k.n(.195),
            surfaceContainer = k.n(.215), surfaceContainerHigh = k.n(.25), surfaceContainerHighest = k.n(.285),
        )
    }

    /** Hue and chroma budget for each role family. */
    private class Keys(seed: Int) {
        private val hue: Double
        private val chroma: Double

        init {
            val (_, c, h) = Oklch.fromArgb(seed)
            hue = h
            chroma = c.coerceIn(0.035, 0.15)
        }

        private val neutralChroma = (chroma * 0.09).coerceIn(0.004, 0.014)
        fun p(l: Double, c: Double) = Oklch.color(l, chroma * c, hue)
        fun s(l: Double, c: Double = 1.0) = Oklch.color(l, chroma * 0.28 * c, hue)
        fun t(l: Double, c: Double) = Oklch.color(l, chroma * 0.85 * c, hue + 60)
        fun n(l: Double) = Oklch.color(l, neutralChroma, hue)
        fun nv(l: Double) = Oklch.color(l, neutralChroma * 1.6, hue)
    }
}

/** OKLCH ⇄ sRGB, after Björn Ottosson's Oklab. */
object Oklch {
    fun color(l: Double, c: Double, hueDegrees: Double): Color {
        var chroma = c
        repeat(30) {
            val rgb = toLinearSrgb(l, chroma, hueDegrees)
            if (rgb.all { it in -1e-4..1.0001 }) return rgb.toColor()
            chroma *= 0.9 // pull back into the sRGB gamut, keeping lightness and hue
        }
        return toLinearSrgb(l, 0.0, hueDegrees).toColor()
    }

    /** Returns (L, C, hue°) for an ARGB int. */
    fun fromArgb(argb: Int): Triple<Double, Double, Double> {
        val r = decode((argb shr 16) and 0xFF)
        val g = decode((argb shr 8) and 0xFF)
        val b = decode(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val aa = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = (Math.toDegrees(atan2(bb, aa)) + 360) % 360
        return Triple(lightness, hypot(aa, bb), hue)
    }

    private fun toLinearSrgb(lightness: Double, c: Double, hueDegrees: Double): DoubleArray {
        val h = Math.toRadians(hueDegrees)
        val a = c * cos(h)
        val b = c * sin(h)
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    private fun DoubleArray.toColor() = Color(encode(this[0]), encode(this[1]), encode(this[2]))

    private fun encode(linear: Double): Float {
        val x = linear.coerceIn(0.0, 1.0)
        return (if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055).toFloat()
    }

    private fun decode(channel: Int): Double {
        val x = channel / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
}
