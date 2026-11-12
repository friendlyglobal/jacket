package io.github.friendlyglobal.jacket.importer

import io.github.friendlyglobal.jacket.reader.TappedLink
import io.github.friendlyglobal.jacket.reader.findNote
import io.github.friendlyglobal.jacket.reader.readNotes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * An FB2 book is read (Fb2.kt) and turned into a small EPUB (Fb2Book.kt): what is read of it,
 * however it was saved; what the pages are made of; and that a file meaning harm is turned away.
 */
class Fb2Test {
    private val about = Fb2Book.About("b1", "A Book", "Someone", "en")

    private fun fb2(body: String, description: String = "<title-info><book-title>A Book</book-title></title-info>", after: String = "") =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">\n" +
            "<description>$description</description>\n$body\n$after</FictionBook>\n"

    private fun read(xml: String): Fb2Document = Fb2.read(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun book(xml: String, pictures: Map<String, String> = emptyMap()): Fb2Book = Fb2Book.of(read(xml), about, pictures)

    private fun unreadable(xml: String): Fb2Unreadable? = unreadable(xml.toByteArray(Charsets.UTF_8))

    private fun unreadable(bytes: ByteArray): Fb2Unreadable? = try {
        Fb2.read(ByteArrayInputStream(bytes))
        null
    } catch (e: Fb2Unreadable) {
        e
    }

    /** Parses as a web view does a chapter of a book: as XML, where one stray character is an error page. */
    private fun wellFormed(markup: String) {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setErrorHandler(null)
        builder.parse(InputSource(StringReader(markup)))
    }

    /** The files of the EPUB made from [xml], by name, in the order they were written. */
    private fun pages(xml: ByteArray, about: Fb2Book.About = this.about): LinkedHashMap<String, ByteArray> {
        val out = ByteArrayOutputStream()
        Fb2Pages.write(ByteArrayInputStream(xml), out, "test") { about }
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes()
            }
        }
        return files
    }

    private val simple = fb2(
        description = """
            <title-info>
              <genre>prose_classic</genre>
              <author><first-name>Anton</first-name><middle-name>Pavlovich</middle-name><last-name>Chekhov</last-name></author>
              <author><nickname>A Friend</nickname></author>
              <book-title>The  Lady
                 with the Dog</book-title>
              <annotation><p>A story of <emphasis>Yalta</emphasis>.</p><p>And of Moscow.</p></annotation>
              <coverpage><image l:href="#cover.jpg"/></coverpage>
              <lang>RU</lang>
            </title-info>
            <document-info><author><nickname>somebody else</nickname></author></document-info>
        """,
        body = """
            <body>
              <title><p>Anton Chekhov</p><p>The Lady with the Dog</p></title>
              <section id="one">
                <title><p>I</p></title>
                <p>It was said that a <strong>new person</strong> had appeared on the
                   sea-front: a lady with a <emphasis>little <strong>dog</strong></emphasis>.</p>
                <empty-line/>
                <p>Dmitri Dmitritch Gurov had begun to take an interest in new arrivals.</p>
              </section>
              <section>
                <title><p>II</p><p>A week later</p></title>
                <p>A week had passed since they had met; see <a l:href="#one">the first part</a>.</p>
                <section><title><p>Evening</p></title><p>It was a holiday.</p></section>
              </section>
            </body>
        """,
    )

    // ---------------------------------------------------------------------------------------------
    // What is read of the file
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the description gives the title, the authors, the language, the annotation and the cover`() {
        val read = read(simple)
        assertEquals("The Lady with the Dog", read.title)
        assertEquals(listOf("Anton Pavlovich Chekhov", "A Friend"), read.authors)
        assertEquals("ru", read.language)
        assertEquals("cover.jpg", read.cover)
        assertEquals(2, read.annotation!!.children.size)
        assertEquals("A story of Yalta.", (read.annotation!!.children[0] as Fb2Text).text)
    }

    @Test
    fun `a paragraph's words are one line, with each stretch set its own way`() {
        val section = read(simple).bodies.single().content.children.filterIsInstance<Fb2Section>().first()
        assertEquals("one", section.id)
        val paragraph = section.children.filterIsInstance<Fb2Text>().first()
        // The file's own line breaks and indents are one space, and stretches set alike are one.
        assertEquals("It was said that a new person had appeared on the sea-front: a lady with a little dog.", paragraph.text)
        assertEquals(
            listOf("It was said that a " to 0, "new person" to Fb2Span.STRONG, " had appeared on the sea-front: a lady with a " to 0, "little " to Fb2Span.EMPHASIS, "dog" to (Fb2Span.EMPHASIS or Fb2Span.STRONG), "." to 0),
            paragraph.spans.map { it.text to it.style },
        )
        assertTrue(section.children[2] is Fb2Gap)
    }

    @Test
    fun `the language a file names is told as a tag a voice is chosen by`() {
        assertEquals("ru", Fb2.languageOf("ru"))
        assertEquals("ru", Fb2.languageOf(" RU "))
        assertEquals("ru", Fb2.languageOf("rus"))
        assertEquals("uk", Fb2.languageOf("ukr"))
        assertEquals("de", Fb2.languageOf("ger"))
        assertEquals("uk-ua", Fb2.languageOf("uk_UA"))
        assertNull(Fb2.languageOf("Russian language"))
        assertNull(Fb2.languageOf(""))
        assertNull(Fb2.languageOf(null))
    }

    @Test
    fun `only the description is kept when that is all that is asked, and the words are still counted`() {
        val read = Fb2.read(ByteArrayInputStream(simple.toByteArray()), bodies = false)
        assertTrue(read.bodies.isEmpty())
        assertEquals("The Lady with the Dog", read.title)
        assertTrue(read.letters > 100)
        assertTrue(read.sample.contains("Gurov"))
        // The annotation is the description's, not the book's words.
        assertFalse(read.sample.contains("Yalta"))
        assertEquals(0, Fb2.read(ByteArrayInputStream(fb2("<body><section><empty-line/></section></body>").toByteArray()), bodies = false).letters)
    }

    @Test
    fun `a file is known as an FB2 book by its name, zipped or not`() {
        assertTrue(Fb2.isNamed("War and Peace.fb2"))
        assertTrue(Fb2.isNamed("war.FB2.ZIP"))
        assertTrue(Fb2.isNamed("war.fbz"))
        assertFalse(Fb2.isNamed("war.zip"))
        assertFalse(Fb2.isNamed("fb2.txt"))
        assertEquals("war", Fb2.bareName("war.fb2.zip"))
        assertEquals("War and Peace", Fb2.bareName("War and Peace.fb2"))
        assertEquals("notes.txt", Fb2.bareName("notes.txt"))
    }

    // ---------------------------------------------------------------------------------------------
    // How the file was saved
    // ---------------------------------------------------------------------------------------------

    private val russian = "<?xml version=\"1.0\" encoding=\"ENC\"?>\n<FictionBook><description><title-info>" +
        "<author><first-name>Лев</first-name><last-name>Толстой</last-name></author><book-title>Война и мир</book-title><lang>ru</lang>" +
        "</title-info></description><body><section><title><p>Часть первая</p></title><p>— Ну, князь, Генуя и Лукка — поместья фамилии Бонапарте.</p></section></body></FictionBook>"

    private fun checkRussian(bytes: ByteArray, dash: String = "—") {
        val read = Fb2.read(ByteArrayInputStream(bytes))
        assertEquals("Война и мир", read.title)
        assertEquals(listOf("Лев Толстой"), read.authors)
        val section = read.bodies.single().content.children.single() as Fb2Section
        assertEquals("$dash Ну, князь, Генуя и Лукка $dash поместья фамилии Бонапарте.", (section.children[1] as Fb2Text).text)
    }

    @Test
    fun `a file saved as windows-1251 and saying so is read as it was written`() {
        val bytes = russian.replace("ENC", "windows-1251").toByteArray(Charset.forName("windows-1251"))
        // (Not UTF-8: read as that, it would be a row of question marks.)
        assertFalse(TextDecoding.isUtf8(bytes))
        checkRussian(bytes)
        // Whatever case the name is in, and by its other name.
        checkRussian(russian.replace("ENC", "Windows-1251").toByteArray(Charset.forName("windows-1251")))
        checkRussian(russian.replace("ENC", "cp1251").toByteArray(Charset.forName("windows-1251")))
    }

    @Test
    fun `utf-8 is read with a mark at its start, without one, and without being named`() {
        val bytes = russian.replace("ENC", "utf-8").toByteArray(Charsets.UTF_8)
        checkRussian(bytes)
        checkRussian(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes)
        checkRussian(russian.replace(" encoding=\"ENC\"", "").toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `other ways of saving a file are read too`() {
        // (KOI8-R has no long dash: such files are written with a short one.)
        checkRussian(russian.replace("ENC", "KOI8-R").replace("—", "-").toByteArray(Charset.forName("KOI8-R")), dash = "-")
        checkRussian(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + russian.replace("ENC", "UTF-16").toByteArray(Charsets.UTF_16LE))
        checkRussian(russian.replace("ENC", "UTF-16").toByteArray(Charsets.UTF_16BE))
        // A file that names no encoding and isn't UTF-8 is windows-1251: that is what such FB2 files are.
        checkRussian(russian.replace(" encoding=\"ENC\"", "").toByteArray(Charset.forName("windows-1251")))
    }

    @Test
    fun `an encoding this phone doesn't have is said, not guessed at`() {
        val refused = unreadable(russian.replace("ENC", "x-no-such-encoding").toByteArray(Charsets.UTF_8))
        assertNotNull(refused)
        assertTrue(refused!!.message!!.contains("x-no-such-encoding"))
    }

    // ---------------------------------------------------------------------------------------------
    // Zipped
    // ---------------------------------------------------------------------------------------------

    private fun temp(name: String, bytes: ByteArray): File = Files.createTempFile("fb2-test", name).toFile().apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a zipped book is the book inside the zip`() {
        val plain = temp(".fb2", simple.toByteArray())
        val zipped = temp(".fb2.zip", zipOf("readme.txt" to "hello".toByteArray(), "The Lady.fb2" to simple.toByteArray()))
        assertEquals("The Lady with the Dog", Fb2.open(plain) { Fb2.read(it) }.title)
        assertEquals("The Lady with the Dog", Fb2.open(zipped) { Fb2.read(it) }.title)
        assertTrue(Fb2.looksLike(plain))
        assertTrue(Fb2.looksLike(zipped))
        // A zip with one file in it is that file, whatever it is called.
        assertTrue(Fb2.looksLike(temp(".zip", zipOf("book" to simple.toByteArray()))))
        assertFalse(Fb2.looksLike(temp(".zip", zipOf("a.txt" to "one".toByteArray(), "b.txt" to "two".toByteArray()))))
        assertFalse(Fb2.looksLike(temp(".txt", "Just some words.".toByteArray())))
        assertFalse(Fb2.looksLike(temp(".xml", "<?xml version=\"1.0\"?><html><body/></html>".toByteArray())))
    }

    @Test
    fun `no more of a file is read than a book could be, however small its zip`() {
        // Ten megabytes of spaces zip to a few kilobytes.
        val padded = simple.replace("<body>", "<body>" + " ".repeat(10_000_000))
        val zipped = temp(".fb2.zip", zipOf("big.fb2" to padded.toByteArray()))
        assertTrue(zipped.length() < 100_000)
        val refused = runCatching { Fb2.open(zipped, most = 1_000_000) { Fb2.read(it) } }.exceptionOrNull()
        assertTrue(refused is Fb2Unreadable)
        // The same file, allowed its size, is read.
        assertEquals("The Lady with the Dog", Fb2.open(zipped, most = 20_000_000) { Fb2.read(it) }.title)
        val plain = temp(".fb2", padded.toByteArray())
        assertTrue(runCatching { Fb2.open(plain, most = 1_000_000) { Fb2.read(it) } }.exceptionOrNull() is Fb2Unreadable)
    }

    // ---------------------------------------------------------------------------------------------
    // Files that mean harm
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a file that names another file to be pasted into it is turned away`() {
        val secret = temp(".txt", "the secret".toByteArray())
        val xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM \"${secret.toURI()}\">]>\n" +
            "<FictionBook><description><title-info><book-title>&xxe;</book-title></title-info></description><body><section><p>&xxe;</p></section></body></FictionBook>"
        assertNotNull(unreadable(xml))
        // Nor with the declaration pushed past where the file's start is looked at: a file that
        // doesn't get to its book by then is no FB2 book.
        val late = xml.replace("<!DOCTYPE", "<!--" + "x".repeat(70_000) + "-->\n<!DOCTYPE")
        assertNotNull(unreadable(late))
    }

    @Test
    fun `a file that multiplies a few letters into gigabytes is turned away`() {
        val laughs = buildString {
            append("<?xml version=\"1.0\"?>\n<!DOCTYPE FictionBook [\n<!ENTITY a0 \"ha\">\n")
            for (i in 1..30) append("<!ENTITY a$i \"&a${i - 1};&a${i - 1};\">\n")
            append("]>\n<FictionBook><description/><body><section><p>&a30;</p></section></body></FictionBook>")
        }
        assertNotNull(unreadable(laughs))
    }

    @Test
    fun `a doctype that declares nothing does no harm, and an entity nobody declared is no book's`() {
        val plain = "<?xml version=\"1.0\"?>\n<!DOCTYPE FictionBook>\n<FictionBook><description><title-info><book-title>Plain</book-title></title-info></description>" +
            "<body><section><p>Words &amp; more&nbsp;words.</p></section></body></FictionBook>"
        val read = read(plain)
        assertEquals("Plain", read.title)
        // (The few named characters hand-made files use are known.)
        assertEquals("Words & more\u00A0words.", ((read.bodies.single().content.children.single() as Fb2Section).children.single() as Fb2Text).text)
        assertNotNull(unreadable(plain.replace("&nbsp;", "&xxe;")))
    }

    @Test
    fun `elements nested deeper than any book are turned away`() {
        fun nested(depth: Int) = fb2("<body>" + "<section>".repeat(depth) + "<p>Deep.</p>" + "</section>".repeat(depth) + "</body>")
        assertNull(unreadable(nested(20)))
        assertNotNull(unreadable(nested(Fb2.DEEPEST)))
        assertNotNull(unreadable(nested(5_000)))
        // Inside a paragraph too.
        assertNotNull(unreadable(fb2("<body><section><p>" + "<emphasis>".repeat(200) + "x" + "</emphasis>".repeat(200) + "</p></section></body>")))
    }

    @Test
    fun `what isn't an FB2 book, or breaks off, is said to be unreadable`() {
        assertNotNull(unreadable("<?xml version=\"1.0\"?><html><body><p>Not a book.</p></body></html>"))
        assertNotNull(unreadable("Just some words."))
        assertNotNull(unreadable(""))
        assertNotNull(unreadable(simple.substring(0, simple.length / 2)))
        assertNotNull(unreadable(simple.replace("</section>", "</sektion>")))
    }

    @Test
    fun `nothing of the file's own markup reaches the pages`() {
        val hostile = fb2(
            """
            <body><section id='"><script>alert(1)</script>'>
              <title><p>&lt;script&gt;alert(2)&lt;/script&gt;</p></title>
              <p>&lt;img src=x onerror=alert(3)&gt; and <a l:href="javascript:alert(4)">a link</a> and <a l:href="file:///etc/passwd">another</a>.</p>
              <p style="x" onclick="alert(5)" id="p&quot;1">Plain.</p>
              <image l:href="#evil.svg"/>
              <script>alert(6)</script>
            </section></body>
            """,
            after = "<binary id=\"evil.svg\" content-type=\"image/jpeg\">" +
                Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(7)</script></svg>".toByteArray()) + "</binary>",
        )
        val files = pages(hostile.toByteArray())
        assertFalse(files.keys.any { it.startsWith("images/") })
        val page = String(files.getValue("text/part0001.xhtml"))
        wellFormed(page)
        // No element but those written here, no address but a web page's or a letter's, no attribute of the file's.
        assertFalse(page.contains("<script"))
        assertFalse(page.contains("<img"))
        assertFalse(page.contains("javascript:"))
        assertFalse(page.contains("file:"))
        assertFalse(page.contains("onclick"))
        assertTrue(page.contains("&lt;script&gt;alert(2)&lt;/script&gt;"))
        assertTrue(page.contains("and a link and another."))
        // (An element nobody knows is read for its words, as words.)
        assertTrue(page.contains("alert(6)"))
        wellFormed(String(files.getValue("nav.xhtml")))
        wellFormed(String(files.getValue("content.opf")))
    }

    // ---------------------------------------------------------------------------------------------
    // The pages
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `each of the book's top sections is a chapter, and every titled section a line of the contents`() {
        val book = book(simple)
        // The book's own page (it has an annotation), the body's title, then a file for each top section.
        assertEquals(4, book.parts.size)
        assertEquals(listOf("I" to 0, "II. A week later" to 0, "Evening" to 1), book.contents.map { it.title to it.level })
        assertEquals(listOf("text/part0003.xhtml", "text/part0004.xhtml"), book.contents.take(2).map { it.href })
        assertTrue(book.contents[2].href.startsWith("text/part0004.xhtml#b"))
        book.parts.forEach { wellFormed(it.page()) }

        val own = book.parts[0].page()
        assertTrue(own.contains("<h1 class=\"book-title\""))
        assertTrue(own.contains(">A Book</h1>"))
        assertTrue(own.contains("<p class=\"book-author\""))
        assertTrue(own.contains("<div class=\"annotation\""))
        assertTrue(own.contains("A story of <em>Yalta</em>."))
        // The body's title is the book's heading; a section's is a chapter's, its lines one under the other.
        assertTrue(book.parts[1].page().contains(Regex("<h1 id=\"b\\d+\" dir=\"auto\">Anton Chekhov<br/>The Lady with the Dog</h1>")))
        assertTrue(book.parts[3].page().contains(Regex("<h2 id=\"b\\d+\" dir=\"auto\">II<br/>A week later</h2>")))
        assertTrue(book.parts[3].page().contains(Regex("<h3 id=\"b\\d+\" dir=\"auto\">Evening</h3>")))
        val first = book.parts[2].page()
        assertTrue(first.contains("a <strong>new person</strong> had appeared"))
        assertTrue(first.contains("<em>little </em><strong><em>dog</em></strong>."))
        assertTrue(first.contains(Regex("<div class=\"gap\" id=\"b\\d+\"></div>")))
        assertEquals("A story of Yalta. And of Moscow.", book.description)
    }

    @Test
    fun `a link to a place in the book leads to where that place is in the pages`() {
        val book = book(simple)
        val linked = Regex("see <a href=\"(part\\d+\\.xhtml)#(b\\d+)\">the first part</a>").find(book.parts[3].page())!!
        assertEquals("part0003.xhtml", linked.groupValues[1])
        // The section's name is its first element's: its heading.
        assertTrue(book.parts[2].page().contains("<h2 id=\"${linked.groupValues[2]}\""))
    }

    @Test
    fun `a book without an annotation starts with its own first page`() {
        val book = book(fb2("<body><section><title><p>One</p></title><p>First.</p></section><section><p>Second, untitled.</p></section></body>"))
        assertEquals(2, book.parts.size)
        assertFalse(book.parts[0].page().contains("book-title"))
        assertEquals(listOf("One"), book.contents.map { it.title })
        assertNull(book.description)
    }

    @Test
    fun `sections further in share a file until it has a few pages to it, and no file is longer than a web view lays out with ease`() {
        val small = "<p>" + "word ".repeat(100) + "</p>"
        val inner = (1..12).joinToString("") { "<section><title><p>Scene $it</p></title>$small</section>" }
        val book = book(fb2("<body><section><title><p>Part</p></title>$inner</section></body>"))
        // Twelve scenes of 500 letters: a new file at each scene that follows 4 000 letters or more.
        assertEquals(2, book.parts.size)
        assertEquals(13, book.contents.size)
        assertTrue(book.contents.drop(1).all { it.level == 1 })

        val long = (1..40).joinToString("") { "<p>" + "word ".repeat(1_000) + "</p>" }
        val cut = book(fb2("<body><section><title><p>Long</p></title>$long</section></body>"))
        assertEquals(4, cut.parts.size)
        cut.parts.forEach { assertTrue(it.page().length < 80_000) }
    }

    @Test
    fun `a file cut into a great many sections doesn't become a great many files`() {
        val sections = (1..Fb2Book.MOST_CHAPTER_FILES + 50).joinToString("") { "<section><p>Line $it.</p></section>" }
        val book = book(fb2("<body>$sections</body>"))
        assertTrue(book.parts.size < 20)
    }

    @Test
    fun `the same file always gives the same pages`() {
        val first = pages(simple.toByteArray())
        val second = pages(simple.toByteArray())
        assertEquals(first.keys.toList(), second.keys.toList())
        first.forEach { (name, bytes) -> assertArrayEquals(name, bytes, second[name]) }
    }

    // ---------------------------------------------------------------------------------------------
    // Notes, poems and the rest
    // ---------------------------------------------------------------------------------------------

    private val noted = fb2(
        body = """
            <body>
              <section>
                <title><p>The Raven</p></title>
                <epigraph><p>Quoth the Raven.</p><text-author>E. A. Poe</text-author></epigraph>
                <p>Once upon a midnight<a l:href="#n1" type="note">[1]</a> dreary, while I pondered<a l:href="#n2"><sup>2</sup></a>.</p>
                <poem>
                  <title><p>A Poem</p></title>
                  <stanza><v>Once upon a midnight dreary,</v><v>while I pondered, weak and <emphasis>weary</emphasis>,</v></stanza>
                  <stanza><v>Over many a quaint and curious</v><v>volume of forgotten lore.</v></stanza>
                  <text-author>Edgar Allan Poe</text-author>
                  <date>1845</date>
                </poem>
                <subtitle>* * *</subtitle>
                <cite><p>Nevermore.</p><text-author>The Raven</text-author></cite>
                <table><tr><th>Year</th><th align="right">Lines</th></tr><tr><td colspan="2">1845</td></tr></table>
                <p>See <a l:href="#n1" type="note">[1]</a> again, and <a l:href="https://example.org/a?b=1&amp;c=2">the web</a>, and <a l:href="#nowhere">nothing</a>.</p>
              </section>
            </body>
            <body name="notes">
              <title><p>Notes</p></title>
              <section id="n1"><title><p>1</p></title><p>Midnight: the <emphasis>middle</emphasis> of the night.</p><p>A second paragraph.</p></section>
              <section id="n2"><title><p>2</p></title><p>To ponder is to think.</p></section>
              <section id="n3"><title><p>3</p></title><p>Nobody points here.</p></section>
            </body>
        """,
    )

    @Test
    fun `epigraphs, poems, quotations, subtitles and tables are set as such`() {
        val book = book(noted)
        val page = book.parts[0].page()
        wellFormed(page)
        assertTrue(page.contains(Regex("<blockquote class=\"epigraph\" id=\"b\\d+\">\\s*<p id=\"b\\d+\" dir=\"auto\">Quoth the Raven.</p>\\s*<p class=\"text-author\" id=\"b\\d+\" dir=\"auto\">E. A. Poe</p>\\s*</blockquote>")))
        assertTrue(page.contains(Regex("<div class=\"poem\" id=\"b\\d+\">\\s*<div class=\"title\" id=\"b\\d+\">\\s*<p id=\"b\\d+\" dir=\"auto\">A Poem</p>")))
        assertEquals(2, Regex("<div class=\"stanza\"").findAll(page).count())
        assertEquals(4, Regex("<p class=\"v\"").findAll(page).count())
        assertTrue(page.contains("while I pondered, weak and <em>weary</em>,</p>"))
        assertTrue(page.contains(Regex("<p class=\"date\" id=\"b\\d+\" dir=\"auto\">1845</p>")))
        assertTrue(page.contains(Regex("<p class=\"subtitle\" id=\"b\\d+\" dir=\"auto\">\\* \\* \\*</p>")))
        assertTrue(page.contains(Regex("<blockquote class=\"cite\" id=\"b\\d+\">")))
        assertTrue(page.contains("<tr><th dir=\"auto\">Year</th><th style=\"text-align: right\" dir=\"auto\">Lines</th></tr>"))
        assertTrue(page.contains("<td colspan=\"2\" dir=\"auto\">1845</td>"))
        // A link out of the book is kept; one to a name the file doesn't have is plain words.
        assertTrue(page.contains("<a href=\"https://example.org/a?b=1&amp;c=2\">the web</a>"))
        assertTrue(page.contains(", and nothing.</p>"))
    }

    @Test
    fun `the notes are at the back, each marked as a note, and each link to one as a note's number`() {
        val book = book(noted)
        assertEquals(2, book.parts.size)
        assertEquals(listOf("The Raven", "Notes"), book.contents.map { it.title })
        val page = book.parts[0].page()
        val notes = book.parts[1].page()
        wellFormed(notes)
        // Marked by the file as a note's, or leading into the notes without saying so: both are.
        val first = Regex("<a class=\"noteref\" epub:type=\"noteref\" role=\"doc-noteref\" id=\"(r\\d+)\" href=\"part0002.xhtml#(b\\d+)\">\\[1]</a>").findAll(page).toList()
        assertEquals(2, first.size)
        val second = Regex("<a class=\"noteref\" epub:type=\"noteref\" role=\"doc-noteref\" id=\"(r\\d+)\" href=\"part0002.xhtml#(b\\d+)\"><sup>2</sup></a>").find(page)
        assertNotNull(second)
        val noteId = first[0].groupValues[2]
        assertEquals(noteId, first[1].groupValues[2])
        // Each note leads back to the first place the book calls it; a note nobody calls has no way back.
        assertTrue(notes.contains("<div class=\"note\" epub:type=\"footnote\" role=\"doc-footnote\" id=\"$noteId\">"))
        assertTrue(notes.contains("<a epub:type=\"backlink\" role=\"doc-backlink\" href=\"part0001.xhtml#${first[0].groupValues[1]}\">1</a>"))
        assertTrue(notes.contains("<p class=\"note-title\" dir=\"auto\">3</p>"))
        assertTrue(notes.contains(Regex("<h2 id=\"b\\d+\" dir=\"auto\">Notes</h2>")))
    }

    @Test
    fun `a tap on a note's number finds the note, without its number or the way back`() {
        val book = book(noted)
        val page = book.parts[0].page()
        val link = Regex("id=\"(r\\d+)\" href=\"part0002.xhtml#(b\\d+)\">\\[1]").find(page)!!
        // As the reader does it: the file the link leads into is read, and the note looked for where the link points.
        val file = readNotes(book.parts[1].page())
        val note = findNote(file, link.groupValues[2], TappedLink(types = "noteref", role = "doc-noteref", text = "[1]", ids = listOf(link.groupValues[1])))!!
        assertEquals("1", note.mark)
        assertEquals(listOf("Midnight: the middle of the night.", "A second paragraph."), note.paragraphs.map { it.text })
        assertEquals(listOf(14..19), note.paragraphs[0].italic)
    }

    @Test
    fun `notes grouped under headings keep their headings`() {
        val book = book(
            fb2(
                """
                <body><section><p>Text<a l:href="#a1" type="note">1</a>.</p></section></body>
                <body name="notes"><title><p>Notes</p></title>
                  <section><title><p>Chapter one</p></title>
                    <section id="a1"><title><p>1</p></title><p>The note.</p></section>
                  </section>
                </body>
                """,
            ),
        )
        assertEquals(listOf("Notes" to 0, "Chapter one" to 1), book.contents.map { it.title to it.level })
        val notes = book.parts[1].page()
        assertTrue(notes.contains(Regex("<h3 id=\"b\\d+\" dir=\"auto\">Chapter one</h3>")))
        assertEquals(1, Regex("epub:type=\"footnote\"").findAll(notes).count())
    }

    // ---------------------------------------------------------------------------------------------
    // The EPUB
    // ---------------------------------------------------------------------------------------------

    /** The smallest picture there is: one white pixel, as a PNG. */
    private val pixel = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")

    @Test
    fun `the pages are an EPUB that says what the file says, with its cover and its pictures`() {
        val encoded = Base64.getMimeEncoder(40, "\n".toByteArray()).encodeToString(pixel)
        val pictured = simple.replace("<empty-line/>", "<image l:href=\"#pic\" alt=\"A &quot;dog&quot;\"/><p>Inline <image l:href=\"#pic\"/> too, and <image l:href=\"#missing\"/> not.</p>") +
            ""
        val xml = pictured.replace(
            "</FictionBook>",
            "<binary id=\"cover.jpg\" content-type=\"image/png\">$encoded</binary>\n<binary id=\"pic\" content-type=\"image/png\">\n$encoded\n</binary>\n" +
                "<binary id=\"text\" content-type=\"text/plain\">aGVsbG8=</binary>\n<binary id=\"broken\" content-type=\"image/png\">!!!</binary>\n</FictionBook>",
        )
        val files = pages(xml.toByteArray(), Fb2Book.About("b1", "The Lady with the Dog", "Anton Chekhov", "ru"))
        assertEquals("mimetype", files.keys.first())
        assertEquals("application/epub+zip", String(files.getValue("mimetype")))
        // Only what is a picture is kept, named anew.
        assertEquals(listOf("images/i1.png", "images/i2.png"), files.keys.filter { it.startsWith("images/") })
        assertArrayEquals(pixel, files.getValue("images/i2.png"))

        val opf = String(files.getValue("content.opf"))
        wellFormed(opf)
        assertTrue(opf.contains("<dc:title>The Lady with the Dog</dc:title>"))
        assertTrue(opf.contains("<dc:creator>Anton Chekhov</dc:creator>"))
        assertTrue(opf.contains("<dc:language>ru</dc:language>"))
        assertTrue(opf.contains("<dc:description>A story of Yalta. And of Moscow.</dc:description>"))
        assertTrue(opf.contains("<item id=\"img-i1\" href=\"images/i1.png\" media-type=\"image/png\" properties=\"cover-image\"/>"))
        assertTrue(opf.contains("<meta name=\"cover\" content=\"img-i1\"/>"))
        assertTrue(opf.contains("<item id=\"img-i2\" href=\"images/i2.png\" media-type=\"image/png\"/>"))
        assertEquals(4, Regex("<itemref ").findAll(opf).count())
        wellFormed(String(files.getValue("nav.xhtml")))
        assertTrue(String(files.getValue("META-INF/container.xml")).contains("content.opf"))

        val page = String(files.getValue("text/part0003.xhtml"))
        wellFormed(page)
        assertTrue(page.contains(Regex("<div class=\"image\" id=\"b\\d+\"><img src=\"../images/i2.png\" alt=\"A &quot;dog&quot;\"/></div>")))
        assertTrue(page.contains("Inline <img class=\"inline\" src=\"../images/i2.png\" alt=\"\"/> too, and  not.</p>"))
        assertTrue(page.contains("lang=\"ru\""))
    }

    @Test
    fun `the cover is the only picture taken when a book is added`() {
        val encoded = Base64.getEncoder().encodeToString(pixel)
        val xml = simple.replace("</FictionBook>", "<binary id=\"other\" content-type=\"image/png\">AAAA</binary><binary id=\"cover.jpg\" content-type=\"image/png\">$encoded</binary></FictionBook>")
        val taken = ArrayList<Fb2Binary>()
        Fb2.read(ByteArrayInputStream(xml.toByteArray()), bodies = false, wants = { _, cover -> cover }) { taken += it }
        assertEquals(listOf("cover.jpg"), taken.map { it.id })
        assertTrue(taken.single().cover)
        assertArrayEquals(pixel, Base64.getMimeDecoder().decode(taken.single().base64))
    }

    @Test
    fun `a picture is known by its bytes, whatever the file calls it`() {
        assertEquals("image/png" to "png", Fb2Pages.pictureType(pixel))
        assertEquals("image/jpeg" to "jpg", Fb2Pages.pictureType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif" to "gif", Fb2Pages.pictureType("GIF89a".toByteArray()))
        assertNull(Fb2Pages.pictureType("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertNull(Fb2Pages.pictureType(ByteArray(0)))
    }

    @Test
    fun `a book in a language written right to left is laid out that way`() {
        val files = pages(simple.toByteArray(), Fb2Book.About("b1", "كتاب", "", "ar"))
        assertTrue(String(files.getValue("content.opf")).contains("<spine page-progression-direction=\"rtl\">"))
        assertTrue(String(files.getValue("text/part0002.xhtml")).contains(" dir=\"rtl\""))
    }
}
