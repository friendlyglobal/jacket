package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock

/**
 * The read-aloud player, for the buttons that sit away from the reader's screen: the home-screen
 * widget's, which on a Galaxy Z Flip are on the cover screen of a phone folded shut.
 *
 * Play and pause already reach the voice the way a headphone button does (see ListenService). A
 * sentence back, a sentence on and the sleep timer are the player's own, so the voice is handed
 * over here for as long as there is one (see Listening), and these act on it: the same voice, the
 * same timer, and the same buttons' doings as in the reader, whether or not a reader is open.
 * With nothing being read aloud there is nothing to act on, and they say so by returning false.
 *
 * Main thread only, like the player itself.
 */
internal object ListenRemote {
    class Player(
        val listen: ListenState,
        val controller: () -> ListenController?,
        /** The book has chapters: "end of chapter" is one of the timers. */
        val hasChapters: () -> Boolean,
        /** The sleep timer length the listener last typed in, in minutes; 0 for none. */
        val ownSleepMinutes: () -> Int,
    )

    /** The player, while there is a voice (see Listening). */
    var player: Player? = null

    /** The voice that can be acted on: a book is being read aloud (playing or paused). */
    private fun listening(): Player? = player?.takeIf { it.listen.active && it.controller() != null }

    /** A sentence back ([delta] -1) or on (+1), as the player's own skip buttons go. */
    fun skip(delta: Int): Boolean {
        val player = listening() ?: return false
        player.controller()?.skip(delta)
        return true
    }

    /** Steps the sleep timer on to the next one at [now] (uptime millis; see [nextSleepChoice]); after the last, off. */
    fun nextSleep(now: Long = SystemClock.uptimeMillis()): Boolean {
        val player = listening() ?: return false
        val next = nextSleepChoice(player.listen.sleep, sleepChoices(player.ownSleepMinutes(), player.hasChapters()))
        player.listen.startSleep(next.timer, next.minutes, now)
        return true
    }
}
