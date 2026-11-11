package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notes as the free books really write them, and links that only look like notes. */
class NotesTest {
    private fun xhtml(body: String) = readNotes(
        """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>t</title></head>
        <body>$body</body></html>""",
    )

    // ---- Books that mark their notes ----

    /** Standard Ebooks (Moby-Dick): the link and the note both say what they are. */
    private val standardEbooks = xhtml(
        """<section id="endnotes" role="doc-endnotes" epub:type="endnotes footnotes"><h2 epub:type="title">Endnotes</h2><ol>
        <li id="note-1" epub:type="endnote footnote">
            <p class="first-child">See subsequent chapters for something more on this head. <a href="chapter-24.xhtml#noteref-1" role="doc-backlink" epub:type="backlink">↩︎</a></p>
        </li>
        <li id="note-2" epub:type="endnote footnote">
            <p>I am aware that the fish styled <i>Lamatins</i> and <b>Dugongs</b> are included by many naturalists among the whales.</p>
            <p>But I deny their credentials. <a href="chapter-32.xhtml#noteref-2" role="doc-backlink" epub:type="backlink">↩︎</a></p>
        </li></ol></section>""",
    )

    @Test
    fun `a marked note is read without its way back`() {
        val link = TappedLink(types = "noteref", role = "doc-noteref", text = "1", ids = listOf("noteref-1"))
        val note = findNote(standardEbooks, "note-1", link)
        assertEquals(Note("1", listOf(NoteParagraph("See subsequent chapters for something more on this head."))), note)
    }

    @Test
    fun `a note keeps its paragraphs, italics and bold`() {
        val note = findNote(standardEbooks, "note-2", TappedLink(types = "noteref", text = "2", ids = listOf("noteref-2")))!!
        assertEquals(2, note.paragraphs.size)
        val first = note.paragraphs[0]
        assertEquals("I am aware that the fish styled Lamatins and Dugongs are included by many naturalists among the whales.", first.text)
        assertEquals(listOf("Lamatins"), first.italic.map { first.text.substring(it) })
        assertEquals(listOf("Dugongs"), first.bold.map { first.text.substring(it) })
        assertEquals("But I deny their credentials.", note.paragraphs[1].text)
    }

    @Test
    fun `a marked note is a note whatever the link to it looks like`() {
        // The page couldn't say which link was tapped; and a link in words rather than a number.
        assertNotNull(findNote(standardEbooks, "note-1", link = null))
        assertEquals("", findNote(standardEbooks, "note-1", TappedLink(text = "see the note"))!!.mark)
    }

    @Test
    fun `the roles alone mark a note`() {
        val file = xhtml("""<aside id="fn1" role="doc-footnote"><p>A footnote by its role.</p></aside><p id="plain">Not a note.</p>""")
        assertEquals("A footnote by its role.", findNote(file, "fn1", link = null)!!.paragraphs.single().text)
        assertNull(findNote(file, "plain", TappedLink(text = "here")))
        // A link marked as a note's is believed, though what it leads to says nothing.
        assertEquals("Not a note.", findNote(file, "plain", TappedLink(role = "doc-noteref", text = "here"))!!.paragraphs.single().text)
        assertEquals("Not a note.", findNote(file, "plain", TappedLink(text = "here"), marked = true)!!.paragraphs.single().text)
    }

    @Test
    fun `every kind of note the book can name is known`() {
        for (type in listOf("footnote", "endnote", "rearnote")) {
            val file = xhtml("""<div id="n" epub:type="$type">The note.</div>""")
            assertNotNull(type, findNote(file, "n", link = null))
        }
        val file = xhtml("""<div id="n" role="doc-endnote">The note.</div>""")
        assertNotNull(findNote(file, "n", link = null))
        assertTrue(TappedLink(types = "noteref").marked)
        assertTrue(TappedLink(types = "z3998:fiction NoteRef").marked)
        assertTrue(TappedLink(role = "doc-noteref").marked)
        assertFalse(TappedLink(types = "backlink", role = "doc-backlink").marked)
        // The lists notes come in are not notes: a link to the notes' chapter is an ordinary link.
        assertNull(findNote(standardEbooks, "endnotes", TappedLink(text = "Endnotes")))
    }

    /** Wikisource (Eugene Onegin): the note is marked, and opens with an arrow back. */
    @Test
    fun `a note that opens with its way back is read without it`() {
        val file = xhtml(
            """<p>Но вреден север для меня<sup class="mw-ref reference" id="cite_ref-2"><a href="#cite_note-2" epub:type="noteref"><span class="mw-reflink-text"><span class="cite-bracket">[</span>1<span class="cite-bracket">]</span></span></a></sup>.</p>
            <ol class="mw-references references"><li id="cite_note-2" epub:type="footnote"><span class="mw-cite-backlink"><a href="#cite_ref-2" rel="mw:referencedBy"><span class="mw-linkback-text">↑</span></a></span> <span class="mw-reference-text reference-text">Писано в Бессарабии.</span></li></ol>""",
        )
        val note = findNote(file, "cite_note-2", TappedLink(types = "noteref", text = "[1]", ids = listOf("cite_ref-2")))
        assertEquals(Note("1", listOf(NoteParagraph("Писано в Бессарабии."))), note)
    }

    // ---- Books that mark nothing ----

    /** Wolne Lektury (Lalka): a superscript number links to a paragraph at the back, which links back to it. */
    private val wolneLektury = xhtml(
        """<div id="book-text"><div id="footnotes"><h2>Przypisy:</h2><div id="annotation-1" class="annotation"><a href="part4.xhtml#anchor-1">1</a>. <em class="foreign-word">pokój san-stefański</em> — traktat zawarty między Rosją a Turcją. [przypis redakcyjny]</div>
        <div id="annotation-2" class="annotation"><a href="part4.xhtml#anchor-2">2</a>. <em class="foreign-word">wybór nowego papieża</em> — 20 lutego 1878 r.</div></div></div>""",
    )

    @Test
    fun `an unmarked note is told by its number and its way back`() {
        val link = TappedLink(text = "1", ids = listOf("anchor-1"))
        val note = findNote(wolneLektury, "annotation-1", link)!!
        assertEquals("1", note.mark)
        val text = note.paragraphs.single()
        // Without the number it opens with, nor the full stop after it.
        assertEquals("pokój san-stefański — traktat zawarty między Rosją a Turcją. [przypis redakcyjny]", text.text)
        assertEquals(listOf("pokój san-stefański"), text.italic.map { text.text.substring(it) })
    }

    @Test
    fun `an unmarked link is a note only if everything says so`() {
        // Not the number's own note: nothing there links back to this link.
        assertNull(findNote(wolneLektury, "annotation-2", TappedLink(text = "1", ids = listOf("anchor-1"))))
        // A link in words to the same place: a cross-reference, followed as ever.
        assertNull(findNote(wolneLektury, "annotation-1", TappedLink(text = "zobacz przypis", ids = listOf("anchor-1"))))
        // The page couldn't say which link it was.
        assertNull(findNote(wolneLektury, "annotation-1", link = null))
        // The whole list of notes isn't a note.
        assertNull(findNote(wolneLektury, "footnotes", TappedLink(text = "1", ids = listOf("anchor-1"))))
        // Nowhere to go.
        assertNull(findNote(wolneLektury, "annotation-99", TappedLink(text = "99", ids = listOf("anchor-99"))))
    }

    /** Project Gutenberg's own style: the note's label is the way back, and may be an empty anchor's neighbour. */
    @Test
    fun `a note whose label is its way back`() {
        val file = xhtml(
            """<p>Text<a id="FNanchor_1"></a><a href="#Footnote_1" class="fnanchor">[1]</a> goes on.</p>
            <div class="footnote"><p><a id="Footnote_1"/><a href="#FNanchor_1"><span class="label">[1]</span></a> The <cite>Histoire</cite> says otherwise.</p></div>
            <div class="footnote"><p><a id="Footnote_2" href="#FNanchor_2"><span class="label">[2]</span></a> Another note.</p></div>""",
        )
        val first = findNote(file, "Footnote_1", TappedLink(text = "[1]", ids = listOf("FNanchor_1")))!!
        assertEquals("1", first.mark)
        assertEquals("The Histoire says otherwise.", first.paragraphs.single().text)
        assertEquals(listOf("Histoire"), first.paragraphs.single().italic.map { first.paragraphs.single().text.substring(it) })
        val second = findNote(file, "Footnote_2", TappedLink(text = "[2]", ids = listOf("FNanchor_2")))!!
        assertEquals(Note("2", listOf(NoteParagraph("Another note."))), second)
    }

    @Test
    fun `an empty anchor before a note's paragraph leads to that paragraph, not to the chapter`() {
        val file = xhtml(
            """<div class="notes"><a id="n1"/><p><a href="ch1.xhtml#r1">*</a> The first note.</p><a id="n2"/><p><a href="ch1.xhtml#r2">†</a> The second note.</p></div>""",
        )
        assertEquals("The first note.", findNote(file, "n1", TappedLink(text = "*", ids = listOf("r1")))!!.paragraphs.single().text)
        assertEquals("†", findNote(file, "n2", TappedLink(text = "†", ids = listOf("r2")))!!.mark)
    }

    @Test
    fun `contents and index links are not notes`() {
        val file = xhtml(
            """<h2 id="chap01"><a href="toc.xhtml#toc01">I</a></h2><p>It was the best of times.</p>
            <p id="page_45">A paragraph an index entry points at.<a href="index.xhtml#i45">45</a></p>""",
        )
        // A chapter number in the contents, whose heading links back to the contents.
        assertNull(findNote(file, "chap01", TappedLink(text = "I", ids = listOf("toc01"))))
        // A page number in an index: the page says nothing of a way back to this link.
        assertNull(findNote(file, "page_45", TappedLink(text = "45", ids = listOf("i46"))))
        assertNull(findNote(file, "page_45", TappedLink(text = "45")))
    }

    @Test
    fun `what looks like a note's mark`() {
        for (mark in listOf("1", "12", "[3]", "(4)", "*", "**", "†", "‡", "a", "B", "iv", "XII", "[* 1]", "¹", "1.")) {
            assertTrue(mark, looksLikeNoteMark(mark))
        }
        for (words in listOf("", "  ", "see note", "Chapter 1", "Tasa", "note", "1234567", "↩︎", "page")) {
            assertFalse(words, looksLikeNoteMark(words))
        }
    }

    @Test
    fun `line breaks in a note are kept, and what can't be read is left out`() {
        val file = xhtml(
            """<aside id="fn" epub:type="footnote"><p>A verse:<br/>its second line. <img src="x.png" alt="x"/><script>var a = 1;</script></p></aside>""",
        )
        assertEquals("A verse:\nits second line.", findNote(file, "fn", link = null)!!.paragraphs.single().text)
        // A note with nothing in it to read isn't shown.
        assertNull(findNote(xhtml("""<aside id="fn" epub:type="footnote"><img src="x.png"/></aside>"""), "fn", link = null))
    }

    @Test
    fun `a file that isn't quite XML is still read`() {
        val file = readNotes("""<html><body><p>Text<br>more</p><aside id=fn epub:type=footnote><p>An old book's note.</aside></body></html>""")
        assertEquals("An old book's note.", findNote(file, "fn", link = null)!!.paragraphs.single().text)
    }

    @Test
    fun `a note Readium has cut out already is shown as it is`() {
        val note = noteOf("<p>The <em>first</em> paragraph.</p><p>The second.</p>", TappedLink(types = "noteref", text = "7"))!!
        assertEquals("7", note.mark)
        assertEquals(listOf("The first paragraph.", "The second."), note.paragraphs.map { it.text })
        assertNull(noteOf("  ", link = null))
    }

    @Test
    fun `whether a file marks its notes`() {
        assertTrue(standardEbooks.marksNotes)
        assertFalse(wolneLektury.marksNotes)
    }
}
