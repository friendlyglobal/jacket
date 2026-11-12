package io.github.friendlyglobal.jacket.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerButtonsTest {
    @Test
    fun `as many buttons as fit at a full 48dp each`() {
        // Four with three gaps of 4dp is 204dp; three with two is 152dp.
        assertEquals(4, playerButtonsIn(204.dp))
        assertEquals(3, playerButtonsIn(203.dp))
        assertEquals(3, playerButtonsIn(152.dp))
        assertEquals(1, playerButtonsIn(151.dp))
        assertEquals(1, playerButtonsIn(48.dp))
        assertEquals(1, playerButtonsIn(0.dp))
        assertEquals(4, playerButtonsIn(600.dp))
    }

    @Test
    fun `the sleep timer's words are shown whole or not at all`() {
        // On the cover screen of a Flip (about 268dp for the words) every state fits in English...
        assertTrue(fitsOnOneLine("Sleep timer", 14, 268.dp))
        assertTrue(fitsOnOneLine("Sleep timer: 15 minutes left", 14, 268.dp))
        assertTrue(fitsOnOneLine("Sleep timer: end of chapter", 14, 268.dp))
        // ...and the long French one doesn't: the moon and the minutes are shown instead.
        assertTrue(!fitsOnOneLine("Minuteur de veille : 15 minutes restantes", 14, 268.dp))
        // A narrow widget has room for the name of the thing and no more.
        assertTrue(fitsOnOneLine("Sleep timer", 13, 100.dp))
        assertTrue(!fitsOnOneLine("Sleep timer: 15 minutes left", 13, 100.dp))
        // Chinese, Japanese and Korean letters are as wide as they are tall.
        assertTrue(fitsOnOneLine("スリープタイマー：残り 15 分", 14, 268.dp))
        assertTrue(!fitsOnOneLine("スリープタイマー：残り 15 分", 14, 150.dp))
        // With the phone's font turned up the same words need more room.
        assertTrue(!fitsOnOneLine("Sleep timer: 15 minutes left", 14, 268.dp, fontScale = 1.5f))
        assertTrue(fitsOnOneLine("", 14, 0.dp))
    }

    @Test
    fun `bigger buttons need more room, and never get less than their size`() {
        // The cover screen's: 56dp skips, a 72dp play button, 12dp between them.
        assertEquals(3, playerButtonsIn(208.dp, skip = 56.dp, play = 72.dp, gap = 12.dp))
        assertEquals(1, playerButtonsIn(207.dp, skip = 56.dp, play = 72.dp, gap = 12.dp))
        assertEquals(4, playerButtonsIn(276.dp, skip = 56.dp, play = 72.dp, gap = 12.dp))
        assertEquals(3, playerButtonsIn(275.dp, skip = 56.dp, play = 72.dp, gap = 12.dp))
        // Whatever the room, the buttons counted fit in it.
        for (room in 0..400) {
            val count = playerButtonsIn(room.dp)
            val width = 48 * count + 4 * (count - 1)
            assertTrue("$count buttons in ${room}dp", count == 1 || width <= room)
        }
    }
}
