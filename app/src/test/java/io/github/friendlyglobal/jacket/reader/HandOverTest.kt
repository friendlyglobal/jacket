package io.github.friendlyglobal.jacket.reader

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-aloud belongs to the app, which keeps it reading with no reader open, and a reader that
 * opens the book shows and steers the same voice. This is how the voice, and the player's state,
 * pass between the two: one voice at a time, never a second player, and a voice kept only while
 * it has something to do.
 */
class HandOverTest {
    /** A voice that keeps what was done to it. */
    private class Voice(override val bookId: String, override val listen: ListenState = ListenState()) : BookVoice {
        override var attached = false
        var held = false
        var stops = 0
        var closed = false

        fun read() {
            listen.active = true
            listen.playing = true
        }

        override fun stop() {
            stops++
            listen.active = false
            listen.playing = false
            onStop(this)
        }

        var onStop: (Voice) -> Unit = {}

        override fun hold(): Boolean {
            if (closed) return false
            held = true
            return true
        }

        override fun release() {
            held = false
        }
    }

    private val hand = HandOver<Voice>()

    /** A voice as [Listening] wires it: stopping tells the hand-over, as [ReadAloud] does. */
    private fun voice(bookId: String, listen: ListenState = ListenState()) = Voice(bookId, listen).also { it.onStop = hand::stopped }

    /** Play with no reader open: a voice is made for the book and starts. */
    private fun startWithNoReader(bookId: String): Voice = voice(bookId, hand.stateForVoice(bookId)).also {
        assertTrue(hand.speaking(it))
        it.read()
    }

    /** A reader opens [bookId]: its screen first, then (the file opened) its pages. */
    private fun openReader(bookId: String): Voice {
        val listen = hand.stateFor(bookId)
        hand.entered(bookId)
        val voice = hand.voiceOf(bookId, listen) ?: voice(bookId, listen)
        hand.install(voice)
        voice.attached = true
        return voice
    }

    private fun closeReader(voice: Voice) {
        voice.attached = false
        hand.uninstall(voice)
        hand.left(voice.bookId, voice.listen)
    }

    // ---- A reader opening the book that is being read aloud --------------------------------------

    @Test
    fun `a reader that opens the book being read aloud gets the same voice and the same player`() {
        val reading = startWithNoReader("book")
        val joined = openReader("book")
        assertSame(reading, joined)
        assertSame(reading.listen, hand.stateFor("book"))
        // Nothing was stopped or started over to get there.
        assertEquals(0, reading.stops)
        assertTrue(reading.listen.playing)
        assertSame(reading, hand.reading)
    }

    @Test
    fun `a book opened while nothing is read aloud gets a voice of its own, waiting to be started`() {
        val voice = openReader("book")
        assertSame(voice, hand.voice)
        assertTrue(voice.held)
        assertFalse(voice.listen.active)
        assertNull(hand.reading)
        // The reader asks again as it recomposes: the same state, not a new player each time.
        assertSame(voice.listen, hand.stateFor("book"))
    }

    @Test
    fun `Play pressed while a book is still opening starts the voice the reader will show`() {
        // The reader's screen is up and showing a player; its file is still being opened.
        val shown = hand.stateFor("book")
        hand.entered("book")
        // A headphone button meanwhile: the voice made for it takes up the state the reader shows.
        val voice = startWithNoReader("book")
        assertSame(shown, voice.listen)
        // The file opened, the reader finds that voice rather than making a second one.
        assertSame(voice, hand.voiceOf("book", shown))
    }

    // ---- One voice at a time -----------------------------------------------------------------

    @Test
    fun `opening another book ends listening to the first`() {
        val first = startWithNoReader("first")
        val second = openReader("second")
        assertEquals(1, first.stops)
        assertFalse(first.held)
        assertSame(second, hand.voice)
        assertNotSame(first.listen, second.listen)
        assertFalse(second.listen.active)
    }

    @Test
    fun `a voice that starts takes the place of the one reading`() {
        val first = startWithNoReader("first")
        val second = startWithNoReader("second")
        assertEquals(1, first.stops)
        assertFalse(first.held)
        assertSame(second, hand.voice)
        assertSame(second, hand.reading)
    }

    @Test
    fun `a book chosen from the car while another is open in the reader leaves that reader its own player`() {
        val open = openReader("open")
        val chosen = startWithNoReader("chosen")
        assertSame(chosen, hand.reading)
        // The reader's voice isn't the app's any more, but it is still the reader's: untouched, and not playing.
        assertEquals(0, open.stops)
        assertTrue(open.held)
        assertFalse(open.listen.active)
        assertNotSame(open.listen, chosen.listen)
        // Listen pressed in that reader takes over in turn.
        assertTrue(hand.speaking(open))
        open.read()
        assertEquals(1, chosen.stops)
        assertFalse(chosen.held)
        assertSame(open, hand.reading)
    }

    @Test
    fun `a reader opening while the car's book plays does not take the voice from it`() {
        val chosen = startWithNoReader("chosen")
        // (Its screen was up before the car asked, so opening it ended nothing.)
        val listen = hand.stateFor("open")
        val waiting = voice("open", listen)
        hand.install(waiting)
        assertSame(chosen, hand.voice)
        assertEquals(0, chosen.stops)
        assertFalse(waiting.held)
    }

    // ---- A reader going away ------------------------------------------------------------------

    @Test
    fun `a voice still reading when its reader's screen goes reads on without it`() {
        val voice = openReader("book")
        assertTrue(hand.speaking(voice))
        voice.read()
        closeReader(voice)
        assertEquals(0, voice.stops)
        assertTrue(voice.held)
        assertSame(voice, hand.reading)
        assertNull(hand.inReader)
        // Stopped later, with no reader: there is nothing to keep it for.
        voice.stop()
        assertFalse(voice.held)
        assertNull(hand.voice)
    }

    @Test
    fun `a voice that isn't reading goes with its reader`() {
        val voice = openReader("book")
        closeReader(voice)
        assertFalse(voice.held)
        assertNull(hand.voice)
        // Opened again, the book starts from a fresh player.
        assertNotSame(voice.listen, hand.stateFor("book"))
    }

    @Test
    fun `a voice stopped in an open reader stays for the reader to start again`() {
        val voice = openReader("book")
        assertTrue(hand.speaking(voice))
        voice.read()
        voice.stop()
        assertTrue(voice.held)
        assertSame(voice, hand.voice)
        assertNull(hand.reading)
    }

    @Test
    fun `a voice stopped while its reader is still opening is kept for it`() {
        val shown = hand.stateFor("book")
        hand.entered("book")
        val voice = startWithNoReader("book")
        voice.stop()
        // The reader's pages aren't there yet, but its screen is: the voice it will find is this one.
        assertSame(voice, hand.voiceOf("book", shown))
    }

    @Test
    fun `a voice whose book has been closed can't start`() {
        val reading = startWithNoReader("first")
        val stale = voice("second").also { it.closed = true }
        assertFalse(hand.speaking(stale))
        // And the one reading reads on.
        assertSame(reading, hand.reading)
        assertEquals(0, reading.stops)
    }

    // ---- What the phone is told ---------------------------------------------------------------

    @Test
    fun `with nothing being read aloud the player is empty, and Play resumes the book being read`() {
        assertEquals(PlayerFace(null, Player.STATE_IDLE, playWhenReady = false, skips = false), playerFace(null, null, playing = false, preparing = false, asked = null, wanted = null))
        // A book asked for from the car, before its Play.
        assertEquals(PlayerFace("asked", Player.STATE_IDLE, playWhenReady = false, skips = false), playerFace(null, null, playing = false, preparing = false, asked = "asked", wanted = null))
    }

    @Test
    fun `opening the book and loading the voice count as playing`() {
        // Android gives a service started by a Play a few seconds to show that it is playing.
        val opening = playerFace(starting = "book", reading = null, playing = false, preparing = false, asked = null, wanted = null)
        assertEquals(PlayerFace("book", Player.STATE_BUFFERING, playWhenReady = true, skips = false), opening)
        val loading = playerFace(starting = null, reading = "book", playing = true, preparing = true, asked = null, wanted = null)
        assertEquals(PlayerFace("book", Player.STATE_BUFFERING, playWhenReady = true, skips = true), loading)
    }

    @Test
    fun `reading and paused are told as they are, whichever book was asked for meanwhile`() {
        assertEquals(PlayerFace("book", Player.STATE_READY, playWhenReady = true, skips = true), playerFace(null, "book", playing = true, preparing = false, asked = null, wanted = null))
        assertEquals(PlayerFace("book", Player.STATE_READY, playWhenReady = false, skips = true), playerFace(null, "book", playing = false, preparing = false, asked = null, wanted = null))
        // Another book chosen in the car: the one being read is what is playing until Play changes it.
        assertEquals("book", playerFace(null, "book", playing = true, preparing = false, asked = "other", wanted = null).bookId)
        // Then the other is opened, and that is what the phone shows.
        assertEquals("other", playerFace("other", "book", playing = true, preparing = false, asked = null, wanted = null).bookId)
    }

    @Test
    fun `a paused voice asked to read counts as reading at once`() {
        // The voice takes a moment to say it is reading; the service's promise to play can't wait for it.
        assertTrue(playerFace(null, "book", playing = false, preparing = false, asked = null, wanted = true).playWhenReady)
        assertFalse(playerFace(null, "book", playing = false, preparing = false, asked = null, wanted = null).playWhenReady)
    }

    // ---- The sleep timer, with and without a reader ------------------------------------------------

    @Test
    fun `end of chapter is told by the voice's file, and by the page's chapter when a reader is open`() {
        // With no reader open the page says nothing (-1): the voice moving into another file ends the chapter.
        assertFalse(chapterEnded(startFile = 3, startChapter = -1, file = 3, chapter = -1))
        assertTrue(chapterEnded(startFile = 3, startChapter = -1, file = 4, chapter = -1))
        // With one, chapters that share a file end when the page reaches the next of them.
        assertTrue(chapterEnded(startFile = 3, startChapter = 7, file = 3, chapter = 8))
        assertFalse(chapterEnded(startFile = 3, startChapter = 7, file = 3, chapter = 7))
        // A voice that hasn't said which file it is in yet (-1) has not left one.
        assertFalse(chapterEnded(startFile = -1, startChapter = -1, file = 2, chapter = -1))
        assertFalse(chapterEnded(startFile = 3, startChapter = -1, file = -1, chapter = -1))
    }

    // ---- The voice, with and without a reader ------------------------------------------------------

    @Test
    fun `a voice started with no reader open is voiced as the reader would voice it`() {
        // The book's own language, as its file gives it: nothing is chosen for it.
        voicing(bookLanguage = "en", said = "en", phoneLanguage = "pt-BR", text = false).let {
            assertEquals("en", it.readIn)
            assertNull(it.chosen)
        }
        // A language the listener picked for a book that says another.
        voicing(bookLanguage = "pt", said = "en", phoneLanguage = "en-US", text = false).let {
            assertEquals("pt", it.readIn)
            assertEquals("pt", it.chosen)
        }
        // A book that doesn't say is read in what the library has for it.
        assertEquals("fr", voicing(bookLanguage = "fr", said = null, phoneLanguage = "en-US", text = false).chosen)
        // A text file whose language couldn't be told is read in the phone's; a book that says nothing is left to the voice.
        assertEquals("de-DE", voicing(bookLanguage = null, said = null, phoneLanguage = "de-DE", text = true).readIn)
        assertNull(voicing(bookLanguage = null, said = null, phoneLanguage = "de-DE", text = false).readIn)
    }
}
