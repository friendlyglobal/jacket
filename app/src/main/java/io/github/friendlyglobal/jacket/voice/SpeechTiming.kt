package io.github.friendlyglobal.jacket.voice

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A word of a spoken text: its characters [start, end) and the audio frame where it begins. */
class SpokenWord(val start: Int, val end: Int, val frame: Int)

/**
 * A piece of text as sound, ready to play: 16-bit mono samples with the silence the model puts
 * around them cut away, and the frame where each word starts.
 */
class Clip(
    val text: String,
    val pcm: ShortArray,
    val sampleRate: Int,
    /** The pace it was made at; playing it at another pace stretches it. */
    val speed: Float,
    val words: List<SpokenWord>,
    /** How long the phone took to make it. */
    val synthMillis: Long,
) {
    val millis: Long get() = pcm.size * 1000L / sampleRate

    /** The frame the word holding [frame] starts on: where to pick up again after a pause. */
    fun wordStartAt(frame: Int): Int = words.lastOrNull { it.frame <= frame }?.frame ?: 0
}

/**
 * The natural voices return sound only, not when each word falls in it. The ink still has to
 * follow the voice, so the timing is worked out: speech is spread over the words by their length,
 * and where the voice audibly pauses as often as the text has commas and full stops, each pause
 * pins the words around it in place.
 */
object SpeechTiming {
    private const val WINDOW_MS = 10

    /** A stretch this quiet for this long (at normal pace) is a pause inside the sentence. */
    private const val PAUSE_MS = 130

    /** Sound kept before the first and after the last audible window, so nothing is clipped. */
    private const val LEAD_MS = 20
    private const val TAIL_MS = 60

    /** How far a word's quiet start or dying end is followed out from the loud part of the speech. */
    private const val MAX_SOFT_START_MS = 250
    private const val MAX_SOFT_END_MS = 600

    /**
     * A silence this short inside a soft start or end is part of it: the closing of the lips
     * before the "p" of "tempo", after which its last, quiet syllable is still to come.
     */
    private const val BRIDGE_START_MS = 120
    private const val BRIDGE_END_MS = 200

    /** The ease in and the ease out: the end is let down gently, as a voice's own endings are. */
    private const val FADE_IN_MS = 5
    private const val FADE_OUT_MS = 40

    /** How much the voice is turned up (about 3.5 dB), to sit level with other things people listen to. */
    private const val LIFT = 1.5f

    /**
     * Cuts the silence off both ends of [samples] and makes 16-bit audio of the rest, eased in
     * and out and turned down if the model overshot full scale. Empty if nothing is audible.
     *
     * The last sounds of a sentence are its softest: an "s", a final "o", a breath dying away,
     * far below the level of its vowels. So the cut isn't made where the speech stops being loud,
     * but where it sinks into the clip's own quiet.
     */
    fun trim(samples: FloatArray, sampleRate: Int): ShortArray {
        val edges = edges(samples, sampleRate) ?: return ShortArray(0)
        val from = edges.first
        val to = edges.last + 1
        var peak = 0f
        for (i in from until to) peak = max(peak, abs(samples[i]))
        // The voices come out quiet (peaks about half of full scale): lifted by the same amount
        // every time, so sentences match, and held under full scale where one runs hot.
        val gain = if (peak * LIFT > 0.97f) 0.97f / peak else LIFT
        val fadeIn = (sampleRate * FADE_IN_MS / 1000).coerceAtLeast(1)
        val fadeOut = (sampleRate * FADE_OUT_MS / 1000).coerceAtLeast(1)
        val length = to - from
        return ShortArray(length) { i ->
            val ease = min(1f, min(i / fadeIn.toFloat(), (length - 1 - i) / fadeOut.toFloat()))
            (samples[from + i] * gain * ease * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** The frames of [samples] worth playing, from the first sound to the last; null if there is none. */
    internal fun edges(samples: FloatArray, sampleRate: Int): IntRange? {
        val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        val levels = levels(samples, window)
        val loud = floorOf(levels)
        var first = levels.indexOfFirst { it > loud }
        if (first < 0) return null
        var last = levels.indexOfLast { it > loud }
        // Out from the loud part for as long as there is still sound above the clip's own quiet.
        val quiet = quietOf(levels, loud)
        first = reach(levels, first, -1, quiet, MAX_SOFT_START_MS / WINDOW_MS, BRIDGE_START_MS / WINDOW_MS)
        last = reach(levels, last, 1, quiet, MAX_SOFT_END_MS / WINDOW_MS, BRIDGE_END_MS / WINDOW_MS)
        val from = (first * window - sampleRate * LEAD_MS / 1000).coerceAtLeast(0)
        val to = ((last + 1) * window + sampleRate * TAIL_MS / 1000).coerceAtMost(samples.size)
        return from until to
    }

    /**
     * The furthest window from [from], going [step] by [step] for at most [most] windows, that
     * still has sound above [quiet], stepping over silences of up to [bridge] windows. A lone
     * window above the quiet after a silence is a crackle, not a sound: two together are.
     */
    private fun reach(levels: FloatArray, from: Int, step: Int, quiet: Float, most: Int, bridge: Int): Int {
        var edge = from
        var gap = 0
        var i = from + step
        var gone = 1
        while (i in levels.indices && gone <= most && gap <= bridge) {
            val sound = levels[i] > quiet && (gap == 0 || (levels.getOrNull(i + step) ?: 0f) > quiet)
            if (sound) {
                edge = i
                gap = 0
            } else {
                gap++
            }
            i += step
            gone++
        }
        return edge
    }

    /**
     * The level of a clip's own quiet: three times its stillest moments (a voice taught from
     * home recordings has a little room noise even in its silences), never under what can be
     * heard at all, and never over [loud].
     */
    private fun quietOf(levels: FloatArray, loud: Float): Float {
        val sorted = levels.sortedArray()
        val stillest = sorted[(sorted.size * 5 / 100).coerceAtMost(sorted.lastIndex)]
        return (stillest * 3f).coerceIn(0.0008f, loud)
    }

    /**
     * The words of [text] as character ranges. A word keeps the punctuation that touches it, so a
     * comma or a quotation mark inks in with its word; a dash between two words ends the first.
     * Text written without spaces (Chinese, Japanese, Thai) is split by its language's own rules.
     */
    fun words(text: String, locale: Locale? = null): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            if (end > i) chunk(text, i, end, locale, out)
            i = end
        }
        return out
    }

    /** One run of [text] between spaces: usually a word, sometimes several. */
    private fun chunk(text: String, from: Int, until: Int, locale: Locale?, out: MutableList<IntRange>) {
        if ((from until until).none { text[it].isLetterOrDigit() }) return
        if (until - from > 3 && (from until until).any { unspaced(text[it]) }) {
            // No spaces to go by: the language's word rules, with punctuation joining the word before it.
            val iterator = BreakIterator.getWordInstance(locale ?: Locale.ROOT)
            iterator.setText(text.substring(from, until))
            var start = iterator.first()
            var end = iterator.next()
            var pending = from
            var any = false
            while (end != BreakIterator.DONE) {
                if ((from + start until from + end).any { text[it].isLetterOrDigit() }) {
                    if (any) out[out.lastIndex] = out.last().first until from + start
                    out += pending until from + end
                    pending = from + end
                    any = true
                }
                start = end
                end = iterator.next()
            }
            if (any) out[out.lastIndex] = out.last().first until until
            return
        }
        // "time—now": two words, the dash staying with the first.
        var start = from
        for (i in from until until - 1) {
            if (text[i] in DASHES && (start..i).any { text[it].isLetterOrDigit() } && (i + 1 until until).any { text[it].isLetterOrDigit() }) {
                out += start..i
                start = i + 1
            }
        }
        out += start until until
    }

    private const val DASHES = "—–"

    /** Scripts written without spaces between words. */
    private fun unspaced(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0x0E00..0x0EFF || c.code in 0xF900..0xFAFF

    /** Says when each of [words] starts in [pcm], the sound of [text] made at [speed]. */
    fun time(text: String, words: List<IntRange>, pcm: ShortArray, sampleRate: Int, speed: Float = 1f): List<SpokenWord> {
        if (words.isEmpty() || pcm.isEmpty()) return emptyList()
        val weights = words.map { weight(text, it) }
        val breaks = words.indices.filter { it < words.lastIndex && pauses(text, words[it]) > 0 }
        val gaps = pauses(pcm, sampleRate, speed)
        val frames = IntArray(words.size)
        if (breaks.isNotEmpty() && gaps.size == breaks.size) {
            // The voice paused exactly where the text does: each phrase gets its own stretch of sound.
            var word = 0
            var from = 0
            for (phrase in 0..breaks.size) {
                val lastWord = if (phrase < breaks.size) breaks[phrase] else words.lastIndex
                val until = if (phrase < gaps.size) gaps[phrase].first else pcm.size
                spread(weights, word..lastWord, from, until, frames)
                word = lastWord + 1
                if (phrase < gaps.size) from = gaps[phrase].last + 1
            }
        } else {
            // Otherwise by length alone, with room left where the punctuation suggests a breath.
            val padded = weights.mapIndexed { i, w -> w + if (i < words.lastIndex) pauses(text, words[i]) * PAUSE_WEIGHT else 0f }
            spread(padded, words.indices, 0, pcm.size, frames)
        }
        return words.mapIndexed { i, range -> SpokenWord(range.first, range.last + 1, frames[i]) }
    }

    /** Gives each word in [range] its start frame within [from, until), in proportion to the weights. */
    private fun spread(weights: List<Float>, range: IntRange, from: Int, until: Int, frames: IntArray) {
        val total = range.sumOf { weights[it].toDouble() }.toFloat().coerceAtLeast(0.001f)
        var before = 0f
        for (i in range) {
            frames[i] = from + ((until - from) * (before / total)).toInt()
            before += weights[i]
        }
    }

    /** How long a word takes to say, roughly: its letters, with digits counted as the words they become. */
    internal fun weight(text: String, range: IntRange): Float {
        var weight = 0f
        for (i in range) {
            val c = text[i]
            weight += when {
                c.isDigit() -> 3.5f
                // Han, kana and hangul carry a syllable or more each.
                c.isLetter() && c.code >= 0x2E80 -> 2.2f
                c.isLetter() -> 1f
                else -> 0f
            }
        }
        return weight.coerceAtLeast(1f)
    }

    private const val PAUSE_WEIGHT = 3f

    /** 0 for a word the voice runs on from, 1 after a comma or dash, 2 after the end of a sentence. */
    private fun pauses(text: String, range: IntRange): Int {
        var i = range.last
        // Past closing quotes and brackets to the mark itself.
        while (i > range.first && text[i] in CLOSERS) i--
        return when (text[i]) {
            '.', '!', '?', '…', '。', '！', '？', '।' -> 2
            ',', ';', ':', '—', '–', '、', '，', '；', '：', '،', '؛' -> 1
            else -> 0
        }
    }

    private const val CLOSERS = "\"'”’»)]}›」』"

    /** Quiet stretches inside [pcm] long enough to be heard as pauses, as frame ranges. */
    private fun pauses(pcm: ShortArray, sampleRate: Int, speed: Float): List<IntRange> {
        val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        val levels = FloatArray(pcm.size / window) { w ->
            var sum = 0.0
            for (i in w * window until (w + 1) * window) sum += (pcm[i] / 32768.0).let { it * it }
            sqrt(sum / window).toFloat()
        }
        val floor = floorOf(levels)
        val needed = (PAUSE_MS / speed.coerceIn(0.5f, 3f) / WINDOW_MS).toInt().coerceAtLeast(4)
        val out = ArrayList<IntRange>()
        var run = -1
        for (w in levels.indices) {
            val quiet = levels[w] <= floor
            if (quiet && run < 0) run = w
            if (!quiet && run >= 0) {
                // Not the quiet at either end: only what has speech on both sides.
                if (run > 0 && w - run >= needed) out += run * window until w * window
                run = -1
            }
        }
        return out
    }

    private fun levels(samples: FloatArray, window: Int): FloatArray = FloatArray(samples.size / window) { w ->
        var sum = 0.0
        for (i in w * window until (w + 1) * window) sum += samples[i].toDouble() * samples[i]
        sqrt(sum / window).toFloat()
    }

    /** The level under which a window counts as silence: well below the loudest, never below the noise. */
    private fun floorOf(levels: FloatArray): Float = max(0.004f, (levels.maxOrNull() ?: 0f) * 0.035f)
}

/**
 * How a text goes to the voice. Usually whole: but a long sentence asked for with nothing ready
 * (the first one after Play, or after a jump) is cut at its first comma, so the voice can start
 * on the opening words while the rest is still being made. Very long sentences are always cut,
 * at clause ends, so no single piece keeps the listener waiting.
 */
object SpeechPieces {
    private const val LONG = 320
    private const val QUICK_START_FROM = 90

    fun of(text: String, quickStart: Boolean): List<IntRange> {
        if (text.length <= (if (quickStart) QUICK_START_FROM else LONG)) return listOf(text.indices)
        val out = ArrayList<IntRange>()
        var start = 0
        if (quickStart) {
            val cut = clauseEnd(text, 0, min = 24, max = 150)
            if (cut != null && text.length - cut >= 20) {
                out += 0 until cut
                start = cut
            }
        }
        while (text.length - start > LONG) {
            val cut = clauseEnd(text, start, min = 120, max = 280, last = true)
                ?: text.lastIndexOf(' ', start + 280).takeIf { it > start + 40 }?.plus(1)
                ?: (start + 280)
            out += start until cut
            start = cut
        }
        if (start < text.length) out += start until text.length
        return out
    }

    /**
     * Where a clause of [text] ends (just past the mark and the space after it), between [min] and
     * [max] characters after [from]: the first such place, or with [last] the last.
     */
    private fun clauseEnd(text: String, from: Int, min: Int, max: Int, last: Boolean = false): Int? {
        var found: Int? = null
        val until = min(text.length - 1, from + max)
        for (i in from + min until until) {
            val c = text[i]
            val western = c in ",;:—–" && text[i + 1].isWhitespace()
            val eastern = c in "，、；：。"
            if (western || eastern) {
                found = if (western) i + 2 else i + 1
                if (!last) break
            }
        }
        return found?.coerceAtMost(text.length)
    }
}

/** A title or an initial and its full stop, at the end of what was taken for a sentence. */
private val TITLE_AT_END = Regex(
    "(?:^|[\\s(\"“«—-])(?:\\p{Lu}|Sr|Sra|Srs|Sras|Srta|Snr|Snra|Dr|Dra|Drs|Prof|Profa|Exmo|Exma|Ilmo|Ilma|Revmo|Pe|Fr|Sto|Sta|" +
        "Mme|Mlle|Mr|Mrs|Ms|Cel|Gen|Cap|Ten|Maj|Cons|Com|Av|St|Mt|Mons|Sgt|Lt|Col|Hr|Mgr)\\.\\s*$",
)

/**
 * "O Sr. Bento" is one sentence, though a full stop and a capital letter look like the end of one
 * and the start of the next: [ranges] with each sentence that ends in a title ("Sr.", "Dr.", "D.")
 * or an initial joined to the one after it.
 */
fun joinTitles(text: String, ranges: List<IntRange>): List<IntRange> {
    if (ranges.size < 2) return ranges
    val out = ArrayList<IntRange>(ranges.size)
    for (range in ranges) {
        val last = out.lastOrNull()
        if (last != null && TITLE_AT_END.containsMatchIn(text.substring(last.first, last.last + 1))) out[out.lastIndex] = last.first..range.last else out += range
    }
    return out
}

/** The sentences of [text] as character ranges, for speaking a paragraph one sentence at a time. */
fun sentencesOf(text: String, locale: Locale? = null): List<IntRange> {
    val iterator = BreakIterator.getSentenceInstance(locale ?: Locale.ROOT)
    iterator.setText(text)
    val out = ArrayList<IntRange>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        var last = end
        while (last > start && text[last - 1].isWhitespace()) last--
        if ((start until last).any { text[it].isLetterOrDigit() }) out += start until last
        start = end
        end = iterator.next()
    }
    return joinTitles(text, out)
}
