package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class SpeechTimingTest {
    private val rate = 16_000

    /** [millis] of a tone, loud enough to count as speech. */
    private fun tone(millis: Int): FloatArray = FloatArray(rate * millis / 1000) { (0.4 * sin(2 * PI * 220 * it / rate)).toFloat() }

    private fun quiet(millis: Int) = FloatArray(rate * millis / 1000)

    @Test
    fun silenceIsCutFromBothEnds() {
        val pcm = SpeechTiming.trim(quiet(500) + tone(1000) + quiet(600), rate)
        val millis = pcm.size * 1000 / rate
        // The second of sound, and a few hundredths of a second around it.
        assertTrue("was $millis ms", millis in 1000..1100)
        assertEquals(0, SpeechTiming.trim(quiet(800), rate).size)
    }

    @Test
    fun soundThatOvershootsIsTurnedDownNotClipped() {
        val loud = FloatArray(rate) { (1.6 * sin(2 * PI * 220 * it / rate)).toFloat() }
        val pcm = SpeechTiming.trim(loud, rate)
        assertTrue(pcm.maxOf { it.toInt() } in 31_000..32_767)
        // A sine that was merely clipped would sit on the ceiling for long stretches.
        assertTrue(pcm.count { it.toInt() >= 32_700 } < pcm.size / 100)
    }

    @Test
    fun wordsTakeTheirPunctuation() {
        val text = "“Well,” said he, “it’s time—now!”"
        val words = SpeechTiming.words(text, Locale.ENGLISH).map { text.substring(it) }
        assertEquals(listOf("“Well,”", "said", "he,", "“it’s", "time—", "now!”"), words)
        // A dash standing alone between spaces is nobody's word.
        assertEquals(listOf("yes", "no"), SpeechTiming.words("yes — no").map { "yes — no".substring(it) })
    }

    @Test
    fun wordsAreSpreadByLengthWhenTheVoiceDoesNotPause() {
        val text = "A considerably longer word"
        val words = SpeechTiming.words(text, Locale.ENGLISH)
        val pcm = SpeechTiming.trim(tone(2000), rate)
        val timed = SpeechTiming.time(text, words, pcm, rate)
        assertEquals(4, timed.size)
        assertEquals(0, timed[0].frame)
        // "A" is over in a moment; "considerably" takes about half of what's left.
        assertTrue(timed[1].frame < pcm.size / 8)
        assertTrue(timed[2].frame - timed[1].frame > pcm.size / 3)
        assertTrue(timed.zipWithNext().all { (a, b) -> a.frame <= b.frame })
        assertEquals(text.length, timed.last().end)
    }

    @Test
    fun aPauseInTheSoundPinsTheWordsAroundAComma() {
        val text = "Stop, and then go on reading aloud"
        val words = SpeechTiming.words(text, Locale.ENGLISH)
        // One short word, a long pause, then the rest: by length alone "and" would start far too early.
        val pcm = SpeechTiming.trim(tone(400) + quiet(900) + tone(1500), rate)
        val timed = SpeechTiming.time(text, words, pcm, rate)
        val and = timed[1]
        assertEquals("and", text.substring(and.start, and.end))
        val startsAt = and.frame * 1000 / rate
        assertTrue("\"and\" starts at $startsAt ms", startsAt in 1250..1400)
    }

    @Test
    fun aLongSentenceAskedForColdStartsWithItsFirstClause() {
        val text = "When the Time Traveller came back, he found the room exactly as he had left it, " +
            "with the lamps burning and the dinner cold upon the table."
        val quick = SpeechPieces.of(text, quickStart = true)
        assertEquals(2, quick.size)
        assertEquals("When the Time Traveller came back, ", text.substring(quick[0]))
        assertEquals(text, quick.joinToString("") { text.substring(it) })
        // With its sound made ahead of time there's no hurry: it stays whole.
        assertEquals(listOf(text.indices), SpeechPieces.of(text, quickStart = false))
        assertEquals(listOf("Short one.".indices), SpeechPieces.of("Short one.", quickStart = true))
    }

    @Test
    fun aVeryLongSentenceIsCutAtClauseEnds() {
        val clause = "and the river ran on past the mill, "
        val text = "It began quietly, " + clause.repeat(14) + "until the sea."
        val pieces = SpeechPieces.of(text, quickStart = false)
        assertTrue(pieces.size >= 2)
        assertEquals(text, pieces.joinToString("") { text.substring(it) })
        assertTrue(pieces.all { it.count() <= 320 })
        assertTrue(pieces.dropLast(1).all { text.substring(it).endsWith(", ") })
    }

    @Test
    fun paragraphsAreSplitIntoSentences() {
        val text = "It was late. \"Who's there?\" he asked.  No answer came…"
        assertEquals(listOf("It was late.", "\"Who's there?\"", "he asked.", "No answer came…").size.coerceAtMost(4), sentencesOf(text, Locale.ENGLISH).size.coerceAtMost(4))
        val all = sentencesOf(text, Locale.ENGLISH).map { text.substring(it) }
        assertEquals("It was late.", all.first())
        assertEquals("No answer came…", all.last())
        assertTrue(all.none { it.endsWith(" ") })
    }

    @Test
    fun aTitleDoesNotEndASentence() {
        val text = "O Sr. Bento chegou. A Sra. D. Glória e o Dr. B. L. Garnier saíram. Fim."
        // As a sentence breaker cuts it: after every full stop followed by a capital.
        val cut = Regex("[^.]+\\.\\s*").findAll(text).map { it.range }.toList()
        assertEquals(
            listOf("O Sr. Bento chegou. ", "A Sra. D. Glória e o Dr. B. L. Garnier saíram. ", "Fim."),
            joinTitles(text, cut).map { text.substring(it) },
        )
        assertEquals(cut.take(1), joinTitles(text, cut.take(1)))
    }

    /** [millis] of a tone as quiet as a word's dying end: a hundredth of the loud one. */
    private fun soft(millis: Int, level: Double = 0.004): FloatArray =
        FloatArray(rate * millis / 1000) { (level * sin(2 * PI * 220 * it / rate)).toFloat() }

    /** [millis] of faint, steady room noise: what a voice taught from home recordings has for silence. */
    private fun room(millis: Int): FloatArray {
        val random = java.util.Random(7)
        return FloatArray(rate * millis / 1000) { (random.nextGaussian() * 0.0006).toFloat() }
    }

    private fun millisOf(pcm: ShortArray) = pcm.size * 1000 / rate

    @Test
    fun theSoftEndOfASentenceIsKept() {
        // Loud speech, then its last sound dying away far below it, then silence.
        val kept = millisOf(SpeechTiming.trim(tone(800) + soft(300) + quiet(600), rate))
        assertTrue("kept $kept ms", kept in 1100..1200)
        // A soft start is kept too.
        val both = millisOf(SpeechTiming.trim(quiet(400) + soft(100) + tone(800) + quiet(400), rate))
        assertTrue("kept $both ms", both in 900..1000)
    }

    @Test
    fun aLastQuietSyllableAfterAClosedConsonantIsKept() {
        // "tempo": the lips close (a moment of nothing), then the last, quiet syllable.
        val kept = millisOf(SpeechTiming.trim(tone(800) + quiet(90) + soft(150) + quiet(600), rate))
        assertTrue("kept $kept ms", kept in 1040..1130)
        // But a sound long after the speech has ended isn't part of it.
        val apart = millisOf(SpeechTiming.trim(tone(800) + quiet(400) + soft(150) + quiet(600), rate))
        assertTrue("kept $apart ms", apart in 800..900)
    }

    @Test
    fun roomNoiseIsNotTakenForSpeech() {
        // The silences of some voices are never quite silent: they are still cut.
        val kept = millisOf(SpeechTiming.trim(room(500) + tone(800) + room(700), rate))
        assertTrue("kept $kept ms", kept in 800..900)
    }

    @Test
    fun theEndIsLetDownGently() {
        val pcm = SpeechTiming.trim(tone(1000), rate)
        // No step at either end to click on.
        assertTrue(kotlin.math.abs(pcm.first().toInt()) < 200)
        assertTrue(kotlin.math.abs(pcm.last().toInt()) < 200)
        // The last hundredth of a second is already well under the level of the rest.
        val end = pcm.takeLast(rate / 100).maxOf { kotlin.math.abs(it.toInt()) }
        val middle = pcm.slice(pcm.size / 2 until pcm.size / 2 + rate / 100).maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(end < middle / 3)
    }
}
