package io.github.friendlyglobal.jacket.voice

import io.github.friendlyglobal.jacket.voice.QualityGuard.Companion.FULL_STEPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The natural voice making its speech with as little processor time as keeps it fine: on few
 * cores whose threads sleep while they wait, a sound stream looked at only when there is
 * something to do, and a line in the log about once a minute of what it all costs.
 */
class CalmVoiceTest {
    @Test
    fun `the voice runs on two cores, whatever the phone`() {
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = false, warm = false))
        assertEquals(2, ModelHost.threadsFor(cores = 12, saving = false, warm = false))
        assertEquals(2, ModelHost.threadsFor(cores = 6, saving = false, warm = false))
        assertEquals(2, ModelHost.threadsFor(cores = 4, saving = false, warm = false))
        // Never more than half of them.
        assertEquals(1, ModelHost.threadsFor(cores = 2, saving = false, warm = false))
        assertEquals(1, ModelHost.threadsFor(cores = 1, saving = false, warm = false))
        // Warm, or in battery saver: the same two.
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = false, warm = true))
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = true, warm = false))
        assertEquals(1, ModelHost.threadsFor(cores = 2, saving = true, warm = true))
    }

    @Test
    fun `a voice that can't keep up on two gets more cores while the phone is cool, never more than four`() {
        assertEquals(4, ModelHost.threadsFor(cores = 8, saving = false, warm = false, behind = true))
        assertEquals(4, ModelHost.threadsFor(cores = 12, saving = false, warm = false, behind = true))
        assertEquals(3, ModelHost.threadsFor(cores = 6, saving = false, warm = false, behind = true))
        // A phone with four cores has nothing more to give than two (the other two are its slow ones).
        assertEquals(2, ModelHost.threadsFor(cores = 4, saving = false, warm = false, behind = true))
        // Warm or in battery saver it goes back to two: the heat and the battery come first, and the speech in hand covers.
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = false, warm = true, behind = true))
        assertEquals(2, ModelHost.threadsFor(cores = 8, saving = true, warm = false, behind = true))
    }

    @Test
    fun `only sentences that agree say the phone is short of speed`() {
        val fine = QualityGuard()
        assertFalse(fine.shortOfSpeed)
        // One slow sentence: the phone was busy for a moment. Not enough to load the model again.
        val once = fine.after(0.95f, inHand = 30f)
        assertTrue(once.struggling)
        assertFalse(once.shortOfSpeed)
        // Nor two, of which only one is slow.
        assertFalse(once.after(0.3f, inHand = 30f).shortOfSpeed)
        // Two slow ones of three: it is the phone.
        val twice = once.after(0.3f, inHand = 30f).after(0.9f, inHand = 30f)
        assertTrue(twice.shortOfSpeed)
        // Keeping up easily, three in a row: not short of anything.
        assertFalse(fine.after(0.3f, 30f).after(0.4f, 30f).after(0.35f, 30f).shortOfSpeed)
        // Made rougher to keep up: short of speed, whatever the last sentences say.
        assertTrue(QualityGuard(steps = FULL_STEPS - 1).shortOfSpeed)
    }

    @Test
    fun `the engine's settings put its threads to sleep while they wait`() {
        val lines = EngineSettings.QUIET.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        // sherpa-onnx hands lines that start with "SessionConfig." to ONNX Runtime as they are.
        assertEquals(
            listOf("SessionConfig.session.intra_op.allow_spinning=0", "SessionConfig.session.inter_op.allow_spinning=0"),
            lines,
        )
        // It reads the file named after the processor, past the first colon.
        assertEquals("cpu:/data/user/0/app/no_backup/voice-engine.conf", EngineSettings.providerFor("/data/user/0/app/no_backup/voice-engine.conf"))
    }

    // ---- The sound stream ----------------------------------------------------------------------

    private fun look(queued: Long = 44_100, more: Boolean = true, toNext: Long? = null, due: Long? = null, ratio: Float = 1f) =
        PcmOut.nextLook(framesQueued = queued, moreToWrite = more, framesToNext = toNext, msToDue = due, sampleRate = 44_100, ratio = ratio)

    @Test
    fun `the stream is looked at when the next word is due, not every few milliseconds`() {
        // A word 300 ms away, a second of sound in the stream: it sleeps until the word.
        assertEquals(301L, look(toNext = 13_230))
        // At one and a half times the pace, the word comes sooner.
        assertEquals(201L, look(toNext = 13_230, ratio = 1.5f))
        // With no word to wait for, it tops the stream up once half of it has played.
        assertEquals(500L, look(queued = 44_100))
        assertEquals(250L, look(queued = 44_100, ratio = 2f))
        // Everything written: only the words and the end of the speech wake it, at most a second apart.
        assertEquals(PcmOut.MOST_WAIT_MS, look(queued = 44_100, more = false))
        assertEquals(1_001L.coerceAtMost(PcmOut.MOST_WAIT_MS), look(more = false, toNext = 44_100))
    }

    @Test
    fun `the stream never sleeps past what is due, nor spins`() {
        // The clock says the clip must have been heard by now (the phone stopped saying where it is).
        assertEquals(PcmOut.LEAST_WAIT_MS, look(toNext = 44_100, due = -50))
        assertEquals(120L, look(toNext = 44_100, due = 120))
        // A word due already, or the stream about to run dry: at once, though not in a busy loop.
        assertEquals(PcmOut.LEAST_WAIT_MS, look(toNext = 0))
        assertEquals(PcmOut.LEAST_WAIT_MS, look(queued = 0))
    }

    @Test
    fun `while the voice speaks, the stream wakes a few times a second instead of eighty`() {
        // A sentence of 12 s with 30 words, read from a stream holding a second: count the looks.
        val rate = 44_100
        val words = List(30) { it * 12 * rate / 30L }
        var heard = 0L
        var written = 44_100L
        val clip = 12L * rate
        var looks = 0
        while (heard < clip) {
            written = minOf(clip, maxOf(written, heard + rate))
            val next = words.firstOrNull { it > heard } ?: clip
            val wait = PcmOut.nextLook(written - heard, written < clip, next - heard, null, rate, 1f)
            heard += wait * rate / 1000
            looks++
        }
        // About one a word, and a few to top up: some 3 a second, where it was 83.
        assertTrue("looked $looks times", looks in 30..45)
    }

    // ---- The log ---------------------------------------------------------------------------------

    @Test
    fun `about once a minute of listening, what it cost is told per minute of speech`() {
        val meter = VoiceMeter()
        var cpu = 0L
        var making = 0L
        meter.start(now = 0L, cpu = { cpu }, making = { making })
        var reading: VoiceMeter.Reading? = null
        // Sentences of 8 s, heard one after another, each costing 2 s of processor (1.6 s of it making speech).
        var t = 0L
        while (reading == null) {
            t += 8_000L
            cpu += 2_000L
            making += 1_600L
            reading = meter.heard(8_000L, t, { cpu }, { making })
        }
        assertEquals(64_000L, reading.wallMs)
        assertEquals(64_000L, reading.speechMs)
        assertEquals(15f, reading.cpuPerSpeechMinute, 0.01f)
        assertEquals(12f, reading.makingPerSpeechMinute, 0.01f)
        // The next minute is counted afresh.
        assertNull(meter.heard(8_000L, t + 8_000L, { cpu }, { making }))
    }

    @Test
    fun `a pause is neither speech nor its cost`() {
        val meter = VoiceMeter()
        var cpu = 0L
        meter.start(0L, { cpu }, { 0L })
        cpu += 5_000L
        meter.heard(30_000L, 30_000L, { cpu }, { 0L })
        // Paused for ten minutes, with the processor busy elsewhere in the app meanwhile.
        meter.stop()
        assertNull(meter.heard(8_000L, 600_000L, { cpu }, { 0L }))
        cpu += 100_000L
        // Listening again: the count starts there.
        meter.start(600_000L, { cpu }, { 0L })
        assertTrue(meter.counting)
        // The voice going on to its next sentence doesn't start the minute again.
        meter.start(630_000L, { cpu + 1 }, { 0L })
        cpu += 4_000L
        val reading = meter.heard(60_000L, 660_000L, { cpu }, { 0L })!!
        assertEquals(60_000L, reading.wallMs)
        assertEquals(4f, reading.cpuPerSpeechMinute, 0.01f)
    }

    @Test
    fun `the processor times are only asked for when a minute is told`() {
        val meter = VoiceMeter()
        var asked = 0
        meter.start(0L, { asked++; 0L }, { asked++; 0L })
        assertEquals(2, asked)
        repeat(5) { meter.heard(5_000L, (it + 1) * 5_000L, { asked++; 0L }, { asked++; 0L }) }
        assertEquals(2, asked)
    }

    @Test
    fun `the voice's threads are told apart by their name`() {
        // A /proc/self/task/<id>/stat line: user time 1500 ticks, system time 120.
        fun stat(name: String) = "4242 ($name) S 4100 4100 0 0 -1 1077952576 3015 0 12 0 1500 120 0 0 22 2 30 0 9000 0 0"
        assertEquals(1_620L, VoiceMeter.voiceTicks(stat("jacket-voice")))
        // The thread that plays the sound isn't making speech (its name is cut to 15 letters).
        assertNull(VoiceMeter.voiceTicks(stat("jacket-voice-ou")))
        assertNull(VoiceMeter.voiceTicks(stat("RenderThread")))
        // A name with spaces and brackets in it.
        assertNull(VoiceMeter.voiceTicks(stat("a (b) c")))
        assertNull(VoiceMeter.voiceTicks("garbage"))
    }

    @Test
    fun `the log line says what the owner of a warm phone needs to tell`() {
        val line = VoiceMeter.describe(
            VoiceMeter.Reading(wallMs = 61_000L, speechMs = 60_000L, cpuMs = 9_400L, makingMs = 8_100L),
            threads = 2, steps = 5, inHand = 23.6f, aim = 20f, thermal = 2, screenOn = false,
        )
        assertTrue(line, line.startsWith("Listening: 9.4 s of processor time per minute of speech (making speech 8.1 s)"))
        assertTrue(line, "2 threads, 5 steps, 24 s in hand of 20, thermal status moderate, screen off" in line)
    }
}
