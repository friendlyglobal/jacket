package io.github.friendlyglobal.jacket.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

/** One spoken piece of a paragraph. Ids look like "token:paragraph:offset:flags". */
data class Utterance(val token: Int, val paragraph: Int, val offset: Int, val first: Boolean, val last: Boolean) {
    companion object {
        fun parse(id: String): Utterance? {
            val parts = id.split(':')
            if (parts.size != 4) return null
            val flags = parts[3].toIntOrNull() ?: return null
            return Utterance(
                token = parts[0].toIntOrNull() ?: return null,
                paragraph = parts[1].toIntOrNull() ?: return null,
                offset = parts[2].toIntOrNull() ?: return null,
                first = flags and 1 != 0,
                last = flags and 2 != 0,
            )
        }
    }
}

/**
 * The phone's text-to-speech engine, with audio focus and "headphones unplugged" handling.
 * All callbacks arrive on the main thread.
 */
class Speaker(context: Context) {
    interface Listener {
        fun onStart(utterance: Utterance) {}

        /** The engine is about to speak characters [start, end) of [utterance]: the current word. */
        fun onRange(utterance: Utterance, start: Int, end: Int) {}

        fun onDone(utterance: Utterance) {}

        /** Speech couldn't continue: no engine, the engine went away, or an utterance failed. */
        fun onFailed() {}

        /** Another app took the audio, or the headphones were unplugged. */
        fun onInterrupted() {}
    }

    var listener: Listener? = null

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 0 = starting, 1 = ready, -1 = no usable engine. */
    private var state = 0
    private val waiting = ArrayDeque<() -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        main.post {
            state = if (status == TextToSpeech.SUCCESS) 1 else -1
            if (state == 1) {
                tts.setAudioAttributes(attributes)
                while (waiting.isNotEmpty()) waiting.removeFirst().invoke()
            } else {
                val wasWaiting = waiting.isNotEmpty()
                waiting.clear()
                if (wasWaiting) fail()
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                main.post { Utterance.parse(id)?.let { listener?.onStart(it) } }
            }

            override fun onDone(id: String) {
                main.post { Utterance.parse(id)?.let { listener?.onDone(it) } }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) {
                main.post { fail() }
            }

            override fun onError(id: String, errorCode: Int) {
                main.post { fail() }
            }

            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
                main.post { Utterance.parse(id)?.let { listener?.onRange(it, start, end) } }
            }
        })
    }

    /** True once it's clear the phone has no working text-to-speech engine. */
    val unavailable: Boolean get() = state == -1

    /**
     * Speaks a paragraph (from character [offset] on). Engines reject long input, so long
     * paragraphs go out as several utterances, cut at sentence or word boundaries.
     */
    fun speak(token: Int, paragraph: Int, offset: Int, text: String, queue: Boolean) = whenReady {
        val pieces = pieces(text, MAX_INPUT)
        pieces.forEachIndexed { index, (start, piece) ->
            val flags = (if (index == 0) 1 else 0) or (if (index == pieces.lastIndex) 2 else 0)
            val result = tts.speak(
                piece,
                if (queue || index > 0) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                null,
                "$token:$paragraph:${offset + start}:$flags",
            )
            if (result != TextToSpeech.SUCCESS) {
                fail()
                return@whenReady
            }
        }
    }

    fun stop() {
        waiting.clear() // anything asked for before the engine was ready is cancelled too
        if (state == 1) tts.stop()
    }

    fun setRate(rate: Float) = whenReady { tts.setSpeechRate(rate) }

    /** Uses the book's language when a voice for it is installed, otherwise the phone's. */
    fun setLanguage(tag: String?) = whenReady {
        val wanted = tag?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag)
        val locale = wanted?.takeIf { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.getDefault()
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
    }

    private fun whenReady(action: () -> Unit) {
        when (state) {
            1 -> action()
            0 -> waiting.addLast(action)
            else -> fail()
        }
    }

    private fun fail() {
        if (listener == null) return // the reader is gone; nothing to tell
        if (state == 1) tts.stop()
        release()
        Toast.makeText(app, "The voice stopped. Check your text-to-speech settings.", Toast.LENGTH_SHORT).show()
        listener?.onFailed()
    }

    // --- Being a good audio citizen -------------------------------------------------------------

    private val audio = app.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        // Speech can't usefully be ducked under navigation prompts and the like: pause instead.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                main.post { listener?.onInterrupted() }
            }
        }
        .build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            listener?.onInterrupted()
        }
    }
    private var holding = false

    /**
     * Takes audio focus and starts listening for headphone unplugs; call before speaking.
     * Returns false if another app (a call, say) won't give up the audio.
     */
    fun acquire(): Boolean {
        if (holding) return true
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        holding = true
        ContextCompat.registerReceiver(
            app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return true
    }

    fun release() {
        if (!holding) return
        holding = false
        audio.abandonAudioFocusRequest(focus)
        runCatching { app.unregisterReceiver(noisy) }
    }

    fun shutdown() {
        release()
        listener = null
        waiting.clear()
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }

    private companion object {
        /** Kept under TextToSpeech.getMaxSpeechInputLength() (4000) with room to spare. */
        const val MAX_INPUT = 3_800
    }
}

/** Splits [text] into pieces of at most [max] characters, preferring sentence, then word, boundaries. */
internal fun pieces(text: String, max: Int): List<Pair<Int, String>> {
    if (text.length <= max) return listOf(0 to text)
    val out = mutableListOf<Pair<Int, String>>()
    var start = 0
    while (start < text.length) {
        var end = (start + max).coerceAtMost(text.length)
        if (end < text.length) {
            val window = text.substring(start, end)
            // End of the last full sentence in the window (delimiter included, so the next piece
            // starts cleanly), else the last space, else a hard cut.
            val sentenceEnd = listOf(". ", "! ", "? ", "; ", "。").maxOf { delimiter ->
                window.lastIndexOf(delimiter).let { if (it < 0) -1 else it + delimiter.length }
            }
            val cut = when {
                sentenceEnd > max / 2 -> sentenceEnd
                window.lastIndexOf(' ') > max / 2 -> window.lastIndexOf(' ') + 1
                else -> window.length
            }
            end = start + cut
        }
        out += start to text.substring(start, end)
        start = end
    }
    return out
}
