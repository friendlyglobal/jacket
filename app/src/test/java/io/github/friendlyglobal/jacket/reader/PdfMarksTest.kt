package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.importer.LETTER
import io.github.friendlyglobal.jacket.importer.PAGE_HEIGHT
import io.github.friendlyglobal.jacket.importer.PdfLayout
import io.github.friendlyglobal.jacket.importer.PdfPageText
import io.github.friendlyglobal.jacket.importer.PdfReading
import io.github.friendlyglobal.jacket.importer.PdfSpot
import io.github.friendlyglobal.jacket.importer.page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Highlights on a PDF (see PdfMarks.kt): where one is kept, how it is found again on a page that
 * is read the same, read differently or read on another device, and which words a finger on the
 * page chooses.
 */
class PdfMarksTest {
    private val one = "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do."
    private val two = "So she was considering in her own mind whether the pleasure of making a daisy chain would be worth the trouble of getting up."
    private val three = "There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit speak."
    private val four = "In another moment down went Alice after it, never once considering how in the world she was to get out again."

    /**
     * Three pages under a running title, with a page number at the foot: two of text set in a
     * narrow column with words broken at the ends of lines, the second paragraph running over
     * onto the second page; and, after a scan with no words, one more page that opens half way down.
     */
    private val pages = listOf(
        page {
            centred("ALICE IN WONDERLAND", 20f)
            val next = column(listOf(one), left = 40f, right = 240f, top = 60f, hyphenate = true)
            column(listOf(two.substringBefore(" whether")), left = 40f, right = 240f, top = next, hyphenate = true)
            centred("1", 570f)
        },
        page {
            centred("ALICE IN WONDERLAND", 20f)
            val next = column(listOf(two.substringAfter("mind ")), left = 40f, right = 240f, top = 60f, hyphenate = true, carriedOn = true)
            column(listOf(three), left = 40f, right = 240f, top = next, hyphenate = true)
            centred("2", 570f)
        },
        PdfPageText(420f, PAGE_HEIGHT, emptyList()),
        page {
            centred("ALICE IN WONDERLAND", 20f)
            column(listOf(four), left = 40f, right = 240f, top = 300f, hyphenate = true)
            centred("4", 570f)
        },
    )
    private val read = PdfReading.paragraphs(pages)
    private val layout = PdfLayout.of(pages, read)
    private val text: (Int) -> String? = { read.getOrNull(it)?.text }

    /** The words of [pages] as a highlight is looked for in them. */
    private fun wordsOn(vararg pages: Int) = PdfPageWords(pages.flatMap(layout::paragraphsOn).distinct().sorted().map { it to read[it].text })

    /** The stretch of [paragraph] that reads [words] (the [nth] such, from 0). */
    private fun stretch(paragraph: Int, words: String, nth: Int = 0): PdfSentence {
        var at = read[paragraph].text.indexOf(words)
        repeat(nth) { at = read[paragraph].text.indexOf(words, at + 1) }
        check(at >= 0)
        return PdfSentence(paragraph, at, at + words.length)
    }

    private fun pageOf(range: PdfSentence) = layout.placeOf(range.paragraph, range.from)!!.page

    @Test
    fun `the pages are read as the paragraphs they hold`() {
        assertEquals(listOf(one, two, three, four), read.map { it.text })
        assertEquals(listOf(0, 1), layout.paragraphsOn(0))
        assertEquals(listOf(1, 2), layout.paragraphsOn(1))
        assertEquals(emptyList<Int>(), layout.paragraphsOn(2))
        assertEquals(listOf(3), layout.paragraphsOn(3))
    }

    // ---- Where a highlight is kept ---------------------------------------------------------------

    @Test
    fun `a highlight keeps its page and a little of what stands either side of its words`() {
        val range = stretch(1, "daisy chain")
        val anchor = PdfAnchor.of(pageOf(range), listOf(range), text)
        assertEquals(1, anchor.page)
        assertEquals(two.substringBefore("daisy chain").takeLast(PdfAnchor.AROUND).trim(), anchor.before)
        assertEquals(two.substringAfter("daisy chain").take(PdfAnchor.AROUND).trim(), anchor.after)
        assertTrue(anchor.before, anchor.before.endsWith("the pleasure of making a"))
        assertTrue(anchor.after, anchor.after.startsWith("would be worth the trouble"))
        assertEquals("daisy chain", pdfWordsOf(listOf(range), text))
        // It is written into the highlight, and read back from it, the same.
        assertEquals(anchor, PdfAnchor.read(anchor.write()))
    }

    @Test
    fun `what stands around a whole paragraph is the end of the one before and the start of the one after`() {
        val anchor = PdfAnchor.of(1, listOf(PdfSentence(2, 0, three.length)), text)
        assertTrue(anchor.before, anchor.before.endsWith("the trouble of getting up."))
        assertTrue(anchor.after, anchor.after.startsWith("In another moment"))
        // The first paragraph has nothing before it, and the last nothing after.
        assertEquals("", PdfAnchor.of(0, listOf(PdfSentence(0, 0, 5)), text).before)
        assertEquals("", PdfAnchor.of(3, listOf(PdfSentence(3, four.length - 6, four.length)), text).after)
    }

    @Test
    fun `only a PDF highlight's place is read as one`() {
        assertNull(PdfAnchor.read(null))
        assertNull(PdfAnchor.read(""))
        assertNull(PdfAnchor.read("{}"))
        assertNull(PdfAnchor.read("not json, but it says pdf"))
        // An EPUB's highlight keeps one of Readium's places, whatever words it holds.
        assertNull(PdfAnchor.read("""{"href":"text/part0001.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5},"text":{"highlight":"a pdf page"}}"""))
        assertEquals(PdfAnchor(4, "b", "a"), PdfAnchor.read("""{"pdf":1,"page":4,"before":"b","after":"a"}"""))
        // What a later Jacket adds to it is passed over, and what is missing is empty.
        assertEquals(PdfAnchor(7), PdfAnchor.read("""{"pdf":2,"page":7,"more":true}"""))
        // A highlight in a PDF says which page it is on; a bookmark and an EPUB's highlight have none to say.
        val made = Annotation("h", "b", AnnotationKind.HIGHLIGHT, locator = PdfAnchor(11).write(), section = 11)
        assertEquals(11, made.pdfPage)
        assertNull(made.copy(kind = AnnotationKind.BOOKMARK).pdfPage)
        assertNull(made.copy(locator = """{"href":"c.xhtml"}""").pdfPage)
        assertNull(made.copy(locator = null).pdfPage)
    }

    // ---- Finding it again ------------------------------------------------------------------------

    @Test
    fun `it is found again where it was`() {
        for (range in listOf(stretch(0, "tired of sitting"), stretch(1, "daisy chain"), stretch(2, "Rabbit"), stretch(3, "In another moment"), PdfSentence(2, 0, three.length))) {
            val page = pageOf(range)
            val anchor = PdfAnchor.of(page, listOf(range), text)
            assertEquals(listOf(range), wordsOn(page).find(pdfWordsOf(listOf(range), text), anchor))
        }
    }

    @Test
    fun `of the same words twice on a page, it is the ones with the same words around them`() {
        val first = stretch(2, "so very", nth = 0)
        val second = stretch(2, "so very", nth = 1)
        assertTrue(second.from > first.from)
        assertEquals(listOf(first), wordsOn(1).find("so very", PdfAnchor.of(1, listOf(first), text)))
        assertEquals(listOf(second), wordsOn(1).find("so very", PdfAnchor.of(1, listOf(second), text)))
        // With nothing to tell them apart by, the first.
        assertEquals(listOf(first), wordsOn(1).find("so very", PdfAnchor(1)))
    }

    @Test
    fun `a highlight that runs over a page's edge is found from the page it begins on, and marked on both`() {
        val range = stretch(1, "her own mind whether the pleasure")
        assertEquals(0, pageOf(range))
        val found = wordsOn(0).find(pdfWordsOf(listOf(range), text), PdfAnchor.of(0, listOf(range), text))
        assertEquals(listOf(range), found)
        assertEquals(listOf(0, 1), layout.marks(range.paragraph, range.from, range.to).map { it.page }.distinct())
    }

    @Test
    fun `read in another order by a later Jacket, it is found by its words whatever the paragraphs are numbered`() {
        // Made by this Jacket: over the words either side of the page's edge, and over a word that was broken at a line's end.
        val over = stretch(1, "her own mind whether the pleasure")
        val broken = stretch(0, "beginning to get very tired")
        val kept = listOf(over, broken).map { pdfWordsOf(listOf(it), text) to PdfAnchor.of(pageOf(it), listOf(it), text) }
        // A later Jacket reads the same pages differently: the running title is a paragraph now,
        // so every number has moved; the paragraph that ran over the page is two; and a word
        // broken at a line's end keeps its hyphen.
        val firstHalf = two.substringBefore(" whether")
        val secondHalf = two.substringAfter("mind ")
        val later = listOf(
            4 to "ALICE IN WONDERLAND",
            5 to one.replace("beginning", "begin-ning").replace("very tired", "very  tired"),
            6 to firstHalf,
            7 to secondHalf,
            8 to three,
        )
        val words = PdfPageWords(later)
        val (overWords, overAnchor) = kept[0]
        val foundOver = words.find(overWords, overAnchor)
        assertEquals(listOf(6, 7), foundOver.map { it.paragraph })
        assertEquals("her own mind", firstHalf.substring(foundOver[0].from, foundOver[0].to))
        assertEquals("whether the pleasure", secondHalf.substring(foundOver[1].from, foundOver[1].to))
        val (brokenWords, brokenAnchor) = kept[1]
        val foundBroken = words.find(brokenWords, brokenAnchor).single()
        assertEquals(5, foundBroken.paragraph)
        assertEquals("begin-ning to get very  tired", later[1].second.substring(foundBroken.from, foundBroken.to))
    }

    @Test
    fun `a highlight of several paragraphs is found whole, or a paragraph at a time where they are read in another order`() {
        val ranges = listOf(PdfSentence(0, one.indexOf("and of having"), one.length), PdfSentence(1, 0, "So she was considering".length))
        val words = pdfWordsOf(ranges, text)
        assertEquals("and of having nothing to do.\nSo she was considering", words)
        val anchor = PdfAnchor.of(0, ranges, text)
        assertEquals(ranges, wordsOn(0).find(words, anchor))
        // The two paragraphs the other way round (the page's columns are told apart now, say): each is found where it is.
        val swapped = PdfPageWords(listOf(10 to two, 11 to one))
        assertEquals(
            listOf(PdfSentence(10, 0, "So she was considering".length), PdfSentence(11, one.indexOf("and of having"), one.length)),
            swapped.find(words, anchor),
        )
    }

    @Test
    fun `spaces, the hyphens of broken words and letters written as one sign are no difference`() {
        val page = "The " + LIGATURE_FI + "rst of-" + LIGATURE_FI + "cial  re" + SOFT_HYPHEN + "port, " + NO_BREAK_SPACE + "at last"
        val found = PdfPageWords(listOf(0 to page)).find("first official report,", PdfAnchor(0)).single()
        assertEquals(LIGATURE_FI + "rst of-" + LIGATURE_FI + "cial  re" + SOFT_HYPHEN + "port,", page.substring(found.from, found.to))
        // And the other way: kept with the sign and the hyphen, found on a page read without them.
        val plain = "The first official report, at last"
        val back = PdfPageWords(listOf(3 to plain)).find(LIGATURE_FI + "rst of-" + LIGATURE_FI + "cial re-port", PdfAnchor(0, before = "The")).single()
        assertEquals(PdfSentence(3, 4, 4 + "first official report".length), back)
    }

    @Test
    fun `words that are not on the page are not found`() {
        assertEquals(emptyList<PdfSentence>(), wordsOn(0).find("Rabbit", PdfAnchor(0)))
        assertEquals(emptyList<PdfSentence>(), wordsOn(0).find("", PdfAnchor(0)))
        assertEquals(emptyList<PdfSentence>(), wordsOn(2).find("Alice", PdfAnchor(2)))
        // On the page it says, or else on the pages either side of it: a highlight whose page was counted one off is still found.
        assertEquals(listOf(stretch(2, "Rabbit")), wordsOn(0, 1, 2).find("Rabbit", PdfAnchor(0)))
    }

    // ---- Choosing words --------------------------------------------------------------------------

    @Test
    fun `a line's words are its runs of letters, each with its marks`() {
        val line = "Alice was  tired, (very)."
        assertEquals(listOf("Alice", "was", "tired,", "(very)."), PdfSelecting.words(line).map { line.substring(it.first, it.last + 1) })
        // Part of a line: what a paragraph has on one of its lines.
        assertEquals(listOf("was", "tired,"), PdfSelecting.words(line, 6, 17).map { line.substring(it.first, it.last + 1) })
        // A press on a word selects the word, not its comma or its bracket.
        val bare = PdfSelecting.words(line).map { PdfSelecting.bareWord(line, it) }.map { line.substring(it.first, it.last + 1) }
        assertEquals(listOf("Alice", "was", "tired", "very"), bare)
        // (A dash by itself is all there is of it.)
        assertEquals(0..0, PdfSelecting.bareWord("- and", 0..0))
        // Writing with no spaces between its words is taken a character at a time.
        val chinese = CHINESE
        assertEquals(listOf(1, 1, 5, 1), PdfSelecting.words(chinese).map { it.count() })
    }

    @Test
    fun `a finger on the page is on the line there and on the word of it`() {
        val lines = layout.linesOn(0)
        // The running title and the page number are no lines of what is read.
        assertTrue(lines.all { it.top >= 60f && it.bottom < 560f })
        assertEquals(layout.lineCountOf(0) + layout.lineCountOf(1) - layout.linesOn(1).count { it.paragraph == 1 }, lines.size)
        val first = lines.first()
        assertEquals(0, first.paragraph)
        assertEquals(0, first.start)
        val drawn = pages[0].lines.first { it.text.startsWith("Alice") }
        val at = drawn.text.indexOf("beginning")
        val x = drawn.left + at * LETTER + 2f
        val line = PdfSelecting.lineAt(lines, x, drawn.top + 5f)!!
        assertEquals(first.top, line.top, 0f)
        val words = PdfSelecting.spread(line, PdfSelecting.words(one, line.start, line.end))
        val word = PdfSelecting.wordNearest(words, x)!!
        assertEquals("beginning", one.substring(word.from, word.to))
        assertEquals(drawn.left + at * LETTER, word.left, 0.01f)
        // Past the end of the line, its last word; before its start, its first.
        assertEquals(words.last().from, PdfSelecting.wordNearest(words, 400f)!!.from)
        assertEquals(0, PdfSelecting.wordNearest(words, 0f)!!.from)
        // In the margin, and in the white under the last line, there is nothing to press on...
        assertNull(PdfSelecting.lineAt(lines, 10f, drawn.top + 5f))
        assertNull(PdfSelecting.lineAt(lines, 100f, 500f))
        // ...but a handle dragged there is taken to the nearest line: the last on the page.
        assertEquals(lines.last().top, PdfSelecting.lineNearest(lines, 100f, 500f)!!.top, 0f)
        assertEquals(first.top, PdfSelecting.lineNearest(lines, 100f, 0f)!!.top, 0f)
        assertNull(PdfSelecting.lineNearest(layout.linesOn(2), 100f, 100f))
    }

    @Test
    fun `what is selected is every word from its start to its end, in the order they are read`() {
        val selection = PdfSelection(PdfSpot(0, one.indexOf("nothing")), PdfSpot(2, "There was".length))
        val ranges = selection.ranges(layout::lengthOf)
        assertEquals(listOf(PdfSentence(0, one.indexOf("nothing"), one.length), PdfSentence(1, 0, two.length), PdfSentence(2, 0, 9)), ranges)
        assertEquals("nothing to do.\n$two\nThere was", pdfWordsOf(ranges, text))
        // Within one paragraph it is one stretch; and a paragraph it only touches is no part of it.
        assertEquals(listOf(PdfSentence(1, 3, 9)), PdfSelection(PdfSpot(1, 3), PdfSpot(1, 9)).ranges(layout::lengthOf))
        assertEquals(listOf(PdfSentence(1, 3, two.length)), PdfSelection(PdfSpot(1, 3), PdfSpot(2, 0)).ranges(layout::lengthOf))
        // Over the page's edge, it is marked on both pages.
        val over = PdfSelection.spanning(stretch(1, "mind"), stretch(1, "whether")).ranges(layout::lengthOf).single()
        assertEquals("mind whether", two.substring(over.from, over.to))
        assertEquals(listOf(0, 1), layout.marks(over.paragraph, over.from, over.to).map { it.page })
    }

    @Test
    fun `a handle moves its end of the selection, and carries on from the other end when it is taken past it`() {
        val selection = PdfSelection(PdfSpot(1, 10), PdfSpot(1, 20))
        // The handle at the end, taken further on, and back to a word still after the start.
        assertEquals(PdfSelection(PdfSpot(1, 10), PdfSpot(2, 6)) to true, selection.handleAt(atEnd = true, PdfSentence(2, 0, 6)))
        assertEquals(PdfSelection(PdfSpot(1, 10), PdfSpot(1, 14)) to true, selection.handleAt(atEnd = true, PdfSentence(1, 11, 14)))
        // Taken past the start: what is selected runs from the word it is on to where the start was, and it is the start's handle now.
        assertEquals(PdfSelection(PdfSpot(0, 3), PdfSpot(1, 10)) to false, selection.handleAt(atEnd = true, PdfSentence(0, 3, 8)))
        // The same for the handle at the start.
        assertEquals(PdfSelection(PdfSpot(1, 2), PdfSpot(1, 20)) to false, selection.handleAt(atEnd = false, PdfSentence(1, 2, 5)))
        assertEquals(PdfSelection(PdfSpot(1, 20), PdfSpot(1, 45)) to true, selection.handleAt(atEnd = false, PdfSentence(1, 40, 45)))
        // A finger that pressed on one word and went on to another has both, and all between, whichever way it went.
        val pressed = PdfSentence(1, 10, 15)
        assertEquals(PdfSelection(PdfSpot(1, 10), PdfSpot(2, 4)), PdfSelection.spanning(pressed, PdfSentence(2, 0, 4)))
        assertEquals(PdfSelection(PdfSpot(0, 7), PdfSpot(1, 15)), PdfSelection.spanning(pressed, PdfSentence(0, 7, 9)))
        assertEquals(PdfSelection(PdfSpot(1, 10), PdfSpot(1, 15)), PdfSelection.spanning(pressed, pressed))
    }

    @Test
    fun `a line by itself that ends no sentence is a heading, not the first paragraph on the page`() {
        assertTrue(PdfSelecting.isHeading("Down the Rabbit-Hole", lines = 1))
        assertTrue(PdfSelecting.isHeading("CHAPTER I", lines = 1))
        assertFalse(PdfSelecting.isHeading("She ran.", lines = 1))
        assertFalse(PdfSelecting.isHeading(one, lines = layout.lineCountOf(0)))
        assertFalse(PdfSelecting.isHeading("Alice was beginning to get very tired of sitting", lines = 2))
        assertTrue(layout.lineCountOf(0) > 2)
        assertEquals(0, layout.lineCountOf(99))
    }

    private companion object {
        const val LIGATURE_FI = "\uFB01"
        const val SOFT_HYPHEN = "\u00AD"
        const val NO_BREAK_SPACE = "\u00A0"
        const val CHINESE = "\u6211\u662F\u3000Alice\u3002"
    }
}
