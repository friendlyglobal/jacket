package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class BookTextSizeTest {
    @Test
    fun `at the phone's normal font size the book is the reader's own size`() {
        assertEquals(1f, BookTextSize.of(1f, 1f), 0.0001f)
        assertEquals(1.5f, BookTextSize.of(1.5f, 1f), 0.0001f)
        assertEquals(MIN_TEXT_SCALE, BookTextSize.of(MIN_TEXT_SCALE, 1f), 0.0001f)
    }

    @Test
    fun `a larger phone font makes the book's words larger, on top of the reader's size`() {
        assertEquals(1.3f, BookTextSize.of(1f, 1.3f), 0.0001f)
        assertEquals(1.56f, BookTextSize.of(1.2f, 1.3f), 0.0001f)
        // And a smaller one smaller.
        assertEquals(0.85f, BookTextSize.of(1f, 0.85f), 0.0001f)
    }

    @Test
    fun `the largest size at the largest phone font stays the largest size`() {
        assertEquals(MAX_TEXT_SCALE, BookTextSize.of(MAX_TEXT_SCALE, 2f), 0.0001f)
        assertEquals(MAX_TEXT_SCALE, BookTextSize.of(2.5f, 1.5f), 0.0001f)
        // Short of it, it grows as far as it can.
        assertEquals(2.6f, BookTextSize.of(2f, 1.3f), 0.0001f)
    }

    @Test
    fun `a phone font size that can't be read leaves the reader's size as it is`() {
        assertEquals(1.2f, BookTextSize.of(1.2f, 0f), 0.0001f)
        assertEquals(1.2f, BookTextSize.of(1.2f, Float.NaN), 0.0001f)
        assertEquals(1.2f, BookTextSize.of(1.2f, -1f), 0.0001f)
    }
}
