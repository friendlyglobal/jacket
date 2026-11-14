package io.github.friendlyglobal.jacket.voice

/*
 * How finely a natural voice is made, kept to what the phone can do.
 *
 * The model refines its sound a few times over: more passes sound smoother, and take longer. The
 * voice works ahead of the listener (see [Cushion]), so a phone that slows down for a while only
 * eats into the speech made ahead, and the voice stays at its finest for as long as that lasts.
 * Only when the cushion is about to run dry and the phone still can't keep up is it given fewer
 * passes: a slightly rougher voice rather than a gap after every sentence. Once there is speech in
 * hand again and the phone has room, the passes come back, one at a time.
 *
 * What it goes by is the pace of each sentence as it is made (the seconds it took to make a second
 * of speech: under 1 the voice keeps ahead of the listener, over 1 it can't), and how many seconds
 * of speech were ready ahead of the listener when it was made.
 */

/**
 * The quality the voice is being made at ([steps] passes), and what the last few sentences say of
 * how the phone is keeping up. Each sentence made gives the next one ([after]); nothing here
 * touches the phone, so it can be tested with sentences made up for the purpose.
 *
 * The rules, in the order they are tried:
 * - It eases off only when the cushion is about to run dry (under [Cushion.LOW_SECONDS] in hand)
 *   and two of the last three sentences fell behind the listener ([BEHIND]): by one pass, or
 *   straight to the fewest when one pass fewer still wouldn't keep up. One slow sentence alone
 *   changes nothing; nor do any number of them while there is speech in hand to cover for them.
 *   A phone running hot isn't eased here at all: it gets a bigger cushion and less work at once
 *   (see NaturalSpeech), and keeps its finest voice for as long as that keeps up.
 * - It climbs back, a pass at a time, when there is room ([Cushion.ROOMY_SECONDS] in hand, or all
 *   there is to make made) and two of the last three sentences would each have kept up with one
 *   pass more, with a little to spare ([CLIMB], reckoned by [paceWith]). The gap between the two
 *   lines, and between running dry and having room, is what keeps it from going back and forth.
 * - A climb that had to be undone makes the next one wait twice as long ([patience]), up to
 *   [MOST_PATIENCE] sentences; one that has held that long clears the slate.
 *
 * Every change of [steps] starts the count afresh: only sentences made at the setting in use say
 * how that setting is doing.
 */
internal data class QualityGuard(
    /** How many times the model refines its sound, from [FEWEST_STEPS] to [FULL_STEPS]. */
    val steps: Int = FULL_STEPS,
    /** The pace it went by when the last sentence was made: the middle one of the last three (of two, the slower). */
    val pace: Float = 0f,
    /** What that pace would be with one pass more, as reckoned; 0 when the voice is at its finest already. */
    val oneStepUp: Float = 0f,
    /** The paces of the last sentences made at [steps], newest last: no more than [RECENT]. */
    val recent: List<Float> = emptyList(),
    /** How many sentences have been made at [steps] since it was last changed. */
    val settled: Int = 0,
    /** How many sentences are made at a setting before a pass is added. */
    val patience: Int = CLIMB_AFTER,
    /** [steps] was last climbed to, and hasn't held for long enough yet to be taken as safe. */
    val trying: Boolean = false,
) {
    /**
     * Even the lightest setting can't keep up: two of the last three sentences made with the
     * fewest passes took about as long to make as to hear. The reader suggests another voice.
     */
    val slow: Boolean get() = steps == FEWEST_STEPS && recent.count { it > CANT_KEEP_UP } >= AGREE

    /**
     * The voice is behind, or only just keeping up, or has had to be made rougher: it should
     * work further ahead (see [Cushion.target]).
     */
    val struggling: Boolean get() = steps < FULL_STEPS || pace > EASE

    /**
     * The phone is short of speed for this voice, as far as the sentences say: it has had to be
     * made rougher, or the middle one of the last three was only just kept up with. One slow
     * sentence (the phone busy with something else for a moment) doesn't say so. The voice then
     * runs on more cores, while the phone is cool (see [ModelHost.threadsFor]).
     */
    val shortOfSpeed: Boolean get() = steps < FULL_STEPS || (recent.size >= RECENT && pace > EASE)

    /**
     * The guard after a sentence made at [steps] passes at [sentencePace] (the seconds it took to
     * make a second of it), with [inHand] seconds of speech ready ahead of the listener once it
     * was made. [caughtUp] when everything the voice can see to make is made: a cushion can't be
     * any bigger than that (a page of one-line paragraphs, say), and has as much room as it can.
     */
    fun after(sentencePace: Float, inHand: Float, caughtUp: Boolean = false): QualityGuard {
        val seen = (recent + sentencePace).takeLast(RECENT)
        val count = settled + 1
        val middle = seen.sorted()[seen.size / 2]
        fun twoAgree(test: (Float) -> Boolean) = seen.count(test) >= AGREE
        // A climb that has held this long was a safe one.
        val held = trying && count >= MOST_PATIENCE
        val now = copy(
            pace = middle,
            oneStepUp = if (steps < FULL_STEPS) paceWith(steps + 1, middle, steps) else 0f,
            recent = seen,
            settled = count,
            patience = if (held) CLIMB_AFTER else patience,
            trying = trying && !held,
        )
        val runningDry = inHand < Cushion.LOW_SECONDS
        val room = caughtUp || inHand >= Cushion.ROOMY_SECONDS
        val to = when {
            steps > FEWEST_STEPS && runningDry && twoAgree { it > BEHIND } ->
                if (twoAgree { paceWith(steps - 1, it, steps) >= BEHIND }) FEWEST_STEPS else steps - 1
            steps < FULL_STEPS && room && count >= patience && twoAgree { paceWith(steps + 1, it, steps) < CLIMB } -> steps + 1
            else -> steps
        }
        if (to == steps) return now
        return now.copy(
            steps = to,
            recent = emptyList(),
            settled = 0,
            trying = to > steps,
            // Eased straight after climbing: that pass was one too many. It isn't tried again so soon.
            patience = if (to < steps && now.trying) (now.patience * 2).coerceAtMost(MOST_PATIENCE) else now.patience,
        )
    }

    companion object {
        /** The most passes: the voice at its finest. */
        const val FULL_STEPS = 5

        /** The fewest: a little rougher, and the quickest to make. (Two passes sound clearly worse, and aren't used.) */
        const val FEWEST_STEPS = 3

        /** Slower than this, the phone only just keeps up: the voice works further ahead. */
        const val EASE = 0.8f

        /** Slower than this, the voice is falling behind the listener: the cushion is shrinking. */
        const val BEHIND = 1f

        /** A pass is added only where the pace with it is reckoned to stay under this. */
        const val CLIMB = 0.9f

        /** With the fewest passes and still slower than this, the phone can't keep this voice up. */
        const val CANT_KEEP_UP = 0.95f

        /** How many of the last sentences it looks at, and how many of them must say the same for it to act. */
        const val RECENT = 3
        const val AGREE = 2

        /** Sentences made at a setting before a pass is added; and the most that wait grows to after climbs that didn't hold. */
        const val CLIMB_AFTER = 2
        const val MOST_PATIENCE = 16

        /**
         * What a sentence costs to make besides its passes (reading its text, and turning the
         * result into sound), counted in passes. Measured on a laptop it is a quarter to three
         * quarters of a pass (the passes are 86 to 91 % of the work at five), so this is on the
         * high side: a pass taken off gains at least what was reckoned, and one added costs a
         * little more than reckoned, which [CLIMB]'s margin and the speech in hand cover.
         */
        const val FIXED_COST = 1f

        /**
         * The pace a sentence would be made at with [steps] passes, going by the [pace] it was
         * made at with [madeWith]: the time it takes grows with the passes, on top of a part that
         * is the same however many there are ([FIXED_COST]).
         */
        fun paceWith(steps: Int, pace: Float, madeWith: Int): Float = pace * (steps + FIXED_COST) / (madeWith + FIXED_COST)
    }
}
