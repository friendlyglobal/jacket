package io.github.friendlyglobal.jacket.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderKeysTest {
    private val reading = KeyContext()

    private fun key(code: Int, context: KeyContext = reading, shift: Boolean = false) = readerCommand(code, shift, context)

    @Test
    fun `arrows, page keys and space turn pages`() {
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_SPACE))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_SPACE, shift = true))
    }

    @Test
    fun `in a right-to-left book the arrows across change places, and nothing else does`() {
        val rtl = KeyContext(rightToLeft = true)
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_DPAD_LEFT, rtl))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_DPAD_RIGHT, rtl))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_DPAD_DOWN, rtl))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_PAGE_UP, rtl))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_SPACE, rtl))
    }

    @Test
    fun `while the book is read aloud space is the player's, and the other keys still turn pages`() {
        val listening = KeyContext(listening = true)
        assertEquals(ReaderCommand.PLAY_PAUSE, key(KeyEvent.KEYCODE_SPACE, listening))
        assertEquals(ReaderCommand.PLAY_PAUSE, key(KeyEvent.KEYCODE_SPACE, listening, shift = true))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_DPAD_RIGHT, listening))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_PAGE_UP, listening))
    }

    @Test
    fun `volume keys turn pages only when that is switched on, and never while listening`() {
        assertNull(key(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(key(KeyEvent.KEYCODE_VOLUME_UP))
        val on = KeyContext(volumeKeys = true)
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_VOLUME_DOWN, on))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_VOLUME_UP, on))
        assertNull(key(KeyEvent.KEYCODE_VOLUME_DOWN, on.copy(listening = true)))
        assertNull(key(KeyEvent.KEYCODE_VOLUME_UP, on.copy(listening = true)))
    }

    @Test
    fun `under the cover of a folded phone no page turns, and the volume keys are the phone's`() {
        val covered = KeyContext(pages = false, volumeKeys = true)
        // Taken, so the page doesn't scroll under the cover, but nothing is done with them.
        assertEquals(ReaderCommand.NOTHING, key(KeyEvent.KEYCODE_DPAD_RIGHT, covered))
        assertEquals(ReaderCommand.NOTHING, key(KeyEvent.KEYCODE_PAGE_DOWN, covered))
        assertEquals(ReaderCommand.NOTHING, key(KeyEvent.KEYCODE_SPACE, covered))
        assertNull(key(KeyEvent.KEYCODE_VOLUME_DOWN, covered))
        // The player on the cover still answers to Space.
        assertEquals(ReaderCommand.PLAY_PAUSE, key(KeyEvent.KEYCODE_SPACE, covered.copy(listening = true)))
    }

    @Test
    fun `other keys are left alone`() {
        for (code in listOf(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertNull(key(code))
            assertNull(key(code, KeyContext(listening = true, volumeKeys = true)))
        }
    }

    @Test
    fun `a key held down turns pages at a pace that can be followed`() {
        val repeats = KeyRepeats()
        assertTrue(repeats.accepts(ReaderCommand.NEXT_PAGE, repeat = 0, at = 1_000))
        // The keyboard repeats every 50 ms or so.
        val turned = (1..20).count { repeats.accepts(ReaderCommand.NEXT_PAGE, repeat = it, at = 1_000 + it * 50L) }
        assertTrue("$turned pages in a second", turned in 4..6)
        // A fresh press always counts, however soon.
        assertTrue(repeats.accepts(ReaderCommand.PREVIOUS_PAGE, repeat = 0, at = 2_010))
    }

    @Test
    fun `a held space bar pauses once`() {
        val repeats = KeyRepeats()
        assertTrue(repeats.accepts(ReaderCommand.PLAY_PAUSE, repeat = 0, at = 0))
        assertFalse(repeats.accepts(ReaderCommand.PLAY_PAUSE, repeat = 1, at = 500))
        assertFalse(repeats.accepts(ReaderCommand.PLAY_PAUSE, repeat = 9, at = 5_000))
        assertFalse(repeats.accepts(ReaderCommand.NOTHING, repeat = 0, at = 6_000))
    }

    @Test
    fun `a click of the wheel is a page`() {
        val wheel = WheelPages()
        assertEquals(1, wheel.turned(down = 1f, right = 0f, rightToLeft = false, at = 0))
        assertEquals(-1, wheel.turned(down = -1f, right = 0f, rightToLeft = false, at = 1_000))
        assertEquals(1, wheel.turned(down = 1f, right = 0f, rightToLeft = false, at = 2_000))
    }

    @Test
    fun `a touchpad swipe is one page, not a chapter`() {
        val wheel = WheelPages()
        // Forty small movements, 10 ms apart: more than a dozen clicks' worth.
        val pages = (0 until 40).sumOf { wheel.turned(down = 0.35f, right = 0f, rightToLeft = false, at = it * 10L) }
        assertEquals(2, pages)
        // Small movements that never add up to a click turn nothing.
        val fresh = WheelPages()
        assertEquals(0, fresh.turned(down = 0.4f, right = 0f, rightToLeft = false, at = 0))
        assertEquals(0, fresh.turned(down = 0.4f, right = 0f, rightToLeft = false, at = 20))
        // ...and are forgotten once the wheel has been left alone.
        assertEquals(0, fresh.turned(down = 0.4f, right = 0f, rightToLeft = false, at = 5_000))
    }

    @Test
    fun `turning the wheel back starts afresh`() {
        val wheel = WheelPages()
        assertEquals(0, wheel.turned(down = 0.8f, right = 0f, rightToLeft = false, at = 0))
        assertEquals(0, wheel.turned(down = -0.5f, right = 0f, rightToLeft = false, at = 20))
        assertEquals(-1, wheel.turned(down = -0.5f, right = 0f, rightToLeft = false, at = 40))
    }

    @Test
    fun `a sideways swipe goes by the book's direction`() {
        assertEquals(1, WheelPages().turned(down = 0f, right = 1f, rightToLeft = false, at = 0))
        assertEquals(-1, WheelPages().turned(down = 0f, right = -1f, rightToLeft = false, at = 0))
        assertEquals(-1, WheelPages().turned(down = 0f, right = 1f, rightToLeft = true, at = 0))
        assertEquals(1, WheelPages().turned(down = 0f, right = -1f, rightToLeft = true, at = 0))
        // Up and down is reading order whichever way the book reads.
        assertEquals(1, WheelPages().turned(down = 1f, right = 0.2f, rightToLeft = true, at = 0))
    }
}
