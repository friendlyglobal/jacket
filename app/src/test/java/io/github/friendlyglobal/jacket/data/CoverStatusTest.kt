package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverStatusTest {
    private fun book(progress: Float = 0f, opened: Long = 10, finished: Long = 0) = Book(
        id = "b", title = "T", author = "A", format = BookFormat.EPUB, fileName = "b.epub",
        coverFile = null, seedColor = null, sourceName = "b.epub", sizeBytes = 1, addedAt = 1,
        progress = progress, lastOpenedAt = opened, finishedAt = finished,
    )

    private fun phase(book: Book, moment: ReadingMoment? = null) = CoverStatus.of(book, moment).phase

    @Test
    fun phasesFollowTheBook() {
        assertEquals(CoverPhase.NEW, phase(book(opened = 0)))
        // Opened, but still on the first pages.
        assertEquals(CoverPhase.NEW, phase(book(progress = 0.004f)))
        assertEquals(CoverPhase.READING, phase(book(progress = 0.42f)))
        assertEquals(CoverPhase.READING, phase(book(progress = 0.899f)))
        assertEquals(CoverPhase.ALMOST_DONE, phase(book(progress = 0.9f)))
        assertEquals(CoverPhase.ALMOST_DONE, phase(book(progress = 1f)))
        assertEquals(CoverPhase.FINISHED, phase(book(progress = 1f, finished = 5)))
    }

    @Test
    fun listeningWinsWhileItLasts() {
        val listening = ReadingMoment("b", listening = true, playing = true)
        assertEquals(CoverPhase.LISTENING, phase(book(progress = 0.95f), listening))
        assertEquals(CoverPhase.LISTENING, phase(book(progress = 1f, finished = 5), listening))
        assertTrue(CoverStatus.of(book(), listening).playing)
        // Paused is still listening; it just stops moving.
        assertEquals(CoverPhase.LISTENING, phase(book(progress = 0.3f), listening.copy(playing = false)))
    }

    @Test
    fun anotherBooksMomentIsIgnored() {
        val other = ReadingMoment("other", chapter = "IV", chapterProgress = 0.5f, listening = true, playing = true)
        val status = CoverStatus.of(book(progress = 0.3f), other)
        assertEquals(CoverPhase.READING, status.phase)
        assertEquals("", status.chapter)
        assertNull(status.chapterProgress)
        assertFalse(status.playing)
    }

    @Test
    fun chapterScaleNeedsANameAndAPlace() {
        val reading = book(progress = 0.3f)
        assertTrue(CoverStatus.of(reading, ReadingMoment("b", "IV", 0.25f)).hasChapterScale)
        assertFalse(CoverStatus.of(reading, ReadingMoment("b", "IV")).hasChapterScale)
        assertFalse(CoverStatus.of(reading, ReadingMoment("b", "", 0.25f)).hasChapterScale)
        assertEquals(1f, CoverStatus.of(reading, ReadingMoment("b", "IV", 1.4f)).chapterProgress)
    }
}
