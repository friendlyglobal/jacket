package io.github.friendlyglobal.jacket.voice

/*
 * How finely a natural voice is made, kept to what the phone can do.
 *
 * The model refines its sound a few times over: more passes sound smoother, and take longer. A
 * phone that makes a second of speech in well under a second is given them all. One that falls
 * behind is given fewer, so the listener hears a slightly rougher voice rather than gaps between
 * sentences; and once it has room again, the passes come back, one at a time.
 *
 * What it goes by is the pace of each sentence as it is made: the seconds it took to make a second
 * of speech. Under 1 the voice keeps ahead of the listener; over 1 it can't.
 */

/**
 * The quality the voice is being made at ([steps] passes), and what the last few sentences say of
 * how the phone is keeping up. Each sentence made gives the next one ([after]); nothing here
 * touches the phone, so it can be tested with sentences made up for the purpose.
 *
 * The rules, in the order they are tried:
 * - A phone running hot is given the fewest passes at once, for as long as it is hot.
 * - It eases off when two of the last three sentences were slow ([EASE]): by one pass, or straight
 *   to the fewest when they couldn't keep up at all ([BEHIND]). One slow sentence alone changes
 *   nothing: opening a book, a page loading or another app taking the processor for a moment is
 *   not the phone being slow. Two are acted on at once, while the sentences made ahead still cover
 *   for them.
 * - It climbs back, a pass at a time, when two of the last three sentences would each have left
 *   room with one pass more ([CLIMB], reckoned by [paceWith]). The gap between the two lines is
 *   what keeps it from going back and forth.
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
     * The guard after a sentence made at [steps] passes at [sentencePace] (the seconds it took to
     * make a second of it). [hot] while the phone is running hot.
     */
    fun after(sentencePace: Float, hot: Boolean = false): QualityGuard {
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
        val to = when {
            hot -> FEWEST_STEPS
            twoAgree { it > BEHIND } -> FEWEST_STEPS
            twoAgree { it > EASE } -> (steps - 1).coerceAtLeast(FEWEST_STEPS)
            steps < FULL_STEPS && count >= patience && twoAgree { paceWith(steps + 1, it, steps) < CLIMB } -> steps + 1
            else -> steps
        }
        if (to == steps) return now
        return now.copy(
            steps = to,
            recent = emptyList(),
            settled = 0,
            trying = to > steps,
            // Eased straight after climbing: that pass was one too many. It isn't tried again so soon.
            patience = if (to < steps && now.trying && !hot) (now.patience * 2).coerceAtMost(MOST_PATIENCE) else now.patience,
        )
    }

    companion object {
        /** The most passes: the voice at its finest. */
        const val FULL_STEPS = 5

        /** The fewest: a little rougher, and the quickest to make. */
        const val FEWEST_STEPS = 3

        /** A sentence slower than this is a slow one: the phone has less than a fifth of its time in hand. */
        const val EASE = 0.8f

        /** Slower than this, the voice is falling behind the listener: gaps are on their way. */
        const val BEHIND = 1f

        /** A pass is added only where the pace with it is reckoned to stay under this. */
        const val CLIMB = 0.7f

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
         * result into sound), counted in passes. Taken low on purpose: the lower it is, the more
         * a pass is reckoned to cost, and the surer a climb is to hold.
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
