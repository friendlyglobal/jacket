package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Time left in chapter" while the book is read aloud: from where the voice is and how fast it speaks. */
class VoicePaceTest {
    /** A voice that reads [lettersPerSecond] letters a second, sentence after sentence of [letters] letters, from [from]. */
    private fun VoicePace.hear(sentences: Int, letters: Int = 140, lettersPerSecond: Double = 14.0, from: Long = 0L): Long {
        var now = from
        repeat(sentences) {
            began(letters, now)
            now += (letters * 1_000 / lettersPerSecond).toLong()
        }
        return now
    }

    @Test
    fun `nothing is known before a sentence has been heard through`() {
        val pace = VoicePace()
        pace.hear(sentences = 2)
        // The first sentence began with the voice getting ready: it isn't timed.
        assertEquals(0f, pace.lettersPerMs)
    }

    @Test
    fun `the pace is the voice's own, as it is heard`() {
        val pace = VoicePace()
        pace.hear(sentences = 6)
        assertEquals(0.014f, pace.lettersPerMs, 0.0001f)
    }

    @Test
    fun `a sentence stopped in the middle of is not timed`() {
        val pace = VoicePace()
        var now = pace.hear(sentences = 4)
        // Paused for ten minutes in the middle of a sentence, then on again.
        pace.began(140, now)
        pace.interrupted()
        now += 600_000L
        pace.began(140, now)
        pace.hear(sentences = 3, from = now + 10_000L)
        assertEquals(0.014f, pace.lettersPerMs, 0.0001f)
    }

    @Test
    fun `a sentence that took far too long says nothing of the pace`() {
        val pace = VoicePace()
        val now = pace.hear(sentences = 4)
        // The voice waited a minute on a slow phone before the next one.
        pace.began(20, now)
        pace.hear(sentences = 3, from = now + 60_000L)
        assertEquals(0.014f, pace.lettersPerMs, 0.0001f)
    }

    @Test
    fun `a new speed carries what was learnt over, scaled`() {
        val pace = VoicePace()
        pace.hear(sentences = 6)
        pace.retimed(1f, 1.5f)
        assertEquals(0.021f, pace.lettersPerMs, 0.0001f)
    }

    @Test
    fun `it follows a voice that changes pace`() {
        val pace = VoicePace()
        val now = pace.hear(sentences = 10, lettersPerSecond = 10.0)
        pace.hear(sentences = 80, lettersPerSecond = 20.0, from = now)
        assertEquals(0.020f, pace.lettersPerMs, 0.0005f)
    }

    @Test
    fun `another voice starts from nothing`() {
        val pace = VoicePace()
        pace.hear(sentences = 6)
        pace.forget()
        assertEquals(0f, pace.lettersPerMs)
    }

    // ---- The letters left -----------------------------------------------------------------------

    @Test
    fun `a chapter that is one file is counted exactly`() {
        // The voice is a quarter of the way down a file of 40,000 letters that is the whole chapter.
        assertEquals(30_000f, lettersLeftInChapter(0.25, 40_000, fileStart = 0.2, fileEnd = 0.3, chapterEnd = 0.3)!!, 1f)
    }

    @Test
    fun `a chapter that runs on into the next file counts that file as this one is`() {
        // The next file takes up as much of the book again: as many letters again.
        assertEquals(70_000f, lettersLeftInChapter(0.25, 40_000, fileStart = 0.2, fileEnd = 0.3, chapterEnd = 0.4)!!, 1f)
    }

    @Test
    fun `a chapter that ends part of the way down its file stops there`() {
        // The next chapter starts three quarters of the way down.
        assertEquals(20_000f, lettersLeftInChapter(0.25, 40_000, fileStart = 0.2, fileEnd = 0.3, chapterEnd = 0.275)!!, 1f)
    }

    @Test
    fun `past the chapter's end there is nothing left`() {
        assertEquals(0f, lettersLeftInChapter(0.9, 40_000, fileStart = 0.2, fileEnd = 0.3, chapterEnd = 0.275)!!, 0f)
    }

    @Test
    fun `what can't be told isn't guessed`() {
        assertNull(lettersLeftInChapter(0.5, 0, fileStart = 0.2, fileEnd = 0.3, chapterEnd = 0.3))
        assertNull(lettersLeftInChapter(0.5, 100, fileStart = 0.3, fileEnd = 0.3, chapterEnd = 0.3))
        assertNull(lettersLeftInChapter(Double.NaN, 100, fileStart = 0.2, fileEnd = 0.3, chapterEnd = 0.3))
    }

    // ---- Minutes ----------------------------------------------------------------------------------

    @Test
    fun `thirteen minutes of text at the voice's pace is thirteen minutes`() {
        // What the emulator showed as "3 min": 13 minutes of the voice at 14 letters a second.
        val letters = 13 * 60 * 14f
        assertEquals(13, voiceMinutesLeft(letters, measured = 0.014f, rate = 1f, language = "en"))
    }

    @Test
    fun `before the voice has been heard, the usual pace at the chosen speed stands in`() {
        val letters = 13 * 60 * 14f
        assertEquals(13, voiceMinutesLeft(letters, measured = 0f, rate = 1f, language = "en"))
        // Twice as fast, half the time (rounded up).
        assertEquals(7, voiceMinutesLeft(letters, measured = 0f, rate = 2f, language = "en-GB"))
        // A character is more to say in Chinese than a letter is in English.
        assertTrue(voiceMinutesLeft(letters, measured = 0f, rate = 1f, language = "zh-CN")!! > 30)
    }

    @Test
    fun `the measured pace already has the speed in it`() {
        assertEquals(13, voiceMinutesLeft(13 * 60 * 28f, measured = 0.028f, rate = 2f, language = "en"))
    }

    @Test
    fun `it rounds up, to a minute until the chapter is done`() {
        assertEquals(1, voiceMinutesLeft(10f, measured = 0.014f, rate = 1f, language = null))
        assertEquals(1, voiceMinutesLeft(0f, measured = 0.014f, rate = 1f, language = null))
        assertEquals(2, voiceMinutesLeft(14 * 61f, measured = 0.014f, rate = 1f, language = null))
    }

    @Test
    fun `no letters, no minutes`() {
        assertNull(voiceMinutesLeft(Float.NaN, measured = 0.014f, rate = 1f, language = null))
        assertNull(voiceMinutesLeft(-1f, measured = 0.014f, rate = 1f, language = null))
    }
}
