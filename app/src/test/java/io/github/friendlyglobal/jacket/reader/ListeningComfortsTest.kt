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

    // ---- The sleep timer as one button (the widget's) ----

    @Test
    fun `one button steps through the same timers as the player's menu`() {
        val plain = sleepChoices(ownMinutes = 0, hasChapters = false)
        assertEquals(listOf(SleepTimer.OFF, SleepTimer.MIN_15, SleepTimer.MIN_30, SleepTimer.MIN_60), plain.map { it.timer })
        assertEquals(listOf(0, 15, 30, 60), plain.map { it.minutes })
        val all = sleepChoices(ownMinutes = 45, hasChapters = true)
        assertEquals(
            listOf(SleepTimer.OFF, SleepTimer.MIN_15, SleepTimer.MIN_30, SleepTimer.MIN_60, SleepTimer.OTHER, SleepTimer.CHAPTER),
            all.map { it.timer },
        )
        assertEquals(45, all.first { it.timer == SleepTimer.OTHER }.minutes)
        // A length of their own that is one of the usual ones isn't offered twice.
        assertEquals(plain, sleepChoices(ownMinutes = 30, hasChapters = false))
        // Nor one that is no length at all.
        assertEquals(plain, sleepChoices(ownMinutes = -3, hasChapters = false))
        assertEquals(plain, sleepChoices(ownMinutes = 5000, hasChapters = false))
        // Every timed choice runs for some time, and no two choices are the same timer.
        assertTrue(all.filter { it.timer.timed }.all { it.minutes > 0 })
        assertEquals(all.size, all.map { it.timer }.distinct().size)
    }

    @Test
    fun `each press sets the next timer, and the one after the last is off`() {
        val choices = sleepChoices(ownMinutes = 45, hasChapters = true)
        var timer = SleepTimer.OFF
        val pressed = (1..choices.size).map { nextSleepChoice(timer, choices).also { timer = it.timer } }
        assertEquals(
            listOf(SleepTimer.MIN_15, SleepTimer.MIN_30, SleepTimer.MIN_60, SleepTimer.OTHER, SleepTimer.CHAPTER, SleepTimer.OFF),
            pressed.map { it.timer },
        )
        assertEquals(listOf(15, 30, 60, 45, 0, 0), pressed.map { it.minutes })
        // In a book without chapters, an hour is followed by off.
        assertEquals(SleepChoice(SleepTimer.OFF), nextSleepChoice(SleepTimer.MIN_60, sleepChoices(0, hasChapters = false)))
    }

    @Test
    fun `a timer the button doesn't offer is followed by off`() {
        // Set in the player: to the end of the chapter, then the contents turned out empty; or a
        // length typed in and changed since.
        assertEquals(SleepChoice(SleepTimer.OFF), nextSleepChoice(SleepTimer.CHAPTER, sleepChoices(0, hasChapters = false)))
        assertEquals(SleepChoice(SleepTimer.OFF), nextSleepChoice(SleepTimer.OTHER, sleepChoices(0, hasChapters = true)))
        assertEquals(SleepChoice(SleepTimer.OFF), nextSleepChoice(SleepTimer.MIN_15, emptyList()))
    }

    @Test
    fun `the minutes left are counted up, and counted again as each one ends`() {
        val minute = 60_000L
        assertEquals(15, sleepMinutesShown(15 * minute))
        assertEquals(15, sleepMinutesShown(14 * minute + 1))
        assertEquals(14, sleepMinutesShown(14 * minute))
        assertEquals(1, sleepMinutesShown(minute))
        assertEquals(1, sleepMinutesShown(1))
        assertEquals(0, sleepMinutesShown(0))
        assertEquals(0, sleepMinutesShown(-5_000))
        // The wait runs to the moment the number drops by one, never to nothing while time is left.
        assertEquals(minute, sleepMinuteEndsIn(15 * minute))
        assertEquals(1, sleepMinuteEndsIn(14 * minute + 1))
        assertEquals(30_000L, sleepMinuteEndsIn(30_000L))
        assertEquals(0, sleepMinuteEndsIn(0))
        assertEquals(0, sleepMinuteEndsIn(-1))
        // Stepping through a whole timer: every number once, in order, and it ends on time.
        var left = 3 * minute - 250
        val shown = ArrayList<Int>()
        var waited = 0L
        while (left > 0) {
            shown += sleepMinutesShown(left)
            val wait = sleepMinuteEndsIn(left)
            assertTrue(wait in 1..minute)
            waited += wait
            left -= wait
        }
        assertEquals(listOf(3, 2, 1), shown)
        assertEquals(3 * minute - 250, waited)
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
