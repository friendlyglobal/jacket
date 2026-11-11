package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechCleanupTest {
    private val rate = 22_050

    private fun tone(millis: Int, level: Double = 0.3, hz: Double = 220.0): FloatArray =
        FloatArray(rate * millis / 1000) { (level * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Faint, steady noise: the room a voice was recorded in. */
    private fun room(millis: Int, level: Double = 0.0005): FloatArray {
        val random = java.util.Random(3)
        return FloatArray(rate * millis / 1000) { (random.nextGaussian() * level).toFloat() }
    }

    /** The level, in dB, of [samples] from [fromMs] for [millis]. */
    private fun level(samples: FloatArray, fromMs: Int, millis: Int): Double {
        val from = rate * fromMs / 1000
        val n = rate * millis / 1000
        var sum = 0.0
        for (i in from until from + n) sum += samples[i].toDouble() * samples[i]
        return 20 * log10(sqrt(sum / n) + 1e-12)
    }

    @Test
    fun theVoiceIsLeftAsItWasAndNothingMovesInTime() {
        val before = tone(600) + room(500) + tone(600)
        val after = SpeechCleanup.clean(before.copyOf(), rate)
        assertEquals(before.size, after.size)
        assertEquals(level(before, 200, 300), level(after, 200, 300), 0.3)
        assertEquals(level(before, 1300, 300), level(after, 1300, 300), 0.3)
    }

    @Test
    fun theSilenceBetweenWordsIsTurnedDownGently() {
        val before = tone(600) + room(800) + tone(600)
        val after = before.copyOf().also { SpeechCleanup.quieten(it, rate) }
        // Well inside the gap it is quieter, by no more than the 12 dB it is allowed.
        val drop = level(before, 950, 150) - level(after, 950, 150)
        assertTrue("dropped $drop dB", drop in 6.0..12.5)
        // Just after the word, where its end may still be dying away, nothing has been touched yet.
        assertEquals(level(before, 610, 50), level(after, 610, 50), 1.0)
        // And it is back up before the next word starts.
        assertEquals(level(before, 1400, 20), level(after, 1400, 20), 0.5)
    }

    @Test
    fun aSoftEndingIsNotASilence() {
        // A word's last sound, a hundredth as loud as its vowels, right after them.
        val before = tone(600) + tone(200, level = 0.004) + room(400)
        val after = before.copyOf().also { SpeechCleanup.quieten(it, rate) }
        assertEquals(level(before, 620, 160), level(after, 620, 160), 0.5)
    }

    @Test
    fun rumbleBelowTheVoiceIsTakenOut() {
        val rumble = tone(1000, level = 0.05, hz = 25.0)
        val voice = tone(1000, level = 0.3, hz = 150.0)
        val cleanRumble = rumble.copyOf().also { SpeechCleanup.highPass(it, rate) }
        val cleanVoice = voice.copyOf().also { SpeechCleanup.highPass(it, rate) }
        assertTrue(level(rumble, 300, 500) - level(cleanRumble, 300, 500) > 12.0)
        assertEquals(level(voice, 300, 500), level(cleanVoice, 300, 500), 0.5)
    }

    @Test
    fun whatIsAllSilenceIsLeftAlone() {
        val before = room(900)
        val after = before.copyOf().also { SpeechCleanup.quieten(it, rate) }
        assertTrue(before.contentEquals(after))
        assertEquals(0, SpeechCleanup.clean(FloatArray(0), rate).size)
    }
}
