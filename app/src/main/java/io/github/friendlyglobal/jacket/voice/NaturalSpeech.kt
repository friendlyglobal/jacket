package io.github.friendlyglobal.jacket.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow

/**
 * How well the natural voice is keeping up on this phone. [pace] is the time it takes to make a
 * second of speech, in seconds: under 1 it keeps ahead of the listener, over 1 it can't.
 */
data class VoiceHealth(val pace: Float = 0f, val slow: Boolean = false, val hot: Boolean = false)

/**
 * Makes clips on the voice thread, most wanted first, and keeps the ones made for as long as
 * someone may still play them. Used from the main thread.
 */
internal class Synthesizer(private val host: ModelHost, private val model: NaturalModel, private val speaker: Int) {
    enum class State { WAITING, RUNNING, DONE, FAILED }

    class Job(val text: String, val language: String, val speed: Float) {
        var priority = 0
        var state = State.WAITING
            internal set
        var clip: Clip? = null
            internal set
        var requestedAt = SystemClock.elapsedRealtime()

        @Volatile
        internal var cancelled = false
        internal val waiting = ArrayList<(Clip?) -> Unit>()

        /** Calls [block] with the clip once it's made: null if it couldn't be. */
        fun whenDone(block: (Clip?) -> Unit) {
            if (state == State.DONE || state == State.FAILED) block(clip) else waiting += block
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val jobs = ArrayList<Job>()
    private var running: Job? = null
    private var closed = false

    /** How many times the model refines its sound: fewer is quicker and a little rougher (see [QualityGuard]). */
    var steps = QualityGuard.FULL_STEPS

    /** Each clip as it's made, and how many times its sound was refined: for keeping an eye on the pace. */
    var onMade: (Clip, Int) -> Unit = { _, _ -> }

    fun find(text: String, language: String, speed: Float): Job? =
        jobs.lastOrNull { it.text == text && it.language == language && it.speed == speed && it.state != State.FAILED && !it.cancelled }

    /** Asks for [text]; the higher the [priority], the sooner. Asking again for the same thing returns the same job. */
    fun request(text: String, language: String, speed: Float, priority: Int): Job {
        val job = find(text, language, speed) ?: Job(text, language, speed).also { jobs += it }
        job.priority = priority
        pump()
        return job
    }

    /** Lets go of every job not in [keep]: nobody is going to play them. */
    fun retain(keep: Collection<Job>) {
        val dropped = jobs.filter { job -> keep.none { it === job } }
        jobs.removeAll(dropped)
        // One being made right now stops at the model's next breath; its result is thrown away.
        dropped.forEach { it.cancelled = true }
    }

    fun close() {
        closed = true
        retain(emptyList())
    }

    private fun pump() {
        if (running != null || closed) return
        val next = jobs.filter { it.state == State.WAITING }.maxByOrNull { it.priority } ?: return
        next.state = State.RUNNING
        running = next
        val steps = steps
        host.run {
            val started = SystemClock.elapsedRealtime()
            val clip = runCatching {
                val samples = model.synthesize(clean(next.text), speaker, next.language, next.speed, steps) { next.cancelled }
                samples?.let {
                    val pcm = SpeechTiming.trim(it, model.sampleRate)
                    val words = SpeechTiming.words(next.text, localeOf(next.language))
                    val timed = SpeechTiming.time(next.text, words, pcm, model.sampleRate, next.speed)
                    Clip(next.text, pcm, model.sampleRate, next.speed, timed, SystemClock.elapsedRealtime() - started)
                }
            }.onFailure { Log.w(TAG, "Couldn't speak a sentence", it) }.getOrNull()
            main.post { finish(next, clip, steps) }
        }
    }

    private fun finish(job: Job, clip: Clip?, madeWith: Int) {
        running = null
        job.clip = clip
        job.state = if (clip != null) State.DONE else State.FAILED
        // (Before the next is started: it is made at whatever quality this one's pace calls for.)
        if (clip != null && !job.cancelled) onMade(clip, madeWith)
        val waiting = job.waiting.toList()
        job.waiting.clear()
        waiting.forEach { it(clip) }
        pump()
    }

    /** Line breaks and runs of spaces mean nothing to a voice. */
    private fun clean(text: String): String = text.replace(WHITESPACE, " ").trim()

    companion object {
        private const val TAG = "JacketVoice"
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Speaks texts with a natural voice, one at a time, as the reader asks for them: makes the sound a
 * sentence or two ahead, plays it without gaps, reports each word as it's heard, and picks up
 * where it was after a pause. Both the EPUB reader (through Readium) and the plain-text reader
 * speak through this. Main thread only.
 */
internal class NaturalSpeech(
    private val host: ModelHost,
    private val model: NaturalModel,
    speaker: Int,
    private val health: MutableStateFlow<VoiceHealth>,
    /** The phone is running hot: ease off. */
    private val hot: () -> Boolean = { false },
) {
    interface Listener {
        /** The voice has reached characters [range] of the text spoken as [id]. */
        fun onRange(id: Any, range: IntRange)

        fun onDone(id: Any)

        /** The voice can't go on. */
        fun onError(id: Any)
    }

    var listener: Listener? = null

    private val synth = Synthesizer(host, model, speaker)
    private val out = PcmOut(model.sampleRate)
    private var session: Session? = null
    private var paused: Paused? = null
    private var ahead: List<Synthesizer.Job> = emptyList()
    private var failures = 0
    private var made = 0

    /** The quality the voice is made at, and how the phone has been keeping up with it. */
    private var guard = QualityGuard()
    private var closed = false

    /** 1 is the voice's own pace. Takes effect at once, on the sentence being spoken too. */
    var speed = 1f
        set(value) {
            if (field == value) return
            field = value
            val playing = session?.jobs?.firstNotNullOfOrNull { it.clip } ?: return
            out.setRatio(value / playing.speed)
        }

    private class Session(
        val id: Any,
        val text: String,
        val language: String,
        val pieces: List<IntRange>,
        val jobs: List<Synthesizer.Job>,
        val endsParagraph: Boolean,
        val startPiece: Int,
        val startFrame: Int,
    )

    private class Paused(
        val text: String,
        val language: String,
        val pieces: List<IntRange>,
        val jobs: List<Synthesizer.Job>,
        val piece: Int,
        val frame: Int,
        val at: Long,
    )

    init {
        health.value = VoiceHealth(hot = hot())
        synth.onMade = ::measure
        out.onFailure = {
            session?.let { s ->
                session = null
                listener?.onError(s.id)
            }
        }
    }

    /**
     * Speaks [text] in [language]. [upcoming] is what will be asked for next, so it can be made
     * meanwhile; [endsParagraph] leaves a longer breath after it. Asked again for the text it was
     * stopped in, it carries on from there.
     */
    fun speak(id: Any, text: String, language: String, endsParagraph: Boolean = false, upcoming: List<String> = emptyList()) {
        if (closed) return
        if (session != null) cut()
        val now = SystemClock.elapsedRealtime()
        val resume = paused?.takeIf { it.text == text && it.language == language }
        paused = null
        val next: Session
        if (resume != null && now - resume.at < FORGET_PAUSE_MS) {
            // Back in the sentence it was stopped in: at once from the very sample (a change of
            // pace), after a pause from the start of the word.
            val clip = resume.jobs.getOrNull(resume.piece)?.clip
            val frame = when {
                clip == null -> 0
                now - resume.at < SEAMLESS_MS -> resume.frame
                else -> clip.wordStartAt(resume.frame)
            }
            resume.jobs.forEachIndexed { i, job -> if (i >= resume.piece) synth.request(job.text, job.language, job.speed, CURRENT - i) }
            next = Session(id, text, language, resume.pieces, resume.jobs, endsParagraph, resume.piece, frame)
        } else {
            val pieces = piecesOf(text, language)
            val jobs = pieces.mapIndexed { i, range -> synth.request(text.substring(range), language, speed, CURRENT - i) }
            next = Session(id, text, language, pieces, jobs, endsParagraph, 0, 0)
        }
        session = next
        // What comes next is made while this plays: a sentence or two, no further, so little is
        // wasted (in battery, too) if the listener stops or jumps.
        ahead = upcoming.take(if (hot()) 1 else sentencesAhead(speed)).flatMapIndexed { i, later ->
            SpeechPieces.of(later, quickStart = false).take(2).mapIndexed { p, range ->
                synth.request(later.substring(range), language, speed, UPCOMING - i * 4 - p)
            }
        }
        synth.retain(next.jobs + ahead)
        feed(next, next.startPiece, next.startFrame)
    }

    /**
     * Stops speaking. The place is remembered: [speak] with the same text goes on from it.
     *
     * With nothing being spoken there is nothing to cut: the last sounds of the sentence just
     * finished may still be on their way to the speaker, and are left to arrive. (A reader that
     * stops the voice before asking for the next paragraph would otherwise clip every paragraph's
     * last word.)
     */
    fun stop() {
        if (session != null) cut()
    }

    fun close() {
        if (closed) return
        closed = true
        listener = null
        session = null
        paused = null
        out.release()
        synth.close()
        host.release(model)
    }

    private fun cut() {
        val was = session ?: return
        session = null
        val cut = out.stop()
        val piece = cut?.let { c -> was.jobs.indexOfFirst { it.clip === c.item.clip } } ?: -1
        val now = SystemClock.elapsedRealtime()
        paused = when {
            // Nothing of it had been heard yet.
            cut == null || piece < 0 -> Paused(was.text, was.language, was.pieces, was.jobs, was.startPiece, was.startFrame, now)
            cut.frame < cut.item.clip.pcm.size -> Paused(was.text, was.language, was.pieces, was.jobs, piece, cut.frame, now)
            piece < was.jobs.lastIndex -> Paused(was.text, was.language, was.pieces, was.jobs, piece + 1, 0, now)
            // All of it had: nothing to go back to.
            else -> null
        }
        synth.retain(paused?.jobs.orEmpty() + ahead)
    }

    /**
     * How [text] is cut up for the voice. If its sound is already made or on the way, the same way
     * as then; if not, the listener is waiting, and a long sentence starts with its first clause.
     */
    private fun piecesOf(text: String, language: String): List<IntRange> {
        val whole = SpeechPieces.of(text, quickStart = false)
        val first = synth.find(text.substring(whole.first()), language, speed)
        return if (first != null && first.state != Synthesizer.State.WAITING) whole else SpeechPieces.of(text, quickStart = true)
    }

    /** Hands piece [index] to the audio stream once its sound is made, then the one after it. */
    private fun feed(s: Session, index: Int, fromFrame: Int) {
        val job = s.jobs.getOrNull(index) ?: return
        val last = index == s.jobs.lastIndex
        job.whenDone { clip ->
            if (session !== s) return@whenDone
            if (clip == null && ++failures >= GIVE_UP_AFTER) {
                session = null
                out.stop()
                listener?.onError(s.id)
                return@whenDone
            }
            if (clip == null || clip.pcm.isEmpty() || fromFrame >= clip.pcm.size) {
                // Nothing to hear in this piece (a line of symbols, say): on to the next.
                if (last) finish(s)
            } else {
                failures = 0
                val offset = s.pieces[index].first
                val breath = when {
                    !last -> CLAUSE_MS
                    s.endsParagraph -> PARAGRAPH_MS
                    else -> SENTENCE_MS
                }
                out.play(
                    PcmOut.Item(
                        clip = clip,
                        fromFrame = fromFrame,
                        // A brisker pace takes shorter breaths, though not in full proportion.
                        pauseFrames = (breath / speed.pow(0.6f) * model.sampleRate / 1000f).toInt(),
                        ratio = speed / clip.speed,
                        onWord = { word -> if (session === s) listener?.onRange(s.id, (offset + word.start) until (offset + word.end)) },
                        onSpoken = { if (session === s && last) finish(s) },
                    ),
                )
            }
            if (!last) feed(s, index + 1, 0)
        }
    }

    private fun finish(s: Session) {
        session = null
        listener?.onDone(s.id)
    }

    /**
     * Watches the pace. A phone that struggles gets a slightly rougher, quicker voice before it
     * gets gaps, and its full voice back once it has room again (see [QualityGuard]); one that
     * can't keep up even so is reported as [VoiceHealth.slow], for the reader to suggest another
     * voice.
     *
     * To see it at work on a phone: `adb shell setprop log.tag.JacketVoice DEBUG`.
     */
    private fun measure(clip: Clip, madeWith: Int) {
        made++
        // The first sentence after loading pays for the model warming up, and a very short one is
        // mostly overhead: neither says how the phone will fare.
        val untold = when {
            made == 1 -> "the first one"
            !tellsThePace(clip.millis, clip.speed) -> "too short to tell"
            // (Made before the quality last changed: it says nothing of the one in use.)
            madeWith != guard.steps -> "made at another quality"
            else -> null
        }
        if (untold != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Made ${clip.millis} ms of speech in ${clip.synthMillis} ms with $madeWith steps (not counted: $untold)")
            }
            return
        }
        val sample = clip.synthMillis / clip.millis.toFloat()
        val warm = hot()
        val before = guard
        guard = before.after(sample, warm)
        synth.steps = guard.steps
        health.value = VoiceHealth(guard.pace, slow = guard.slow, hot = warm)
        if (guard.steps != before.steps) {
            // Rare, and the one thing to know when a voice sounds rougher than it did: said without being asked.
            Log.i(TAG, "Steps ${before.steps} to ${guard.steps}: pace ${"%.2f".format(guard.pace)}" + if (warm) ", the phone is hot" else "")
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val up = if (guard.oneStepUp > 0f) ", one step up about ${"%.2f".format(guard.oneStepUp)}" else ""
            Log.d(
                TAG,
                "Made ${clip.millis} ms of speech in ${clip.synthMillis} ms with $madeWith steps: pace ${"%.2f".format(sample)}, " +
                    "going by ${"%.2f".format(guard.pace)}$up; steps now ${guard.steps}" + (if (warm) ", hot" else "") + (if (guard.slow) ", can't keep up" else ""),
            )
        }
    }

    internal companion object {
        private const val TAG = "JacketVoice"
        private const val CURRENT = 100
        private const val UPCOMING = 50

        /** Sentences made ahead of the one being spoken, at the voice's own pace. */
        private const val AHEAD = 2

        /** The most that are made ahead, however fast it reads. */
        const val MOST_AHEAD = 6

        /**
         * How many sentences are made ahead of the one being spoken at [speed]. A voice reading
         * three times as fast gets through them three times as fast: it is the seconds in hand
         * that are kept the same, so a quick reader has no less of a margin than a slow one.
         */
        fun sentencesAhead(speed: Float): Int = kotlin.math.ceil(AHEAD * speed.coerceAtLeast(1f)).toInt().coerceAtMost(MOST_AHEAD)

        /**
         * Whether a clip of [millis] made at [speed] says how the phone is keeping up: one whose
         * sentence would last a second and a half at the voice's own pace. Counted by the clip's
         * own length, a brisk reader's sentences were all too short to count, and a phone that
         * couldn't keep up at speed was never eased, nor its listener told.
         */
        fun tellsThePace(millis: Long, speed: Float): Boolean = millis * speed >= 1_500f

        /** Breaths, at normal pace: within a sentence cut in two, between sentences, between paragraphs. */
        private const val CLAUSE_MS = 110f
        private const val SENTENCE_MS = 300f
        private const val PARAGRAPH_MS = 600f

        /** Stopped and started again within this: a change of pace, not a pause. */
        private const val SEAMLESS_MS = 400L

        /** After this long a pause, the sentence is read again from its start. */
        private const val FORGET_PAUSE_MS = 90_000L
        private const val GIVE_UP_AFTER = 3
    }
}
