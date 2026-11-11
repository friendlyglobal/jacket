package io.github.friendlyglobal.jacket.ui.voices

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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

/**
 * Lets a natural voice be heard before it is chosen: it says a sentence in the listener's
 * language. One voice at a time; anything else that is playing, the book included, is asked to
 * wait while it speaks.
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

    fun stop() {
        job?.cancel()
        job = null
        speech?.close()
        speech = null
        if (playing != null) audio.abandonAudioFocusRequest(focus)
        playing = null
    }
}

@Composable
fun rememberVoicePreviewer(): VoicePreviewer {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val previewer = remember { VoicePreviewer(context, scope) }
    DisposableEffect(previewer) { onDispose { previewer.stop() } }
    return previewer
}
