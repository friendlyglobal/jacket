package io.github.friendlyglobal.jacket.voice

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * Speaks texts with a natural voice, one at a time, as the reader asks for them: makes the sound
 * ahead of the listener, calmly and at full quality, as far as the cushion calls for (see
 * [Cushion]), plays it without gaps, reports each word as it's heard, and picks up where it was
 * after a pause. Both the EPUB reader (through Readium) and the plain-text reader speak through
 * this. Main thread only.
 *
 * A phone that warms up gets the same voice with less strain: a bigger cushion, and the model on
 * fewer cores. A rougher voice is the last resort, for when the cushion is about to run dry (see
 * [QualityGuard]); and if it runs dry anyway, the listener waits a moment between sentences
 * rather than hearing it garbled.
 *
 * What it does is logged under the `JacketVoice` tag: `adb logcat -s JacketVoice`.
 */
internal class NaturalSpeech(
    private val host: ModelHost,
    private val model: NaturalModel,
    speaker: Int,
    private val health: MutableStateFlow<VoiceHealth>,
    /** The phone is warming up (its own thermal status is moderate or worse): work further ahead, on fewer cores. */
    private val warm: () -> Boolean = { false },
    /** The phone is running hot (severe or worse): for [VoiceHealth.hot]. */
    private val hot: () -> Boolean = { false },
    /** The phone's own word on its temperature (PowerManager.THERMAL_STATUS_*), for the log. */
    private val thermal: () -> Int = { 0 },
    /** The screen is on, for the log. */
    private val screenOn: () -> Boolean = { true },
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

    /** The pieces of the sentences after the one being spoken, in the order they will be heard. */
    private var upcoming: List<String> = emptyList()

    /** Those of them asked for so far: always the first so many of [upcoming]. */
    private var ahead: List<Synthesizer.Job> = emptyList()
    private var failures = 0
    private var made = 0

    /** The seconds of speech it last aimed to keep ready (see [Cushion.target]); 0 before the first. */
    private var aim = 0f

    /** Whether the model was last set up for a warm phone, and for a voice that was behind (see [ModelHost.ease]); null before the first time. */
    private var easedFor: Pair<Boolean, Boolean>? = null

    /**
     * The voice couldn't keep up on the calm few cores while the phone was cool: it runs on more
     * from then on (see [ModelHost.threadsFor]). Never taken back: on fewer it would only fall
     * behind again, and each change loads the model afresh.
     */
    private var behind = host.isBehind(model)

    /** The model may have just been loaded again: the next piece made pays for it warming up, and says nothing of the pace. */
    private var reloaded = false

    /** When the sound handed to the speaker so far runs out (elapsed realtime); 0 with nothing playing. */
    private var dryAt = 0L

    /** The quality the voice is made at, and how the phone has been keeping up with it. */
    private var guard = QualityGuard()
    private var closed = false

    /** What listening costs, logged about once a minute (see [VoiceMeter]). */
    private val meter = VoiceMeter()

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
    ) {
        /** Where the listener is, by the last word heard: the piece, and the frame in its sound. */
        var heardPiece = startPiece
        var heardFrame = startFrame
    }

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
        synth.onMade = { clip, madeWith ->
            measure(clip, madeWith)
            // (Before more is asked for: loading the model again, if it is time to, comes before the next piece.)
            ease()
            // Its real length is known now, and the cushion may have grown: there may be more to ask for.
            planAhead()
        }
        out.onFailure = {
            session?.let { s ->
                session = null
                dryAt = 0L
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
        meter.start(now, Process::getElapsedCpuTime, VoiceMeter::makingMs)
        // What comes next is made while this plays, as far ahead as the cushion calls for.
        this.upcoming = upcoming.flatMap { later -> SpeechPieces.of(later, quickStart = false).map { later.substring(it) } }
        ahead = emptyList()
        planAhead()
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
        meter.stop()
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
        // What is made, or being made, is kept for when the listener goes on; nothing more is
        // started meanwhile. (Asked for again when they do, it costs nothing until it is made.)
        ahead = ahead.filter { it.state != Synthesizer.State.WAITING }
        synth.retain(paused?.jobs.orEmpty() + ahead)
        dryAt = 0L
    }

    /**
     * Asks for the pieces after the sentence being spoken, as far ahead as the cushion calls for:
     * when the sentence is asked for, and again as each piece is made (lengths are reckoned until
     * then, and the cushion may have grown). Never fewer than were asked for already: nothing is
     * thrown away for being further ahead than the reckoning thought.
     */
    private fun planAhead() {
        val s = session ?: return
        val seconds = upcoming.map { piece ->
            synth.find(piece, s.language, speed)?.clip?.let { it.millis / 1000f } ?: Cushion.estimate(piece, speed)
        }
        val count = maxOf(Cushion.ahead(seconds, aim()), ahead.size).coerceAtMost(upcoming.size)
        ahead = upcoming.take(count).mapIndexed { i, piece -> synth.request(piece, s.language, speed, UPCOMING - i) }
        synth.retain(s.jobs + ahead)
    }

    /** The seconds of speech to keep ready (see [Cushion.target]). A change is logged. */
    private fun aim(): Float {
        val warm = warm()
        val next = Cushion.target(warm, guard.struggling, model.sampleRate)
        if (next != aim) {
            val why = when {
                warm -> "the phone is warm"
                guard.struggling -> "the phone is slow"
                aim > 0f -> "the phone keeps up again"
                else -> "the phone keeps up"
            }
            Log.i(TAG, (if (aim > 0f) "Cushion ${aim.roundToInt()} s to " else "Cushion ") + "${next.roundToInt()} s of speech ahead: $why")
            aim = next
        }
        return aim
    }

    /**
     * Seconds of speech made and ready ahead of the listener: what is left of the sentence being
     * spoken, then the pieces after it, for as long as they are made one after another.
     */
    private fun inHand(): Float {
        val s = session ?: return 0f
        var ms = 0L
        for (i in s.heardPiece until s.jobs.size) {
            val clip = s.jobs[i].clip ?: return ms / 1000f
            ms += if (i == s.heardPiece) (clip.pcm.size - s.heardFrame).coerceAtLeast(0) * 1000L / clip.sampleRate else clip.millis
        }
        for (job in ahead) ms += (job.clip ?: return ms / 1000f).millis
        return ms / 1000f
    }

    /** Everything there is to make, as far as the reader has said what comes next, is made. */
    private fun caughtUp(): Boolean {
        val s = session ?: return false
        return ahead.size == upcoming.size && (s.jobs + ahead).none { it.state == Synthesizer.State.WAITING || it.state == Synthesizer.State.RUNNING }
    }

    /**
     * Has the model run on as many cores as the phone calls for (see [ModelHost.ease]): the calm
     * few, more once the voice is seen to fall behind on them while the phone is cool, and the
     * calm few while it is warm. Loading it again takes a moment, so only with enough speech in
     * hand to cover it.
     */
    private fun ease() {
        val warm = warm()
        // (A warm phone that struggles is held back by its heat, not short of cores.)
        if (!behind && !warm && guard.shortOfSpeed) behind = true
        val wanted = warm to behind
        if (wanted == easedFor || inHand() < Cushion.RELOAD_SECONDS) return
        easedFor = wanted
        // Already on as many cores as wanted: nothing is loaded again, and the next piece counts.
        if (!host.wouldEase(model, warm, behind)) return
        reloaded = true
        host.ease(model, warm, behind)
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
        val askedAt = SystemClock.elapsedRealtime()
        job.whenDone { clip ->
            if (session !== s) return@whenDone
            if (clip == null && ++failures >= GIVE_UP_AFTER) {
                session = null
                dryAt = 0L
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
                // A brisker pace takes shorter breaths, though not in full proportion.
                val pauseFrames = (breath / speed.pow(0.6f) * model.sampleRate / 1000f).toInt()
                val ratio = speed / clip.speed
                noteDry(clip, fromFrame, pauseFrames, ratio, askedAt)
                // How long it is heard for, breath and all.
                val heardMs = (((clip.pcm.size - fromFrame) / ratio.coerceAtLeast(0.25f) + pauseFrames) * 1000f / clip.sampleRate).toLong()
                out.play(
                    PcmOut.Item(
                        clip = clip,
                        fromFrame = fromFrame,
                        pauseFrames = pauseFrames,
                        ratio = ratio,
                        onWord = { word ->
                            if (session === s) {
                                s.heardPiece = index
                                s.heardFrame = word.frame
                                listener?.onRange(s.id, (offset + word.start) until (offset + word.end))
                            }
                        },
                        onSpoken = {
                            if (session === s) {
                                logCost(heardMs)
                                if (last) finish(s)
                            }
                        },
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
     * [heardMs] more of speech has been heard: about once a minute, what listening has cost is
     * logged, for a phone that runs warm to be looked into (see [VoiceMeter]).
     */
    private fun logCost(heardMs: Long) {
        val reading = meter.heard(heardMs, SystemClock.elapsedRealtime(), Process::getElapsedCpuTime, VoiceMeter::makingMs) ?: return
        Log.i(TAG, VoiceMeter.describe(reading, model.threads, synth.steps, inHand(), aim, thermal(), screenOn()))
    }

    /**
     * Keeps [dryAt] up to date as [clip] goes to the speaker, and logs the times the listener was
     * kept waiting for the voice: the sound had run out, and the next words, asked for at
     * [askedAt], weren't made yet. (A reader slow to ask, at the turn of a chapter say, isn't the
     * voice's wait.)
     */
    private fun noteDry(clip: Clip, fromFrame: Int, pauseFrames: Int, ratio: Float, askedAt: Long) {
        val now = SystemClock.elapsedRealtime()
        val waited = now - maxOf(dryAt, askedAt)
        if (dryAt > 0L && waited > GAP_MS) {
            Log.i(TAG, "Waited $waited ms for the next words: the speech in hand ran out (${synth.steps} steps, aiming for ${aim.roundToInt()} s ahead)")
        }
        val frames = (clip.pcm.size - fromFrame).coerceAtLeast(0) / ratio.coerceAtLeast(0.25f) + pauseFrames
        dryAt = maxOf(now, dryAt) + (frames * 1000f / clip.sampleRate).toLong()
    }

    /**
     * Watches the pace, and the speech in hand. A phone that falls behind keeps its finest voice
     * while the cushion covers for it; only when that is about to run dry does it get a slightly
     * rougher, quicker voice, and its full voice back once there is room again (see
     * [QualityGuard]). One that can't keep up even so is reported as [VoiceHealth.slow], for the
     * reader to suggest another voice.
     *
     * To see every sentence on a phone: `adb shell setprop log.tag.JacketVoice DEBUG`.
     */
    private fun measure(clip: Clip, madeWith: Int) {
        made++
        val inHand = inHand()
        // The first sentence after loading pays for the model warming up, and a very short one is
        // mostly overhead: neither says how the phone will fare.
        val untold = when {
            made == 1 -> "the first one"
            reloaded -> "the first one after loading again"
            !tellsThePace(clip.millis, clip.speed) -> "too short to tell"
            // (Made before the quality last changed: it says nothing of the one in use.)
            madeWith != guard.steps -> "made at another quality"
            // Finished after the listener stopped: there is no cushion to speak of, and no hurry.
            session == null -> "nobody listening"
            else -> null
        }
        reloaded = false
        if (untold != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Made ${clip.millis} ms of speech in ${clip.synthMillis} ms with $madeWith steps, ${"%.1f".format(inHand)} s in hand (not counted: $untold)")
            }
            return
        }
        val sample = clip.synthMillis / clip.millis.toFloat()
        val hot = hot()
        val before = guard
        guard = before.after(sample, inHand, caughtUp())
        synth.steps = guard.steps
        health.value = VoiceHealth(guard.pace, slow = guard.slow, hot = hot)
        if (guard.steps != before.steps) {
            // Rare, and the one thing to know when a voice sounds rougher than it did: said without being asked.
            Log.i(
                TAG,
                "Steps ${before.steps} to ${guard.steps}: pace ${"%.2f".format(guard.pace)}, ${"%.1f".format(inHand)} s in hand" +
                    (if (warm()) ", the phone is warm" else "") + (if (hot) " (hot)" else ""),
            )
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val up = if (guard.oneStepUp > 0f) ", one step up about ${"%.2f".format(guard.oneStepUp)}" else ""
            Log.d(
                TAG,
                "Made ${clip.millis} ms of speech in ${clip.synthMillis} ms with $madeWith steps: pace ${"%.2f".format(sample)}, " +
                    "going by ${"%.2f".format(guard.pace)}$up; ${"%.1f".format(inHand)} s in hand of ${aim.roundToInt()}; " +
                    "steps now ${guard.steps}" + (if (warm()) ", warm" else "") + (if (guard.slow) ", can't keep up" else ""),
            )
        }
    }

    internal companion object {
        private const val TAG = "JacketVoice"

        /** The sentence being spoken comes first; the pieces after it follow in the order they will be heard. */
        private const val CURRENT = 1_000
        private const val UPCOMING = 500

        /**
         * The most sentences the reader is asked for, after the one being spoken. How many of them
         * are made is the cushion's to say, by their seconds: a voice reading three times as fast
         * gets through them three times as fast, so it makes three times as many, and a quick
         * reader has no less of a margin than a slow one.
         */
        const val MOST_AHEAD = 24

        /** A wait longer than this between the sound handed over and the next is a gap the listener heard. */
        private const val GAP_MS = 250L

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
