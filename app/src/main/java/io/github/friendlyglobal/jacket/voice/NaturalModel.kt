package io.github.friendlyglobal.jacket.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.friendlyglobal.jacket.voices
import io.github.friendlyglobal.jacket.voice.ptbr.PtBrOldSpelling
import io.github.friendlyglobal.jacket.voice.ptbr.PtBrPhonemes
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A pack's model in memory, run by sherpa-onnx (https://github.com/k2-fsa/sherpa-onnx) on the
 * phone's own processor. Its files are read where they are (see [PackPlace]): downloaded, or
 * straight from the installed app in a build that carries them. Everything here blocks, and
 * belongs on [ModelHost]'s thread.
 *
 * [spinning] leaves the engine's threads to wait for each other as ONNX Runtime does by itself,
 * by spinning: only for measuring against (see [EngineSettings]).
 */
internal class NaturalModel(
    private val context: Context,
    val pack: VoicePack,
    threads: Int,
    private val spinning: Boolean = false,
) {
    /** The engine; null only if loading it again failed (see [rethread]), and then every sentence fails. */
    private var tts: OfflineTts?
    val sampleRate: Int

    /** How many processor cores it runs on. sherpa-onnx fixes that when it loads: see [rethread]. */
    @Volatile
    var threads: Int = threads
        private set

    /** A voice that reads sounds rather than letters: Jacket writes the sounds for it (see voice/ptbr). */
    private val readsSounds = pack.engine == ENGINE_PIPER

    /** Where its files are. */
    private val place = requireNotNull(context.voices.placeOf(pack)) { "${pack.id} isn't on this phone" }

    init {
        val loaded = load(threads)
        tts = loaded
        sampleRate = loaded.sampleRate()
    }

    /** False once loading it again has failed: the host loads it afresh for the next reader. */
    val alive: Boolean get() = tts != null

    private fun load(threads: Int): OfflineTts {
        fun path(role: String): String = pack.files[role]?.let(place::path).orEmpty()
        val provider = EngineSettings.provider(context, spinning)
        val model = if (readsSounds) {
            // No dictionary and no data folder: the sounds come from Jacket, ready-made.
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = path("model"), tokens = path("tokens"), lexicon = "", dataDir = ""),
                numThreads = threads,
                provider = provider,
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
                provider = provider,
            )
        }
        return OfflineTts(assetManager = place.assets, config = OfflineTtsConfig(model = model, maxNumSentences = 1))
    }

    /**
     * Loads the model again to run on [count] cores. sherpa-onnx has no way to change that once
     * loaded, so the old one is let go first (two would need twice the memory) and the new one
     * read in its place: a moment without a voice, taken when there is speech in hand. Should
     * loading fail, it goes back to what it had; should that fail too, the sentences after it
     * fail as they would with no model (and the voice stops, with its message).
     */
    fun rethread(count: Int) {
        if (count == threads || tts == null) return
        tts?.release()
        tts = null
        tts = runCatching { load(count) }
            .onSuccess { threads = count }
            .getOrElse { error ->
                Log.w(TAG, "Couldn't load ${pack.id} on $count threads; back to $threads", error)
                runCatching { load(threads) }.onFailure { Log.e(TAG, "Couldn't load ${pack.id} again", it) }.getOrNull()
            }
    }

    private fun engine(): OfflineTts = checkNotNull(tts) { "${pack.id} couldn't be loaded again" }

    /**
     * Speaks [text]: samples between -1 and 1, or null if it was [cancelled] on the way or the
     * model had nothing to say. [steps] trades quality for speed (Supertonic refines its sound
     * that many times); [speed] is the pace, made by the model rather than by stretching.
     *
     * Supertonic starts each sentence from a little random noise, so no two readings of it are
     * quite alike. A [seed] makes the noise, and so the sound, the same every time: for comparing
     * one way of running the model against another. Listening goes without.
     */
    fun synthesize(
        text: String,
        speaker: Int,
        language: String,
        speed: Float,
        steps: Int,
        seed: Int? = null,
        cancelled: () -> Boolean,
    ): FloatArray? {
        if (readsSounds) return speakSounds(text, speaker, speed, cancelled)
        val config = GenerationConfig(
            // "1×" is the pace the voices were made to speak at, a touch brisker than the model's 1.0.
            speed = speed * NATURAL_PACE,
            sid = speaker,
            numSteps = steps,
            extra = if (seed == null) mapOf("lang" to language) else mapOf("lang" to language, "seed" to seed.toString()),
        )
        val audio = engine().generateWithConfigAndCallback(text, config, KeepGoing(cancelled))
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
        val audio = engine().generateWithConfigAndCallback("", config, KeepGoing(cancelled))
        // This voice learnt a little of the room it was recorded in: its silences are tidied.
        return audio.samples.takeIf { it.isNotEmpty() && !cancelled() }?.let { SpeechCleanup.clean(it, sampleRate) }
    }

    fun release() {
        tts?.release()
        tts = null
    }

    private companion object {
        const val TAG = "JacketVoice"

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

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({
            // A little behind the screen and well behind the sound: when the phone is short of
            // processor (hot, or busy), the page and the voice already made come first, and the
            // speech in hand covers for the making. The engine's own threads are started from
            // this one when a model loads, and take its priority with them.
            Process.setThreadPriority(VOICE_PRIORITY)
            task.run()
        }, "jacket-voice").apply { isDaemon = true }
    }
    private val thread = executor.asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())

    /** Touched on the voice thread only. */
    private val entries = ArrayList<Entry>()
    private val unload = Runnable { executor.execute(::dropIdle) }

    /**
     * The packs that couldn't keep up on [CALM_THREADS] on this phone, while it was cool: they
     * run on more from then on, for as long as the app does (see [threadsFor]).
     */
    private val behind: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Loads [pack]'s model (or hands back the one in memory). Throws if it can't be read. [warm]
     * while the phone is warm (see [threadsFor]).
     */
    suspend fun acquire(pack: VoicePack, warm: Boolean = false): NaturalModel = withContext(thread) {
        main.removeCallbacks(unload)
        val entry = entries.firstOrNull { it.model.pack.id == pack.id && it.model.alive } ?: run {
            dropIdle()
            val started = SystemClock.elapsedRealtime()
            val threads = threads(warm, behind = pack.id in behind)
            val model = NaturalModel(context, pack, threads)
            // (How many cores it was given is kept until the phone warms or cools, or the voice falls behind: see ease.)
            Log.i(TAG, "Loaded ${pack.id} in ${SystemClock.elapsedRealtime() - started} ms, on $threads threads")
            Entry(model).also(entries::add)
        }
        entry.users++
        entry.model
    }

    /** [model]'s voice has been seen not to keep up on [CALM_THREADS] while the phone was cool. */
    fun isBehind(model: NaturalModel): Boolean = model.pack.id in behind

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

    /**
     * Whether [ease] would load [model] again for a phone that is [warm] or not, and whose voice is
     * [behind] or not (a phone with few cores is on few already).
     */
    fun wouldEase(model: NaturalModel, warm: Boolean, behind: Boolean): Boolean = threads(warm, behind) != model.threads

    /**
     * Has [model] run on as many cores as the phone calls for (see [threadsFor]): the calm few;
     * more for a voice that is [behind] while the phone is cool, and kept for it from then on;
     * and the calm few again while the phone is [warm]. Done on the voice thread, after the
     * sentence being made: the caller asks only when there is speech in hand to cover the moment
     * it takes.
     */
    fun ease(model: NaturalModel, warm: Boolean, behind: Boolean) = executor.execute {
        if (behind) this.behind += model.pack.id
        // Let go of meanwhile: nothing to do.
        if (entries.none { it.model === model } || !model.alive) return@execute
        val wanted = threads(warm, behind)
        if (wanted == model.threads) return@execute
        val was = model.threads
        val started = SystemClock.elapsedRealtime()
        model.rethread(wanted)
        val why = when {
            warm -> ", the phone is warm"
            wanted > was -> ", the voice couldn't keep up on $was"
            else -> ", the phone has cooled"
        }
        Log.i(TAG, "Threads $was to ${model.threads}$why (loaded again in ${SystemClock.elapsedRealtime() - started} ms)")
    }

    private fun dropIdle() {
        val idle = entries.filter { it.users == 0 }
        entries.removeAll(idle)
        idle.forEach { runCatching { it.model.release() } }
    }

    private fun threads(warm: Boolean, behind: Boolean): Int = threadsFor(
        cores = Runtime.getRuntime().availableProcessors(),
        saving = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
        warm = warm,
        behind = behind,
    )

    internal companion object {
        private const val TAG = "JacketVoice"
        private const val KEEP_MS = 45_000L

        /** Slightly less favoured than the app's other work (0), and nowhere near the background (10, the slow cores). */
        private const val VOICE_PRIORITY = Process.THREAD_PRIORITY_DEFAULT + 2 * Process.THREAD_PRIORITY_LESS_FAVORABLE

        /** The cores the voice runs on while it keeps up: see [threadsFor]. */
        const val CALM_THREADS = 2

        /** The most it ever runs on: half the cores, at most this many. */
        const val MOST_THREADS = 4

        /**
         * How many of the phone's [cores] the voice runs on: two ([CALM_THREADS]), whatever the
         * phone. The voice only has to be made a little faster than it is heard, and two do that
         * for the least processor time. Measured on the emulator with the same models, making a
         * minute of speech took 14.6 processor-seconds on two threads and 15.4 on four, for no
         * gain in speed; with the threads spinning as they used to (see [EngineSettings]), four
         * took 28.6, nearly twice as much, which is what a phone with eight cores was given.
         *
         * A voice [behind] (it couldn't keep up on two while the phone was cool) gets more: half
         * the cores, four at most, since phones pair fast cores with slow ones and spilling onto
         * the slow ones makes the voice slower, not faster. More cores are better than a rougher
         * voice. Not while the phone is [warm], nor in battery saver ([saving]): there it is two,
         * and the speech in hand covers for each sentence taking a little longer.
         */
        fun threadsFor(cores: Int, saving: Boolean, warm: Boolean, behind: Boolean = false): Int {
            val most = (cores / 2).coerceIn(1, MOST_THREADS)
            val calm = most.coerceAtMost(CALM_THREADS)
            return if (behind && !warm && !saving) most else calm
        }
    }
}

/**
 * How the engine's threads wait for each other.
 *
 * A model runs as a long row of small steps, and the threads that share each step wait for the
 * others to finish it. ONNX Runtime, inside sherpa-onnx, has them wait by spinning: a core kept
 * busy doing nothing, ready to go again a moment sooner. That makes a sentence a little quicker,
 * and costs processor time, which is what heats a phone. The voice works well ahead of the listener
 * (see [Cushion]) and has no use for the moment saved: here the threads sleep while they wait.
 * What the model works out is the same either way, and so is the sound.
 *
 * sherpa-onnx takes ONNX Runtime's own settings from a small file named after the processor it
 * runs on ("cpu:<file>"), and this is that file. It is written once, into the app's own storage.
 */
internal object EngineSettings {
    private const val TAG = "JacketVoice"
    private const val FILE = "voice-engine.conf"

    /** One setting a line: those that start with "SessionConfig." go to ONNX Runtime as they are. */
    internal const val QUIET = "# Written by Jacket: the voice's threads sleep while they wait for each other (see NaturalModel.kt).\n" +
        "SessionConfig.session.intra_op.allow_spinning=0\n" +
        "SessionConfig.session.inter_op.allow_spinning=0\n"

    /** What to tell sherpa-onnx the model runs on: the phone's processor, with [QUIET] unless [spinning]. */
    @Synchronized
    fun provider(context: Context, spinning: Boolean = false): String {
        if (spinning) return CPU
        val file = File(context.noBackupFilesDir, FILE)
        runCatching {
            if (!file.isFile || file.readText() != QUIET) {
                val partial = File(file.parentFile, "$FILE.part")
                partial.writeText(QUIET)
                check(partial.renameTo(file)) { "Couldn't store $FILE" }
            }
        }.onFailure { Log.w(TAG, "Couldn't write the voice engine's settings; its threads spin while they wait", it) }
        // sherpa-onnx ends the whole app when it can't open the file it is pointed at: it is only
        // pointed at one that is there, and says what it should.
        val ready = runCatching { file.canRead() && file.readText() == QUIET }.getOrDefault(false)
        return if (ready) providerFor(file.absolutePath) else CPU
    }

    /** sherpa-onnx's way of naming the processor and a file of settings for it. */
    internal fun providerFor(path: String): String = "$CPU:$path"

    private const val CPU = "cpu"
}

/** The locale whose rules split [language]'s text into words and sentences. */
internal fun localeOf(language: String?): Locale =
    language?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
