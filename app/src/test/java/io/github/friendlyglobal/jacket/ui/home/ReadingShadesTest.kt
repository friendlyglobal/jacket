package io.github.friendlyglobal.jacket.ui.home

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.friendlyglobal.jacket.data.READING_SHADES
import io.github.friendlyglobal.jacket.ui.READABLE
import io.github.friendlyglobal.jacket.ui.contrast
import io.github.friendlyglobal.jacket.ui.theme.SeedScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingShadesTest {
    // Covers of every hue, a grey one, a near-black and a near-white one: what a book can tint Jacket with.
    private val covers = listOf(0xFF2E7D8C, 0xFFE0633F, 0xFFF4C76B, 0xFF6750A4, 0xFF386A20, 0xFFB3261E, 0xFF0B57D0, 0xFF808080, 0xFF101010, 0xFFF8F8F8)

    private val schemes = covers.flatMap { listOf(SeedScheme.light(it.toInt()), SeedScheme.dark(it.toInt())) } +
        // Material's own, as a phone without wallpaper colours has them.
        listOf(lightColorScheme(), darkColorScheme())

    @Test
    fun `a day's number is readable on every shade, whatever the book's colours`() {
        for (scheme in schemes) {
            val shades = readingShadeColors(scheme)
            assertEquals(READING_SHADES, shades.size)
            shades.forEachIndexed { shade, colors ->
                val ratio = contrast(colors.ink.toArgb(), colors.fill.toArgb())
                assertTrue("primary %08X, shade %d: %.2f to 1".format(scheme.primary.toArgb(), shade, ratio), ratio >= READABLE)
            }
        }
    }

    @Test
    fun `the shades run from the quiet surface to the theme's own colour, each a step on`() {
        for (scheme in schemes) {
            val fills = readingShadeColors(scheme).map { it.fill }
            assertEquals(scheme.surfaceContainerHigh, fills.first())
            assertEquals(scheme.primary.toArgb(), fills.last().toArgb())
            // No two days of different reading wear the same colour.
            assertEquals(fills.size, fills.map { it.toArgb() }.distinct().size)
            // Each step is nearer the theme's colour than the one before.
            val toPrimary = fills.map { distance(it, scheme.primary) }
            assertEquals(toPrimary.sortedDescending(), toPrimary)
        }
    }

    @Test
    fun `black or white is there for a colour neither of the theme's inks reads on`() {
        // A made-up theme whose inks are both mid grey: on a mid-grey day, neither would do.
        val grey = Color(0xFF808080)
        val scheme = lightColorScheme(primary = grey, onPrimary = grey, onSurface = grey, onSurfaceVariant = grey, surfaceContainerHigh = Color(0xFF8A8A8A))
        for (colors in readingShadeColors(scheme)) {
            assertTrue(contrast(colors.ink.toArgb(), colors.fill.toArgb()) >= READABLE)
        }
    }

    private fun distance(a: Color, b: Color): Float {
        val red = a.red - b.red
        val green = a.green - b.green
        val blue = a.blue - b.blue
        return red * red + green * green + blue * blue
    }
}
