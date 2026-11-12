package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.LibraryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play, with no reader open: a headphone button, the notification, the lock screen, the widget
 * or the car starts the voice in a book, at the place it was left at. Which book, and where in
 * it, are decided here.
 */
class ResumeTest {
    private fun book(id: String, format: BookFormat = BookFormat.EPUB, opened: Long = 0L, finished: Long = 0L, title: String = id, author: String = "") = Book(
        id = id, title = title, author = author, format = format, fileName = "$id.${format.extension}", coverFile = null, seedColor = null,
        sourceName = "$id.${format.extension}", sizeBytes = 1, addedAt = 1, lastOpenedAt = opened, finishedAt = finished,
    )

    // ---- Which book ---------------------------------------------------------------------------

    @Test
    fun `Play reads the book being read, the one the widget shows`() {
        val state = LibraryState(books = listOf(book("old", opened = 10), book("now", opened = 30), book("other", opened = 20)), currentId = "now")
        assertEquals("now", resumeBook(state, emptySet())?.id)
        // With none picked, the one opened last.
        assertEquals("now", resumeBook(state.copy(currentId = null), emptySet())?.id)
    }

    @Test
    fun `a comic open in the reader doesn't leave a headphone button with nothing to do`() {
        val state = LibraryState(
            books = listOf(book("novel", opened = 10), book("comic", BookFormat.CBZ, opened = 30), book("paper", BookFormat.PDF, opened = 20), book("notes", BookFormat.TXT, opened = 5)),
            currentId = "comic",
        )
        // The most recently opened book that can be read aloud.
        assertEquals("novel", resumeBook(state, emptySet())?.id)
        // A text file can be, like an EPUB.
        assertEquals("notes", resumeBook(state, setOf("novel"))?.id)
    }

    @Test
    fun `a book whose file isn't on this phone is passed over`() {
        val state = LibraryState(books = listOf(book("restored", opened = 30), book("here", opened = 10)), currentId = "restored")
        assertEquals("here", resumeBook(state, missing = setOf("restored"))?.id)
        assertNull(resumeBook(state, missing = setOf("restored", "here")))
    }

    @Test
    fun `finished books step aside, and are read only when there is nothing else`() {
        val state = LibraryState(books = listOf(book("done", opened = 30, finished = 31), book("reading", opened = 10)), currentId = "done")
        assertEquals("reading", resumeBook(state, emptySet())?.id)
        val allDone = LibraryState(books = listOf(book("done", opened = 30, finished = 31), book("older", opened = 10, finished = 11)), currentId = "done")
        assertEquals("done", resumeBook(allDone, emptySet())?.id)
        // The picked one a comic, and everything finished: the last one read that has words.
        val comic = LibraryState(books = listOf(book("comic", BookFormat.CBZ, opened = 30, finished = 31), book("older", opened = 10, finished = 11)), currentId = "comic")
        assertEquals("older", resumeBook(comic, emptySet())?.id)
    }

    @Test
    fun `with no book that can be read aloud there is nothing to play`() {
        assertNull(resumeBook(LibraryState(), emptySet()))
        assertNull(resumeBook(LibraryState(books = listOf(book("paper", BookFormat.PDF, opened = 5))), emptySet()))
    }

    @Test
    fun `the car is offered the book being read first, then the recent ones, then the finished`() {
        val state = LibraryState(
            books = listOf(
                book("a", opened = 10), book("b", opened = 40), book("now", opened = 20), book("comic", BookFormat.CBZ, opened = 50),
                book("gone", opened = 45), book("done", opened = 60, finished = 61), book("never"),
            ),
            currentId = "now",
        )
        assertEquals(listOf("now", "b", "a", "never", "done"), booksToListenTo(state, missing = setOf("gone")).map { it.id })
        assertEquals(listOf("now", "b"), booksToListenTo(state, missing = setOf("gone"), limit = 2).map { it.id })
    }

    @Test
    fun `a book asked for by name is found by its title, then by its author`() {
        val books = listOf(book("1", title = "The Time Machine", author = "H. G. Wells"), book("2", title = "Dracula", author = "Bram Stoker"), book("3", title = "The War of the Worlds", author = "H. G. Wells"))
        assertEquals("2", bookSaid("dracula", books)?.id)
        assertEquals("1", bookSaid("  Time Machine ", books)?.id)
        // More said than the title: it is among the words.
        assertEquals("2", bookSaid("Dracula by Bram Stoker", books)?.id)
        // By the author, the first of theirs as the books are offered.
        assertEquals("1", bookSaid("wells", books)?.id)
        assertNull(bookSaid("Moby Dick", books))
        assertNull(bookSaid("", books))
        assertNull(bookSaid(null, books))
    }

    // ---- Where in it ---------------------------------------------------------------------------

    private val first = "It was the best of times, it was the worst of times. It was the age of wisdom, it was the age of foolishness."
    private val second = "We had everything before us, we had nothing before us. In short, the period was so far like the present period. Some of its noisiest authorities insisted on its being received."
    private val third = "There were a king with a large jaw and a queen with a plain face, on the throne of England."
    private val file = listOf("I. The Period", first, second, third)
    private val places = TextPlaces(file)

    @Test
    fun `left on a sentence the voice stopped on, it starts with that sentence`() {
        val sentence = "In short, the period was so far like the present period."
        val spot = resumeSpot(file, places, words = sentence, around = 0.5, block = 2)
        assertEquals(2, spot?.block)
        // How far into its block the sentence starts, spaces aside: where the voice is stepped to.
        assertEquals(bare(second).indexOf(bare(sentence)), spot?.offset)
        assertTrue(spot!!.offset > 0)
    }

    @Test
    fun `a sentence that isn't found in its block starts at the top of the block`() {
        assertEquals(TextSpot(2, 0), resumeSpot(file, places, words = "Words from some other edition of the book.", around = 0.5, block = 2))
    }

    @Test
    fun `left on a page, it starts with the sentence the page's first words are in`() {
        // A page that begins part of the way through the second paragraph.
        val top = "the period was so far like the present period. Some of its noisiest"
        val spot = resumeSpot(file, places, words = top, around = 0.4)
        assertEquals(2, spot?.block)
        assertEquals(bare(second).indexOf(bare(top)), spot?.offset)
    }

    @Test
    fun `a page known only by how far down its file it is starts with the sentence that far down`() {
        // Too few words to tell a place by (a chapter's number at the top of a page), or none kept.
        for (words in listOf(null, "I.", "Words that are not in this file at all, anywhere.")) {
            val spot = resumeSpot(file, places, words = words, around = places.of(2) + 0.01)
            assertEquals("$words", 2, spot?.block)
        }
        assertEquals(TextSpot(0, 0), resumeSpot(file, places, words = null, around = 0.0))
        assertEquals(TextSpot(0, 0), resumeSpot(file, places, words = null, around = null))
        // The very end of the file is its last letter, not past it.
        val end = resumeSpot(file, places, words = null, around = 1.0)
        assertEquals(3, end?.block)
        assertEquals(bare(third).length - 1, end?.offset)
    }

    @Test
    fun `words that come twice in the file are not trusted over the place`() {
        val twice = listOf("The same words open both of these paragraphs, as it happens.", "Between them there is one that is quite different from either.", "The same words open both of these paragraphs, as it happens.")
        val spot = resumeSpot(twice, TextPlaces(twice), words = "The same words open both of these", around = 0.9)
        assertEquals(2, spot?.block)
    }

    @Test
    fun `a file with no words has nowhere to start`() {
        assertNull(resumeSpot(emptyList(), TextPlaces(emptyList()), words = "Anything at all, long enough to look for.", around = 0.5))
        assertNull(resumeSpot(listOf("", " "), TextPlaces(listOf("", " ")), words = null, around = 0.5))
    }

    @Test
    fun `an empty block is never where the voice starts`() {
        val gaps = listOf("First.", "", "Third paragraph here.")
        val places = TextPlaces(gaps)
        // Where the empty block "starts" is where the block after it does: its first letter is that block's.
        assertEquals(TextSpot(2, 0), places.spotAt(places.of(1) + 0.001))
        assertEquals(TextSpot(0, 0), places.spotAt(0.0))
    }

    // ---- How far through the book that counts as -------------------------------------------------

    @Test
    fun `starting the voice with no reader open doesn't move the book's percent`() {
        // The book was left on a page that starts 40% down its file: counted as 0.52 of the book.
        // The voice starts with a sentence a little further down the page, 0.43 by its own count.
        val lead = carriedLead(sameFile = true, counted = 0.52, own = 0.526, within = 0.40, told = 0.43)!!
        // Counted on from the page's count: the number is where it was...
        assertEquals(0.40, spokenWithin(told = 0.43, page = null, lead = lead, pages = null), 1e-9)
        // ...and goes up as the voice reads on.
        assertEquals(0.47, spokenWithin(told = 0.50, page = null, lead = lead, pages = null), 1e-9)
    }

    @Test
    fun `a voice that had to start somewhere else is counted by its own sentence`() {
        // In another file than the place saved.
        assertNull(carriedLead(sameFile = false, counted = 0.52, own = 0.526, within = 0.40, told = 0.43))
        // In the same file, but pages away from what was saved (the saved place wasn't found).
        assertNull(carriedLead(sameFile = true, counted = 0.52, own = 0.60, within = 0.40, told = 0.90))
        assertEquals(0.90, spokenWithin(told = 0.90, page = null, lead = null, pages = null), 1e-9)
    }

    // ---- What the library shows of it --------------------------------------------------------------

    @Test
    fun `a book being read aloud with no reader open is marked in the library`() {
        // Reading: the mark moves, and the "Continue reading" card's Listen button is Pause.
        val reading = playerFace(starting = null, reading = "moby", playing = true, preparing = false, asked = null, wanted = null)
        assertEquals(Aloud("moby", playing = true), aloud(reading))
        // Paused: the mark rests, and the button takes it up again.
        val paused = playerFace(starting = null, reading = "moby", playing = false, preparing = false, asked = null, wanted = null)
        assertEquals(Aloud("moby", playing = false), aloud(paused))
    }

    @Test
    fun `a book being opened for a Play is marked from the press, not from the first word`() {
        // The file is still being opened, or a natural voice is making its first sentence.
        val opening = playerFace(starting = "moby", reading = null, playing = false, preparing = false, asked = null, wanted = null)
        assertEquals(Aloud("moby", playing = true), aloud(opening))
        val gettingReady = playerFace(starting = null, reading = "moby", playing = true, preparing = true, asked = null, wanted = null)
        assertEquals(Aloud("moby", playing = true), aloud(gettingReady))
        // And a press shows at once: Pause on a voice that hasn't stopped speaking yet, Resume on one that hasn't started.
        val pausing = playerFace(starting = null, reading = "moby", playing = true, preparing = false, asked = null, wanted = false)
        assertEquals(Aloud("moby", playing = false), aloud(pausing))
        val resuming = playerFace(starting = null, reading = "moby", playing = false, preparing = false, asked = null, wanted = true)
        assertEquals(Aloud("moby", playing = true), aloud(resuming))
    }

    @Test
    fun `nothing is marked when nothing is being read aloud`() {
        assertNull(aloud(playerFace(starting = null, reading = null, playing = false, preparing = false, asked = null, wanted = null)))
        // A book only asked for (chosen in the car, Play not pressed yet) isn't being read.
        assertNull(aloud(playerFace(starting = null, reading = null, playing = false, preparing = false, asked = "moby", wanted = null)))
    }
}
