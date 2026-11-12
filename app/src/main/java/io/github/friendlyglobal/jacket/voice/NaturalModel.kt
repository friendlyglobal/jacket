package io.github.friendlyglobal.jacket.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.friendlyglobal.jacket.voice.ptbr.PtBrOldSpelling
import io.github.friendlyglobal.jacket.voice.ptbr.PtBrPhonemes
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A pack's model in memory, run by sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx) on the
 * phone's own processor. Its files are read straight from the installed app. Everything here
 * blocks, and belongs on [ModelHost]'s thread.
 */
internal class NaturalModel(context: Context, val pack: VoicePack, threads: Int) {
    private val tts: OfflineTts
    val sampleRate: Int

    /** A voice that reads sounds rather than letters: Jacket writes the sounds for it (see voice/ptbr). */
    private val readsSounds = pack.engine == ENGINE_PIPER

    init {
        val assets = context.assets
        val present = assets.list(pack.folder).orEmpty().toSet()
        val missing = pack.files.values.filterNot { it in present }
        require(missing.isEmpty()) { "Voice files missing from the app: ${missing.joinToString()}" }
        fun path(role: String): String = pack.files[role]?.let { "${pack.folder}/$it" }.orEmpty()
        val model = if (readsSounds) {
            // No dictionary and no data folder: the sounds come from Jacket, ready-made.
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = path("model"), tokens = path("tokens"), lexicon = "", dataDir = ""),
                numThreads = threads,
                provider = "cpu",
            )
        } else {
            OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = path("durationPredictor"),
                    textEncoder = path("textEncoder"),
                    vectorEstimator = path("vectorEstimator"),
                    vocoder = path("vocoder"),
                    ttsJson = path("ttsJson"),
                    unicodeIndexer = path("unicodeIndexer"),
                    voiceStyle = path("voiceStyle"),
                ),
                numThreads = threads,
                provider = "cpu",
            )
        }
        tts = OfflineTts(assetManager = assets, config = OfflineTtsConfig(model = model, maxNumSentences = 1))
        sampleRate = tts.sampleRate()
    }

    /**
     * Speaks [text]: samples between -1 and 1, or null if it was [cancelled] on the way or the
     * model had nothing to say. [steps] trades quality for speed (Supertonic refines its sound
     * that many times); [speed] is the pace, made by the model rather than by stretching.
     */
    fun synthesize(text: String, speaker: Int, language: String, speed: Float, steps: Int, cancelled: () -> Boolean): FloatArray? {
        if (readsSounds) return speakSounds(text, speaker, speed, cancelled)
        val config = GenerationConfig(
            // "1×" is the pace the voices were made to speak at, a touch brisker than the model's 1.0.
            speed = speed * NATURAL_PACE,
            sid = speaker,
            numSteps = steps,
            extra = mapOf("lang" to language),
        )
        val audio = tts.generateWithConfigAndCallback(text, config, KeepGoing(cancelled))
        return audio.samples.takeIf { it.isNotEmpty() && !cancelled() }
    }

    /** The Brazilian voice: [text] is turned into its sounds here, and the voice says them. */
    private fun speakSounds(text: String, speaker: Int, speed: Float, cancelled: () -> Boolean): FloatArray? {
        // A book in the old spelling stays in it: once seen, it is expected for a good while after.
        if (PtBrOldSpelling.looksOld(text)) sinceOldSpelling = 0 else if (sinceOldSpelling < Int.MAX_VALUE) sinceOldSpelling++
        val sounds = PtBrPhonemes.codepoints(text, oldSpelling = sinceOldSpelling < FORGET_OLD_SPELLING_AFTER)
        // Nothing to say (a row of asterisks): a moment of quiet, not a failure.
        if (sounds.isEmpty()) return FloatArray(sampleRate / 10)
        val config = GenerationConfig(
            speed = speed * BRAZILIAN_PACE,
            sid = speaker,
            // The pauses at commas and full stops are the voice's own: left as it makes them.
            silenceScale = 1f,
            phonemeCodepoints = sounds,
        )
        val audio = tts.generateWithConfigAndCallback("", config, KeepGoing(cancelled))
        // This voice learnt a little of the room it was recorded in: its silences are tidied.
        return audio.samples.takeIf { it.isNotEmpty() && !cancelled() }?.let { SpeechCleanup.clean(it, sampleRate) }
    }

    fun release() = tts.release()

    private companion object {
        /**
         * How many sentences have gone by since one in the old spelling ("elle", "theatro"). Kept
         * for as long as the app runs, not with the model: a pause lets the model go, but the
         * book is still the same book. Touched on the voice thread only.
         */
        var sinceOldSpelling = Int.MAX_VALUE

        /** Supertonic's own default pace: at a flat 1.0 its voices drag a little. */
        const val NATURAL_PACE = 1.05f

        /** The Brazilian voice reads a little slower than the others at its own 1.0: this evens them. */
        const val BRAZILIAN_PACE = 1.08f

        /** After this many sentences in today's spelling, a book is no longer taken for an old one. */
        const val FORGET_OLD_SPELLING_AFTER = 60
    }
}

/**
 * What the engine asks after each stretch of sound it makes: 1 to go on, 0 to stop. Its native
 * code finds `invoke` by that exact name and signature, which a lambda doesn't keep once R8 has
 * been at it: hence a class of its own, kept whole in proguard-rules.pro.
 */
internal class KeepGoing(private val cancelled: () -> Boolean) : Function1<FloatArray, Int> {
    override fun invoke(samples: FloatArray): Int = if (cancelled()) 0 else 1
}

/**
 * The one place models are loaded and run: a single thread, so the phone works on one sentence at
 * a time, and one model in memory, shared by everything that speaks (the reader, a preview). It
 * is let go a little after its last user leaves.
 */
internal class ModelHost(private val context: Context) {
    private class Entry(val model: NaturalModel) {
        var users = 0
    }

    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "jacket-voice").apply { isDaemon = true } }
    private val thread = executor.asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())

    /** Touched on the voice thread only. */
    private val entries = ArrayList<Entry>()
    private val unload = Runnable { executor.execute(::dropIdle) }

    /** Loads [pack]'s model (or hands back the one in memory). Throws if it can't be read. */
    suspend fun acquire(pack: VoicePack): NaturalModel = withContext(thread) {
        main.removeCallbacks(unload)
        val entry = entries.firstOrNull { it.model.pack.id == pack.id } ?: run {
            dropIdle()
            val started = SystemClock.elapsedRealtime()
            val threads = threads()
            val model = NaturalModel(context, pack, threads)
            // (How many cores it was given is kept for as long as it is in memory: fewer in battery saver.)
            Log.i(TAG, "Loaded ${pack.id} in ${SystemClock.elapsedRealtime() - started} ms, on $threads threads")
            Entry(model).also(entries::add)
        }
        entry.users++
        entry.model
    }

    fun release(model: NaturalModel) {
        executor.execute {
            val entry = entries.firstOrNull { it.model === model } ?: return@execute
            entry.users = (entry.users - 1).coerceAtLeast(0)
            // Kept a little while: pausing and playing again shouldn't mean loading it again.
            if (entry.users == 0) main.postDelayed(unload, KEEP_MS)
        }
    }

    /** Runs [work] on the voice thread. */
    fun run(work: () -> Unit) = executor.execute(work)

    private fun dropIdle() {
        val idle = entries.filter { it.users == 0 }
        entries.removeAll(idle)
        idle.forEach { runCatching { it.model.release() } }
    }

    /**
     * How many processor cores the voice may use. Half of them, four at most: phones pair fast
     * cores with slow ones, and spilling onto the slow ones makes the voice slower, not faster.
     * In battery saver, two.
     */
    private fun threads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val saving = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        return if (saving) 2.coerceAtMost(cores) else (cores / 2).coerceIn(1, 4)
    }

    private companion object {
        const val TAG = "JacketVoice"
        const val KEEP_MS = 45_000L
    }
}

/** The locale whose rules split [language]'s text into words and sentences. */
internal fun localeOf(language: String?): Locale =
    language?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
