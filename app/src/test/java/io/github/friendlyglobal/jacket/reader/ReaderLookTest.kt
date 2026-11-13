package io.github.friendlyglobal.jacket.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.friendlyglobal.jacket.data.PageColor
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.READABLE
import io.github.friendlyglobal.jacket.ui.contrast
import io.github.friendlyglobal.jacket.ui.fixedPageColors
import io.github.friendlyglobal.jacket.ui.luminance
import io.github.friendlyglobal.jacket.ui.theme.DEFAULT_SEED
import io.github.friendlyglobal.jacket.ui.theme.SeedScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookTest {
    /** Covers of every hue, a grey one, a near-black and a near-white one, and the app's own colour. */
    private val covers = listOf(0xFF2E7D8C, 0xFFE0633F, 0xFFF4C76B, 0xFF6750A4, 0xFF386A20, 0xFFB3261E, 0xFF0B57D0, 0xFF808080, 0xFF101010, 0xFFF8F8F8)
        .map { it.toInt() } + DEFAULT_SEED

    @Test
    fun `dark and black pages have dark controls, paper light ones, whatever the phone's theme`() {
        for (phoneDark in listOf(false, true)) {
            assertTrue(controlsDark(PageColor.DARK, phoneDark))
            assertTrue(controlsDark(PageColor.BLACK, phoneDark))
            assertFalse(controlsDark(PageColor.PAPER, phoneDark))
        }
    }

    @Test
    fun `book pages take their colours from the theme, and their controls follow the phone`() {
        assertFalse(controlsDark(PageColor.BOOK, phoneDark = false))
        assertTrue(controlsDark(PageColor.BOOK, phoneDark = true))
    }

    @Test
    fun `dark pages at night turn paper dark, and its controls with it`() {
        val night = Settings(pageColor = PageColor.PAPER, nightPages = true)
        assertEquals(PageColor.DARK, shownPageColor(night, phoneDark = true))
        assertTrue(readerControlsDark(night, phoneDark = true))
        assertEquals(PageColor.PAPER, shownPageColor(night, phoneDark = false))
        assertFalse(readerControlsDark(night, phoneDark = false))
        // Without it, paper stays paper at night.
        val day = Settings(pageColor = PageColor.PAPER, nightPages = false)
        assertEquals(PageColor.PAPER, shownPageColor(day, phoneDark = true))
        assertFalse(readerControlsDark(day, phoneDark = true))
        // It only ever changes paper.
        assertEquals(PageColor.BLACK, shownPageColor(Settings(pageColor = PageColor.BLACK, nightPages = true), phoneDark = true))
    }

    /** The scheme the reader's controls are drawn in over [page], for a book with [cover]. */
    private fun controls(page: PageColor, phoneDark: Boolean, cover: Int): ColorScheme =
        if (controlsDark(page, phoneDark)) SeedScheme.dark(cover) else SeedScheme.light(cover)

    @Test
    fun `every page colour's controls are readable, in either theme, whatever the cover`() {
        for (page in PageColor.entries) for (phoneDark in listOf(false, true)) for (cover in covers) {
            val c = controls(page, phoneDark, cover)
            val where = "%s page, phone %s, cover %08X".format(page, if (phoneDark) "dark" else "light", cover)
            // The bars, the player and the sheets: their words and icons on their own surfaces.
            for ((surface, name) in listOf(c.surfaceContainer to "bars", c.surfaceContainerHigh to "menus", c.surfaceContainerLow to "sheets")) {
                assertReadable("$where: words on the $name", c.onSurface, surface)
                assertReadable("$where: quiet words on the $name", c.onSurfaceVariant, surface)
            }
            // The chapter pill, and the player's filled buttons.
            assertReadable("$where: the chapter pill", c.onSecondaryContainer, c.secondaryContainer)
            assertReadable("$where: a filled button", c.onPrimary, c.primary)
            assertReadable("$where: a tonal button", c.onPrimaryContainer, c.primaryContainer)
        }
    }

    @Test
    fun `the controls are dark over a dark page and light over a light one`() {
        for (page in listOf(PageColor.PAPER, PageColor.DARK, PageColor.BLACK)) for (phoneDark in listOf(false, true)) for (cover in covers) {
            val paper = fixedPageColors(page)!!.background
            val bars = controls(page, phoneDark, cover).surfaceContainer
            val pageIsDark = luminance(paper.toArgb()) < DARK
            assertEquals(
                "%s page, phone %s, cover %08X".format(page, if (phoneDark) "dark" else "light", cover),
                pageIsDark, luminance(bars.toArgb()) < DARK,
            )
        }
    }

    private fun assertReadable(what: String, ink: Color, paper: Color) {
        val ratio = contrast(ink.toArgb(), paper.toArgb())
        assertTrue("$what: %.2f to 1".format(ratio), ratio >= READABLE)
    }

    private companion object {
        /** Darker than this is a dark page, or a dark bar. */
        const val DARK = 0.18
    }
}
