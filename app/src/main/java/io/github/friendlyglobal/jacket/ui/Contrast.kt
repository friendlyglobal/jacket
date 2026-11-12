package io.github.friendlyglobal.jacket.ui

import kotlin.math.pow

// ---------------------------------------------------------------------------------------------
// Contrast, as the accessibility guidelines (WCAG 2) measure it: text is readable at 4.5 to 1
// against what it sits on. Colours here are opaque ARGB ints.
// ---------------------------------------------------------------------------------------------

/** The least contrast body text may have with its background (WCAG AA). */
internal const val READABLE = 4.5

/** How light a colour is to the eye, 0 (black) to 1 (white). */
internal fun luminance(argb: Int): Double {
    fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
}

/** The contrast between two colours, 1 (the same) to 21 (black on white). */
internal fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** [ink] seen through a veil of [paper] that is [veil] (0..1) opaque. */
internal fun veiled(ink: Int, paper: Int, veil: Float): Int {
    fun mix(shift: Int): Int {
        val i = (ink shr shift) and 0xFF
        val p = (paper shr shift) and 0xFF
        return (i + (p - i) * veil).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/**
 * How strong a veil of [paper] over words in [ink] can be (0 to [most]) with the words still
 * [READABLE] under it. While a book is read aloud, the words still to come in the sentence wait
 * under such a veil and take their full ink as they are spoken: they are paler than the rest,
 * and can still be read ahead.
 */
internal fun readableVeil(ink: Int, paper: Int, most: Float = 0.6f): Float {
    if (contrast(veiled(ink, paper, most), paper) >= READABLE) return most
    var clear = 0f
    var strong = most
    repeat(12) {
        val middle = (clear + strong) / 2f
        if (contrast(veiled(ink, paper, middle), paper) >= READABLE) clear = middle else strong = middle
    }
    return clear
}
