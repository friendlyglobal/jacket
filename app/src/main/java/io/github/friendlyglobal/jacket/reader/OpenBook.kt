package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.TextPages
import io.github.friendlyglobal.jacket.importer.NothingToRead
import io.github.friendlyglobal.jacket.importer.PdfPages
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.readium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.Url
import java.io.File

/**
 * A book opened with Readium, for whoever needs it open: the reader's screen, to show its pages,
 * and read-aloud, which goes on reading it with no reader on screen (see [Listening]). They share
 * the one opening of it, and it is closed when the last of them lets go (see [OpenBooks]).
 *
 * A PDF is opened this way only to be read aloud: its own pages are drawn by Android, and what
 * Readium opens is the plain pages made of its words (see PdfWords.kt).
 */
internal class OpenBook(
    val bookId: String,
    /** The file Readium reads: the book's own, or the pages made of a text file or of a PDF's words. */
    val file: File,
    val publication: Publication,
    /** For a text file: its pages, and where its paragraphs are in them. */
    val text: TextPages?,
    /** For a PDF: the pages it is read aloud from, and where their paragraphs are on the PDF's own. */
    val pdf: PdfPages? = null,
) {
    /** The book's files as read-aloud reads them: where its sentences are, in the pages' terms. */
    val spoken = SpokenFiles(publication)
    val contents by lazy { Contents(publication) }

    /** How many are using the book, and whether it has been closed (see [OpenBooks]). */
    internal var holders = 0
    internal var closed = false

    /** Readium's positions and where each file starts in the whole book (see [count]); null until they have been counted. */
    @Volatile var counted: Counted? = null
        private set
    private val counting = Mutex()

    class Counted(val positions: List<Locator>, val spans: Spans)

    /** Counts the book's positions, which reads all of it, once: how far through the book a place is, is told by them. */
    suspend fun count(): Counted = counting.withLock {
        counted ?: withContext(Dispatchers.IO) {
            val positions = runCatching { publication.positions() }.getOrDefault(emptyList())
            Counted(positions, Spans(positions, contents))
        }.also { counted = it }
    }

    /**
     * Which chapter a file is counted in, so the sleep timer knows when one ends. (A long chapter
     * of a text file is cut into several files: together they are one chapter. So is a long
     * chapter of a PDF's outline, and what comes before its first.)
     */
    fun chapterFileOf(href: Url): Int = contents.resourceOf(href).let { file ->
        pdf?.layout?.let { it.chapterOf(it.firstIn(file)) + 1 } ?: text?.layout?.groupOf(file) ?: file
    }

    /**
     * The place [book] was left at, to open it or to start reading it aloud from; null for the
     * first page. A sentence the voice stopped on, as Jacket 1.0.0 saved it, says which of its
     * file's blocks it is in, not where on the pages: it is told the pages' way first. (One saved
     * since comes out as it went in.)
     */
    suspend fun leftAt(book: Book): Locator? = left(book).place

    /** The same, and whether it was one of Jacket 1.0.0's sentences (see [Left.oldCount]). */
    suspend fun left(book: Book): Left {
        // A PDF's place is a page and how far down it: told as the words that are read from there.
        if (pdf != null) return Left(pdfLeft(book), oldCount = false)
        val saved = savedLocator(book, publication, file, text)
        if (saved?.isSpoken != true) return Left(saved, oldCount = false)
        val placed = spoken.placed(saved)
        return Left(placed, savedTheOldWay(saved.locations.progression, placed.locations.progression))
    }

    /**
     * Where a book was left ([place]; null for the first page), and whether that is a sentence
     * Jacket 1.0.0 saved by its own count ([oldCount]): the book's progress was counted wrongly
     * with it, and is counted over the first time the place is seen, up or down.
     */
    class Left(val place: Locator?, val oldCount: Boolean)
}

/**
 * The books that are open, one opening each. Opening a book is asked for with [acquire] and given
 * back with [release]; the book is closed when everyone who asked has given it back.
 */
internal class OpenBooks(private val context: Context) {
    private val opening = Mutex()
    private val open = HashMap<String, OpenBook>()

    /**
     * Opens [book], or joins those who have it open. Throws if it can't be opened; a text file
     * with nothing in it throws [NothingToRead], and a PDF that can't be read aloud says why (see
     * [io.github.friendlyglobal.jacket.data.Library.pdfPages]).
     */
    suspend fun acquire(book: Book): OpenBook = opening.withLock {
        synchronized(open) { open[book.id]?.takeIf { !it.closed }?.also { it.holders++ } }
            ?: withContext(Dispatchers.IO) { read(book) }.also { synchronized(open) { it.holders = 1; open[book.id] = it } }
    }

    private suspend fun read(book: Book): OpenBook {
        val library = context.library
        val text = if (book.format == BookFormat.TXT) library.textPages(book) else null
        if (text != null && text.layout.paragraphs == 0) throw NothingToRead(book.sourceName)
        // (Throws for a PDF with no words in it, and on a phone that can't get at them.)
        val pdf = if (book.format == BookFormat.PDF) library.pdfPages(book) else null
        val file = pdf?.file ?: text?.file ?: library.fileOf(book)
        return OpenBook(book.id, file, context.readium.open(file), text, pdf)
    }

    /** One more use of a book that is open; false if it has been closed meanwhile. */
    fun retain(book: OpenBook): Boolean = synchronized(open) {
        if (book.closed) false else { book.holders++; true }
    }

    fun release(book: OpenBook) {
        val close = synchronized(open) {
            if (book.closed || --book.holders > 0) return
            book.closed = true
            if (open[book.bookId] === book) open.remove(book.bookId)
            true
        }
        if (close) runCatching { book.publication.close() }
    }

    /** Gives the book back once its pages have been torn down (the reader's fragment goes after composition). */
    fun releaseSoon(book: OpenBook) {
        Handler(Looper.getMainLooper()).postDelayed({ release(book) }, 2_000)
    }
}
