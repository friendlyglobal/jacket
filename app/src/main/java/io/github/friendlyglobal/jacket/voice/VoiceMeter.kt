package io.github.friendlyglobal.jacket.voice

import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.math.roundToInt

/*
 * What listening costs, said in the log about once a minute: how much processor time the app used
 * for each minute of speech heard, and how much of that went on making the speech. Processor time
 * is what heats a phone, so this is the number to watch on a phone that runs warm:
 *
 *     adb logcat -s JacketVoice
 *
 * Counted by the clips heard, not by a timer: nothing wakes the phone to keep the count.
 */

/**
 * Counts the speech heard and the processor time used, a minute at a time. Told when listening
 * starts, and of each stretch of speech as it is heard; hands back a [Reading] once a minute has
 * gone by since the last. Nothing here touches the phone, so it can be tested with made-up times.
 */
internal class VoiceMeter(private val everyMs: Long = MINUTE_MS) {
    /** What one minute (or so) of listening cost. */
    data class Reading(
        /** How long it took, on the clock. */
        val wallMs: Long,
        /** Speech heard meanwhile, at the pace it was heard at. */
        val speechMs: Long,
        /** The whole app's processor time meanwhile, all cores together. */
        val cpuMs: Long,
        /** ... of which making speech (the voice's threads). */
        val makingMs: Long,
    ) {
        /** Processor seconds for each minute of speech heard. */
        val cpuPerSpeechMinute: Float get() = perSpeechMinute(cpuMs)
        val makingPerSpeechMinute: Float get() = perSpeechMinute(makingMs)

        private fun perSpeechMinute(ms: Long): Float = if (speechMs <= 0) 0f else ms / 1000f / (speechMs / 60_000f)
    }

    private var since = -1L
    private var cpuAt = 0L
    private var makingAt = 0L
    private var speechMs = 0L

    /** Whether a minute is being counted: from the first words after listening (re)started. */
    val counting: Boolean get() = since >= 0

    /**
     * Listening (re)starts at [now] (elapsed realtime), with [cpu] and [making] the processor
     * times so far (asked only when they are needed). A minute already being counted goes on: the
     * voice moving to the next sentence starts it again, and that is not a new minute.
     */
    fun start(now: Long, cpu: () -> Long, making: () -> Long) {
        if (counting) return
        since = now
        cpuAt = cpu()
        makingAt = making()
        speechMs = 0L
    }

    /** Listening has stopped (paused, or ended): a pause is neither speech nor its cost. */
    fun stop() {
        since = -1L
    }

    /**
     * [ms] of speech has been heard by [now]. A [Reading] once a minute has gone by, and the count
     * starts again. The processor times are asked for only then.
     */
    fun heard(ms: Long, now: Long, cpu: () -> Long, making: () -> Long): Reading? {
        if (!counting) return null
        speechMs += ms.coerceAtLeast(0L)
        if (now - since < everyMs) return null
        val cpuNow = cpu()
        val makingNow = making()
        val reading = Reading(now - since, speechMs, cpuNow - cpuAt, makingNow - makingAt)
        since = now
        cpuAt = cpuNow
        makingAt = makingNow
        speechMs = 0L
        return reading
    }

    internal companion object {
        const val MINUTE_MS = 60_000L

        /** The name the voice's threads go by: the voice thread, and the engine's own, which take its name. */
        private const val VOICE_THREAD = "jacket-voice"

        /** Milliseconds of processor time the voice's threads have used: those named [VOICE_THREAD], all of them. */
        fun makingMs(): Long = runCatching {
            val tick = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
            var ticks = 0L
            File("/proc/self/task").listFiles()?.forEach { task ->
                val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: return@forEach
                ticks += voiceTicks(stat) ?: 0L
            }
            ticks * 1000L / tick
        }.getOrDefault(0L)

        /**
         * A thread's processor ticks (user and system) from its /proc stat line, if it is one of
         * the voice's; null for any other. (The name is between the brackets, and may hold spaces.)
         */
        fun voiceTicks(stat: String): Long? {
            val open = stat.indexOf('(')
            val close = stat.lastIndexOf(')')
            if (open < 0 || close < open) return null
            if (stat.substring(open + 1, close) != VOICE_THREAD) return null
            val fields = stat.substring(close + 2).split(' ')
            // After the name: state, then 10 more, then user and system time.
            return (fields.getOrNull(11)?.toLongOrNull() ?: return null) + (fields.getOrNull(12)?.toLongOrNull() ?: return null)
        }

        /** The phone's word on its temperature, as it is logged. */
        fun thermalName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }

        /** The line logged for [reading]. */
        fun describe(reading: Reading, threads: Int, steps: Int, inHand: Float, aim: Float, thermal: Int, screenOn: Boolean): String =
            "Listening: ${"%.1f".format(reading.cpuPerSpeechMinute)} s of processor time per minute of speech " +
                "(making speech ${"%.1f".format(reading.makingPerSpeechMinute)} s), over ${reading.speechMs / 1000} s heard; " +
                "$threads threads, $steps steps, ${inHand.roundToInt()} s in hand of ${aim.roundToInt()}, " +
                "thermal status ${thermalName(thermal)}, screen ${if (screenOn) "on" else "off"}"
    }
}
