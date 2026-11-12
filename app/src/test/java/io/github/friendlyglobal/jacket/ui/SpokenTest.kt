package io.github.friendlyglobal.jacket.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTest {
    @Test
    fun `a book is said as one phrase, in the order given`() {
        assertEquals("The Time Machine, H. G. Wells, 42% read", spoken(", ", "The Time Machine", "H. G. Wells", "42% read"))
    }

    @Test
    fun `what has nothing to say is left out`() {
        assertEquals("Shared text, Not started", spoken(", ", "Shared text", "", "Not started", null, "   "))
        assertEquals("", spoken(", ", null, ""))
    }

    @Test
    fun `a piece that repeats the one before it is said once`() {
        // A file with no author often carries its title in both places.
        assertEquals("Lalka, Finished", spoken(", ", "Lalka", "lalka ", "Finished"))
        // The same words further on are another thing, and stay.
        assertEquals("Finished, Pinned, Finished", spoken(", ", "Finished", "Pinned", "Finished"))
    }

    @Test
    fun `the language's own separator goes between the pieces`() {
        assertEquals("坊っちゃん、夏目漱石、12% 読了", spoken("、", "坊っちゃん", "夏目漱石", "12% 読了"))
    }
}
