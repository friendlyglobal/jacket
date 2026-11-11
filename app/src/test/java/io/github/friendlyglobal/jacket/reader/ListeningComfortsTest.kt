package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.LibraryJson
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.voice.NaturalSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningComfortsTest {
    // ---- Speeds ----

    @Test
    fun `speeds run from half to three times the voice's pace`() {
        assertEquals(0.5f, ListenRates.first())
        assertEquals(3f, ListenRates.last())
        assertTrue(1f in ListenRates)
        assertEquals(ListenRates.sorted(), ListenRates)
        assertEquals(ListenRates.distinct(), ListenRates)
    }

    @Test
    fun `a speed kept from an earlier Jacket stays on offer`() {
        assertEquals(ListenRates, listenRateChoices(1.25f))
        val with = listenRateChoices(0.8f)
        assertTrue(0.8f in with)
        assertEquals(with.sorted(), with)
        assertEquals(ListenRates.size + 1, with.size)
        // Nothing that isn't a speed is offered.
        assertEquals(ListenRates, listenRateChoices(Float.NaN))
        assertEquals(ListenRates, listenRateChoices(7f))
    }

    // ---- The sleep timer ----

    @Test
    fun `a sleep timer takes any number of minutes`() {
        assertEquals(1, sleepMinutesOf("1"))
        assertEquals(45, sleepMinutesOf("45"))
        assertEquals(90, sleepMinutesOf(" 90 "))
        assertEquals(999, sleepMinutesOf("999"))
        assertNull(sleepMinutesOf(""))
        assertNull(sleepMinutesOf("0"))
        assertNull(sleepMinutesOf("1000"))
        assertNull(sleepMinutesOf("-5"))
        assertNull(sleepMinutesOf("ten"))
    }

    @Test
    fun `only a length of time can be typed`() {
        assertEquals("45", sleepMinutesTyped("45"))
        assertEquals("45", sleepMinutesTyped("4 5 min"))
        assertEquals("5", sleepMinutesTyped("005"))
        assertEquals("", sleepMinutesTyped("0"))
        assertEquals("123", sleepMinutesTyped("12345"))
        assertEquals("", sleepMinutesTyped("-.,"))
        // Whatever is kept is a length, or is still being typed.
        for (typed in listOf("7", "30", "999")) assertEquals(typed.toInt(), sleepMinutesOf(sleepMinutesTyped(typed)))
    }

    @Test
    fun `the minutes left count down, and never say none while the timer runs`() {
        val start = 1_000_000L
        val ends = start + 45 * 60_000L
        assertEquals(45, sleepMinutesLeft(ends, start + 1))
        assertEquals(45, sleepMinutesLeft(ends, start + 59_000))
        assertEquals(44, sleepMinutesLeft(ends, start + 61_000))
        assertEquals(1, sleepMinutesLeft(ends, ends - 1_000))
        assertEquals(1, sleepMinutesLeft(ends, ends + 5_000))
    }

    @Test
    fun `the listener's own length is kept with the settings`() {
        val saved = LibraryJson.encode(LibraryState(settings = Settings(sleepMinutes = 45)))
        assertEquals(45, LibraryJson.decode(saved).settings.sleepMinutes)
        // A library from before there was one, and one that says nonsense, have none.
        assertEquals(0, LibraryJson.decode("""{"settings":{}}""").settings.sleepMinutes)
        assertEquals(0, LibraryJson.decode("""{"settings":{"sleepMinutes":-3}}""").settings.sleepMinutes)
        assertEquals(0, LibraryJson.decode("""{"settings":{"sleepMinutes":100000}}""").settings.sleepMinutes)
    }

    // ---- Taking up again after a pause ----

    @Test
    fun `a short pause changes nothing, a long one goes back a sentence, a very long one two`() {
        assertEquals(0, sentencesToRewind(0))
        assertEquals(0, sentencesToRewind(20_000))
        assertEquals(0, sentencesToRewind(LONG_PAUSE_MS - 1))
        assertEquals(1, sentencesToRewind(LONG_PAUSE_MS))
        assertEquals(1, sentencesToRewind(10 * 60_000L))
        assertEquals(1, sentencesToRewind(VERY_LONG_PAUSE_MS - 1))
        assertEquals(2, sentencesToRewind(VERY_LONG_PAUSE_MS))
        assertEquals(2, sentencesToRewind(12 * 60 * 60_000L))
        // A book opened again where the voice left it has been away a long while, for all anyone knows.
        assertEquals(2, sentencesToRewind(UNKNOWN_PAUSE_MS))
        // A clock that went backwards isn't a pause.
        assertEquals(0, sentencesToRewind(-5_000))
    }

    private val text = listOf(
        "It was a dark night. The wind blew hard! Nobody was out.",
        "   ",
        "Morning came at last. The sea was calm.",
    )

    private fun from(paragraph: Int, word: String, sentences: Int): String {
        val (p, at) = sentencesBack(text, paragraph, text[paragraph].indexOf(word), sentences)
        return "$p:" + text[p].substring(at)
    }

    @Test
    fun `plain text is taken up from the start of a sentence`() {
        assertEquals("0:Nobody was out.", from(0, "out", 0))
        assertEquals("0:The wind blew hard! Nobody was out.", from(0, "out", 1))
        assertEquals("0:It was a dark night. The wind blew hard! Nobody was out.", from(0, "out", 2))
        // No further back than the text goes.
        assertEquals("0:It was a dark night. The wind blew hard! Nobody was out.", from(0, "out", 9))
        assertEquals("0:It was a dark night. The wind blew hard! Nobody was out.", from(0, "dark", 2))
    }

    @Test
    fun `going back passes into the paragraph before, over one with nothing in it`() {
        assertEquals("2:Morning came at last. The sea was calm.", from(2, "calm", 1))
        assertEquals("0:Nobody was out.", from(2, "calm", 2))
        assertEquals("0:Nobody was out.", from(2, "Morning", 1))
        assertEquals("0:The wind blew hard! Nobody was out.", from(2, "Morning", 2))
    }

    @Test
    fun `a place that isn't in the text is left as it is`() {
        assertEquals(7 to 3, sentencesBack(text, 7, 3, 2))
        assertEquals(-1 to 0, sentencesBack(text, -1, 0, 1))
    }

    // ---- A fast reader and a slow phone ----

    @Test
    fun `a faster voice is made further ahead, by the same seconds in hand`() {
        assertEquals(2, NaturalSpeech.sentencesAhead(0.5f))
        assertEquals(2, NaturalSpeech.sentencesAhead(1f))
        assertEquals(3, NaturalSpeech.sentencesAhead(1.5f))
        assertEquals(4, NaturalSpeech.sentencesAhead(2f))
        assertEquals(6, NaturalSpeech.sentencesAhead(3f))
        assertTrue(ListenRates.all { NaturalSpeech.sentencesAhead(it) <= NaturalSpeech.MOST_AHEAD })
    }

    @Test
    fun `a sentence tells the phone's pace by its length at the voice's own speed`() {
        assertTrue(NaturalSpeech.tellsThePace(2_000, 1f))
        assertFalse(NaturalSpeech.tellsThePace(1_000, 1f))
        // The same sentence read three times as fast lasts a third as long, and still counts.
        assertTrue(NaturalSpeech.tellsThePace(700, 3f))
        assertFalse(NaturalSpeech.tellsThePace(300, 3f))
        // Read at half speed it lasts twice as long, and is no more of a sentence for it.
        assertFalse(NaturalSpeech.tellsThePace(2_000, 0.5f))
    }
}
