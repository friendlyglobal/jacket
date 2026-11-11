package io.github.friendlyglobal.jacket.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tidies a voice's sound before it is played. A voice taught from recordings made at home carries
 * a little of the room with it: a rumble under the voice, and a faint noise in its silences. This
 * takes the rumble out and turns the silences down, gently: the quiet between words sinks further,
 * while a word's soft ending is left alone and the voice itself is not touched.
 *
 * Nothing moves in time (the sound keeps its length, sample for sample), so the words still ink
 * in when they are heard. It costs a few thousandths of the time the voice takes to speak.
 */
internal object SpeechCleanup {
    /** Below the deepest voice: what is under this is the room, not the speaker. */
    private const val RUMBLE_HZ = 70.0

    private const val WINDOW_MS = 5

    /** The quiet starts this far below the level of the speech (40 dB: a hundredth of it). */
    private const val QUIET_BELOW_SPEECH = 0.01f

    /** A silence is never turned down by more than this (12 dB: to a quarter). */
    private const val MOST_DB = 12f

    /** After a sound, the level is held this long before it is let down: a word's end is not a silence. */
    private const val HOLD_MS = 80

    /** And it comes back up this long before the next sound, so no word starts under it. */
    private const val AHEAD_MS = 30

    /** How fast the level comes back up, and how slowly it is let down. */
    private const val OPEN_MS = 5f
    private const val CLOSE_MS = 70f

    /** Cleans [samples] where they are, and returns them. */
    fun clean(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || sampleRate <= 0) return samples
        highPass(samples, sampleRate)
        quieten(samples, sampleRate)
        return samples
    }

    /** Takes out what lies below the voice. */
    internal fun highPass(samples: FloatArray, sampleRate: Int) {
        // A second-order Butterworth high-pass, in its usual digital form.
        val w = 2 * PI * RUMBLE_HZ / sampleRate
        val alpha = sin(w) / sqrt(2.0)
        val c = cos(w)
        val a0 = 1 + alpha
        val b0 = (1 + c) / 2 / a0
        val b1 = -(1 + c) / a0
        val b2 = b0
        val a1 = -2 * c / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            samples[i] = y.toFloat()
        }
    }

    /** Turns the silences down: the further under the speech a stretch is, the more, up to [MOST_DB]. */
    internal fun quieten(samples: FloatArray, sampleRate: Int) {
        val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        val count = (samples.size + window - 1) / window
        if (count < 4) return
        val levels = FloatArray(count) { w ->
            val from = w * window
            val to = min(samples.size, from + window)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / (to - from)).toFloat()
        }
        val speech = levels.sortedArray()[(count * 9 / 10).coerceAtMost(count - 1)]
        // Nothing here is loud enough to be speech: nothing to tell the silences from.
        if (speech < 0.002f) return
        val quiet = (speech * QUIET_BELOW_SPEECH).coerceIn(0.0006f, 0.004f)
        val hold = HOLD_MS / WINDOW_MS
        val ahead = AHEAD_MS / WINDOW_MS
        val open = 1f - 0.5f.pow(WINDOW_MS / OPEN_MS)
        val close = 1f - 0.5f.pow(WINDOW_MS / CLOSE_MS)
        val gains = FloatArray(count)
        var now = 0f // in dB, 0 being untouched
        for (w in 0 until count) {
            // A moment is as loud as the loudest thing just before it or about to come.
            var near = 0f
            for (k in max(0, w - hold)..min(count - 1, w + ahead)) near = max(near, levels[k])
            val wanted = if (near >= quiet) 0f else -min(MOST_DB, 20f * log10(quiet / max(near, 1e-7f)))
            now += (wanted - now) * (if (wanted > now) open else close)
            gains[w] = now
        }
        // From one window's level to the next's without a step.
        for (i in samples.indices) {
            val at = (i - window / 2f) / window
            val w = at.toInt().coerceIn(0, count - 1)
            val next = min(count - 1, w + 1)
            val part = (at - w).coerceIn(0f, 1f)
            val db = gains[w] + (gains[next] - gains[w]) * part
            if (db < -0.01f) samples[i] *= 10f.pow(db / 20f)
        }
    }
}
