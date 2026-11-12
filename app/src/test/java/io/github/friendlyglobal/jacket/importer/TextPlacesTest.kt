package io.github.friendlyglobal.jacket.importer

import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.LibraryJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Moving text books over to pages: a place, a bookmark or a note that Jacket 1.1 and earlier kept
 * as the number of a paragraph (and how far down it the top of the screen was) must be found on
 * the pages now made of the same file, and a place kept on those pages must still say which
 * paragraph it is, for a backup read by an older Jacket.
 */
class TextPlacesTest {
    private fun sentences(count: Int, word: String = "Sentence") = (1..count).joinToString(" ") { "$word number $it ends here." }

    /** A novel's worth of paragraphs, each one different, long and short. */
    private val novel = (0 until 1200).joinToString("\n\n") { "Paragraph $it opens. " + sentences(1 + (it * 7) % 23, "Line$it") }
    private val book = TextBook.of(novel)
    private val layout = book.layout()

    /** What a web view has as the text of each chapter file: what Readium looks for a place's words in. */
    private val shown: Map<String, String> by lazy {
        val out = ByteArrayOutputStream()
        book.write(out, TextBook.About("b1", "Novel"))
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.startsWith("text/")) continue
                val page = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                    .parse(InputSource(StringReader(String(zip.readBytes(), Charsets.UTF_8))))
                files[entry.name] = page.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "body").item(0).textContent
            }
        }
        files
    }

    // --- A place kept as a paragraph, found on the pages ------------------------------------------

    @Test
    fun `the top of a paragraph is found by the paragraph itself`() {
        for (paragraph in listOf(0, 1, 37, 599, 1199)) {
            val spot = book.spotOf(TextPlace(paragraph))!!
            assertEquals("p$paragraph", spot.id)
            assertNull(spot.words)
            assertEquals(layout.hrefOf(paragraph), spot.href)
            // That file is the one whose page holds the paragraph, under that name.
            assertTrue(shown.getValue(spot.href).contains("\n" + book.paragraphs[paragraph] + "\n"))
        }
    }

    @Test
    fun `a place part of the way down a paragraph is found by the words there`() {
        for (paragraph in listOf(22, 310, 867)) {
            val text = book.paragraphs[paragraph]
            for (within in listOf(0.2f, 0.5f, 0.85f, 1f)) {
                val spot = book.spotOf(TextPlace(paragraph, within))!!
                val words = spot.words!!
                assertEquals("p$paragraph", spot.id)
                // The words start a word of the paragraph, at or just before the place...
                val at = text.indexOf(words)
                assertTrue(at > 0 && text[at - 1] == ' ')
                assertTrue(at <= within * text.length && at >= within * text.length - 70)
                // ...and with what stands around them, they are in the file exactly once, there.
                val page = shown.getValue(spot.href)
                val found = page.indexOf(spot.before + words + spot.after)
                assertTrue(found >= 0)
                assertEquals(-1, page.indexOf(spot.before + words + spot.after, found + 1))
                assertEquals(page.indexOf("\n$text\n") + 1 + at, found + spot.before.length)
            }
        }
    }

    @Test
    fun `what stands around the words runs on into the paragraphs either side`() {
        val small = TextBook.of("First paragraph here.\n\nThe second paragraph is the one the reader was partway down when the book was closed.\n\nThird paragraph.")
        val spot = small.spotOf(TextPlace(1, 0.2f))!!
        assertEquals("paragraph is the one the reader was partway down when the bo", spot.words)
        assertEquals("First paragraph here.\nThe second ", spot.before)
        assertEquals("ok was closed.\nThird paragraph.", spot.after)
        // At the very end of a short book there is nothing after.
        val last = small.spotOf(TextPlace(2, 0.5f))!!
        assertNull(last.words)
        assertEquals("p2", last.id)
    }

    @Test
    fun `too few words to tell a place by, and it is the paragraph's top`() {
        val small = TextBook.of("Yes.\n\nNo, never.\n\nPerhaps so.")
        val spot = small.spotOf(TextPlace(1, 0.6f))!!
        assertEquals("p1", spot.id)
        assertNull(spot.words)
    }

    @Test
    fun `a place far down a paragraph laid out in pieces is in the piece that holds it`() {
        val long = TextBook.of("Short opening.\n\n" + sentences(2_000) + "\n\nShort ending.")
        assertTrue(long.blocks.size > 5)
        val spot = long.spotOf(TextPlace(1, 0.5f))!!
        val piece = long.blocks.first { it.id == spot.id }
        assertEquals(1, piece.paragraph)
        assertTrue(piece.from > 0)
        val at = (0.5f * long.paragraphs[1].length).toInt()
        assertTrue(at >= piece.from && at <= piece.from + piece.text.length)
        assertTrue(piece.text.contains(spot.words!!))
        // And the pieces say where in the paragraph they are.
        val place = long.layout().placeOf(spot.id, piece.text.indexOf(spot.words!!))!!
        assertEquals(1, place.paragraph)
        assertEquals(0.5f, place.within, 0.01f)
    }

    @Test
    fun `a place past the end of the book, or not a number, is still a place in it`() {
        assertEquals("p1199", book.spotOf(TextPlace(5_000, 0.5f))!!.id)
        assertEquals("p0", book.spotOf(TextPlace(-3))!!.id)
        assertEquals("p10", book.spotOf(TextPlace(10, Float.NaN))!!.id)
        assertNull(book.spotOf(TextPlace(10, Float.NaN))!!.words)
    }

    // --- What Jacket 1.1 saved ---------------------------------------------------------------------

    @Test
    fun `a book left in a paragraph opens at that paragraph`() {
        // As the text reader saved it: the paragraph at the top of the screen, how far down it, and how many there are.
        assertEquals(TextPlace(431, 0.35f), layout.saved(position = 431, offset = 0.35f, count = 1200))
        assertEquals(TextPlace(431, 0f), layout.saved(431, 0f, 1200))
        assertEquals(TextPlace(0, 0.6f), layout.saved(0, 0.6f, 1200))
        // The last paragraph, and the first Jacket (which kept no count).
        assertEquals(TextPlace(1199, 0f), layout.saved(1199, 0f, 1200))
        assertEquals(TextPlace(431, 0.35f), layout.saved(431, 0.35f, 0))
    }

    @Test
    fun `a book never opened starts at its first page`() {
        assertNull(layout.saved(0, 0f, 0))
        assertNull(layout.saved(0, 0f, 1200))
        assertNull(layout.saved(0, Float.NaN, 1200))
    }

    @Test
    fun `a file older Jackets misread keeps its place by how far through it was`() {
        // Saved as UTF-16, which 1.1 took for Latin-1 and cut into other paragraphs: 900 of them, say.
        assertEquals(TextPlace(600), layout.saved(position = 450, offset = 0f, count = 900))
        assertEquals(TextPlace(1199), layout.saved(899, 0.99f, 900))
        assertEquals(TextPlace(0), layout.saved(0, 0.5f, 900))
        // A place that names a paragraph the file no longer has is its last, not an error.
        assertEquals(TextPlace(1199), layout.saved(4_000, 0.5f, 0))
    }

    @Test
    fun `a bookmark kept as a paragraph is on that paragraph`() {
        // As the text reader saved it: the paragraph's number, and that number over how many there were.
        for (section in listOf(0, 1, 7, 431, 1199)) assertEquals(section, layout.marked(section, section / 1200f))
        // From a file that reads differently now, it is as far through the book as it was.
        assertEquals(800, layout.marked(600, 600 / 900f))
        // Nothing to go by but the number: the number.
        assertEquals(431, layout.marked(431, 0f))
        assertEquals(1199, layout.marked(9_999, Float.NaN))
    }

    @Test
    fun `a library as Jacket 1_1 left it finds its text book's place and bookmarks on the pages`() {
        val library = """
            {"version":1,"currentId":"t1","settings":{"onboarded":true},
             "books":[{"id":"t1","title":"Novel","author":"","format":"TXT","fileName":"t1.txt","coverFile":"t1.jpg","seedColor":-5732312,
               "sourceName":"novel.txt","sizeBytes":${novel.toByteArray().size},"addedAt":1794327745046,"position":431,"positionOffset":0.35,
               "sectionCount":1200,"progress":0.3595,"lastOpenedAt":1794348721840,"locator":null,"finishedAt":0,"pace":0,"language":"en",
               "pinnedAt":0,"collections":[],"coverCustomized":false,"originalCover":null,"originalSeed":null,"sha256":"ab"}],
             "annotations":[{"id":"m1","bookId":"t1","kind":"BOOKMARK","locator":null,"section":37,"progress":0.030833334,
               "chapter":"","text":"Paragraph 37 opens.","note":"","color":"YELLOW","createdAt":6}]}
        """.trimIndent()
        val state = LibraryJson.read(library).state
        val saved = state.books.single()
        assertEquals(BookFormat.TXT, saved.format)
        assertNull(saved.locator)
        // The place: paragraph 431, a third of the way down it.
        val place = layout.saved(saved.position, saved.positionOffset, saved.sectionCount)!!
        val spot = book.spotOf(place)!!
        assertEquals("p431", spot.id)
        assertTrue(book.paragraphs[431].contains(spot.words!!))
        assertTrue(shown.getValue(spot.href).contains(spot.before + spot.words + spot.after))
        // The bookmark: paragraph 37, whose first words the bookmark shows.
        val mark = state.annotations.single()
        assertEquals(AnnotationKind.BOOKMARK, mark.kind)
        val marked = layout.marked(mark.section, mark.progress)
        assertEquals(37, marked)
        assertTrue(book.paragraphs[marked].startsWith(mark.text))
        assertTrue(shown.getValue(layout.hrefOf(marked)).contains("\n" + mark.text))
    }

    // --- A place on the pages, told as a paragraph ---------------------------------------------

    @Test
    fun `an element of the pages says which paragraph it is`() {
        assertEquals(TextPlace(12, 0f), layout.placeOf("p12"))
        assertEquals(TextPlace(1199, 0f), layout.placeOf("p1199"))
        val length = book.paragraphs[12].length
        assertEquals(TextPlace(12, 30f / length), layout.placeOf("p12", at = 30))
        assertEquals(1f, layout.placeOf("p12", at = 99_999)!!.within, 0f)
        // Not one of the book's paragraphs: nothing is made of it.
        for (id in listOf(null, "", "p", "p1200", "p-1", "q12", "p12x", "readium-1")) assertNull(id, layout.placeOf(id))
    }

    @Test
    fun `a page that can't name its paragraph is placed by how far down its file it is`() {
        assertTrue(layout.files > 5)
        var before = -1
        for (file in 0 until layout.files) {
            val first = layout.paragraphAt(file, 0.0)
            val middle = layout.paragraphAt(file, 0.5)
            val last = layout.paragraphAt(file, 1.0)
            // Each file's paragraphs follow the last file's, and run in order down it.
            assertTrue(first >= before && first <= middle && middle <= last)
            assertEquals(TextBook.hrefOf(file), layout.hrefOf(middle))
            assertEquals(TextBook.hrefOf(file), layout.hrefOf(last))
            before = last
        }
        assertEquals(0, layout.paragraphAt(0, 0.0))
        assertEquals(1199, layout.paragraphAt(layout.files - 1, 1.0))
        // Every paragraph is the answer somewhere in its own file.
        val reached = HashSet<Int>()
        for (file in 0 until layout.files) for (step in 0..4000) reached += layout.paragraphAt(file, step / 4000.0)
        assertEquals(1200, reached.size)
    }

    @Test
    fun `a file that only carries on a long paragraph answers with that paragraph`() {
        val long = TextBook.of("Opening.\n\n" + sentences(8_000) + "\n\nEnding.")
        val pieces = long.layout()
        assertTrue(pieces.files > 3)
        assertEquals(0, pieces.paragraphAt(0, 0.0))
        assertEquals(1, pieces.paragraphAt(1, 0.5))
        assertEquals(1, pieces.paragraphAt(2, 0.0))
        assertEquals(2, pieces.paragraphAt(pieces.files - 1, 1.0))
    }

    @Test
    fun `a chapter file is known by its name, whatever stands before it`() {
        assertEquals(0, layout.fileOf("text/part0001.xhtml"))
        assertEquals(3, layout.fileOf("text/part0004.xhtml#p90"))
        assertEquals(2, layout.fileOf("/text/part0003.xhtml"))
        assertEquals(-1, layout.fileOf("text/part9999.xhtml"))
        assertEquals(-1, layout.fileOf("OEBPS/chapter1.xhtml"))
        assertEquals(-1, layout.fileOf("nav.xhtml"))
        for (file in 0 until layout.files) assertEquals(file, layout.fileOf(TextBook.hrefOf(file)))
    }

    @Test
    fun `a place kept on the pages and one kept as a paragraph agree`() {
        // There and back: the paragraph a place names is the one its spot is in.
        for (paragraph in 0 until 1200 step 13) {
            val spot = book.spotOf(TextPlace(paragraph, 0.4f))
            assertNotNull(spot)
            assertEquals(paragraph, layout.placeOf(spot!!.id)!!.paragraph)
            assertEquals(layout.fileOf(spot.href), layout.fileOf(layout.hrefOf(paragraph)))
        }
    }
}
