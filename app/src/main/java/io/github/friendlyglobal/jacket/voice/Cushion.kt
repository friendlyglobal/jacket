package io.github.friendlyglobal.jacket.voice

/*
 * How far ahead of the listener a natural voice works.
 *
 * The voice keeps a cushion of speech that is made and ready to play, counted in seconds of
 * listening, not in sentences: a sentence can last two seconds or twenty. While the cushion holds,
 * a phone that slows down for a while (it is hot, another app wants the processor) costs the
 * listener nothing: no gap between sentences, no rougher voice. The cushion is built at full
 * quality, and grown, never shrunk, while the phone is warm or slow.
 *
 * Working ahead costs no more work in the end, since every sentence is made once either way, but
 * it is lost when the listener jumps elsewhere, and the sound waits in memory. So the cushion is
 * only as big as it needs to be: about [NORMAL_SECONDS] on a phone that keeps up easily, and
 * never more than [MOST_BYTES] of sound.
 */
internal object Cushion {
    /** Seconds of speech kept ready on a phone that keeps up easily: about as much as two sentences, as before. */
    const val NORMAL_SECONDS = 20f

    /** ... on a phone that is warm or slow. The more in hand, the longer a slow spell it rides out without a gap. */
    const val GROWN_SECONDS = 60f

    /**
     * The most memory the sound made ahead may take: 16-bit samples at the voice's own rate. At
     * 44.1 kHz (the Supertonic voices) that is a little over a minute; at 22 kHz (the Brazilian
     * voice), two.
     */
    const val MOST_BYTES = 6_000_000

    /**
     * Under this many seconds in hand, the cushion is about to run dry: about what a sentence
     * takes to make on a phone that has fallen behind (eight seconds of speech, at a pace of 1.25).
     * Only then is the voice made rougher to keep up (see [QualityGuard]).
     */
    const val LOW_SECONDS = 10f

    /** With this many in hand (or all there is to make made), there is room to try the finer voice again. */
    const val ROOMY_SECONDS = 15f

    /**
     * Loading the model again on fewer threads leaves the voice silent for a moment (well under
     * a second on a laptop; a second or two on a phone, it is logged): it waits until this many
     * seconds are in hand.
     */
    const val RELOAD_SECONDS = 10f

    /**
     * How much of a word a voice says in a second at its own pace, counted as [SpeechTiming.weight]
     * counts it (a letter is 1). Measured with Supertonic: 11.5 to 13.5 in English and Portuguese.
     */
    const val WEIGHT_PER_SECOND = 12f

    /**
     * The seconds of speech to keep ready. [warm] while the phone's own thermal status says it is
     * warming up; [struggling] while the voice is behind or only just keeping up (see
     * [QualityGuard.struggling]). [sampleRate] is the voice's, for [MOST_BYTES].
     */
    fun target(warm: Boolean, struggling: Boolean, sampleRate: Int): Float =
        (if (warm || struggling) GROWN_SECONDS else NORMAL_SECONDS).coerceAtMost(mostSeconds(sampleRate))

    /** The seconds of sound [MOST_BYTES] holds at [sampleRate]. */
    fun mostSeconds(sampleRate: Int): Float = MOST_BYTES / (sampleRate.coerceAtLeast(1) * 2f)

    /** About how long [text] lasts at [speed], before it is made: a sentence of 150 letters, some twelve seconds. */
    fun estimate(text: String, speed: Float): Float {
        if (text.isBlank()) return 0f
        return SpeechTiming.weight(text, text.indices) / WEIGHT_PER_SECOND / speed.coerceAtLeast(0.25f)
    }

    /**
     * How many of the pieces after the sentence being spoken to make now: [seconds] is how long each
     * lasts (known once made, reckoned before), in the order they will be heard. Enough to add up
     * to [target], and always the first one, so the next sentence is on its way whatever happens.
     */
    fun ahead(seconds: List<Float>, target: Float): Int {
        var sum = 0f
        var count = 0
        while (count < seconds.size && (count == 0 || sum < target)) {
            sum += seconds[count]
            count++
        }
        return count
    }
}
