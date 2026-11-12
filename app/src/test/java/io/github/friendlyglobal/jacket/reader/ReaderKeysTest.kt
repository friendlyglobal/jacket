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
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ESCAPE)) {
            assertNull(key(code))
            assertNull(key(code, KeyContext(listening = true, volumeKeys = true)))
        }
    }

    @Test
    fun `tab reaches the controls from the page, and enter does what a tap on the page does`() {
        assertEquals(ReaderCommand.FOCUS_CONTROLS, key(KeyEvent.KEYCODE_TAB))
        assertEquals(ReaderCommand.FOCUS_CONTROLS, key(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals(ReaderCommand.FOCUS_CONTROLS, key(KeyEvent.KEYCODE_TAB, KeyContext(listening = true)))
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertEquals(ReaderCommand.TOGGLE_CONTROLS, key(code))
        }
    }

    @Test
    fun `with the focus on a control, tab and enter are the control's own`() {
        val onButton = KeyContext(onControl = true)
        // Left to the focus system: Tab goes on to the next control, Enter presses this one.
        assertNull(key(KeyEvent.KEYCODE_TAB, onButton))
        assertNull(key(KeyEvent.KEYCODE_ENTER, onButton))
        assertNull(key(KeyEvent.KEYCODE_NUMPAD_ENTER, onButton))
        assertNull(key(KeyEvent.KEYCODE_DPAD_CENTER, onButton))
    }

    @Test
    fun `with the focus on a control, space and the arrows are that control's and turn no pages`() {
        val onButton = KeyContext(onControl = true)
        for (code in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertNull(key(code, onButton))
            assertNull(key(code, onButton.copy(rightToLeft = true)))
        }
        assertNull(key(KeyEvent.KEYCODE_SPACE, onButton, shift = true))
        // Space presses the button that has the focus, not the player's play button.
        assertNull(key(KeyEvent.KEYCODE_SPACE, onButton.copy(listening = true)))
    }

    @Test
    fun `page keys and volume keys turn pages wherever the focus is`() {
        val onButton = KeyContext(onControl = true, volumeKeys = true)
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_PAGE_DOWN, onButton))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_PAGE_UP, onButton))
        assertEquals(ReaderCommand.NEXT_PAGE, key(KeyEvent.KEYCODE_VOLUME_DOWN, onButton))
        assertEquals(ReaderCommand.PREVIOUS_PAGE, key(KeyEvent.KEYCODE_VOLUME_UP, onButton))
    }

    @Test
    fun `escape gives the keys back to the page, and is the phone's back otherwise`() {
        assertEquals(ReaderCommand.LEAVE_CONTROLS, key(KeyEvent.KEYCODE_ESCAPE, KeyContext(onControl = true)))
        assertNull(key(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun `under the cover of a folded phone tab and enter are the cover's, and its buttons have their keys`() {
        val covered = KeyContext(pages = false)
        assertNull(key(KeyEvent.KEYCODE_TAB, covered))
        assertNull(key(KeyEvent.KEYCODE_ENTER, covered))
        // A button of the cover's panel with the focus: Space presses it, the arrows move on from it.
        val onPanel = covered.copy(onControl = true)
        assertNull(key(KeyEvent.KEYCODE_SPACE, onPanel))
        assertNull(key(KeyEvent.KEYCODE_DPAD_RIGHT, onPanel))
        // With the focus nowhere, the arrows are still kept from the page underneath.
        assertEquals(ReaderCommand.NOTHING, key(KeyEvent.KEYCODE_DPAD_RIGHT, covered))
    }

    @Test
    fun `no key ever takes tab or enter away from a control`() {
        // Whatever else is going on, a keyboard can always get to a control and press it.
        for (listening in listOf(false, true)) for (rtl in listOf(false, true)) for (volume in listOf(false, true)) for (pages in listOf(false, true)) {
            val context = KeyContext(rightToLeft = rtl, listening = listening, volumeKeys = volume, pages = pages, onControl = true)
            assertNull(key(KeyEvent.KEYCODE_TAB, context))
            assertNull(key(KeyEvent.KEYCODE_ENTER, context))
            assertNull(key(KeyEvent.KEYCODE_SPACE, context))
        }
    }

    @Test
    fun `a held key shows or hides the controls once`() {
        val repeats = KeyRepeats()
        for (command in listOf(ReaderCommand.FOCUS_CONTROLS, ReaderCommand.TOGGLE_CONTROLS, ReaderCommand.LEAVE_CONTROLS)) {
            assertTrue(repeats.accepts(command, repeat = 0, at = 0))
            assertFalse(repeats.accepts(command, repeat = 1, at = 400))
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
