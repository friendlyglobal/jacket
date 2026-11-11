package io.github.friendlyglobal.jacket.voice

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit

/*
 * A natural voice as a Readium text-to-speech engine. Readium's read-aloud navigator drives it
 * exactly as it drives the phone's own voice, so everything built on that navigator keeps working
 * unchanged: the Media3 session, lock-screen and headphone controls, the sleep timer, skipping by
 * sentence, the player on a closed cover.
 */

typealias NaturalTtsNavigator = TtsNavigator<NaturalTtsSettings, NaturalTtsPreferences, NaturalTtsEngine.Error, NaturalTtsEngine.Voice>

typealias NaturalTtsNavigatorFactory =
    TtsNavigatorFactory<NaturalTtsSettings, NaturalTtsPreferences, NaturalTtsPreferencesEditor, NaturalTtsEngine.Error, NaturalTtsEngine.Voice>

data class NaturalTtsPreferences(
    override val language: Language? = null,
    val speed: Double? = null,
) : TtsEngine.Preferences<NaturalTtsPreferences> {
    override fun plus(other: NaturalTtsPreferences) =
        NaturalTtsPreferences(language = other.language ?: language, speed = other.speed ?: speed)
}

data class NaturalTtsSettings(
    override val language: Language,
    override val overrideContentLanguage: Boolean,
    val speed: Double,
) : TtsEngine.Settings

class NaturalTtsPreferencesEditor(initial: NaturalTtsPreferences) : PreferencesEditor<NaturalTtsPreferences> {
    override var preferences: NaturalTtsPreferences = initial
        private set

    override fun clear() {
        preferences = NaturalTtsPreferences()
    }
}

/**
 * Remembers the sentences Readium has cut the book into, in the order it will ask for them, so
 * the engine can make the next ones while it speaks. Readium asks for one sentence at a time and
 * says nothing of what follows; but it cuts each paragraph up a moment before it reads it, with
 * the tokenizer this wraps.
 */
class SentenceLookahead {
    private val paragraphs = ArrayDeque<List<String>>()

    /** Wraps Readium's sentence tokenizer for [language]. */
    fun tokenizer(language: Language?): TextTokenizer {
        val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, language)
        return TextTokenizer { text ->
            joinTitles(text, sentences.tokenize(text)).also { ranges -> note(ranges.map { text.substring(it) }) }
        }
    }

    @Synchronized
    internal fun note(sentences: List<String>) {
        if (sentences.isEmpty()) return
        paragraphs.addLast(sentences)
        while (paragraphs.size > KEPT) paragraphs.removeFirst()
    }

    /** The sentences that follow [sentence], nearest first: the rest of its paragraph, then the next one's. */
    @Synchronized
    fun after(sentence: String, count: Int = 2): List<String> {
        val at = paragraphs.indexOfLast { sentence in it }
        if (at < 0) return emptyList()
        val rest = paragraphs[at].let { it.subList(it.lastIndexOf(sentence) + 1, it.size) }
        return (rest + paragraphs.getOrNull(at + 1).orEmpty()).take(count)
    }

    /** True if [sentence] closes its paragraph: the voice takes a longer breath after it. */
    @Synchronized
    fun endsParagraph(sentence: String): Boolean = paragraphs.lastOrNull { sentence in it }?.last() == sentence

    private companion object {
        const val KEPT = 16
    }
}

/** Readium's [TtsEngine], spoken by a [NaturalSpeech]. Called on the main thread, like all of Readium's read-aloud. */
class NaturalTtsEngine internal constructor(
    private val speech: NaturalSpeech,
    private val pack: VoicePack,
    private val voice: PackVoice,
    private val lookahead: SentenceLookahead,
    bookLanguage: Language,
    /** The listener chose the language to read in: what the book says of itself, passage by passage, is set aside. */
    private val forced: Boolean,
    initial: NaturalTtsPreferences,
) : TtsEngine<NaturalTtsSettings, NaturalTtsPreferences, NaturalTtsEngine.Error, NaturalTtsEngine.Voice> {

    /** The voice couldn't make sound for the text. */
    object Error : TtsEngine.Error {
        override val message = "The natural voice stopped."
        override val cause: org.readium.r2.shared.util.Error? = null
    }

    class Voice(val id: String, override val language: Language) : TtsEngine.Voice

    private val fallback = bookLanguage
    private val _settings = MutableStateFlow(resolve(initial))
    private var listener: TtsEngine.Listener<Error>? = null
    private var speaking: TtsEngine.RequestId? = null

    override val settings: StateFlow<NaturalTtsSettings> = _settings.asStateFlow()

    override val voices: Set<Voice> = pack.languages.mapTo(LinkedHashSet()) { Voice(voice.id, Language(it)) }

    init {
        speech.speed = _settings.value.speed.toFloat()
        speech.listener = object : NaturalSpeech.Listener {
            override fun onRange(id: Any, range: IntRange) {
                (id as? TtsEngine.RequestId)?.let { listener?.onRange(it, range) }
            }

            override fun onDone(id: Any) {
                if (speaking == id) speaking = null
                (id as? TtsEngine.RequestId)?.let { listener?.onDone(it) }
            }

            override fun onError(id: Any) {
                if (speaking == id) speaking = null
                (id as? TtsEngine.RequestId)?.let { listener?.onError(it, Error) }
            }
        }
    }

    override fun submitPreferences(preferences: NaturalTtsPreferences) {
        val next = resolve(preferences)
        _settings.value = next
        // Heard at once; Readium then asks for the sentence again, and it carries on from where it was.
        speech.speed = next.speed.toFloat()
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        speaking = requestId
        listener?.onStart(requestId)
        // A passage in another language the voice knows is read in that language.
        val spoken = language?.code?.takeUnless { forced }?.let(::languageKey)?.takeIf { it in pack.languages }
            ?: languageKey(_settings.value.language.code).takeIf { it in pack.languages }
            ?: "en".takeIf { it in pack.languages }
            ?: pack.languages.first()
        speech.speak(requestId, text, spoken, lookahead.endsParagraph(text), lookahead.after(text))
    }

    override fun stop() {
        speech.stop()
        speaking?.let { listener?.onInterrupted(it) }
        speaking = null
    }

    override fun setListener(listener: TtsEngine.Listener<Error>?) {
        this.listener = listener
    }

    override fun close() {
        listener = null
        speech.close()
    }

    private fun resolve(preferences: NaturalTtsPreferences) = NaturalTtsSettings(
        language = preferences.language ?: fallback,
        overrideContentLanguage = forced,
        speed = (preferences.speed ?: 1.0).coerceIn(0.5, 3.0),
    )
}

/** Makes a [NaturalTtsEngine] for a book: [ref] is the voice chosen for it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NaturalTtsEngineProvider(
    private val voices: Voices,
    private val ref: VoiceRef,
    private val lookahead: SentenceLookahead,
    /** The language to read in when the book's own isn't to be trusted. */
    private val language: String?,
) : TtsEngineProvider<NaturalTtsSettings, NaturalTtsPreferences, NaturalTtsPreferencesEditor, NaturalTtsEngine.Error, NaturalTtsEngine.Voice> {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: NaturalTtsPreferences,
    ): Try<TtsEngine<NaturalTtsSettings, NaturalTtsPreferences, NaturalTtsEngine.Error, NaturalTtsEngine.Voice>, org.readium.r2.shared.util.Error> {
        val pack = voices.pack(ref.pack)
        val voice = pack?.voice(ref.voice)
        val speech = voices.open(ref)
        if (pack == null || voice == null || speech == null) return Try.failure(DebugError("The natural voice couldn't be loaded."))
        val book = Language(language ?: publication.metadata.language?.code ?: pack.languages.first())
        return Try.success(NaturalTtsEngine(speech, pack, voice, lookahead, book, forced = language != null, initialPreferences))
    }

    override fun createPreferencesEditor(publication: Publication, initialPreferences: NaturalTtsPreferences) =
        NaturalTtsPreferencesEditor(initialPreferences)

    override fun createEmptyPreferences() = NaturalTtsPreferences()

    override fun getPlaybackParameters(settings: NaturalTtsSettings) = PlaybackParameters(settings.speed.toFloat())

    override fun updatePlaybackParameters(previousPreferences: NaturalTtsPreferences, playbackParameters: PlaybackParameters) =
        previousPreferences.copy(speed = playbackParameters.speed.toDouble())

    override fun mapEngineError(error: NaturalTtsEngine.Error) =
        PlaybackException(error.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
}
