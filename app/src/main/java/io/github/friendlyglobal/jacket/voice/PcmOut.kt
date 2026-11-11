package io.github.friendlyglobal.jacket.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Plays [Clip]s one after another through a single audio stream, and says when each word and each
 * clip has been heard. A clip is followed by a measured silence, and its "spoken" call comes at
 * the start of that silence: the next clip, queued in answer, lands behind it without a gap.
 *
 * Called from the main thread; calls back on the main thread.
 */
internal class PcmOut(private val sampleRate: Int) {
    class Item(
        val clip: Clip,
        /** Where in the clip to start: not 0 when picking up after a pause. */
        val fromFrame: Int,
        /** Silence after the clip, in frames. */
        val pauseFrames: Int,
        /** Playback pace against the clip's own: 1 unless the pace changed after it was made. */
        val ratio: Float,
        val onWord: (SpokenWord) -> Unit,
        val onSpoken: () -> Unit,
    ) {
        /** Where this stream started the clip: [fromFrame], or later if the stream had to be reopened. */
        internal var from = fromFrame
        internal var streamStart = -1L
        internal var written = 0
        internal var silenceWritten = 0
        internal var nextWord = 0
        internal var spoken = false
        internal var due = 0L
        internal val speechFrames get() = clip.pcm.size - from
        internal val allWritten get() = written >= speechFrames && silenceWritten >= pauseFrames
    }

    /** What was playing when the sound was cut, and how far into its clip it had got. */
    class Cut(val item: Item, val frame: Int)

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("jacket-voice-out").apply { start() }
    private val audio = Handler(thread.looper)
    private val queue = ArrayDeque<Item>()
    private var track: AudioTrack? = null
    private var total = 0L
    private var ratio = 1f
    private var pumping = false
    private var released = false

    /** The phone won't give us a way to make sound at all: nothing queued will ever be heard. */
    var onFailure: () -> Unit = {}
    private val silence = ShortArray(sampleRate / 20)
    private val pump = object : Runnable {
        override fun run() {
            val more = synchronized(lock) { if (released) false else step() }
            if (more) audio.postDelayed(this, PUMP_MS) else synchronized(lock) { pumping = false; if (queue.isNotEmpty() && !released) start() }
        }
    }

    fun play(item: Item) = synchronized(lock) {
        if (released) return@synchronized
        queue.addLast(item)
        start()
    }

    /** Cuts the sound now and forgets what was queued. */
    fun stop(): Cut? = synchronized(lock) {
        // Where the listener really is: what the phone had taken but not yet played is cut with
        // the rest, and must not count as heard, or it would be skipped when the voice goes on.
        val position = heard()
        val current = queue.firstOrNull { it.streamStart >= 0 && !it.spoken } ?: queue.firstOrNull { it.streamStart >= 0 }
        val cut = current?.let { Cut(it, (it.from + (position - it.streamStart)).toInt().coerceIn(it.from, it.clip.pcm.size)) }
        queue.clear()
        // A stream that has been flushed can't be trusted to count from zero again: start afresh.
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
            it.release()
        }
        track = null
        total = 0L
        lastTaken = -1L
        cut
    }

    /** Changes the pace of what is playing right now (see [Item.ratio]). */
    fun setRatio(value: Float) = synchronized(lock) {
        if (queue.none { it.streamStart >= 0 }) return@synchronized
        apply(value)
        // What is in the stream now takes a different time to play out.
        val now = SystemClock.uptimeMillis()
        for (item in queue) {
            if (item.due != 0L) item.due = now + ((item.speechFrames * 1000L / sampleRate) / value.coerceAtLeast(0.25f)).toLong() + STALL_MS
        }
    }

    fun release() {
        stop()
        synchronized(lock) { released = true }
        thread.quitSafely()
    }

    private fun start() {
        if (pumping) return
        pumping = true
        audio.post(pump)
    }

    /** Writes what the stream will take, reports what has been heard. False once there is nothing left to do. */
    private fun step(): Boolean {
        if (queue.isEmpty()) return false
        val out = track ?: runCatching { open() }.getOrElse { error ->
            Log.e(TAG, "Couldn't open an audio stream", error)
            queue.clear()
            main.post { onFailure() }
            return false
        }.also { track = it }
        // Feed the stream, in order.
        for (item in queue) {
            if (item.allWritten) continue
            if (item.streamStart < 0) {
                item.streamStart = total
                apply(item.ratio)
            }
            val pcm = item.clip.pcm
            var blocked = false
            while (item.written < item.speechFrames) {
                val n = out.write(pcm, item.from + item.written, item.speechFrames - item.written, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) return reopen(n)
                if (n == 0) {
                    blocked = true
                    break
                }
                item.written += n
                total += n
            }
            while (!blocked && item.silenceWritten < item.pauseFrames) {
                val n = out.write(silence, 0, minOf(silence.size, item.pauseFrames - item.silenceWritten), AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) return reopen(n)
                if (n == 0) {
                    blocked = true
                    break
                }
                item.silenceWritten += n
                total += n
            }
            if (item.due == 0L) {
                // If the stream never reports getting there, the clock says when it must have.
                item.due = SystemClock.uptimeMillis() + ((item.speechFrames * 1000L / sampleRate) / item.ratio.coerceAtLeast(0.25f)).toLong() + STALL_MS
            }
            if (blocked) break
        }
        // Report what has been heard.
        val position = heard()
        val now = SystemClock.uptimeMillis()
        for (item in queue) {
            if (item.streamStart < 0) break
            val at = item.from + (position - item.streamStart)
            val words = item.clip.words
            while (item.nextWord < words.size && words[item.nextWord].frame <= at) {
                val word = words[item.nextWord++]
                // Words before the place we picked up from have been heard already.
                if (word.frame >= item.from || item.nextWord == words.size || words[item.nextWord].frame > item.from) {
                    main.post { item.onWord(word) }
                }
            }
            val heard = at >= item.clip.pcm.size
            if (!item.spoken && (heard || (item.written >= item.speechFrames && now >= item.due))) {
                if (!heard) Log.w(TAG, "Audio position stalled; moving on by the clock")
                item.spoken = true
                main.post(item.onSpoken)
            }
            if (!item.spoken) break
        }
        while (queue.isNotEmpty() && queue.first().spoken && queue.first().allWritten) queue.removeFirst()
        return queue.isNotEmpty()
    }

    /**
     * The stream died under us (the sound went to another device, say: headphones, a speaker).
     * Open a new one and carry on from what had been heard.
     */
    private fun reopen(error: Int): Boolean {
        Log.w(TAG, "Audio stream lost ($error); opening another")
        val position = heard()
        for (item in queue) {
            if (item.streamStart >= 0 && !item.spoken) {
                item.from = (item.from + (position - item.streamStart)).toInt().coerceIn(item.from, item.clip.pcm.size)
            } else if (item.streamStart >= 0) {
                // Its words were all heard; only what's left of its breath is owed.
                item.from = item.clip.pcm.size
            }
            item.streamStart = -1L
            item.written = 0
            item.due = 0L
        }
        track?.let { runCatching { it.release() } }
        track = null
        total = 0L
        lastTaken = -1L
        return true
    }

    /** Frames the phone has taken from the stream since it opened. */
    private fun taken(): Long = track?.let { it.playbackHeadPosition.toLong() and 0xFFFFFFFFL } ?: 0L

    private val stamp = AudioTimestamp()
    private var lastTaken = -1L
    private var lastTakenAt = 0L

    /**
     * Frames the listener has heard since the stream opened. That is fewer than the phone has
     * [taken]: its mixer and its speaker (or a Bluetooth link) hold the sound a moment before it
     * is out. Calling a sentence heard when it has only been taken would let its last sounds be
     * cut by whatever happens next. Where the phone can't say, or says something unlikely, the
     * lag is taken to be at most a quarter of a second: less than the pause after a sentence, so
     * the next one is always asked for in time to follow without a gap.
     */
    private fun heard(): Long {
        val out = track ?: return 0L
        val taken = taken()
        val clock = SystemClock.uptimeMillis()
        if (taken != lastTaken) {
            lastTaken = taken
            lastTakenAt = clock
        } else if (clock - lastTakenAt > MOST_LAG_MS) {
            // The stream ran dry a while ago: everything it was given has been heard.
            return taken
        }
        val earliest = (taken - sampleRate * MOST_LAG_MS / 1000).coerceAtLeast(0L)
        if (!runCatching { out.getTimestamp(stamp) }.getOrDefault(false)) return earliest
        val since = (System.nanoTime() - stamp.nanoTime).coerceIn(0L, 500_000_000L)
        val now = stamp.framePosition + (since * sampleRate.toDouble() * ratio / 1e9).toLong()
        return now.coerceIn(earliest, taken)
    }

    private fun apply(value: Float) {
        val out = track ?: return
        if (value == ratio) return
        ratio = value
        runCatching { out.playbackParams = PlaybackParams().allowDefaults().setSpeed(value.coerceIn(0.25f, 4f)).setPitch(1f) }
            .onFailure { Log.w(TAG, "Couldn't change the pace of the stream", it) }
    }

    private fun open(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // A quarter of a second: enough never to run dry between pumps, little enough that
        // pausing and changing pace are felt at once.
        val size = maxOf(minimum, sampleRate / 4 * 2)
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Start on the first words rather than wait for the buffer to fill.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { out.setStartThresholdInFrames(startThreshold()) }
        ratio = 1f
        out.play()
        return out
    }

    /** A twenty-fifth of a second of sound is enough to start on. */
    private fun startThreshold(): Int = maxOf(1, sampleRate / 25)

    private companion object {
        const val TAG = "JacketVoice"
        const val PUMP_MS = 12L
        const val STALL_MS = 1_500L

        /** The most the sound is taken to lag behind what the phone has accepted. */
        const val MOST_LAG_MS = 250L
    }
}
