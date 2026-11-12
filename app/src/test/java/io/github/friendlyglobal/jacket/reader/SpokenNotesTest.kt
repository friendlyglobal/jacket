package io.github.friendlyglobal.jacket.reader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers of notes the voice passes over: found in a chapter file, then in the paragraphs the voice is given. */
class SpokenNotesTest {
    /** The chapter read as Readium's read-aloud reads it (as HTML), and its unmarked links asked of the same file. */
    private fun marksOf(chapter: String): List<NoteMark> {
        val notes = readNotes(chapter)
        return noteMarksIn(Jsoup.parse(chapter)) { href, link -> href.startsWith("#") && findNote(notes, href.drop(1), link) != null }
    }

    private fun spokenOver(paragraph: String, chapter: String): String {
        val out = StringBuilder(paragraph)
        noteMarksIn(paragraph, marksOf(chapter)).sortedByDescending { it.first }.forEach { out.delete(it.first, it.last + 1) }
        return out.toString()
    }

    private val marked = """
        <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
        <p>It was late.<sup><a epub:type="noteref" href="#n1">1</a></sup> Nobody came, not even
           the   doctor<a epub:type="noteref" href="#n2">2</a> who had promised.</p>
        <p>Room 12 was shut. See <a href="#ch2">chapter 2</a>.</p>
        <aside epub:type="footnote" id="n1"><p>A first note.</p></aside>
        <aside epub:type="footnote" id="n2"><p>A second note.</p></aside>
        </body></html>
    """.trimIndent()

    @Test
    fun aMarkedNotesNumberIsKeptWithTheWordsBeforeIt() {
        val marks = marksOf(marked)
        assertEquals(listOf("1", "2"), marks.map { it.mark })
        assertEquals("It was late.", marks[0].before)
        assertTrue(marks[0].opens)
        // Runs of spaces and line breaks are one space, as the voice is given them.
        assertTrue(marks[1].before.endsWith("not even the doctor"))
    }

    @Test
    fun theNumbersAreFoundInTheParagraphTheVoiceIsGiven() {
        val paragraph = "It was late.1 Nobody came, not even the doctor2 who had promised."
        assertEquals("It was late. Nobody came, not even the doctor who had promised.", spokenOver(paragraph, marked))
        // Other numbers are no notes' numbers; nor is a link that is words.
        val other = "Room 12 was shut. See chapter 2."
        assertEquals(other, spokenOver(other, marked))
    }

    @Test
    fun anUnmarkedNumberIsANotesWhenItsNoteLinksBack() {
        val chapter = """
            <html><body>
            <p>The whale<a id="r1" href="#fn1">[1]</a> is a mammal, as in chapter <a href="#c3">3</a>.</p>
            <h2 id="c3">Chapter 3</h2>
            <p>More of the book.</p>
            <p id="fn1"><a href="#r1">1.</a> Linnaeus says so.</p>
            </body></html>
        """.trimIndent()
        assertEquals("The whale is a mammal, as in chapter 3.", spokenOver("The whale[1] is a mammal, as in chapter 3.", chapter))
    }

    @Test
    fun bracketsLeftOutsideTheLinkGoWithTheNumber() {
        val chapter = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <p>The whale[<a epub:type="noteref" href="notes.xhtml#fn1">1</a>] is a mammal (<a role="doc-noteref" href="notes.xhtml#fn2">2</a>).</p>
            </body></html>
        """.trimIndent()
        assertEquals("The whale is a mammal .", spokenOver("The whale[1] is a mammal (2).", chapter))
    }

    @Test
    fun aNotesOwnNumberInFrontOfItIsRead() {
        val chapter = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <p>The whale<a id="r1" epub:type="noteref" href="#fn1">1</a> is a mammal.</p>
            <p id="fn1"><a epub:type="noteref" href="#r1">1</a> Linnaeus says so.</p>
            </body></html>
        """.trimIndent()
        assertEquals(1, marksOf(chapter).size)
        assertEquals("1 Linnaeus says so.", spokenOver("1 Linnaeus says so.", chapter))
    }

    @Test
    fun aLineBreakStartsTheWordsAgainAsItDoesForTheVoice() {
        val chapter = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <p>Call me Ishmael.<br/>Some years ago<a epub:type="noteref" href="#n1">*</a> never mind.</p>
            </body></html>
        """.trimIndent()
        val mark = marksOf(chapter).single()
        // Readium gives the voice each line of the paragraph by itself.
        assertEquals("Some years ago", mark.before)
        assertEquals("Some years ago never mind.", spokenOver("Some years ago* never mind.", chapter))
    }

    @Test
    fun wordsInAnotherLanguageStartThemAgainToo() {
        val chapter = """
            <html lang="en" xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <p>He said <span lang="fr">bonjour<a epub:type="noteref" href="#n1">3</a></span> and left.</p>
            </body></html>
        """.trimIndent()
        val mark = marksOf(chapter).single()
        assertEquals("bonjour", mark.before)
        // The French words reach the voice by themselves, the space before them with them.
        assertEquals(listOf(8..8), noteMarksIn(" bonjour3", listOf(mark)))
    }

    @Test
    fun aLongParagraphIsKnownByTheWordsJustBeforeTheNumber() {
        val filler = "word ".repeat(40)
        val chapter = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><p>${filler}the last one<a epub:type="noteref" href="#n">7</a> and on. Chapter 7 again.</p></body></html>"""
        val mark = marksOf(chapter).single()
        assertTrue(!mark.opens && mark.before.length == 32)
        val paragraph = "${filler}the last one7 and on. Chapter 7 again."
        assertEquals("${filler}the last one and on. Chapter 7 again.", spokenOver(paragraph, chapter))
    }

    @Test
    fun aChapterWithoutNotesHasNone() {
        assertEquals(emptyList<NoteMark>(), marksOf("<html><body><p>Chapter 1</p><p>See <a href='#x'>below</a>.</p><p id='x'>Here.</p></body></html>"))
        assertEquals(emptyList<IntRange>(), noteMarksIn("Any words 1.", emptyList()))
    }
}
