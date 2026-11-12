package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSizeTest {
    @Test
    fun `text size goes from 70 to 300 percent, a tenth at a time`() {
        var scale = MIN_TEXT_SCALE
        var steps = 0
        while (scale < MAX_TEXT_SCALE) {
            val next = step(scale, 1)
            assertEquals(scale + 0.1f, next, 0.001f)
            scale = next
            steps++
        }
        assertEquals(23, steps)
        assertEquals(3f, scale, 0.001f)
        assertEquals(0.7f, MIN_TEXT_SCALE, 0.001f)
    }

    @Test
    fun `it stops at both ends`() {
        assertEquals(MAX_TEXT_SCALE, step(MAX_TEXT_SCALE, 1), 0.001f)
        assertEquals(MIN_TEXT_SCALE, step(MIN_TEXT_SCALE, -1), 0.001f)
        assertEquals(2.9f, step(MAX_TEXT_SCALE, -1), 0.001f)
    }

    @Test
    fun `a size between two tenths steps to the nearest tenth and on`() {
        // A backup made by hand, or an older build, may hold any number.
        assertEquals(1.3f, step(1.24f, 1), 0.001f)
        assertEquals(1.1f, step(1.24f, -1), 0.001f)
    }
}
