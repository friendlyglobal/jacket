package io.github.friendlyglobal.jacket.voice

import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

/*
 * "Say it this way", for whichever voice reads: Readium hands a voice each sentence as the page
 * has it, and is told back which of its characters are being spoken. Here the sentence is given to
 * the voice as it is to be said (see [SpeechEdits]), and what the voice reports of that is told
 * back as the place on the page. Nothing else of Readium's read-aloud knows the difference: the
 * highlight, the saved place and the closed cover's line all go by the page's words, as before.
 */

/** A voice that is given each sentence through [edits], and tells its place by the page's words. */
class SpokenEngine<S : TtsEngine.Settings, P : TtsEngine.Preferences<P>, E : TtsEngine.Error, V : TtsEngine.Voice>(
    private val engine: TtsEngine<S, P, E, V>,
    private val edits: SpeechEdits,
) : TtsEngine<S, P, E, V> by engine {
    /** The sentences on their way to the voice or being spoken, by the request they were asked with. */
    private val saying = HashMap<TtsEngine.RequestId, SpokenText>()

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        val spoken = edits.spoken(text)
        synchronized(saying) {
            // (Readium asks for one sentence at a time; anything left from a voice that never answered goes.)
            if (saying.size > 8) saying.clear()
            if (spoken.changed) saying[requestId] = spoken else saying.remove(requestId)
        }
        engine.speak(requestId, spoken.spoken, language)
    }

    override fun setListener(listener: TtsEngine.Listener<E>?) {
        engine.setListener(listener?.let(::OnThePage))
    }

    private inner class OnThePage(private val listener: TtsEngine.Listener<E>) : TtsEngine.Listener<E> {
        override fun onStart(requestId: TtsEngine.RequestId) = listener.onStart(requestId)

        override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) {
            val spoken = synchronized(saying) { saying[requestId] }
            listener.onRange(requestId, spoken?.toPage(range) ?: range)
        }

        override fun onInterrupted(requestId: TtsEngine.RequestId) {
            forget(requestId)
            listener.onInterrupted(requestId)
        }

        override fun onFlushed(requestId: TtsEngine.RequestId) {
            forget(requestId)
            listener.onFlushed(requestId)
        }

        override fun onDone(requestId: TtsEngine.RequestId) {
            forget(requestId)
            listener.onDone(requestId)
        }

        override fun onError(requestId: TtsEngine.RequestId, error: E) {
            forget(requestId)
            listener.onError(requestId, error)
        }

        private fun forget(requestId: TtsEngine.RequestId) {
            synchronized(saying) { saying.remove(requestId) }
        }
    }
}

/** Makes [provider]'s voice, read through [edits]. Everything else is the provider's own. */
class SpokenEngineProvider<S : TtsEngine.Settings, P : TtsEngine.Preferences<P>, D : PreferencesEditor<P>, E : TtsEngine.Error, V : TtsEngine.Voice>(
    private val provider: TtsEngineProvider<S, P, D, E, V>,
    private val edits: SpeechEdits,
) : TtsEngineProvider<S, P, D, E, V> by provider {
    override suspend fun createEngine(publication: Publication, initialPreferences: P): Try<TtsEngine<S, P, E, V>, Error> =
        provider.createEngine(publication, initialPreferences).map { SpokenEngine(it, edits) }
}

/** Readium's sentences for a voice that is read through [edits]: a note's number is set aside before they are cut. */
fun spokenSentences(edits: SpeechEdits): (Language?) -> TextTokenizer = { language ->
    val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, language)
    TextTokenizer { text -> edits.sentences(text, sentences::tokenize) }
}
