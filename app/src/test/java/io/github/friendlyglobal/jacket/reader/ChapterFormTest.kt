package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * There is one chapter control over the foot of the page at a time. On a narrow phone, with the
 * full controls up while a book was read aloud, there used to be two: the slider's card, and under
 * it the player's two chapter steps in a pill of their own. Now the steps ride in the card.
 */
class ChapterFormTest {
    @Test
    fun `listening on a narrow window with the controls down, the steps are a pill`() {
        assertEquals(ChapterForm.PILL, chapterForm(slider = false, listening = true, steps = true, stepsOnBar = false))
    }

    @Test
    fun `listening on a narrow window with the controls up, the steps are in the slider's card`() {
        assertEquals(ChapterForm.CARD_WITH_STEPS, chapterForm(slider = true, listening = true, steps = true, stepsOnBar = false))
    }

    @Test
    fun `while you read, the card has no steps - the reading toolbar has its own`() {
        assertEquals(ChapterForm.CARD, chapterForm(slider = true, listening = false, steps = true, stepsOnBar = false))
        assertEquals(ChapterForm.NONE, chapterForm(slider = false, listening = false, steps = true, stepsOnBar = false))
    }

    @Test
    fun `where the player's bar has room for the steps, the card stays as it was`() {
        assertEquals(ChapterForm.CARD, chapterForm(slider = true, listening = true, steps = true, stepsOnBar = true))
        assertEquals(ChapterForm.NONE, chapterForm(slider = false, listening = true, steps = true, stepsOnBar = true))
    }

    @Test
    fun `a book with no chapters to step by has no steps anywhere`() {
        assertEquals(ChapterForm.CARD, chapterForm(slider = true, listening = true, steps = false, stepsOnBar = false))
        assertEquals(ChapterForm.NONE, chapterForm(slider = false, listening = true, steps = false, stepsOnBar = false))
    }

    @Test
    fun `a book too short for a slider still gets its steps as a pill`() {
        // (One place to slide to: the card never comes up, with the full controls or without.)
        assertEquals(ChapterForm.PILL, chapterForm(slider = false, listening = true, steps = true, stepsOnBar = false))
    }

    @Test
    fun `the steps are never in two places - whatever is up, they are on the bar or in one control`() {
        for (slider in listOf(false, true)) for (listening in listOf(false, true)) for (steps in listOf(false, true)) for (onBar in listOf(false, true)) {
            val form = chapterForm(slider, listening, steps, onBar)
            // The steps are here exactly when the player needs them and its bar can't hold them...
            assertEquals("$slider $listening $steps $onBar", listening && steps && !onBar, form.steps)
            // ...and the slider exactly when the full controls ask for it.
            assertEquals("$slider $listening $steps $onBar", slider, form.slider)
        }
    }

    @Test
    fun `each form says what it holds`() {
        assertFalse(ChapterForm.NONE.slider || ChapterForm.NONE.steps)
        assertTrue(ChapterForm.PILL.steps && !ChapterForm.PILL.slider)
        assertTrue(ChapterForm.CARD.slider && !ChapterForm.CARD.steps)
        assertTrue(ChapterForm.CARD_WITH_STEPS.slider && ChapterForm.CARD_WITH_STEPS.steps)
    }
}
