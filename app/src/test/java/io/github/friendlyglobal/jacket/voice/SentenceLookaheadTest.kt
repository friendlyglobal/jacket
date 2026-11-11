package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceLookaheadTest {
    @Test
    fun whatFollowsASentenceIsKnownAsSoonAsItsParagraphHasBeenCut() {
        val lookahead = SentenceLookahead()
        lookahead.note(listOf("One.", "Two.", "Three."))
        assertEquals(listOf("Two.", "Three."), lookahead.after("One."))
        assertEquals(emptyList<String>(), lookahead.after("Three."))
        // Readium cuts the next paragraph while the last sentence of this one is spoken.
        lookahead.note(listOf("Four.", "Five."))
        assertEquals(listOf("Three.", "Four."), lookahead.after("Two."))
        assertEquals(listOf("Four.", "Five."), lookahead.after("Three."))
        assertEquals(emptyList<String>(), lookahead.after("Never seen."))
    }

    @Test
    fun theLastSentenceOfAParagraphIsKnown() {
        val lookahead = SentenceLookahead()
        lookahead.note(listOf("One.", "Two."))
        assertFalse(lookahead.endsParagraph("One."))
        assertTrue(lookahead.endsParagraph("Two."))
        assertFalse(lookahead.endsParagraph("Elsewhere."))
    }
}
