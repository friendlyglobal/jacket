package io.github.friendlyglobal.jacket.reader

import kotlin.math.ceil

/*
 * "Time left in chapter" while a book is read aloud: told from where the voice is and how fast
 * it speaks. Apart from Android and Readium so it can be tested.
 *
 * It used to be told from the page in view and from how fast the voice had been turning pages.
 * But the page is not where the voice is (it starts mid-page, the listener looks elsewhere, a
 * spread holds two pages), and page turns are few and far between, so the pace was a guess for
 * the first minutes and wrong whenever the book's count of the place was put right under it: it
 * said 3 minutes with 13 to go.
 *
 * Now the text is counted, in letters (spaces aside, as the voice's places in a file are told:
 * see [TextPlaces]): what lies between the sentence being spoken and the end of its chapter
 * ([lettersLeftInChapter]). And the voice is timed as it speaks ([VoicePace]): how many letters
 * a second it really gets through, whichever voice it is and whatever speed it is set to.
 */

/**
 * How fast the voice is speaking, learnt from the sentences it reads: each one that is read
 * through without a break took from its own first word to the next one's, and held so many
 * letters. That is the voice as it is heard, the pauses between sentences included, at the speed
 * it is set to, whether it is a natural voice or the phone's own.
 *
 * It goes by the last couple of minutes, so it follows a voice that slows down or a book that
 * turns from talk to tables. A sentence that was cut short (a pause, a skip, a tap elsewhere)
 * isn't timed. Neither is the first one after the voice starts or is taken up again: it began
 * with the voice getting ready, or part of the way through. And neither is one that took no time
 * at all or far too long (the voice waiting on a slow phone). None of these say how fast the
 * next page will be read.
 */
internal class VoicePace {
    /** The sentence being read: its letters, and when it began. */
    private var letters = 0
    private var since = 0L

    /** How many sentences the voice has begun, one after the other, since it started or was last interrupted. */
    private var run = 0

    /** What has been heard, the latest counting most: letters, and the milliseconds they took. */
    private var heardLetters = 0.0
    private var heardMs = 0.0

    /** Letters a millisecond, at the speed the voice is set to; 0 until enough has been heard to say. */
    val lettersPerMs: Float
        get() = if (heardLetters >= ENOUGH_LETTERS && heardMs > 0.0) (heardLetters / heardMs).toFloat() else 0f

    /**
     * The voice has begun a sentence of [letters] letters at [now] (milliseconds). The sentence
     * before it, if it was read through, ended here: it is timed.
     */
    fun began(letters: Int, now: Long) {
        val read = this.letters
        val took = now - since
        // (The first of a run is passed over: see above.)
        if (run >= 2 && read > 0 && took > 0L) {
            val perSecond = read * 1_000.0 / took
            if (perSecond in SLOWEST..FASTEST) {
                heardLetters = heardLetters * KEPT + read
                heardMs = heardMs * KEPT + took
            }
        }
        run++
        this.letters = letters.coerceAtLeast(0)
        since = now
    }

    /** The voice was paused, taken up again, or sent elsewhere: the sentence it is in the middle of isn't timed. */
    fun interrupted() {
        run = 0
    }

    /** The speed went from [old] to [new]: what was learnt carries over, scaled, and is put right as the new speed is heard. */
    fun retimed(old: Float, new: Float) {
        if (old > 0f && new > 0f) heardMs *= old / new
        run = 0
    }

    /** Another voice reads from here (or another language): how fast the last one spoke says nothing of it. */
    fun forget() {
        heardLetters = 0.0
        heardMs = 0.0
        run = 0
    }

    private companion object {
        /** Nothing is said before this many letters have been timed: a sentence or two. */
        const val ENOUGH_LETTERS = 120.0

        /** Each sentence heard leaves this much of what was heard before it: about the last twenty count. */
        const val KEPT = 0.95

        /** Letters a second no voice reads slower or faster than, at any speed Jacket offers. */
        const val SLOWEST = 1.0
        const val FASTEST = 70.0
    }
}

/**
 * How many letters a second a voice gets through at its own pace (speed 1), for a book in
 * [language] (a BCP 47 tag), before it has been heard: what the time left is told by for the
 * first sentence or two. Writing that is a letter to a sound runs to about 14 a second; a
 * character is a syllable or a word in Chinese, Japanese and Korean, and there are far fewer.
 */
internal fun usualLettersPerSecond(language: String?): Float = when (language?.substringBefore('-')?.substringBefore('_')?.lowercase()) {
    "zh", "yue" -> 4.5f
    "ja" -> 6.5f
    "ko" -> 5.5f
    else -> 14f
}

/**
 * The letters between the sentence the voice is on and the end of its chapter; null where that
 * can't be told.
 *
 * The sentence starts [within] of the way down its chapter file (0..1, by letters), which holds
 * [fileLetters] of them. The file runs from [fileStart] to [fileEnd] of the whole book, as
 * Readium counts the book (see [Spans]), and the chapter ends at [chapterEnd] of it: at the end
 * of the file, part of the way down it (a chapter that shares its file with the next), or in a
 * file further on. What is in the voice's own file is counted exactly. The files after it haven't
 * been read: they are taken to hold letters as thickly as this one does.
 */
internal fun lettersLeftInChapter(within: Double, fileLetters: Int, fileStart: Double, fileEnd: Double, chapterEnd: Double): Float? {
    val span = fileEnd - fileStart
    if (fileLetters <= 0 || !(span > 0.0) || !chapterEnd.isFinite() || !within.isFinite()) return null
    val ends = (chapterEnd - fileStart) / span
    return ((ends - within.coerceIn(0.0, 1.0)).coerceAtLeast(0.0) * fileLetters).toFloat()
}

/**
 * The whole minutes the voice still needs for [lettersLeft] letters, rounded up ("1 min" until
 * the chapter is done); null while the letters aren't known.
 *
 * [measured] is how fast the voice has been heard to speak (letters a millisecond, see
 * [VoicePace]). Until it has been heard, the usual pace of a voice in the book's [language]
 * stands in, at the speed chosen ([rate], 1 being the voice's own).
 */
internal fun voiceMinutesLeft(lettersLeft: Float, measured: Float, rate: Float, language: String?): Int? {
    if (!(lettersLeft >= 0f)) return null
    val perMs = if (measured > 0f) measured else usualLettersPerSecond(language) * (if (rate > 0f) rate else 1f) / 1_000f
    return ceil(lettersLeft / perMs / 60_000f).toInt().coerceAtLeast(1)
}
