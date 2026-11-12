package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** A text file is turned into a small EPUB (see TextBook.kt): what the pages are made of, and that any text gives pages a web view can show. */
class TextBookTest {
    private val about = TextBook.About("b1", "A Book", "Someone", "en")

    /** The files of the EPUB made from [book], by name, in the order they were written. */
    private fun files(book: TextBook, about: TextBook.About = this.about): LinkedHashMap<String, ByteArray> {
        val out = ByteArrayOutputStream()
        book.write(out, about)
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        return files
    }

    /** Parses as a web view does a chapter of a book: as XML, where one stray character is an error page. */
    private fun xml(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(InputSource(StringReader(String(bytes, Charsets.UTF_8))))

    private fun body(bytes: ByteArray): String = xml(bytes).getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "body").item(0).textContent

    private fun sentences(count: Int, word: String = "Sentence") = (1..count).joinToString(" ") { "$word number $it ends here." }

    // --- Paragraphs ---------------------------------------------------------------------------

    /** The text reader's own cutting of a file into paragraphs, as it was in Jacket 1.1.0 (reader/PageReaders.kt). */
    private fun paragraphsAsIn110(decoded: String): List<String> {
        val text = decoded.removePrefix("\uFEFF").replace("\r\n", "\n")
        val blocks = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val paragraphs = if (blocks.size <= 1) text.lines().map { it.trim() }.filter { it.isNotEmpty() } else blocks
        return paragraphs.map { it.replace(Regex("\\s*\n\\s*"), " ") }
    }

    @Test
    fun `paragraphs are set apart by blank lines, and wrapped lines are joined`() {
        val text = "First line\nwrapped on.\n\n   Second paragraph.   \n \n\nThird."
        assertEquals(listOf("First line wrapped on.", "Second paragraph.", "Third."), paragraphsOf(text))
    }

    @Test
    fun `a file with no blank lines has a paragraph to a line`() {
        assertEquals(listOf("One.", "Two.", "Three."), paragraphsOf("One.\nTwo.\r\n  Three.\n"))
        assertEquals(listOf("Old Mac line one.", "Line two."), paragraphsOf("Old Mac line one.\rLine two."))
    }

    @Test
    fun `a paragraph has the number it had in Jacket 1_1`() {
        // A place in a text book is the number of its paragraph: the same text must go on being cut the same way.
        val texts = listOf(
            "One.\n\nTwo.\n\nThree.",
            "\uFEFFWith a mark.\r\n\r\nAnd Windows line ends.\r\nWrapped.\r\n\r\n\r\nLast.",
            "No blank lines\nat all\n\tin this one\n",
            "Only one paragraph, wrapped\nover two lines.",
            "Tabs\t\tinside.\n \t \nSpaces   kept   inside.\n\n* * *\n\n— Fala.\n\n",
            "Form\u000Cfeed.\n\n\u000C\n\nAfter it.",
            "A\n\n\n\n\nB\n \n \nC\n\u00A0\nD",
            "",
            "   \n\n  ",
            "Line one\rLine two\r\rLine three",
        )
        for (text in texts) assertEquals(text, paragraphsAsIn110(text), paragraphsOf(text))
    }

    // --- What a paragraph is ------------------------------------------------------------------

    @Test
    fun `a chapter's heading is known when it can hardly be anything else`() {
        val headings = listOf(
            "Chapter 1", "CHAPTER IV", "Chapter IV. The Rabbit Sends in a Little Bill", "Chapter Twenty-One", "CHAPTER THE FIRST",
            "Chapter 12: Home", "Chapter 3 — The Storm", "Chapter 7 The Road North", "Part II", "BOOK ONE", "Act 3", "Scene 2",
            "Capítulo 12", "CAPÍTULO XII", "Chapitre 3", "Kapitel 9", "Capitolo 4", "Hoofdstuk 2", "Rozdział 5", "Глава 3", "ГЛАВА XI", "Розділ 2", "Bölüm 6",
            "第三章", "第12章 归来", "第一回 灵根育孕源流出", "제1장", "Prologue", "EPILOGUE", "Prólogo", "Introdução", "Vorwort", "Contents", "Appendix B", "プロローグ",
        )
        for (line in headings) assertNotNull(line, headingOf(line, markdown = false))
    }

    @Test
    fun `an ordinary sentence is not a heading`() {
        val prose = listOf(
            "Part of me wanted to leave.",
            "Part 2 of the report shows that sales fell.",
            "Book me a table for two.",
            "Chapter and verse, he knew it all.",
            "Part did not matter.",
            "part mill",
            "Introduction to the problem",
            "He said nothing.",
            "# Not a heading in a plain text file",
            "Chapter 1 " + "was long and went on and on and on and on and on and on and on and on and on and on",
            "I",
            "1984",
        )
        for (line in prose) assertNull(line, headingOf(line, markdown = false))
    }

    @Test
    fun `a Markdown file's headings are its hash lines, without the hashes`() {
        val heading = headingOf("## Getting started ##", markdown = true)!!
        assertEquals("Getting started", "## Getting started ##".substring(heading.from, heading.to))
        assertEquals(2, heading.level)
        assertEquals(1, headingOf("# Title", markdown = true)!!.level)
        // A tag, a lone hash and a seventh level are not headings.
        assertNull(headingOf("#hashtag", markdown = true))
        assertNull(headingOf("#", markdown = true))
        assertNull(headingOf("####### Seven", markdown = true))
    }

    @Test
    fun `a change of scene is a row of marks, not words and not a silence`() {
        for (rule in listOf("* * *", "***", "———", "- - -", "~", "§", "⁂", "* * * * *", "=====")) assertTrue(rule, isSceneBreak(rule))
        // Words, numbers and a line of dialogue that is all dots stay ordinary paragraphs.
        for (text in listOf("* * * (later) * * *", "1984", "…", "...", "?!", "", "   ", "-".repeat(60))) assertFalse(text, isSceneBreak(text))
    }

    @Test
    fun `scene breaks and headings are set as such, and lose none of their words`() {
        val book = TextBook.of("CHAPTER I\n\nIt began.\n\n* * *\n\nLater.")
        assertEquals(listOf(TextBook.Kind.HEADING, TextBook.Kind.TEXT, TextBook.Kind.BREAK, TextBook.Kind.TEXT), book.blocks.map { it.kind })
        assertEquals(listOf("CHAPTER I", "It began.", "* * *", "Later."), book.blocks.map { it.text })
        val page = String(files(book).getValue("text/part0001.xhtml"), Charsets.UTF_8)
        assertTrue(page.contains("<h2 id=\"p0\" dir=\"auto\">CHAPTER I</h2>"))
        assertTrue(page.contains("<p class=\"break\" id=\"p2\" dir=\"auto\">* * *</p>"))
        assertTrue(page.contains("<p id=\"p3\" dir=\"auto\">Later.</p>"))
    }

    // --- Files ---------------------------------------------------------------------------------

    @Test
    fun `every paragraph is on the pages once, in order, under its own number`() {
        val text = (0 until 900).joinToString("\n\n") { "Paragraph $it. " + sentences(1 + it % 9) }
        val book = TextBook.of(text)
        assertEquals(900, book.paragraphs.size)
        assertTrue(book.parts.size > 1)
        // The files follow one another without a gap, and none is too much for a web view.
        assertEquals(0, book.parts.first().blocks.first)
        assertEquals(book.blocks.lastIndex, book.parts.last().blocks.last)
        book.parts.zipWithNext { a, b -> assertEquals(a.blocks.last + 1, b.blocks.first) }
        for (part in book.parts) assertTrue(part.blocks.sumOf { book.blocks[it].text.length } <= TextBook.MOST_IN_A_FILE)
        assertEquals((0 until 900).map { "p$it" }, book.blocks.map { it.id })
        // And what the pages hold is the file's own words.
        val pages = files(book)
        val shown = book.parts.joinToString("") { body(pages.getValue(it.href)) }
        assertEquals("\n" + book.paragraphs.joinToString("\n") + "\n", shown.replace("\n\n", "\n"))
    }

    @Test
    fun `a chapter starts a file of its own once the one before has some pages`() {
        val chapters = (1..5).joinToString("\n\n") { "Chapter $it\n\n" + (1..12).joinToString("\n\n") { sentences(40) } }
        val book = TextBook.of("A Title\n\nby Someone\n\n$chapters")
        // The title page is too short to stand alone: the first chapter shares its file.
        assertEquals(5, book.parts.size)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5"), book.contents.map { it.title })
        assertEquals("text/part0001.xhtml#p2", book.contents[0].href)
        assertEquals(listOf("text/part0002.xhtml", "text/part0003.xhtml", "text/part0004.xhtml", "text/part0005.xhtml"), book.contents.drop(1).map { it.href })
        assertTrue(book.contents.all { it.level == 0 })
    }

    @Test
    fun `short sections share a file, and are found in it by name`() {
        val notes = (1..30).joinToString("\n\n") { "# Day $it\n\nWent out. Came back.\n\n## Seen\n\nA bird." }
        val book = TextBook.of(notes, markdown = true)
        assertEquals(1, book.parts.size)
        assertEquals(60, book.contents.size)
        assertEquals("Day 1", book.contents[0].title)
        assertEquals("text/part0001.xhtml", book.contents[0].href)
        assertEquals("text/part0001.xhtml#p2", book.contents[1].href)
        assertEquals(listOf(0, 1, 0, 1), book.contents.take(4).map { it.level })
        // The contents are a list within a list, and still XML.
        val nav = xml(files(book).getValue("nav.xhtml"))
        assertEquals(60, nav.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "a").length)
        assertEquals(31, nav.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "ol").length)
    }

    @Test
    fun `no level of the contents is skipped on the way down`() {
        val book = TextBook.of("# One\n\ntext\n\n### Deep\n\ntext\n\n## Middle\n\ntext\n\n# Two", markdown = true)
        assertEquals(listOf(0, 1, 1, 0), book.contents.map { it.level })
        xml(files(book).getValue("nav.xhtml"))
    }

    @Test
    fun `a long chapter cut into files is still one chapter`() {
        val book = TextBook.of("Chapter 1\n\n" + (1..120).joinToString("\n\n") { sentences(40) } + "\n\nChapter 2\n\n" + sentences(40))
        assertTrue(book.parts.size >= 4)
        // Every file of the first chapter says it began in the first; the second chapter begins its own.
        assertEquals(setOf(0), book.parts.dropLast(1).map { it.group }.toSet())
        assertEquals(book.parts.lastIndex, book.parts.last().group)
        val layout = book.layout()
        assertEquals(0, layout.groupOf(book.parts.size - 2))
        assertEquals(book.parts.lastIndex, layout.groupOf(book.parts.lastIndex))
    }

    @Test
    fun `a file with no line breaks at all is still laid out in pieces`() {
        val text = sentences(40_000) // one paragraph of over a million characters
        val book = TextBook.of(text)
        assertEquals(1, book.paragraphs.size)
        assertTrue(book.parts.size > 15)
        assertTrue(book.blocks.all { it.text.length <= TextBook.LONGEST_BLOCK })
        assertEquals(text, book.blocks.joinToString("") { it.text })
        assertEquals("p0", book.blocks[0].id)
        assertEquals("p0-${book.blocks[1].from}", book.blocks[1].id)
        assertEquals(book.blocks.size, book.blocks.map { it.id }.toSet().size)
        // Pieces run on from one to the next on the page.
        val page = String(files(book).getValue("text/part0001.xhtml"), Charsets.UTF_8)
        assertTrue(page.contains("<p class=\"more\" id=\"p0\""))
    }

    @Test
    fun `a very long file is made into pages in good time`() {
        // About six million characters: more than the longest novels.
        val text = (0 until 30_000).joinToString("\n\n") { "Paragraph $it. " + sentences(6) }
        val started = System.nanoTime()
        val book = TextBook.of(text)
        val out = ByteArrayOutputStream()
        book.write(out, about)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(30_000, book.paragraphs.size)
        assertTrue("took $seconds s", seconds < 20)
        // Text packs small: the pages take less room than the file they were made from.
        assertTrue(out.size() < text.length)
    }

    // --- The EPUB ------------------------------------------------------------------------------

    @Test
    fun `the pages are an EPUB, with the book's name and language`() {
        val book = TextBook.of("Chapter 1\n\nOnce.\n\nChapter 2\n\nTwice.")
        val out = ByteArrayOutputStream()
        book.write(out, about)
        // The type comes first and is stored as it is: that is how an EPUB is known.
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            val first = zip.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", String(zip.readBytes(), Charsets.US_ASCII))
        }
        val files = files(book)
        assertEquals(listOf("mimetype", "META-INF/container.xml", "content.opf", "nav.xhtml", "style.css", "text/part0001.xhtml"), files.keys.toList())
        assertTrue(String(files.getValue("META-INF/container.xml")).contains("full-path=\"content.opf\""))
        val opf = xml(files.getValue("content.opf"))
        fun dc(name: String) = opf.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", name).item(0)?.textContent
        assertEquals("A Book", dc("title"))
        assertEquals("Someone", dc("creator"))
        assertEquals("en", dc("language"))
        assertEquals("urn:jacket:text:b1", dc("identifier"))
        val page = xml(files.getValue("text/part0001.xhtml"))
        assertEquals("en", page.documentElement.getAttribute("lang"))
        assertEquals("", page.documentElement.getAttribute("dir"))
    }

    @Test
    fun `a book with no chapters has no contents, and one with no known language names none`() {
        val files = files(TextBook.of("Just a note.\n\nNothing more."), TextBook.About("b2", "Note"))
        assertFalse("nav.xhtml" in files)
        val opf = String(files.getValue("content.opf"), Charsets.UTF_8)
        assertFalse(opf.contains("properties=\"nav\""))
        assertFalse(opf.contains("dc:language"))
        assertFalse(opf.contains("dc:creator"))
        assertFalse(String(files.getValue("text/part0001.xhtml"), Charsets.UTF_8).contains("lang="))
    }

    @Test
    fun `a book in a right-to-left language is laid out that way`() {
        val files = files(TextBook.of("مرحبا بالعالم.\n\nهذا كتاب."), TextBook.About("b3", "كتاب", language = "ar"))
        assertTrue(String(files.getValue("content.opf"), Charsets.UTF_8).contains("page-progression-direction=\"rtl\""))
        assertEquals("rtl", xml(files.getValue("text/part0001.xhtml")).documentElement.getAttribute("dir"))
    }

    @Test
    fun `no character in a file stops its pages from being shown`() {
        // What text files really hold: marks of code, a nought, a bell, a form feed, half a character, an emoji.
        val nasty = "A < B && C > D \"quoted\" 'single' ]]> <!-- --> &amp; <p>\n\nNul\u0000 bell\u0007 escape\u001B feed\u000C done.\n\n" +
            "Half \uD83D alone, \uDCD6 too, whole \uD83D\uDCD6.\n\nNot a character: \uFFFE \uFFFF.\n\nChapter 1 <One> & \"Two\""
        val book = TextBook.of(nasty)
        val files = files(book, TextBook.About("b<4>", "T<i>tle & \u0001\"more\"", "A&B"))
        for ((name, bytes) in files) if (name.endsWith(".xhtml") || name.endsWith(".opf") || name.endsWith(".xml")) xml(bytes)
        val shown = body(files.getValue("text/part0001.xhtml"))
        assertTrue(shown.contains("A < B && C > D \"quoted\" 'single' ]]> <!-- --> &amp; <p>"))
        // What can't be kept is a space, so everything after it is still where it was.
        assertTrue(shown.contains("Nul  bell  escape  feed  done."))
        assertTrue(shown.contains("whole \uD83D\uDCD6."))
        assertEquals(book.paragraphs.map { it.length }, book.blocks.map { it.text.length })
        assertEquals("Chapter 1 <One> & \"Two\"", book.contents.single().title)
    }

    @Test
    fun `the same text always gives the same pages`() {
        val text = "Chapter 1\n\n" + sentences(300) + "\n\n* * *\n\n" + sentences(300)
        val first = ByteArrayOutputStream().also { TextBook.of(text).write(it, about) }.toByteArray()
        val second = ByteArrayOutputStream().also { TextBook.of(text).write(it, about) }.toByteArray()
        assertArrayEquals(first, second)
    }

    @Test
    fun `pages made by this version are known, and anything else is made again`() {
        val folder = Files.createTempDirectory("jacket-text-book").toFile()
        try {
            val made = File(folder, "made.epub")
            made.outputStream().use { TextBook.of("Words.").write(it, about) }
            assertTrue(TextBook.isCurrent(made))
            val other = File(folder, "other.epub")
            ZipOutputStream(other.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray())
                zip.closeEntry()
            }
            assertFalse(TextBook.isCurrent(other))
            assertFalse(TextBook.isCurrent(File(folder, "missing.epub")))
            assertFalse(TextBook.isCurrent(File(folder, "cut-short.epub").apply { writeBytes(made.readBytes().copyOf(40)) }))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `a file with nothing in it has no pages`() {
        for (text in listOf("", "  \n\n \t", "\uFEFF")) {
            val book = TextBook.of(text)
            assertTrue(book.paragraphs.isEmpty() && book.parts.isEmpty() && book.contents.isEmpty())
            assertNull(book.spotOf(TextPlace(0)))
            assertEquals(0, book.layout().paragraphs)
        }
    }

    // --- What a text says of itself -------------------------------------------------------------

    @Test
    fun `a Project Gutenberg file names its book and its author`() {
        val text = "The Project Gutenberg eBook of Alice's Adventures in Wonderland\n\nThis ebook is for the use of anyone anywhere.\n\n" +
            "Title: Alice's Adventures in Wonderland\n\nAuthor: Lewis Carroll\n\nRelease date: June 27, 2008\n\nCHAPTER I.\nDown the Rabbit-Hole"
        val credits = creditsOf(paragraphsOf(text))
        assertEquals("Alice's Adventures in Wonderland", credits.title)
        assertEquals("Lewis Carroll", credits.author)
        // Most files say nothing, and "title:" in the middle of a sentence is not a title.
        val none = creditsOf(paragraphsOf("Dear all,\n\nThe working title: something better.\n\nBye."))
        assertNull(none.title)
        assertNull(none.author)
    }

    @Test
    fun `shared text is named by its first line, or its first words`() {
        assertEquals("Ten ways to read more", titleOfSharedText("Ten ways to read more\n\nThe first is to carry a book."))
        assertEquals("Meeting notes", titleOfSharedText("\n\n  # Meeting notes  \nAttendees: all"))
        assertEquals("A short thought", titleOfSharedText("A short thought."))
        // A long first line is named by its first sentence, when that is one worth having.
        assertEquals("Ten ways to read more", titleOfSharedText("Ten ways to read more. The first is to carry a book everywhere you go, and the second is to stop."))
        assertEquals("Why do we read at all?", titleOfSharedText("Why do we read at all? Nobody quite knows, though everyone who reads has an answer of their own to give."))
        // (A first sentence of a word or two is no title: the first words are.)
        assertEquals("No. That was all she said before she left the room and went…", titleOfSharedText("No. That was all she said before she left the room and went out into the rain without her coat."))
        val long = "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness"
        assertEquals("It was the best of times, it was the worst of times, it was…", titleOfSharedText(long))
        assertTrue(titleOfSharedText("x".repeat(500))!!.length <= 61)
        // Nothing to name it by: the caller's own words for it are used.
        assertNull(titleOfSharedText("  \n * * * \n"))
        assertNull(titleOfSharedText(""))
    }
}
