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
