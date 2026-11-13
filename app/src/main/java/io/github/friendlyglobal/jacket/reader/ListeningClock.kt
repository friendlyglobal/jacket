package io.github.friendlyglobal.jacket.reader

/**
 * Listening time: the clock that runs while the voice is really speaking a book, and only then.
 * Apart from Android and the voice so it can be tested.
 *
 * Listening counts as reading (see the library's `addListening`). The voice is the app's, one at
 * a time, wherever it is heard (the book open, the screen off, Jacket behind another app, the
 * cover screen's player, the widget, a headset), so one clock here covers them all. It is told
 * each time the voice starts or stops speaking, and asked now and then while it speaks; what it
 * hands back is time to be counted, each stretch once.
 *
 * It is not running while the voice is paused, stopped (by the listener, the sleep timer, a call
 * taking the phone's sound) or still getting ready: whoever tells it the voice is speaking
 * answers for that (see [Listening]).
 *
 * Times are milliseconds of a clock that never goes back and stands still while the phone
 * sleeps (Android's uptime): a voice that is speaking keeps the phone awake, so a clock that
 * jumped forward by hours was a phone asleep with nothing being read.
 *
 * [Listening] keeps a second one, told when the player is up rather than when the voice speaks,
 * and run on the clock a reader times its pages by: what a page on screen meanwhile was not
 * read in (see [timeIn]).
 */
internal class ListeningClock {
    /** The book the voice is speaking, and when what has been counted of it ends. */
    private var book: String? = null
    private var since = 0L

    /** How long the voice has spoken each book since Jacket started, by book id. */
    private val spoken = HashMap<String, Long>()

    /** A stretch of the voice reading [bookId] aloud, [millis] long, that hasn't been counted yet. */
    data class Heard(val bookId: String, val millis: Long)

    /**
     * At [now] the voice is speaking [bookId], or (null) is not speaking. The stretch that ends
     * here, of the book it was speaking until now, is handed back to be counted; null if there
     * was none.
     */
    fun speaking(bookId: String?, now: Long): Heard? {
        val heard = take(now)
        book = bookId
        since = now
        return heard
    }

    /** Still speaking at [now]: what has been heard since the last time is handed back to be counted, and the clock runs on. */
    fun tick(now: Long): Heard? = take(now)

    private fun take(now: Long): Heard? {
        val reading = book ?: return null
        val millis = now - since
        // (A clock that went back has nothing to count, and is counted on from where it is now.)
        since = now
        if (millis <= 0L) return null
        spoken[reading] = (spoken[reading] ?: 0L) + millis
        return Heard(reading, millis)
    }

    /**
     * All the time the clock has run for [bookId] since Jacket started, up to [now], whether it
     * has been handed back yet or not.
     *
     * The same clock, told when listening is on rather than when the voice speaks, says how long
     * the player was up (the voice reading, paused or getting ready): a reader takes the
     * difference between two of these off the time a page was on screen, so that no minute is
     * counted as reading and listening both (see [Listening.listeningMs]).
     */
    fun timeIn(bookId: String, now: Long): Long =
        (spoken[bookId] ?: 0L) + if (book == bookId) (now - since).coerceAtLeast(0L) else 0L
}
