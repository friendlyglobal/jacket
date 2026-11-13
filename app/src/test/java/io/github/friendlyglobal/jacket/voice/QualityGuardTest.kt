package io.github.friendlyglobal.jacket.voice

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
 * How finely the voice is made, sentence after sentence, on phones made up for the purpose, with
 * a listener hearing each sentence as soon as it is made: the finest voice for as long as the
 * speech made ahead covers for a slow phone, a rougher one only when that is about to run out,
 * hardly ever a gap, and the full voice back once there is room for it.
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

    /**
     * One sentence as it was made: with how many passes, at what pace, the guard after it, the
     * seconds of speech ready ahead of the listener once it was made, and how long the listener
     * waited for it after the one before ran out.
     */
    private class Made(val with: Int, val pace: Float, val after: QualityGuard, val inHand: Float, val gap: Double)

    /**
     * A listening session of [sentences] sentences of [length] seconds each. The phone makes them
     * in order, each at the quality the guard has got to and at the pace [pace] gives for it, as
     * far ahead as [Cushion.target] calls for and no further (it rests while the cushion is full),
     * and never more than [visible] sentences past the one being heard (what the reader has said
     * comes next). The listener hears each one as soon as it is made and the one before has been
     * heard. [warm] is the phone's thermal status, sentence by sentence.
     */
    private fun listen(
        sentences: Int,
        from: QualityGuard = QualityGuard(),
        length: (Int) -> Float = { 8f },
        visible: Int = Int.MAX_VALUE,
        warm: (Int) -> Boolean = { false },
        pace: (steps: Int, sentence: Int) -> Float,
    ): List<Made> {
        var guard = from
        val lengths = DoubleArray(sentences) { length(it).toDouble() }
        val playAt = DoubleArray(sentences)
        val endAt = DoubleArray(sentences)
        var free = 0.0
        /** The sentence being heard at [t], of the first [made]: -1 before the first. */
        fun hearing(t: Double, made: Int): Int = (0 until made).lastOrNull { playAt[it] <= t } ?: -1
        return List(sentences) { i ->
            // Rest until sentence i is within the cushion and within sight.
            var start = free
            while (i > 0) {
                val k = hearing(start, i)
                val ahead = ((k + 1) until i).sumOf { lengths[it] }
                val target = Cushion.target(warm(i), guard.struggling, 44_100)
                if (i == k + 1 || (ahead < target && i - k <= visible)) break
                start = endAt[k]
            }
            val with = guard.steps
            val took = pace(with, i)
            val madeAt = start + took * lengths[i]
            free = madeAt
            playAt[i] = if (i == 0) madeAt else maxOf(endAt[i - 1], madeAt)
            endAt[i] = playAt[i] + lengths[i]
            val gap = if (i == 0) 0.0 else (madeAt - endAt[i - 1]).coerceAtLeast(0.0)
            val inHand = (0..i).sumOf { j ->
                when {
                    endAt[j] <= madeAt -> 0.0
                    playAt[j] <= madeAt -> endAt[j] - madeAt
                    else -> lengths[j]
                }
            }.toFloat()
            val caughtUp = i + 1 > hearing(madeAt, i + 1) + visible
            guard = guard.after(took, inHand, caughtUp)
            Made(with, took, guard, inHand, gap)
        }
    }

    private fun List<Made>.steps() = map { it.after.steps }

    /** How many times the quality changed. */
    private fun List<Made>.changes() = zipWithNext().count { (a, b) -> a.after.steps != b.after.steps } + if (first().after.steps != first().with) 1 else 0

    /** Seconds the listener spent waiting between sentences, the first one (the voice starting) aside. */
    private fun List<Made>.waited() = drop(1).sumOf { it.gap }

    /** The longest the listener waited between two sentences. */
    private fun List<Made>.longestWait() = drop(1).maxOf { it.gap }

    // ---- A steady phone ----

    @Test
    fun `a steady fast phone keeps the voice at its finest, without a gap`() {
        for (fixed in listOf(0f, 2f, 4f)) {
            val phone = Phone(0.3f, fixed)
            val heard = listen(200) { steps, _ -> phone.pace(steps) }
            assertTrue(heard.all { it.after.steps == FULL_STEPS })
            assertFalse(heard.any { it.after.slow })
            assertEquals(0.0, heard.waited(), 1e-9)
            // It rests once the cushion is full: no further ahead than it needs to be.
            assertTrue(heard.drop(10).all { it.inHand <= Cushion.NORMAL_SECONDS + 2 * 8f })
        }
    }

    @Test
    fun `a phone that only just keeps up keeps its finest voice, and works further ahead`() {
        // It makes a second of speech in 0.9: 1.4.1 took a pass off it by its second sentence.
        // It keeps ahead of the listener all the same, so there is nothing to ease for; but with
        // so little to spare, it builds a bigger cushion.
        val phone = Phone(0.9f)
        val heard = listen(200) { steps, _ -> phone.pace(steps) }
        assertTrue(heard.all { it.after.steps == FULL_STEPS })
        assertEquals(0.0, heard.waited(), 1e-9)
        assertTrue(heard.last().inHand > Cushion.NORMAL_SECONDS)
        assertFalse(heard.any { it.after.slow })
    }

    @Test
    fun `a phone that falls behind keeps its finest voice while the cushion lasts, then eases`() {
        // Comfortable for thirty sentences, then something takes the processor for good.
        val heard = listen(120) { steps, i -> Phone(if (i < 30) 0.5f else 1.3f).pace(steps) }
        val firstEased = heard.indexOfFirst { it.after.steps < FULL_STEPS }
        // The speech in hand covers for it a good while: the finest voice, still, for several sentences.
        assertTrue("eased at sentence $firstEased", firstEased >= 33)
        // And it is eased only once that has nearly run out.
        assertTrue(heard[firstEased].inHand < Cushion.LOW_SECONDS)
        // With the lightest voice it keeps up (0.93 of a second for each second): no gap, and no red dot.
        assertEquals(FEWEST_STEPS, heard.last().after.steps)
        assertTrue("waited ${heard.waited()} s", heard.waited() < 1.0)
        assertFalse(heard.any { it.after.slow })
    }

    @Test
    fun `a phone that falls behind from the start is eased by its second sentence`() {
        // Nothing in hand yet: there is no cushion to cover for it.
        val phone = Phone(1.3f)
        val heard = listen(200) { steps, _ -> phone.pace(steps) }
        assertEquals(listOf(FULL_STEPS, FEWEST_STEPS), heard.steps().take(2))
        assertTrue(heard.drop(1).all { it.after.steps == FEWEST_STEPS })
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
        // Only just keeping up at the finest setting: left alone.
        val phone = Phone(0.99f)
        val heard = listen(50) { steps, _ -> phone.pace(steps) }
        assertTrue(heard.all { it.after.steps == FULL_STEPS })
        assertFalse(heard.any { it.after.slow })
        // And one slow sentence at the lightest setting isn't the phone failing.
        val lightest = QualityGuard(steps = FEWEST_STEPS)
        assertFalse(lightest.after(2f, 0f).slow)
        assertTrue(lightest.after(2f, 0f).after(2f, 0f).slow)
        assertFalse(lightest.after(0.9f, 0f).after(0.9f, 0f).after(0.9f, 0f).slow)
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
    fun `a slow sentence now and then is no reason to ease`() {
        val phone = Phone(0.5f)
        val everyFourth = listen(200) { steps, i -> if (i % 4 == 3) 1.5f else phone.pace(steps) }
        assertTrue(everyFourth.all { it.after.steps == FULL_STEPS })
        assertEquals(0.0, everyFourth.waited(), 1e-9)
    }

    @Test
    fun `a slow spell the cushion covers changes nothing`() {
        // Another app takes the processor for three sentences: three times slower, behind the listener.
        val phone = Phone(0.5f)
        val heard = listen(60) { steps, i -> if (i in 30..32) phone.pace(steps) * 3f else phone.pace(steps) }
        assertTrue(heard.all { it.after.steps == FULL_STEPS })
        assertEquals(0.0, heard.waited(), 1e-9)
    }

    @Test
    fun `a slow start costs little, and the full voice is soon back`() {
        // What 1.4.0 got wrong, and 1.4.1 put right. This phone makes a second of speech in 0.55
        // at the finest setting; the book opening makes its first two sentences slow.
        for (fixed in listOf(0f, 2f, 4f)) {
            val phone = Phone(0.55f, fixed)
            val heard = listen(40) { steps, i -> if (i < 2) 2f else phone.pace(steps) }
            assertTrue("what its sentences cost besides the passes: $fixed; ${heard.steps()}", heard.drop(6).all { it.after.steps == FULL_STEPS })
        }
    }

    @Test
    fun `a long slow spell in the middle of a book passes too`() {
        val phone = Phone(0.5f)
        // Another app takes the processor for twelve sentences: so much of it that even the
        // lightest voice is a little behind the listener (1.07 seconds for each second).
        val heard = listen(80) { steps, i -> if (i in 20..31) phone.pace(steps) * 3f else phone.pace(steps) }
        assertTrue(heard.take(20).all { it.after.steps == FULL_STEPS })
        // The cushion covers its start: the finest voice for a few sentences more.
        assertTrue(heard.subList(20, 23).all { it.after.steps == FULL_STEPS })
        assertTrue(heard.subList(20, 32).any { it.after.steps < FULL_STEPS })
        // Once the cushion is spent, the listener waits a moment between sentences: never long,
        // and better than a voice rougher than the lightest.
        assertTrue("waited ${heard.longestWait()} s at most", heard.longestWait() < 1.0)
        assertTrue(heard.drop(45).all { it.after.steps == FULL_STEPS })
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
        // Behind the listener with every pass, keeping up with one fewer, and comfortable with two fewer.
        val phone = Phone(1.0f)
        val heard = listen(200, from = QualityGuard(steps = FEWEST_STEPS)) { steps, _ -> phone.pace(steps) }
        assertEquals(FULL_STEPS - 1, heard.last().after.steps)
        // And having got there, it stays.
        assertTrue(heard.changes() <= 2)
        assertTrue("waited ${heard.waited()} s", heard.waited() < 1.0)
    }

    @Test
    fun `with nothing more in sight it still climbs back`() {
        // A page of one-line paragraphs: the reader says only one sentence ahead, so the cushion
        // can't grow, however fast the phone. Having made all it can is room enough.
        val phone = Phone(0.3f)
        val heard = listen(30, from = QualityGuard(steps = FEWEST_STEPS), length = { 3f }, visible = 1) { steps, _ -> phone.pace(steps) }
        assertTrue(heard.all { it.inHand < Cushion.ROOMY_SECONDS })
        assertEquals(FULL_STEPS, heard.last().after.steps)
    }

    // ---- A phone that changes ----

    @Test
    fun `a slowly warming phone keeps its finest voice longer, and is eased without a gap`() {
        // From comfortable to struggling over a hundred sentences, then cool again.
        fun warmth(i: Int) = if (i < 100) i / 100f else ((200 - i) / 100f).coerceAtLeast(0f)
        for (fixed in listOf(0f, 2f, 4f)) {
            val heard = listen(260) { steps, i -> Phone(0.4f + 0.9f * warmth(i), fixed).pace(steps) }
            val warming = heard.take(100)
            // Never a finer voice while it gets slower.
            assertTrue(warming.zipWithNext().all { (a, b) -> b.after.steps <= a.after.steps })
            // Still the finest voice well after the phone fell behind the listener (it does so at
            // about sentence 67): the speech made ahead while it could covered for it.
            val behind = warming.indexOfFirst { it.with == FULL_STEPS && it.pace > 1f }
            val eased = warming.indexOfFirst { it.after.steps < FULL_STEPS }
            assertTrue("behind at $behind, eased at $eased", eased - behind >= 5)
            // And the listener never waited.
            assertTrue("waited ${heard.waited()} s", heard.waited() < 0.5)
            // Cool again, it has all its passes back, and keeps them.
            assertTrue(heard.takeLast(30).all { it.after.steps == FULL_STEPS })
        }
    }

    @Test
    fun `a warm phone keeps its finest voice, and its bigger cushion rides out a slow spell`() {
        // The phone says it is warm from sentence 10 on. At sentence 50 another app makes it four
        // times slower, far behind the listener, for seven sentences.
        val phone = Phone(0.4f)
        fun pace(steps: Int, i: Int) = if (i in 50..56) phone.pace(steps) * 4f else phone.pace(steps)
        val warm = listen(80, warm = { it >= 10 }, pace = ::pace)
        // Warm, it works further ahead, at the same quality.
        assertTrue(warm[49].inHand > Cushion.NORMAL_SECONDS + 15f)
        assertTrue(warm.all { it.after.steps == FULL_STEPS })
        assertEquals(0.0, warm.waited(), 1e-9)
        // The same spell on a phone that never said it was warm: the smaller cushion runs out first.
        val cool = listen(80, pace = ::pace)
        assertTrue(cool.any { it.after.steps < FULL_STEPS })
    }

    // ---- Not back and forth ----

    @Test
    fun `a phone near the line settles`() {
        // Sentences are not all alike: this phone's come out a tenth quicker or slower than its usual.
        for (full in listOf(0.6f, 0.8f, 0.9f, 0.95f)) {
            val luck = Random(7)
            val phone = Phone(full)
            val heard = listen(600) { steps, _ -> phone.pace(steps) * (0.9f + luck.nextFloat() * 0.2f) }
            assertEquals("a phone at $full", 0, heard.changes())
        }
        // One that is behind the listener at its finest now and then tries a pass more on a lucky
        // run of sentences, and gives it back: a handful of times in an hour and a half of
        // listening, never a gap for it.
        for (full in listOf(1f, 1.05f, 1.1f, 1.2f)) {
            val luck = Random(7)
            val phone = Phone(full)
            val heard = listen(600) { steps, _ -> phone.pace(steps) * (0.9f + luck.nextFloat() * 0.2f) }
            assertTrue("a phone at $full changed ${heard.changes()} times", heard.changes() <= 4)
            assertTrue("a phone at $full waited ${heard.longestWait()} s", heard.drop(5).maxOf { it.gap } < 0.5)
        }
    }

    @Test
    fun `a pass that proves one too many isn't tried again and again`() {
        // A phone the reckoning is wrong about: with all its passes it is behind the listener,
        // though one pass fewer leaves plenty to spare (it heats with the work, say).
        fun pace(steps: Int) = if (steps == FULL_STEPS) 1.1f else 0.5f
        val heard = listen(400, from = QualityGuard(steps = FULL_STEPS - 1)) { steps, _ -> pace(steps) }
        val tries = heard.zipWithNext().count { (a, b) -> b.after.steps > a.after.steps } + 1
        // Left to try every time it looked safe, it would have been at it a hundred times.
        assertTrue("tried $tries times", tries <= 400 / MOST_PATIENCE + 3)
        // Each try lasts as long as the speech in hand covers it: never a gap.
        assertEquals(0.0, heard.waited(), 1e-9)
    }

    @Test
    fun `a pass that holds is trusted again`() {
        // It failed once: the next try waits longer.
        val wary = QualityGuard(steps = FULL_STEPS - 1).after(0.4f, 20f).after(0.4f, 20f).after(1.1f, 5f).after(1.1f, 5f)
        assertEquals(FULL_STEPS - 1, wary.steps)
        assertEquals(CLIMB_AFTER * 2, wary.patience)
        var guard = wary
        val heard = List(4 + MOST_PATIENCE) { guard = guard.after(0.4f, 20f); guard }
        assertEquals(listOf(4, 4, 4, FULL_STEPS), heard.take(4).map { it.steps })
        // This time it held, for long enough to be believed.
        assertEquals(CLIMB_AFTER, heard.last().patience)
        assertFalse(heard.last().trying)
    }

    @Test
    fun `it eases only when the speech in hand is about to run out`() {
        val behind = QualityGuard().after(1.2f, 30f).after(1.2f, 30f).after(1.2f, 30f)
        assertEquals(FULL_STEPS, behind.steps)
        // By one pass where one is enough to keep up, by two where it isn't.
        assertEquals(FULL_STEPS - 1, QualityGuard().after(1.1f, 5f).after(1.1f, 5f).steps)
        assertEquals(FEWEST_STEPS, QualityGuard().after(1.5f, 5f).after(1.5f, 5f).steps)
        // Climbing back needs room: none while the cushion is low, whatever the pace.
        val eased = QualityGuard(steps = FEWEST_STEPS)
        assertEquals(FEWEST_STEPS, eased.after(0.3f, 5f).after(0.3f, 5f).after(0.3f, 5f).steps)
        assertEquals(4, eased.after(0.3f, 20f).after(0.3f, 20f).steps)
        assertEquals(4, eased.after(0.3f, 5f, caughtUp = true).after(0.3f, 5f, caughtUp = true).steps)
    }

    // ---- The reckoning ----

    @Test
    fun `a pass more is reckoned to cost its share and a little over`() {
        assertEquals(0.5f, QualityGuard.paceWith(FULL_STEPS, 0.5f, FULL_STEPS), 1e-6f)
        // From four passes to five: a fifth more, not a quarter (some of the work is the same either way).
        assertEquals(0.6f, QualityGuard.paceWith(5, 0.5f, 4), 1e-6f)
        assertEquals(0.5f, QualityGuard.paceWith(4, 0.4f, 3), 1e-6f)
        // What the guard goes by, and what it reckons a pass more would come to, are there to be logged.
        val eased = QualityGuard(steps = 4).after(0.5f, 20f)
        assertEquals(0.5f, eased.pace, 1e-6f)
        assertEquals(0.6f, eased.oneStepUp, 1e-6f)
        assertEquals(0f, QualityGuard().after(0.5f, 20f).oneStepUp, 0f)
    }

    @Test
    fun `it goes by the middle one of the last three sentences`() {
        val guard = QualityGuard().after(0.3f, 20f).after(0.7f, 20f).after(0.5f, 20f)
        assertEquals(0.5f, guard.pace, 1e-6f)
        assertEquals(0.5f, guard.after(0.2f, 20f).pace, 1e-6f)
        // Of two, the slower: with one sentence to go by, that one.
        assertEquals(0.7f, QualityGuard().after(0.3f, 20f).after(0.7f, 20f).pace, 1e-6f)
        assertEquals(0.3f, QualityGuard().after(0.3f, 20f).pace, 1e-6f)
    }

    @Test
    fun `struggling is behind, close to it, or eased`() {
        assertFalse(QualityGuard().struggling)
        assertFalse(QualityGuard().after(0.5f, 20f).struggling)
        assertTrue(QualityGuard().after(0.85f, 20f).struggling)
        assertTrue(QualityGuard(steps = 4).after(0.3f, 20f).struggling)
    }
}
