package io.github.friendlyglobal.jacket.importer

import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.leftAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** From the words the voice reads to the pages they are drawn on, and back (see PdfLayout.kt). */
class PdfLayoutTest {
    private val one = "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do."
    private val two = "So she was considering in her own mind whether the pleasure of making a daisy chain would be worth the trouble."
    private val three = "There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit speak."
    private val four = "In another moment down went Alice after it, never once considering how in the world she was to get out again."

    /** Four pages: two of text, a scan, and one more of text that opens half way down. */
    private val pages = listOf(
        page { column(listOf(one, two), left = 40f, right = 380f, top = 60f) },
        page { column(listOf(three), left = 40f, right = 380f, top = 120f) },
        PdfPageText(PAGE_WIDTH, PAGE_HEIGHT, emptyList()),
        page { column(listOf(four), left = 40f, right = 380f, top = 300f) },
    )
    private val read = PdfReading.paragraphs(pages)
    private val layout = PdfLayout.of(pages, read)

    @Test
    fun aSentenceIsOnThePageItIsDrawnOnAndAsFarDownAsItsLine() {
        assertEquals(listOf(one, two, three, four), read.map { it.text })
        assertEquals(PdfPlace(0, 60f / PAGE_HEIGHT), layout.placeOf(0, 0))
        // The second line of the first paragraph.
        val secondLine = read[0].runs[1]
        assertEquals(PdfPlace(0, (60f + PITCH) / PAGE_HEIGHT), layout.placeOf(0, secondLine.at + 3))
        assertEquals(PdfPlace(1, 120f / PAGE_HEIGHT), layout.placeOf(2, 0))
        assertEquals(PdfPlace(3, 0.5f), layout.placeOf(3, 0))
        assertNull(layout.placeOf(9, 0))
    }

    @Test
    fun aPlaceIsCountedAsThePagesBeforeItAndHowFarDownItsOwn() {
        assertEquals(0f, layout.progressOf(PdfPlace(0, 0f)), 0f)
        assertEquals(0.3f, layout.progressOf(PdfPlace(1, 0.2f)), 0.0001f)
        assertEquals(0.875f, layout.progressOf(PdfPlace(3, 0.5f)), 0.0001f)
        assertEquals(1f, layout.progressOf(PdfPlace(3, 1f)), 0f)
        // What the library shows of it: "87% read".
        assertEquals(87, book().leftAt("{}", layout.progressOf(PdfPlace(3, 0.5f)), 3, 4, 0.5f, back = false).percent)
    }

    private fun book(progress: Float = 0f) = Book("id", "A PDF", "", BookFormat.PDF, "a.pdf", null, null, "a.pdf", 1, 1, progress = progress)

    @Test
    fun listeningNeverTakesThePercentDownUnlessTheListenerWentBack() {
        // Read aloud from the second page on: the number goes up with the voice...
        var book = book()
        for (paragraph in 2..3) {
            val place = layout.placeOf(paragraph, 0)!!
            book = book.leftAt("{}", layout.progressOf(place), place.page, layout.pageCount, place.within, back = false)
        }
        assertEquals(3, book.position)
        assertEquals(0.5f, book.positionOffset, 0f)
        assertEquals(87, book.percent)
        // ...a place counted again a little lower leaves it where it was...
        val again = layout.placeOf(2, 0)!!
        assertEquals(87, book.leftAt("{}", layout.progressOf(again), again.page, layout.pageCount, again.within, back = false).percent)
        // ...and only a sentence the listener went back to takes it down. The place itself is always the one given.
        val back = book.leftAt("{}", layout.progressOf(again), again.page, layout.pageCount, again.within, back = true)
        assertEquals(30, back.percent)
        assertEquals(1, back.position)
    }

    @Test
    fun readingStartsWithTheFirstWordsBelowTheTopOfTheScreen() {
        assertEquals(PdfSpot(0, 0), layout.spotAt(PdfPlace(0, 0f)))
        // Scrolled part of the way past the first paragraph's first line: its second.
        assertEquals(PdfSpot(0, read[0].runs[1].at), layout.spotAt(PdfPlace(0, 65f / PAGE_HEIGHT)))
        // A place kept as that second line's own comes back as it, whatever a fraction loses on the way.
        assertEquals(PdfSpot(0, read[0].runs[1].at), layout.spotAt(layout.placeOf(0, read[0].runs[1].at + 4)!!))
        // Past all of the first paragraph: the second.
        val under = pages[0].lines[read[0].runs.last().line].bottom + 1f
        assertEquals(PdfSpot(1, 0), layout.spotAt(PdfPlace(0, under / PAGE_HEIGHT)))
        // Past everything on the page: the next page's first words.
        assertEquals(PdfSpot(2, 0), layout.spotAt(PdfPlace(0, 0.99f)))
    }

    @Test
    fun aPageWithNoTextIsPassedOver() {
        assertFalse(layout.hasText(2))
        assertTrue(layout.hasText(3))
        // Started on the scan, the voice begins with the next page that has words.
        assertEquals(PdfSpot(3, 0), layout.firstOn(2))
        assertEquals(1, layout.textPageBefore(3))
        assertNull(layout.textPageBefore(0))
        // And after the last words there is nothing to start with.
        assertNull(layout.spotAt(PdfPlace(3, 0.9f)))
    }

    @Test
    fun onAPageOfColumnsReadingStartsInTheLeftOne() {
        val columns = page {
            column(listOf(one, two), left = 30f, right = 200f, top = 60f)
            column(listOf(three, four), left = 220f, right = 390f, top = 60f)
        }
        val paragraphs = PdfReading.paragraphs(listOf(columns))
        val layout = PdfLayout.of(listOf(columns), paragraphs)
        // The screen's top a few lines down: the left column's line there, not the right column's.
        val spot = layout.spotAt(PdfPlace(0, 100f / PAGE_HEIGHT))!!
        assertEquals(0, spot.paragraph)
        assertTrue(spot.offset > 0)
    }

    @Test
    fun aSentenceIsMarkedLineByLineWhereItsLettersAre() {
        // "of having nothing to do." ends the first paragraph, on its last line.
        val from = one.indexOf("and of having")
        val marks = layout.marks(0, from, one.length)
        assertTrue(marks.isNotEmpty())
        for (mark in marks) {
            val line = pages[mark.page].lines.first { it.top == mark.top }
            // Counted as the PDF reader counts the page's text: the line's own start, and so many letters in.
            assertEquals(one.substring(mark.start, mark.end), line.text.substring(mark.from - line.start, mark.to - line.start))
        }
        assertEquals(one.substring(from).replace(" ", ""), marks.joinToString("") { one.substring(it.start, it.end) }.replace(" ", ""))
        assertEquals(emptyList<PdfMark>(), layout.marks(0, 5, 5))
        assertEquals(emptyList<PdfMark>(), layout.marks(7, 0, 5))
    }

    @Test
    fun aSentenceThatRunsOverThePageIsMarkedOnBoth() {
        val whole = "$one $two"
        val set = PageBuilder().apply { column(listOf(whole), left = 40f, right = 380f, top = 60f) }.build()
        val first = page { set.lines.take(2).forEach { line(it.text, it.left, 500f + (it.top - 60f)) } }
        val second = page { set.lines.drop(2).forEachIndexed { i, it -> line(it.text, it.left, 60f + i * PITCH) } }
        val paragraphs = PdfReading.paragraphs(listOf(first, second))
        assertEquals(listOf(whole), paragraphs.map { it.text })
        val layout = PdfLayout.of(listOf(first, second), paragraphs)
        val sentence = whole.indexOf("So she")
        val marks = layout.marks(0, sentence, whole.length)
        assertEquals(listOf(0, 1), marks.map { it.page }.distinct())
        // The voice is on the first page until it reaches the words on the second: the page turns there.
        assertEquals(0, layout.placeOf(0, sentence)!!.page)
        assertEquals(1, layout.placeOf(0, marks.first { it.page == 1 }.start)!!.page)
    }

    @Test
    fun nextPageGoesToTheFirstSentenceThatBeginsThere() {
        // From the first page: the second page's first words.
        assertEquals(PdfSpot(2, 0), layout.nextPageFrom(0, 0, 20))
        // From the second: over the scan, to the fourth.
        assertEquals(PdfSpot(3, 0), layout.nextPageFrom(2, 0, 20))
        assertNull(layout.nextPageFrom(3, 0, 20))

        // A sentence that runs over the page is already being read when the page turns: the one after it.
        val whole = "$one $two $three"
        val set = PageBuilder().apply { column(listOf(whole), left = 40f, right = 380f, top = 60f) }.build()
        val first = page { set.lines.take(2).forEach { line(it.text, it.left, 500f + (it.top - 60f)) } }
        val second = page { set.lines.drop(2).forEachIndexed { i, it -> line(it.text, it.left, 60f + i * PITCH) } }
        val paragraphs = PdfReading.paragraphs(listOf(first, second))
        val layout = PdfLayout.of(listOf(first, second), paragraphs)
        assertEquals(whole.length, layout.lengthOf(0))
        val sentence = whole.indexOf("So she")
        val after = whole.indexOf("There was")
        // (Its end is the space before the next sentence, which is where that one is found from.)
        assertEquals(PdfSpot(0, after - 1), layout.nextPageFrom(0, sentence, after - 1))
        // A sentence wholly on the first page: the first words on the second, though they are the middle of a sentence.
        assertEquals(1, layout.placeOf(0, layout.nextPageFrom(0, 0, sentence - 1)!!.offset)!!.page)
    }

    @Test
    fun aLineWhoseCountIsNotKnownIsMarkedWhole() {
        val page = PdfPageText(PAGE_WIDTH, PAGE_HEIGHT, listOf(PdfLine("A line the reader couldn't count.", 40f, 60f, 300f, 70f, start = -1)))
        val layout = PdfLayout.of(listOf(page), PdfReading.paragraphs(listOf(page)))
        val mark = layout.marks(0, 2, 6).single()
        assertEquals(-1, mark.from)
        assertEquals(40f, mark.left, 0f)
        assertEquals(300f, mark.right, 0f)
    }

    @Test
    fun theOutlinesChaptersOpenAtTheParagraphsTheyPointAt() {
        val outline = listOf(
            PdfOutlineEntry("The Bank", 0, page = 0, within = 0f),
            // Part of the way down the first page: its second paragraph.
            PdfOutlineEntry("The Daisies", 1, page = 0, within = (pages[0].lines[read[1].runs.first().line].top - 2f) / PAGE_HEIGHT),
            PdfOutlineEntry("The Rabbit", 0, page = 1),
            // It points at the scan: the words read first from there are the next page's.
            PdfOutlineEntry("The Hole", 0, page = 2),
            PdfOutlineEntry("Nowhere", 0, page = 40),
        )
        val layout = PdfLayout.of(pages, read, outline)
        assertEquals(listOf("The Bank", "The Daisies", "The Rabbit", "The Hole"), layout.chapters.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), layout.chapters.map { it.paragraph })
        assertEquals(listOf(0, 1, 2, 3), (0..3).map(layout::chapterOf))
        assertEquals(2, layout.chapterAt(1))
        // Each chapter begins a file of the pages made for the voice, so "end of chapter" can tell.
        assertEquals(listOf(0, 1, 2, 3), (0..3).map(layout::fileOf))
        assertEquals("text/part0003.xhtml", layout.hrefOf(2))
        assertEquals(2, layout.fileNamed("text/part0003.xhtml#p2"))
        assertEquals(-1, layout.fileNamed("text/part0009.xhtml"))
        assertEquals(2, layout.paragraphOf("#p2"))
        assertNull(layout.paragraphOf("#p12"))
    }

    @Test
    fun withoutAnOutlineTheParagraphsShareAFile() {
        assertEquals(emptyList<PdfChapter>(), layout.chapters)
        assertEquals(1, layout.fileCount)
        assertEquals(-1, layout.chapterOf(3))
    }

    @Test
    fun aLayoutKeptAndReadBackIsTheSame() {
        val full = PdfLayout.of(pages, read, listOf(PdfOutlineEntry("The Rabbit", 0, page = 1)))
        val bytes = ByteArrayOutputStream().also { full.write(DataOutputStream(it)) }.toByteArray()
        val back = PdfLayout.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(full.pageCount, back.pageCount)
        assertEquals(full.paragraphs, back.paragraphs)
        assertEquals(full.placeOf(3, 0), back.placeOf(3, 0))
        assertEquals(full.spotAt(PdfPlace(1, 0f)), back.spotAt(PdfPlace(1, 0f)))
        assertEquals(full.chapters.map { it.title to it.paragraph }, back.chapters.map { it.title to it.paragraph })
        assertEquals(full.marks(0, 0, 40).map { it.from to it.to }, back.marks(0, 0, 40).map { it.from to it.to })
        // Something else, or cut short, is refused rather than believed.
        assertTrue(runCatching { PdfLayout.read(DataInputStream(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)))) }.isFailure)
        assertTrue(runCatching { PdfLayout.read(DataInputStream(ByteArrayInputStream(ByteArray(64)))) }.isFailure)
    }

    @Test
    fun aSentenceIsFoundInItsParagraphByHowItOpens() {
        val paragraph = "Down, down, down. Would the fall never come to an end? Down, down, down. There was nothing else to do."
        assertEquals(0, sentenceOffset(paragraph, "Down, down, down."))
        assertEquals(18, sentenceOffset(paragraph, "Would the fall never come to an end?"))
        // The same words twice: told apart by what stands before them.
        assertEquals(55, sentenceOffset(paragraph, "Down, down, down.", before = "never come to an end? "))
        assertEquals(0, sentenceOffset(paragraph, "Down, down, down.", before = ""))
        // A sentence the voice cut its own way is still found by its opening.
        assertEquals(73, sentenceOffset(paragraph, "There was nothing else to do. And more."))
        assertEquals(0, sentenceOffset(paragraph, "Words that are not there."))
    }
}
