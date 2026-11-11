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
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.voice.NaturalSpeech
import io.github.friendlyglobal.jacket.voice.VoiceRef
import io.github.friendlyglobal.jacket.voice.Voices
import io.github.friendlyglobal.jacket.voice.localeOf
import io.github.friendlyglobal.jacket.voice.sentencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * A voice that reads plain-text books aloud, a paragraph at a time, with audio focus and
 * "headphones unplugged" handling. Either the phone's own text-to-speech ([SystemSpeaker]) or a
 * natural voice ([NaturalSpeaker]). All callbacks arrive on the main thread.
 */
abstract class Speaker(context: Context) {
    interface Listener {
        fun onStart(utterance: Utterance) {}

        /** The voice is about to speak characters [start, end) of [utterance]: the current word. */
        fun onRange(utterance: Utterance, start: Int, end: Int) {}

        fun onDone(utterance: Utterance) {}

        /** Speech couldn't continue: no engine, the engine went away, or an utterance failed. */
        fun onFailed() {}

        /** Another app took the audio, or the headphones were unplugged. */
        fun onInterrupted() {}
    }

    var listener: Listener? = null

    protected val app: Context = context.applicationContext
    protected val main = Handler(Looper.getMainLooper())
    protected val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** True once it's clear this voice can't speak. */
    abstract val unavailable: Boolean

    /** The voice takes a moment before its first word (it has a model to load): the player shows it getting ready. */
    open val slowToStart: Boolean get() = false

    /**
     * Speaks a paragraph (from character [offset] on): at once, or with [queue] after what is
     * already being spoken.
     */
    abstract fun speak(token: Int, paragraph: Int, offset: Int, text: String, queue: Boolean)

    abstract fun stop()

    abstract fun setRate(rate: Float)

    /** Reads in the language [tag] when the voice can, otherwise the phone's. */
    abstract fun setLanguage(tag: String?)

    /** Lets go of the voice for good. */
    open fun shutdown() {
        release()
        listener = null
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
}

/** The phone's text-to-speech engine. */
class SystemSpeaker(context: Context) : Speaker(context) {
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
    override val unavailable: Boolean get() = state == -1

    /**
     * Speaks a paragraph (from character [offset] on). Engines reject long input, so long
     * paragraphs go out as several utterances, cut at sentence or word boundaries.
     */
    override fun speak(token: Int, paragraph: Int, offset: Int, text: String, queue: Boolean) = whenReady {
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

    override fun stop() {
        waiting.clear() // anything asked for before the engine was ready is cancelled too
        if (state == 1) tts.stop()
    }

    override fun setRate(rate: Float) = whenReady { tts.setSpeechRate(rate) }

    /** Uses the book's language when a voice for it is installed, otherwise the phone's. */
    override fun setLanguage(tag: String?) = whenReady {
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
        Toast.makeText(app, R.string.listen_voice_stopped, Toast.LENGTH_SHORT).show()
        listener?.onFailed()
    }

    override fun shutdown() {
        super.shutdown()
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

/**
 * A natural voice (see the voice package) reading plain text. A paragraph goes to it a sentence
 * at a time, each sentence its own [Utterance]; the sound for the next ones is made while one
 * plays, and a paragraph asked for again from inside the sentence it was stopped in (a pause, a
 * change of pace) carries on from that very place.
 */
class NaturalSpeaker(context: Context, private val voices: Voices, private val ref: VoiceRef) : Speaker(context) {
    private class Line(val utterance: Utterance, val text: String, val paragraph: Int)

    /** The sentence that was being spoken when the voice was stopped. */
    private class Stopped(val paragraph: Int, val offset: Int, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<Line>()
    private var speech: NaturalSpeech? = null
    private var current: Line? = null
    private var stopped: Stopped? = null
    private var language = ""
    private var rate = 1f
    private var failed = false

    override val unavailable: Boolean get() = failed || voices.pack(ref.pack) == null
    override val slowToStart: Boolean get() = true

    init {
        // The model takes a moment to load: whatever is asked for meanwhile waits in the queue.
        scope.launch {
            val opened = voices.open(ref)
            if (opened == null) {
                failed = true
                if (queue.isNotEmpty() || current != null) fail()
                return@launch
            }
            opened.speed = rate
            opened.listener = object : NaturalSpeech.Listener {
                override fun onRange(id: Any, range: IntRange) {
                    (id as? Line)?.takeIf { it === current }?.let { listener?.onRange(it.utterance, range.first, range.last + 1) }
                }

                override fun onDone(id: Any) {
                    val line = (id as? Line)?.takeIf { it === current } ?: return
                    current = null
                    listener?.onDone(line.utterance)
                    next()
                }

                override fun onError(id: Any) = fail()
            }
            speech = opened
            next()
        }
    }

    override fun speak(token: Int, paragraph: Int, offset: Int, text: String, queue: Boolean) {
        if (failed) return fail()
        if (!queue) {
            halt()
            this.queue.clear()
        }
        val locale = localeOf(language)
        val was = stopped?.takeIf { !queue && it.paragraph == paragraph && offset >= it.offset && offset < it.offset + it.text.length }
        stopped = null
        // Asked to go on from inside the sentence that was stopped: that whole sentence again, as
        // the voice knows it, and the voice finds its own place in it.
        val lead = was?.let { it.offset + it.text.length - offset }?.coerceIn(0, text.length) ?: 0
        val sentences = sentencesOf(text.substring(lead), locale).map { (it.first + lead)..(it.last + lead) }
        val lines = ArrayList<Line>()
        if (was != null) lines += Line(Utterance(token, paragraph, was.offset, first = true, last = sentences.isEmpty()), was.text, paragraph)
        sentences.forEachIndexed { i, range ->
            val utterance = Utterance(token, paragraph, offset + range.first, first = lines.isEmpty(), last = i == sentences.lastIndex)
            lines += Line(utterance, text.substring(range), paragraph)
        }
        this.queue.addAll(lines)
        if (current == null) next()
    }

    override fun stop() {
        halt()
        queue.clear()
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        speech?.speed = rate
    }

    override fun setLanguage(tag: String?) {
        val pack = voices.pack(ref.pack)
        language = pack?.let { voices.languageFor(it, tag) }.orEmpty()
    }

    override fun shutdown() {
        super.shutdown()
        scope.cancel()
        queue.clear()
        current = null
        speech?.close()
        speech = null
    }

    /** Cuts the voice, remembering the sentence it was in. */
    private fun halt() {
        current?.let { stopped = Stopped(it.paragraph, it.utterance.offset, it.text) }
        current = null
        speech?.stop()
    }

    private fun next() {
        val speech = speech ?: return
        if (current != null) return
        val line = queue.removeFirstOrNull() ?: return
        current = line
        listener?.onStart(line.utterance)
        // The reader may have answered the start by stopping, or by asking for something else.
        if (current !== line) return
        speech.speak(line, line.text, language, endsParagraph = line.utterance.last, upcoming = queue.take(2).map { it.text })
    }

    private fun fail() {
        if (listener == null) return
        queue.clear()
        current = null
        speech?.stop()
        release()
        Toast.makeText(app, R.string.voice_stopped, Toast.LENGTH_SHORT).show()
        listener?.onFailed()
    }
}
