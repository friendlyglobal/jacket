package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How far ahead of the listener the voice works, in seconds of speech, and how much memory that may take. */
class CushionTest {
    @Test
    fun `a phone that keeps up easily keeps about two sentences in hand, a warm or slow one three times that`() {
        assertEquals(Cushion.NORMAL_SECONDS, Cushion.target(warm = false, struggling = false, sampleRate = 44_100), 0f)
        assertEquals(Cushion.GROWN_SECONDS, Cushion.target(warm = true, struggling = false, sampleRate = 44_100), 0f)
        assertEquals(Cushion.GROWN_SECONDS, Cushion.target(warm = false, struggling = true, sampleRate = 44_100), 0f)
        assertEquals(Cushion.GROWN_SECONDS, Cushion.target(warm = true, struggling = true, sampleRate = 22_050), 0f)
        assertTrue(Cushion.GROWN_SECONDS >= 3 * Cushion.NORMAL_SECONDS)
    }

    @Test
    fun `what is made ahead fits in a few megabytes, whatever the voice's sample rate`() {
        // 16-bit samples: a minute at 44.1 kHz is 5.3 MB.
        assertEquals(68f, Cushion.mostSeconds(44_100), 1f)
        assertEquals(136f, Cushion.mostSeconds(22_050), 1f)
        // A voice with a finer sound would get a smaller cushion rather than more memory.
        val fine = Cushion.target(warm = true, struggling = true, sampleRate = 96_000)
        assertTrue(fine < Cushion.GROWN_SECONDS)
        assertTrue(fine * 96_000 * 2 <= Cushion.MOST_BYTES)
        for (rate in listOf(16_000, 22_050, 24_000, 44_100, 48_000)) {
            assertTrue(Cushion.target(warm = true, struggling = true, sampleRate = rate) * rate * 2 <= Cushion.MOST_BYTES)
        }
    }

    @Test
    fun `a sentence's length is reckoned close to what the voice really takes`() {
        // Measured with Supertonic 3 at 1x: 8.9 s and 17.8 s in English, 10.3 s in Portuguese.
        val measured = mapOf(
            "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness, and nobody quite knew which." to 8.9f,
            "There was no possibility of taking a walk that day; we had been wandering, indeed, in the leafless shrubbery an hour in the morning, but since dinner the cold winter wind had brought with it clouds so sombre, and a rain so penetrating, that further outdoor exercise was now out of the question." to 17.8f,
            "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central um rapaz aqui do bairro, que eu conheço de vista e de chapéu." to 10.3f,
        )
        for ((text, seconds) in measured) {
            val reckoned = Cushion.estimate(text, 1f)
            assertTrue("$reckoned s for $seconds s", reckoned in seconds * 0.8f..seconds * 1.2f)
        }
        // Twice as fast, half as long; nothing to say, no time.
        val text = measured.keys.first()
        assertEquals(Cushion.estimate(text, 1f) / 2, Cushion.estimate(text, 2f), 1e-4f)
        assertEquals(0f, Cushion.estimate("   ", 1f), 0f)
        // A Chinese character carries a syllable or more: a line of them takes longer than its count of letters.
        assertTrue(Cushion.estimate("我们明天早上去公园散步吧。", 1f) > Cushion.estimate("abcdefghijklm", 1f))
    }

    @Test
    fun `enough is asked for to fill the cushion, and always the next sentence`() {
        assertEquals(3, Cushion.ahead(listOf(8f, 8f, 8f, 8f, 8f), 20f))
        assertEquals(1, Cushion.ahead(listOf(30f, 8f), 20f))
        // However full the cushion, the next sentence is on its way.
        assertEquals(1, Cushion.ahead(listOf(2f, 2f), 0f))
        // As far as there is anything to make.
        assertEquals(2, Cushion.ahead(listOf(2f, 2f), 60f))
        assertEquals(0, Cushion.ahead(emptyList(), 20f))
    }

    @Test
    fun `a faster voice is made further ahead, by the same seconds in hand`() {
        val sentence = "She closed the book and looked out of the window for a long while."
        val pieces = List(40) { sentence }
        fun ahead(speed: Float) = Cushion.ahead(pieces.map { Cushion.estimate(it, speed) }, Cushion.NORMAL_SECONDS)
        assertEquals(ahead(1f) * 3f, ahead(3f).toFloat(), 2f)
        assertEquals(ahead(1f) * 2f, ahead(2f).toFloat(), 1f)
        assertTrue(ahead(0.5f) <= ahead(1f))
    }

    @Test
    fun `a warm phone runs the voice on fewer cores, and its usual number once cool`() {
        // Half the cores, four at most, as before; two in battery saver.
        assertEquals(4, ModelHost.threadsFor(cores = 8, saving = false, warm = false))
        assertEquals(4, ModelHost.threadsFor(cores = 12, saving = false, warm = false))
        assertEquals(2, ModelHost.threadsFor(cores = 4, saving = false, warm = false))
        assertEquals(1, ModelHost.threadsFor(cores = 2, saving = false, warm = false))
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = true, warm = false))
        assertEquals(1, ModelHost.threadsFor(cores = 1, saving = true, warm = false))
        // Warm: two at most, never more than it had.
        assertEquals(ModelHost.WARM_THREADS, ModelHost.threadsFor(cores = 8, saving = false, warm = true))
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = true, warm = true))
        assertEquals(1, ModelHost.threadsFor(cores = 2, saving = false, warm = true))
    }
}
