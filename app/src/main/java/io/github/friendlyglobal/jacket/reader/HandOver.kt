package io.github.friendlyglobal.jacket.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/** A book's voice, as far as handing it between the app and a reader goes (see [HandOver]; the voice itself is [ReadAloud]). */
internal interface BookVoice {
    val bookId: String

    /** The player's state: what a reader showing this book shows. */
    val listen: ListenState

    /** A reader has the book open and has handed the voice its pages. */
    val attached: Boolean

    fun stop()

    /** Keeps the book open for the voice; false if it has been closed meanwhile. */
    fun hold(): Boolean

    /** Lets the book go. */
    fun release()
}

/**
 * Who has the voice: the rules by which read-aloud passes between the app, which keeps it
 * reading with no reader open, and a reader's screen, which shows and steers it. Apart from
 * Android and Readium, so they can be tested.
 *
 * There is one voice at a time. A reader that opens the book it is reading gets that same voice,
 * and the same player state, so it shows what is playing rather than a player of its own; one
 * that opens any other book ends it. A voice that starts takes the place of whichever was
 * reading. And a voice is kept only while it has something to do: reading (or paused in the
 * middle of it), or waiting in an open reader to be started.
 */
internal class HandOver<V : BookVoice> {
    /** The voice: reading a book aloud (or paused in one), or waiting in an open reader to be started. */
    var voice: V? by mutableStateOf(null)
        private set

    /** The book a reader has on screen, whether or not it is read aloud. */
    var inReader: String? by mutableStateOf(null)
        private set

    /** The player's state a reader has been given for its book, before that book has a voice. */
    private var pending: Pair<String, ListenState>? = null

    /** The voice, if it is reading (or paused in the middle of reading). */
    val reading: V? get() = voice?.takeIf { it.listen.active }

    /**
     * The player's state for the reader of [bookId] to show: the state of the voice that is
     * reading that book already, if one is, so the reader opens on the same player; else one of
     * its own, which a voice made for the book takes up (see [stateForVoice]).
     */
    fun stateFor(bookId: String): ListenState {
        pending?.takeIf { it.first == bookId }?.let { return it.second }
        val reading = Snapshot.withoutReadObservation { voice }?.takeIf { it.bookId == bookId }
        return (reading?.listen ?: ListenState()).also { pending = bookId to it }
    }

    /** The player's state for a voice made for [bookId] with no reader's doing: the one a reader that is opening the book already shows, if there is one. */
    fun stateForVoice(bookId: String): ListenState = pending?.takeIf { it.first == bookId }?.second ?: ListenState()

    /** The voice already there for the book a reader has open, showing [listen]; null if one is to be made. */
    fun voiceOf(bookId: String, listen: ListenState): V? =
        Snapshot.withoutReadObservation { voice }?.takeIf { it.listen === listen && it.bookId == bookId }

    /** A reader has [bookId] on screen. Opening a book ends listening to any other. */
    fun entered(bookId: String) {
        inReader = bookId
        voice?.takeIf { it.bookId != bookId && it.listen.active }?.stop()
    }

    /** The reader of [bookId], which showed [listen], has gone. A voice still reading goes on without it. */
    fun left(bookId: String, listen: ListenState) {
        if (inReader == bookId) inReader = null
        if (pending?.second === listen) pending = null
        voice?.takeIf { it.listen === listen && !it.listen.active && !it.attached }?.let(::drop)
    }

    /**
     * [voice]'s reader is on screen. It becomes the voice, unless another book is being read
     * aloud right now (asked for from the car while this one was opening): it then waits, and
     * starting it takes over (see [speaking]).
     */
    fun install(voice: V) {
        val other = this.voice
        if (other === voice) {
            voice.hold()
            return
        }
        if (other != null && other.listen.active) return
        if (!voice.hold()) return
        other?.takeIf { !it.attached }?.release()
        this.voice = voice
    }

    /** [voice]'s reader has gone: unless it is reading still, it goes too. */
    fun uninstall(voice: V) {
        if (!voice.listen.active) drop(voice)
    }

    /**
     * [voice] is about to read: it is the voice from here, and whichever was reading stops (the
     * sentence it was on is its book's place). False if its book has been closed meanwhile.
     */
    fun speaking(voice: V): Boolean {
        if (!voice.hold()) return false
        val other = this.voice
        if (other !== voice) {
            this.voice = voice
            other?.let {
                if (it.listen.active) it.stop()
                if (!it.attached) it.release()
            }
        }
        return true
    }

    /** [voice] has stopped. With no reader open on its book there is nothing to keep it for. */
    fun stopped(voice: V) {
        if (!voice.attached && inReader != voice.bookId) drop(voice)
    }

    private fun drop(voice: V) {
        voice.release()
        if (this.voice === voice) this.voice = null
    }
}
