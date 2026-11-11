package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.voice.sentencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Plain-text novels mark a change of scene with a paragraph of symbols. A natural voice is given a
 * paragraph a sentence at a time, and such a paragraph has none: the speaker makes one line for it
 * all the same (see NaturalSpeaker.speak), which is over as it starts, so the reading goes on.
 */
class SceneBreakTest {
    private val breaks = listOf("* * *", "***", "———", "- - -", "…", "~", "§")

    @Test
    fun aSceneBreakHasNoSentences() {
        for (rule in breaks) assertEquals(rule, emptyList<IntRange>(), sentencesOf(rule, Locale.ENGLISH))
        // Where a paragraph of words has.
        assertEquals(2, sentencesOf("The rain had stopped. She shook out her coat.", Locale.ENGLISH).size)
    }

    @Test
    fun aSceneBreakIsNotSentToTheVoice() {
        for (rule in breaks) assertFalse(rule, hasWords(rule))
        assertFalse(hasWords(""))
        assertFalse(hasWords("   "))
    }

    @Test
    fun wordsAndNumbersAre() {
        assertTrue(hasWords("Chapter One"))
        assertTrue(hasWords("1984"))
        assertTrue(hasWords("— Não."))
        assertTrue(hasWords("第一章"))
        assertTrue(hasWords("* * * (later) * * *"))
    }
}
