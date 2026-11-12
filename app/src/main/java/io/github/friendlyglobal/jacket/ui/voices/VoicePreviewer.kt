package io.github.friendlyglobal.jacket.ui.voices

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.friendlyglobal.jacket.voice.NaturalSpeech
import io.github.friendlyglobal.jacket.voice.VoiceRef
import io.github.friendlyglobal.jacket.voices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lets a natural voice be heard before it is chosen: it says a sentence in the listener's
 * language. And lets a word be heard the way it has just been written for the voice ("Say it this
 * way"), in whichever voice reads the book. One thing at a time; anything else that is playing,
 * the book included, is asked to wait while it speaks.
 */
@Stable
class VoicePreviewer(context: Context, private val scope: CoroutineScope) {
    /** The voice being heard ("pack/voice"), or null. */
    var playing by mutableStateOf<String?>(null)
        private set

    private val voices = context.voices
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()
    private var speech: NaturalSpeech? = null
    private var job: Job? = null
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The phone's own voice, while it is the one saying a word. */
    private var phone: TextToSpeech? = null

    /** Says a sentence in [language] with [ref]; pressed again while it speaks, stops it. */
    fun toggle(ref: VoiceRef, language: String?) {
        val key = ref.toString()
        if (playing == key) return stop()
        stop()
        val pack = voices.pack(ref.pack) ?: return
        val spoken = voices.languageFor(pack, language)
        val text = pack.samples[spoken] ?: pack.samples["en"] ?: return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        playing = key
        job = scope.launch {
            val opened = voices.open(ref)
            if (opened == null || playing != key) {
                opened?.close()
                if (playing == key) stop()
                return@launch
            }
            speech = opened
            opened.listener = object : NaturalSpeech.Listener {
                override fun onRange(id: Any, range: IntRange) {}
                override fun onDone(id: Any) = stop()
                override fun onError(id: Any) = stop()
            }
            opened.speak(key, text, spoken)
        }
    }

    /**
     * Says [text] in [language] with the natural voice [ref], or with the phone's own voice when
     * the book is read by that (null): how a word sounds, written the way it is to be said.
     * Pressed again while it speaks, stops it.
     */
    fun say(ref: VoiceRef?, language: String?, text: String) {
        if (playing == SAYING) return stop()
        stop()
        if (text.isBlank()) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        playing = SAYING
        val pack = ref?.let { voices.pack(it.pack) }
        if (ref == null || pack == null) return sayWithPhone(language, text)
        job = scope.launch {
            val opened = voices.open(ref)
            if (playing != SAYING) {
                opened?.close()
                return@launch
            }
            // The natural voice can't be loaded just now: the phone's will say it.
            if (opened == null) return@launch sayWithPhone(language, text)
            speech = opened
            opened.listener = object : NaturalSpeech.Listener {
                override fun onRange(id: Any, range: IntRange) {}
                override fun onDone(id: Any) = stop()
                override fun onError(id: Any) = stop()
            }
            opened.speak(SAYING, text, voices.languageFor(pack, language))
        }
    }

    private fun sayWithPhone(language: String?, text: String) {
        val made = arrayOfNulls<TextToSpeech>(1)
        // (Answered on the main thread a moment later, whatever thread the phone's voice says it on.)
        val voice = TextToSpeech(app) { status -> main.post { made[0]?.let { phoneReady(it, status == TextToSpeech.SUCCESS, language, text) } } }
        made[0] = voice
        phone = voice
    }

    private fun phoneReady(voice: TextToSpeech, ready: Boolean, language: String?, text: String) {
        // Stopped, or asked for something else, while the phone's voice was starting.
        if (phone !== voice) return
        if (!ready || playing != SAYING) return stop()
        language?.let { tag -> runCatching { voice.language = Locale.forLanguageTag(tag) } }
        voice.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = ended()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = ended()

            override fun onError(utteranceId: String?, errorCode: Int) = ended()

            // Told on the voice's own thread.
            private fun ended() {
                main.post { if (phone === voice) stop() }
            }
        })
        if (voice.speak(text, TextToSpeech.QUEUE_FLUSH, null, SAYING) != TextToSpeech.SUCCESS) stop()
    }

    fun stop() {
        job?.cancel()
        job = null
        speech?.close()
        speech = null
        phone?.let { voice ->
            runCatching {
                voice.stop()
                voice.shutdown()
            }
        }
        phone = null
        if (playing != null) audio.abandonAudioFocusRequest(focus)
        playing = null
    }
}

/** What [VoicePreviewer.playing] holds while a word is being said, rather than a voice's sample. */
const val SAYING = "say"

@Composable
fun rememberVoicePreviewer(): VoicePreviewer {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val previewer = remember { VoicePreviewer(context, scope) }
    DisposableEffect(previewer) { onDispose { previewer.stop() } }
    return previewer
}
