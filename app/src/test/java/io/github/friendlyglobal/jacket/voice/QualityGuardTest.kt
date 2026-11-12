package io.github.friendlyglobal.jacket.voice

import io.github.friendlyglobal.jacket.voice.QualityGuard.Companion.BEHIND
import io.github.friendlyglobal.jacket.voice.QualityGuard.Companion.CLIMB_AFTER
import io.github.friendlyglobal.jacket.voice.QualityGuard.Companion.FEWEST_STEPS
import io.github.friendlyglobal.jacket.voice.QualityGuard.Companion.FULL_STEPS
import io.github.friendlyglobal.jacket.voice.QualityGuard.Companion.MOST_PATIENCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * How finely the voice is made, sentence after sentence, on phones made up for the purpose: a
 * rougher voice only while the phone really is slow, never a gap, and the full voice back once
 * there is room for it.
 */
class QualityGuardTest {
    /**
     * A phone that makes a second of speech in [full] seconds at the finest setting. What a
     * sentence costs it besides the passes is [fixed] passes' worth: nobody knows that number for
     * a given phone, so the guard has to do right by a low one and a high one alike.
     */
    private class Phone(val full: Float, val fixed: Float = 2f) {
        fun pace(steps: Int): Float = full * (steps + fixed) / (FULL_STEPS + fixed)
    }

    /** One sentence as it was made: with how many passes, at what pace, and the guard after it. */
    private class Made(val with: Int, val pace: Float, val after: QualityGuard)

    /** Makes [sentences] sentences, each at the quality the guard has got to, at the pace [pace] gives for it. */
    private fun listen(
        sentences: Int,
        from: QualityGuard = QualityGuard(),
        hot: (Int) -> Boolean = { false },
        pace: (steps: Int, sentence: Int) -> Float,
    ): List<Made> {
        var guard = from
        return List(sentences) { i ->
            val with = guard.steps
            val took = pace(with, i)
            guard = guard.after(took, hot(i))
            Made(with, took, guard)
        }
    }

    private fun List<Made>.steps() = map { it.after.steps }

    /** How many times the quality changed. */
    private fun List<Made>.changes() = zipWithNext().count { (a, b) -> a.after.steps != b.after.steps } + if (first().after.steps != first().with) 1 else 0

    // ---- A steady phone ----

    @Test
    fun `a steady fast phone keeps the voice at its finest`() {
        for (fixed in listOf(0f, 2f, 4f)) {
            val phone = Phone(0.3f, fixed)
            val heard = listen(200) { steps, _ -> phone.pace(steps) }
            assertTrue(heard.all { it.after.steps == FULL_STEPS })
            assertFalse(heard.any { it.after.slow })
        }
    }

    @Test
    fun `a phone with less in hand than the old guard asked for keeps its finest voice too`() {
        // It makes a second of speech in 0.7: 1.4.0 took a pass off it from the first sentence on.
        // It keeps ahead of the listener all the same, so there is nothing to ease for.
        val phone = Phone(0.7f)
        assertTrue(listen(200) { steps, _ -> phone.pace(steps) }.all { it.after.steps == FULL_STEPS })
    }

    @Test
    fun `a steady slow phone is eased by its second sentence and stays eased`() {
        val phone = Phone(0.9f)
        val heard = listen(200) { steps, _ -> phone.pace(steps) }
        // One slow sentence could be anything. Two are the phone.
        assertEquals(listOf(FULL_STEPS, FULL_STEPS - 1), heard.steps().take(2))
        assertTrue(heard.drop(1).all { it.after.steps == FULL_STEPS - 1 })
        // A pass fewer is enough for it: it isn't told to find another voice.
        assertFalse(heard.any { it.after.slow })
    }

    @Test
    fun `a phone that falls behind goes straight to the lightest voice`() {
        val phone = Phone(1.3f)
        val heard = listen(200) { steps, _ -> phone.pace(steps) }
        assertEquals(listOf(FULL_STEPS, FEWEST_STEPS), heard.steps().take(2))
        assertTrue(heard.drop(1).all { it.after.steps == FEWEST_STEPS })
        // With the fewest passes it keeps up (0.93 of a second for each second): no red dot.
        assertFalse(heard.any { it.after.slow })
    }

    @Test
    fun `a phone that can't keep up even with the lightest voice is told so`() {
        val phone = Phone(1.6f)
        val heard = listen(20) { steps, _ -> phone.pace(steps) }
        assertEquals(FEWEST_STEPS, heard[1].after.steps)
        // Not on the strength of the finer voice being too much for it: the lightest is tried first.
        assertFalse(heard[1].after.slow)
        assertFalse(heard[2].after.slow)
        assertTrue(heard.drop(3).all { it.after.slow && it.after.steps == FEWEST_STEPS })
    }

    @Test
    fun `can't keep up is only ever said of the lightest voice`() {
        // Slow at the finest setting, coping with a pass fewer: eased, not given up on.
        val phone = Phone(0.99f)
        val heard = listen(50) { steps, _ -> phone.pace(steps) }
        assertTrue(heard.last().after.steps < FULL_STEPS)
        assertFalse(heard.any { it.after.slow })
        // And one slow sentence at the lightest setting isn't the phone failing.
        val lightest = QualityGuard(steps = FEWEST_STEPS)
        assertFalse(lightest.after(2f).slow)
        assertTrue(lightest.after(2f).after(2f).slow)
        assertFalse(lightest.after(0.9f).after(0.9f).after(0.9f).slow)
    }

    // ---- A spike ----

    @Test
    fun `one slow sentence changes nothing, wherever it falls`() {
        for (full in listOf(0.3f, 0.5f, 0.75f)) {
            val phone = Phone(full)
            // The very first sentence of a session is the likeliest: the book is opening.
            for (at in listOf(0, 1, 2, 7, 30)) {
                val heard = listen(60) { steps, i -> if (i == at) 4f else phone.pace(steps) }
                assertTrue("a phone at $full, a spike at sentence $at", heard.all { it.after.steps == FULL_STEPS })
            }
        }
    }

    @Test
    fun `a slow sentence now and then is no reason to ease, but every other one is`() {
        val phone = Phone(0.5f)
        val everyFourth = listen(200) { steps, i -> if (i % 4 == 3) 1.5f else phone.pace(steps) }
        assertTrue(everyFourth.all { it.after.steps == FULL_STEPS })
        // Half its sentences late: on the whole it isn't keeping up, whatever the quick ones say.
        val everyOther = listen(200) { steps, i -> if (i % 2 == 1) phone.pace(steps) * 3f else phone.pace(steps) }
        assertTrue(everyOther.last().after.steps < FULL_STEPS)
    }

    @Test
    fun `the full voice is back four sentences after a slow spell`() {
        // What 1.4.0 got wrong. This phone makes a second of speech in 0.55 at the finest setting.
        // A slow start (the book opening, the page loading) took its passes away, and with the
        // fewest it then made a second in 0.39: never under the 0.35 that gave them back. It read
        // the rest of the session in the rougher voice.
        for (fixed in listOf(0f, 2f, 4f)) {
            val phone = Phone(0.55f, fixed)
            val heard = listen(40) { steps, i -> if (i < 2) 2f else phone.pace(steps) }
            assertEquals(
                "what its sentences cost besides the passes: $fixed",
                // Two slow sentences: the lightest voice. Then a pass back for every two that leave room.
                listOf(FULL_STEPS, FEWEST_STEPS, FEWEST_STEPS, 4, 4, FULL_STEPS),
                heard.steps().take(6),
            )
            assertTrue(heard.drop(5).all { it.after.steps == FULL_STEPS })
        }
    }

    @Test
    fun `a slow spell in the middle of a book passes too`() {
        val phone = Phone(0.5f)
        // Another app takes the processor for five sentences.
        val heard = listen(60) { steps, i -> if (i in 20..24) phone.pace(steps) * 3f else phone.pace(steps) }
        assertTrue(heard.take(20).all { it.after.steps == FULL_STEPS })
        assertTrue(heard[21].after.steps < FULL_STEPS)
        // While it lasts, the voice is never behind the listener for long: it eased to what the phone could do.
        assertTrue(heard.subList(22, 25).all { it.pace <= BEHIND * 1.1f })
        assertTrue(heard.drop(29).all { it.after.steps == FULL_STEPS })
    }

    @Test
    fun `it climbs a pass at a time`() {
        val phone = Phone(0.3f)
        val heard = listen(20, from = QualityGuard(steps = FEWEST_STEPS)) { steps, _ -> phone.pace(steps) }
        assertTrue(heard.zipWithNext().all { (a, b) -> b.after.steps - a.after.steps in 0..1 })
        assertEquals(FULL_STEPS, heard.last().after.steps)
    }

    @Test
    fun `a phone with room for one more pass and not two stops there`() {
        // Slowed for a while, it gets back the pass it can carry: not the one that would leave it short.
        val phone = Phone(0.78f)
        val heard = listen(200) { steps, i -> if (i < 3) 2f else phone.pace(steps) }
        assertEquals(FEWEST_STEPS, heard[2].after.steps)
        assertEquals(FULL_STEPS - 1, heard.last().after.steps)
        // And having got there, it stays.
        assertTrue(heard.changes() <= 2)
    }

    // ---- A phone that changes ----

    @Test
    fun `a slowly warming phone is eased a pass at a time, without a gap`() {
        // From comfortable to struggling over a hundred sentences, then cool again.
        fun warmth(i: Int) = if (i < 100) i / 100f else ((200 - i) / 100f).coerceAtLeast(0f)
        for (fixed in listOf(0f, 2f, 4f)) {
            val heard = listen(260) { steps, i -> Phone(0.4f + 0.9f * warmth(i), fixed).pace(steps) }
            val warming = heard.take(100)
            // Never a finer voice while it gets slower, and never straight to the lightest: it had warning.
            assertTrue(warming.zipWithNext().all { (a, b) -> b.after.steps - a.after.steps in -1..0 })
            assertEquals(FEWEST_STEPS, warming.last().after.steps)
            // While there was still a lighter voice to go to, no sentence took longer to make than to hear.
            assertTrue(warming.filter { it.with > FEWEST_STEPS }.all { it.pace < BEHIND })
            // Cool again, it has all its passes back, and keeps them.
            assertTrue(heard.takeLast(30).all { it.after.steps == FULL_STEPS })
        }
    }

    @Test
    fun `a hot phone gets the lightest voice at once, and the full one back when it has cooled`() {
        val phone = Phone(0.4f)
        val heard = listen(40, hot = { it in 10..19 }) { steps, _ -> phone.pace(steps) }
        assertEquals(FULL_STEPS, heard[9].after.steps)
        // However well it is keeping up: the heat is the reason.
        assertTrue(heard.subList(10, 20).all { it.after.steps == FEWEST_STEPS })
        assertEquals(listOf(4, 4, FULL_STEPS), heard.subList(20, 23).steps())
        assertTrue(heard.drop(22).all { it.after.steps == FULL_STEPS })
        assertFalse(heard.any { it.after.slow })
    }

    // ---- Not back and forth ----

    @Test
    fun `a phone near the line settles`() {
        // Sentences are not all alike: this phone's come out a tenth quicker or slower than its usual.
        for (full in listOf(0.6f, 0.7f, 0.76f, 0.8f, 0.84f, 0.9f)) {
            val luck = Random(7)
            val phone = Phone(full)
            val heard = listen(600) { steps, _ -> phone.pace(steps) * (0.9f + luck.nextFloat() * 0.2f) }
            assertTrue("a phone at $full changed ${heard.changes()} times", heard.changes() <= 2)
        }
    }

    @Test
    fun `a pass that proves one too many isn't tried again and again`() {
        // A phone the reckoning is wrong about: with all its passes it is far slower than one
        // pass fewer would suggest (it heats with the work, say).
        fun pace(steps: Int) = if (steps == FULL_STEPS) 0.9f else 0.5f
        val heard = listen(400, from = QualityGuard(steps = FULL_STEPS - 1)) { steps, _ -> pace(steps) }
        val tries = heard.zipWithNext().count { (a, b) -> b.after.steps > a.after.steps } + 1
        // Left to try every time it looked safe, it would have been at it a hundred times.
        assertTrue("tried $tries times", tries <= 400 / MOST_PATIENCE + 3)
        // Each try costs two sentences in the finer voice it can't quite carry, never a gap.
        assertTrue(heard.all { it.pace < BEHIND })
        assertEquals(MOST_PATIENCE, heard.last().after.patience)
    }

    @Test
    fun `a pass that holds is trusted again`() {
        // It failed once: the next try waits longer.
        val wary = QualityGuard(steps = FULL_STEPS - 1).after(0.4f).after(0.4f).after(0.9f).after(0.9f)
        assertEquals(FULL_STEPS - 1, wary.steps)
        assertEquals(CLIMB_AFTER * 2, wary.patience)
        val phone = Phone(0.4f)
        val heard = listen(4 + MOST_PATIENCE, from = wary) { steps, _ -> phone.pace(steps) }
        assertEquals(listOf(4, 4, 4, FULL_STEPS), heard.steps().take(4))
        // This time it held, for long enough to be believed.
        assertEquals(CLIMB_AFTER, heard.last().after.patience)
        assertFalse(heard.last().after.trying)
    }

    // ---- The reckoning ----

    @Test
    fun `a pass more is reckoned to cost its share and a little over`() {
        assertEquals(0.5f, QualityGuard.paceWith(FULL_STEPS, 0.5f, FULL_STEPS), 1e-6f)
        // From four passes to five: a fifth more, not a quarter (some of the work is the same either way).
        assertEquals(0.6f, QualityGuard.paceWith(5, 0.5f, 4), 1e-6f)
        assertEquals(0.5f, QualityGuard.paceWith(4, 0.4f, 3), 1e-6f)
        // What the guard goes by, and what it reckons a pass more would come to, are there to be logged.
        val eased = QualityGuard(steps = 4).after(0.5f)
        assertEquals(0.5f, eased.pace, 1e-6f)
        assertEquals(0.6f, eased.oneStepUp, 1e-6f)
        assertEquals(0f, QualityGuard().after(0.5f).oneStepUp, 0f)
    }

    @Test
    fun `it goes by the middle one of the last three sentences`() {
        val guard = QualityGuard().after(0.3f).after(0.7f).after(0.5f)
        assertEquals(0.5f, guard.pace, 1e-6f)
        assertEquals(0.5f, guard.after(0.2f).pace, 1e-6f)
        // Of two, the slower: with one sentence to go by, that one.
        assertEquals(0.7f, QualityGuard().after(0.3f).after(0.7f).pace, 1e-6f)
        assertEquals(0.3f, QualityGuard().after(0.3f).pace, 1e-6f)
    }
}
