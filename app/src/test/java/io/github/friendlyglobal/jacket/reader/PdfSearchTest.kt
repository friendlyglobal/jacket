package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.importer.PAGE_HEIGHT
import io.github.friendlyglobal.jacket.importer.PITCH
import io.github.friendlyglobal.jacket.importer.PdfLayout
import io.github.friendlyglobal.jacket.importer.PdfPageText
import io.github.friendlyglobal.jacket.importer.PdfReading
import io.github.friendlyglobal.jacket.importer.page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search inside a PDF: what is found in its words, and where on its pages that is (see PdfSearch.kt). */
class PdfSearchTest {
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

    private fun matches(typed: String): List<PdfMatch> =
        read.flatMapIndexed { index, paragraph -> PdfFinding.matches(index, paragraph.text, TextSearch.query(typed)) }

    @Test
    fun `the pages are read as the paragraphs they hold`() {
        assertEquals(listOf(one, two, three, four), read.map { it.text })
    }

    @Test
    fun `a word is found in the paragraph it is in, and on the page it is drawn on`() {
        val found = matches("Rabbit").single()
        assertEquals(2, found.paragraph)
        assertEquals("Rabbit", three.substring(found.from, found.to))
        assertEquals(1, PdfFinding.pageOf(layout, found))
        assertEquals(3, PdfFinding.pageOf(layout, matches("another moment").single()))
        // Where it is drawn: one line's worth, starting where the word does.
        val mark = layout.marks(found.paragraph, found.from, found.to).single()
        assertEquals(1, mark.page)
        assertEquals(found.to - found.from, mark.to - mark.from)
        val line = pages[1].lines.first { it.start <= mark.from && mark.from < it.start + it.text.length }
        assertEquals("Rabbit", line.text.substring(mark.from - line.start, mark.to - line.start))
        assertEquals(line.top, mark.top, 0f)
    }

    @Test
    fun `every place a word is in is found, in reading order`() {
        val found = matches("alice")
        assertEquals(listOf(0, 2, 3), found.map { it.paragraph })
        assertEquals(listOf(0, 1, 3), found.map { PdfFinding.pageOf(layout, it) })
        // Found whatever its case; and the running title at the top of each page isn't part of what is read.
        assertTrue(matches("ALICE IN WONDERLAND").isEmpty())
        assertTrue(matches("wonderland").isEmpty())
    }

    @Test
    fun `a word broken at a line's end is found whole, and marked on both its lines`() {
        // A column narrow enough that a word is broken at the end of one of its lines.
        val narrow = (120..240 step 10).map { right -> listOf(page { column(listOf(one), left = 40f, right = right.toFloat(), top = 60f, hyphenate = true) }) }
            .first { set -> set[0].lines.any { it.text.endsWith("-") } }
        val paragraphs = PdfReading.paragraphs(narrow)
        val layout = PdfLayout.of(narrow, paragraphs)
        assertEquals(listOf(one), paragraphs.map { it.text })
        val broken = narrow[0].lines.first { it.text.endsWith("-") }
        val head = broken.text.substringAfterLast(' ').removeSuffix("-")
        val word = one.split(' ').first { it.startsWith(head) && it.length > head.length }.trimEnd(',', '.')
        val found = PdfFinding.matches(0, paragraphs[0].text, TextSearch.query(word)).first()
        assertEquals(word, one.substring(found.from, found.to))
        val marks = layout.marks(found.paragraph, found.from, found.to)
        assertEquals(2, marks.size)
        assertEquals(PITCH, marks[1].top - marks[0].top, 0.01f)
        // Together they are the word's letters, without the hyphen it was broken with.
        assertEquals(found.to - found.from, marks.sumOf { it.end - it.start })
    }

    @Test
    fun `words that run over a page are found, and are where they begin`() {
        // "her own mind" ends the first page; "whether" begins the second.
        val found = matches("own mind whether the pleasure").single()
        assertEquals(1, found.paragraph)
        assertEquals(0, PdfFinding.pageOf(layout, found))
        val marks = layout.marks(found.paragraph, found.from, found.to)
        assertEquals(setOf(0, 1), marks.mapTo(HashSet()) { it.page })
        // What begins on the second page is on the second page.
        assertEquals(1, PdfFinding.pageOf(layout, matches("daisy chain").single()))
    }

    @Test
    fun `a result is quoted with what stands around it, cut at whole words`() {
        val found = matches("daisy chain").single()
        val quote = PdfFinding.quote(two, found)
        assertEquals("daisy chain", quote.match)
        assertTrue(two.contains(quote.before + quote.match + quote.after))
        assertTrue(quote.before.endsWith("making a "))
        assertEquals(" would be worth the trouble of getting up.", quote.after)
        // Not from the middle of a word.
        assertTrue(quote.before.startsWith("she ") || quote.before.startsWith("So ") || quote.before.first().isLetter())
        val start = two.indexOf(quote.before)
        assertTrue(start == 0 || two[start - 1] == ' ')
        // At the very start and the very end of a paragraph there is nothing more to quote.
        assertEquals("", PdfFinding.quote(two, PdfMatch(1, 0, 2)).before)
        assertEquals("", PdfFinding.quote(two, PdfMatch(1, two.length - 3, two.length)).after)
    }

    @Test
    fun `a place that isn't on the pages has no page`() {
        assertNull(PdfFinding.pageOf(layout, PdfMatch(99, 0, 3)))
    }

    // ---------------------------------------------------------------------------------------------
    // The search as the reader's panel uses it
    // ---------------------------------------------------------------------------------------------

    private class Shown {
        val gone = ArrayList<PdfMatch>()
        val marked = ArrayList<Pair<PdfMatch?, String>>()
        var asked = 0
    }

    /** The search over the pages above, as if their words were kept in two files (a paragraph is in the file [fileOf] says). */
    private fun search(shown: Shown): PdfSearch = PdfSearch(
        layout = layout,
        paragraphs = { file ->
            shown.asked++
            read.indices.filter { layout.fileOf(it) == file }.map { it to read[it].text }
        },
        pageName = { "Page ${it + 1}" },
        scope = CoroutineScope(Dispatchers.Unconfined),
        go = { shown.gone += it },
        mark = { match, text -> shown.marked += match to text },
    )

    @Test
    fun `results come with their page and their words, in the order they are read`() = runBlocking {
        val shown = Shown()
        val search = search(shown)
        val hits = ArrayList<SearchHit>()
        search.search("alice") { hits += it }
        assertEquals(listOf("Page 1", "Page 2", "Page 4"), hits.map { it.chapter })
        assertEquals(listOf("Alice", "Alice", "Alice"), hits.map { it.match })
        assertEquals(listOf(0, 1, 2), hits.map { it.index })
        assertEquals("", hits[0].before)
        assertTrue(hits[1].before.endsWith("nor did "))
        assertTrue(hits[2].after.startsWith(" after it,"))

        // Choosing one goes to it and marks its words.
        search.show(hits[1])
        assertEquals(2, shown.gone.single().paragraph)
        assertEquals("Alice", shown.marked.single().second)
        assertEquals(shown.gone.single(), shown.marked.single().first)

        // A second search doesn't read the PDF's words again, and starts its results anew.
        val asked = shown.asked
        val again = ArrayList<SearchHit>()
        search.search("rabbit") { again += it }
        assertEquals(asked, shown.asked)
        assertEquals(listOf("Rabbit"), again.map { it.match })
        assertEquals(0, again.single().index)

        // Clearing the search takes the mark away.
        search.clear()
        assertNull(shown.marked.last().first)
        search.show(again.single())
        assertEquals(2, shown.marked.size)
    }

    @Test
    fun `nothing typed, or nothing found, gives no results`() = runBlocking {
        val search = search(Shown())
        val hits = ArrayList<SearchHit>()
        search.search("  ") { hits += it }
        search.search("jabberwock") { hits += it }
        assertTrue(hits.isEmpty())
    }
}
