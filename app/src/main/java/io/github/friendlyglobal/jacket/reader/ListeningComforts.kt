package io.github.friendlyglobal.jacket.reader

/*
 * The rules behind the player's comforts, kept apart from the player so they can be tested: the
 * speeds on offer, a sleep timer of any length, and where the voice takes up again after a pause.
 */

/** The speeds the player offers, from half the voice's own pace to three times it. */
val ListenRates = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/**
 * The speeds to choose from when the voice is reading at [current]: the ones on offer, and the
 * current one among them if it is another (0.8×, from an earlier Jacket), so it is never lost by
 * opening the menu.
 */
internal fun listenRateChoices(current: Float): List<Float> =
    if (ListenRates.any { it == current } || !current.isFinite() || current !in ListenRates.first()..ListenRates.last()) {
        ListenRates
    } else {
        (ListenRates + current).sorted()
    }

/** The longest sleep timer: three digits of minutes, a little over sixteen hours. */
internal const val LONGEST_SLEEP_MINUTES = 999

/** What is kept of what is typed into the sleep timer's field: digits, and no more of them than a timer can have. */
internal fun sleepMinutesTyped(text: String): String = text.filter { it in '0'..'9' }.trimStart('0').take(3)

/** The minutes a sleep timer typed as [text] runs for; null if that isn't a length of time. */
internal fun sleepMinutesOf(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..LONGEST_SLEEP_MINUTES }

/** The whole minutes a timer ending at [endsAt] still has at [now] (both in milliseconds), counted up: never less than one. */
internal fun sleepMinutesLeft(endsAt: Long, now: Long): Int = ((endsAt - now) / 60_000 + 1).coerceAtLeast(1).toInt()

/** A sleep timer as a single button sets it: which timer, and for a timed one, how many minutes. */
internal data class SleepChoice(val timer: SleepTimer, val minutes: Int = timer.minutes)

/**
 * The sleep timers a single button steps through, in order: off, the usual lengths, the
 * listener's own length ([ownMinutes], kept from the last time one was typed in; 0 for none) and,
 * in a book that has chapters, the end of the chapter. The same ones the player's menu lists.
 */
internal fun sleepChoices(ownMinutes: Int, hasChapters: Boolean): List<SleepChoice> = buildList {
    add(SleepChoice(SleepTimer.OFF))
    SleepTimer.entries.filter { it.minutes > 0 }.forEach { add(SleepChoice(it)) }
    // A length of their own that is one of the usual ones anyway is already there.
    if (ownMinutes in 1..LONGEST_SLEEP_MINUTES && SleepTimer.entries.none { it.minutes == ownMinutes }) add(SleepChoice(SleepTimer.OTHER, ownMinutes))
    if (hasChapters) add(SleepChoice(SleepTimer.CHAPTER))
}

/**
 * The timer a press of that button sets when [current] is running: the next of [choices], and
 * after the last of them, off again. A timer that isn't among them (a length typed in the player
 * and changed since) is followed by off, so a press never leaves a timer running that the button
 * can't show the way out of.
 */
internal fun nextSleepChoice(current: SleepTimer, choices: List<SleepChoice>): SleepChoice {
    val at = choices.indexOfFirst { it.timer == current }
    return choices.getOrNull(at + 1)?.takeIf { at >= 0 } ?: SleepChoice(SleepTimer.OFF)
}

/**
 * The whole minutes a timer with [leftMillis] still to run has, counted up: a timer just set to
 * 15 minutes says 15 for its first minute, and 1 for its last.
 */
internal fun sleepMinutesShown(leftMillis: Long): Int = ((leftMillis + 59_999L) / 60_000L).coerceAtLeast(0L).toInt()

/** How long until that number changes: the wait before it is counted again. */
internal fun sleepMinuteEndsIn(leftMillis: Long): Long = if (leftMillis <= 0L) 0L else (leftMillis - 1L) % 60_000L + 1L

/**
 * How many sentences back the voice takes up again after a pause of [pausedMillis], so the
 * listener finds the thread.
 *
 * After a short pause the sentence that was interrupted is still in mind, and the voice carries on
 * from it as ever. After a real break (a phone call, a change of train) it starts a sentence
 * earlier; after half an hour or more, when the listener has been somewhere else entirely, two.
 */
internal fun sentencesToRewind(pausedMillis: Long): Int = when {
    pausedMillis >= VERY_LONG_PAUSE_MS -> 2
    pausedMillis >= LONG_PAUSE_MS -> 1
    else -> 0
}

/** A pause this long is a break: the voice goes back a sentence. */
internal const val LONG_PAUSE_MS = 2 * 60_000L

/** A pause this long, and the listener has been elsewhere: two sentences. */
internal const val VERY_LONG_PAUSE_MS = 30 * 60_000L

/** A book opened again on the sentence it was left at has been away for nobody knows how long: taken for a long while. */
internal const val UNKNOWN_PAUSE_MS = VERY_LONG_PAUSE_MS
