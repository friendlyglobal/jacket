package io.github.friendlyglobal.jacket.importer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipFile

/*
 * An FB2 book (FictionBook 2), read.
 *
 * FB2 is one XML file that holds a whole book: what it is called and who wrote it, its chapters
 * (sections inside sections), its notes (a second body, named "notes"), and its pictures, written
 * out as text at the very end. It is the format most books are passed around in among Russian and
 * Ukrainian readers, as often as not zipped (".fb2.zip").
 *
 * It is read here with the XML reader Android already has, into the few things Jacket makes its
 * pages of (see Fb2Book.kt). The file is somebody else's, and is read as one that may mean harm:
 *
 *  - nothing outside the file is ever fetched or read for it. The XML reader is never asked to
 *    act on a DOCTYPE, so an entity the file declares (the way a file names another file to be
 *    pasted into it, or a few letters to be multiplied into gigabytes) is unknown to it, and a
 *    file that declares any is turned away before the reader sees it;
 *  - no more than [Fb2.MOST_BYTES] is read, zipped or not: a small zip can't unfold into more;
 *  - elements nested deeper than [Fb2.DEEPEST] end the reading.
 *
 * Everything here is apart from Android, so it can be tested.
 */

/** The file can't be read as an FB2 book: it isn't one, it is damaged, or it is more than Jacket takes in. */
class Fb2Unreadable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Where a stretch of words links to, as the file writes it ("#n1", "https://…"), and whether the file marks it as a note's. */
internal class Fb2Link(val href: String, val note: Boolean)

/** A stretch of words set one way; or, with [image], a small picture set among the words. */
internal data class Fb2Span(val text: String, val style: Int = 0, val link: Fb2Link? = null, val image: String? = null) {
    companion object {
        const val STRONG = 1
        const val EMPHASIS = 2
        const val STRIKE = 4
        const val SUB = 8
        const val SUP = 16
        const val CODE = 32
    }
}

/** A piece of the book. [id] is the name the file gives it, which links inside the file point at. */
internal sealed class Fb2Node {
    abstract val id: String?
}

/** A paragraph, or one of the other lines of words a book is made of. */
internal class Fb2Text(val kind: Kind, val spans: List<Fb2Span>, override val id: String? = null) : Fb2Node() {
    enum class Kind { PARAGRAPH, SUBTITLE, VERSE, TEXT_AUTHOR, DATE }

    val text: String get() = spans.joinToString("") { it.text }
}

/** Pieces that belong together: a title's lines, an epigraph, a quotation, a poem and its stanzas, an annotation. */
internal class Fb2Group(val kind: Kind, val children: List<Fb2Node>, override val id: String? = null) : Fb2Node() {
    /** [PLAIN] is an element this reader doesn't know: its words are kept, set as ordinary ones. */
    enum class Kind { TITLE, EPIGRAPH, CITE, POEM, STANZA, ANNOTATION, PLAIN }
}

/** A picture, by the name of the binary that holds it. */
internal class Fb2Image(val ref: String, val alt: String = "", override val id: String? = null) : Fb2Node()

/** An empty line. */
internal class Fb2Gap : Fb2Node() {
    override val id: String? get() = null
}

internal class Fb2Cell(val header: Boolean, val spans: List<Fb2Span>, val colspan: Int = 1, val rowspan: Int = 1, val align: String? = null)

internal class Fb2Table(val rows: List<List<Fb2Cell>>, override val id: String? = null) : Fb2Node()

/** A section: a chapter, a part, a note. Its title (a [Fb2Group]) and the sections inside it are among its [children]. */
internal class Fb2Section(val children: List<Fb2Node>, override val id: String? = null) : Fb2Node() {
    val title: Fb2Group? get() = children.firstOrNull { it is Fb2Group && it.kind == Fb2Group.Kind.TITLE } as Fb2Group?
}

/** One of the file's bodies: the book itself, or (named "notes") the notes its pages point to. */
internal class Fb2Body(val name: String?, val content: Fb2Section) {
    val notes: Boolean get() = name?.trim()?.lowercase(Locale.ROOT) in NOTE_BODIES

    private companion object {
        val NOTE_BODIES = setOf("notes", "footnotes", "comments")
    }
}

/** A picture as the file holds it: written out as text ([base64]). */
internal class Fb2Binary(val id: String, val type: String, val base64: String, val cover: Boolean)

/** What was read of an FB2 file. */
internal class Fb2Document(
    val title: String?,
    val authors: List<String>,
    /** The language the book says it is in, as a tag a voice can be chosen by ("ru"); null if it doesn't say, or says nothing usable. */
    val language: String?,
    val annotation: Fb2Group?,
    /** The name of the binary that is the cover. */
    val cover: String?,
    /** Empty when only the description was asked for. */
    val bodies: List<Fb2Body>,
    /** How many letters the bodies hold, spaces aside. */
    val letters: Long,
    /** The book's first words, to tell its language by when it doesn't say. */
    val sample: String,
)

internal object Fb2 {
    /** No more of a file than this is read, zipped or not: a book with a great many pictures is a few tens of megabytes. */
    const val MOST_BYTES = 96L * 1024 * 1024

    /** Elements nested deeper than this are no book's: sections inside sections go a handful deep. */
    const val DEEPEST = 64

    /** How much of the file's start is looked at to tell how it was saved, and that it is an FB2 book. */
    private const val HEAD = 64 * 1024

    /** How many of the first words are kept to tell the language by. */
    private const val SAMPLE = 20_000

    /** Whether [name], a file's, says it is an FB2 book: "book.fb2", or zipped, "book.fb2.zip" and "book.fbz". */
    fun isNamed(name: String): Boolean = name.lowercase(Locale.ROOT).let { it.endsWith(".fb2") || it.endsWith(".fb2.zip") || it.endsWith(".fbz") }

    /** [name] without what says it is an FB2 book: "war.fb2.zip" is "war". */
    fun bareName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val ending = listOf(".fb2.zip", ".fb2", ".fbz").firstOrNull { lower.endsWith(it) } ?: return name
        return name.dropLast(ending.length)
    }

    /** Whether [file] is an FB2 book, zipped or not, whatever it is called: told from how it starts. */
    fun looksLike(file: File): Boolean = runCatching { open(file) { prepared(it) }; true }.getOrDefault(false)

    /**
     * Opens [file] for [read]: the file itself, or, when it is a zip, the FB2 book inside it (the
     * first entry named ".fb2", or the only entry). Either way no more than [most] bytes can be
     * read from it: reading past them throws [Fb2Unreadable], as does a zip with no book in it.
     */
    fun <T> open(file: File, most: Long = MOST_BYTES, read: (InputStream) -> T): T {
        val zipped = file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
        if (!zipped) return file.inputStream().use { read(Limited(it, most)) }
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.take(MOST_ENTRIES).toList()
                val entry = entries.firstOrNull { it.name.lowercase(Locale.ROOT).endsWith(".fb2") } ?: entries.singleOrNull()
                    ?: throw Fb2Unreadable("No FB2 book in this zip")
                zip.getInputStream(entry).use { read(Limited(it, most)) }
            }
        } catch (e: java.util.zip.ZipException) {
            throw Fb2Unreadable("Not a zip that can be read", e)
        }
    }

    private const val MOST_ENTRIES = 64

    /**
     * Reads an FB2 book from [input], which is the XML itself (see [open]).
     *
     * With [bodies] false only the description is kept (the bodies are still counted, see
     * [Fb2Document.letters]). Each picture the file holds that [wants] says is wanted is handed
     * to [binary] as it is come to; they stand at the end of the file, after everything else.
     *
     * Throws [Fb2Unreadable] if it isn't an FB2 book, is damaged past reading, is too big, nests
     * too deep, or declares entities (see the top of this file).
     */
    fun read(
        input: InputStream,
        bodies: Boolean = true,
        wants: (id: String, cover: Boolean) -> Boolean = { _, _ -> true },
        binary: ((Fb2Binary) -> Unit)? = null,
    ): Fb2Document {
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            // Names without their prefixes ("l:href" is "href"), and nothing done about a DOCTYPE:
            // that is how the reader starts out, and it is never told otherwise.
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(prepared(input))
            // The few named characters files written by hand use without saying what they are.
            for ((name, character) in NAMED_CHARACTERS) runCatching { parser.defineEntityReplacementText(name, character) }
            return Parse(parser, bodies, wants, binary).document()
        } catch (e: Fb2Unreadable) {
            throw e
        } catch (e: XmlPullParserException) {
            throw Fb2Unreadable("Not XML that can be read: ${e.message}", e)
        } catch (e: IOException) {
            throw Fb2Unreadable("Couldn't be read: ${e.message}", e)
        } catch (e: RuntimeException) {
            // (The reader answers some damaged files with an index out of bounds.)
            throw Fb2Unreadable("Not XML that can be read", e)
        }
    }

    /**
     * [input] as text, read the way the file says it was saved: by the mark at its start, or the
     * "encoding" its first line names (UTF-8 and windows-1251 are what FB2 files use; anything
     * Java can read is read). A file that says nothing is UTF-8, as XML has it; unless it can't
     * be, when it is taken for windows-1251, which is what such files are.
     *
     * Turns away what isn't an FB2 book, and any file that declares an entity before its book begins.
     */
    internal fun prepared(input: InputStream): Reader {
        val stream = BufferedInputStream(input, HEAD)
        stream.mark(HEAD)
        val head = ByteArray(HEAD)
        var read = 0
        while (read < HEAD) {
            val n = stream.read(head, read, HEAD - read)
            if (n < 0) break
            read += n
        }
        stream.reset()
        val bytes = head.copyOf(read)
        val (charset, mark) = charsetOf(bytes)
        // The mark is no part of the text.
        repeat(mark) { stream.read() }
        val text = String(bytes, mark, bytes.size - mark, charset)
        val root = ROOT.find(text) ?: throw Fb2Unreadable("Not an FB2 book")
        if (text.substring(0, root.range.first).contains("<!ENTITY")) throw Fb2Unreadable("This file declares entities, which no FB2 book needs")
        return InputStreamReader(stream, charset)
    }

    /** How [head], the start of a file, says the file was saved, and how many bytes of mark come before its text. */
    internal fun charsetOf(head: ByteArray): Pair<Charset, Int> {
        fun starts(vararg mark: Int) = head.size >= mark.size && mark.indices.all { head[it] == mark[it].toByte() }
        return when {
            starts(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            starts(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            starts(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            // "<?" in UTF-16 saved without its mark.
            starts(0x3C, 0x00, 0x3F, 0x00) -> Charsets.UTF_16LE to 0
            starts(0x00, 0x3C, 0x00, 0x3F) -> Charsets.UTF_16BE to 0
            else -> {
                val declared = DECLARED.find(String(head, 0, minOf(head.size, 1024), Charsets.ISO_8859_1))?.groupValues?.get(1)
                val charset = when {
                    declared != null -> runCatching { Charset.forName(declared) }.getOrNull() ?: throw Fb2Unreadable("Saved in a way this phone can't read: $declared")
                    // Up to the end of a tag, so that no letter is cut in two.
                    TextDecoding.isUtf8(head.copyOf(head.lastIndexOf('>'.code.toByte()) + 1)) -> Charsets.UTF_8
                    else -> runCatching { Charset.forName("windows-1251") }.getOrDefault(Charsets.UTF_8)
                }
                charset to 0
            }
        }
    }

    private val DECLARED = Regex("^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z][A-Za-z0-9._:-]*)[\"']")
    private val ROOT = Regex("<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?FictionBook[\\s>]")

    private val NAMED_CHARACTERS = listOf(
        "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "copy" to "©", "shy" to "\u00AD",
    )

    /**
     * The language an FB2 book says it is in ([said]: "ru", "RU", "rus", "uk-UA"), as a tag a
     * voice can be chosen by; null when it says nothing that is one.
     */
    fun languageOf(said: String?): String? {
        val tag = said?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)?.takeIf { LANGUAGE.matches(it) } ?: return null
        val language = tag.substringBefore('-')
        // Three letters where two will do ("rus"): the two.
        val short = if (language.length == 3) THREE_LETTERS[language] ?: language else language
        return short + tag.substring(language.length)
    }

    private val LANGUAGE = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")
    private val THREE_LETTERS: Map<String, String> by lazy {
        // (And the other three letters a few languages go by in libraries.)
        val other = mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "rum" to "ro", "slo" to "sk", "per" to "fa")
        other + Locale.getISOLanguages().mapNotNull { two -> runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()?.takeIf { it.length == 3 }?.let { it to two } }
    }

    /** One reading of a file. */
    private class Parse(
        private val p: XmlPullParser,
        private val keepBodies: Boolean,
        private val wants: (String, Boolean) -> Boolean,
        private val binary: ((Fb2Binary) -> Unit)?,
    ) {
        private var letters = 0L
        private val sample = StringBuilder()
        private var title: String? = null
        private val authors = ArrayList<String>()
        private var language: String? = null
        private var annotation: Fb2Group? = null
        private var cover: String? = null
        private var described = false

        fun document(): Fb2Document {
            var event = p.eventType
            while (event != XmlPullParser.START_TAG) {
                if (event == XmlPullParser.END_DOCUMENT) throw Fb2Unreadable("Not an FB2 book")
                event = p.next()
            }
            if (p.name != "FictionBook") throw Fb2Unreadable("Not an FB2 book")
            val bodies = ArrayList<Fb2Body>()
            each { name ->
                when (name) {
                    "description" -> each { part -> if (part == "title-info" && !described) titleInfo() else skip() }
                    "body" -> body()?.let(bodies::add)
                    "binary" -> binary()
                    else -> skip()
                }
            }
            return Fb2Document(
                title = title?.takeIf { it.isNotEmpty() }, authors = authors, language = languageOf(language),
                annotation = annotation?.takeIf { it.children.isNotEmpty() }, cover = cover, bodies = bodies, letters = letters, sample = sample.toString(),
            )
        }

        /** Goes through the elements inside the one the reader is on. [handle] reads each to its end. */
        private inline fun each(handle: (String) -> Unit) {
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        deep()
                        handle(p.name)
                    }
                    XmlPullParser.END_TAG -> return
                    XmlPullParser.END_DOCUMENT -> throw Fb2Unreadable("The file ends before its book does")
                }
            }
        }

        private fun deep() {
            if (p.depth > DEEPEST) throw Fb2Unreadable("Nested deeper than any book is")
        }

        /** Passes over the element the reader is on, and everything in it. With [counted], its words are counted as the book's. */
        private fun skip(counted: Boolean = false) {
            var depth = 1
            while (depth > 0) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        deep()
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> if (counted) count(p.text)
                    XmlPullParser.END_DOCUMENT -> throw Fb2Unreadable("The file ends before its book does")
                }
            }
        }

        private fun count(text: String) {
            val words = text.count { !it.isWhitespace() }
            if (words == 0) return
            letters += words
            if (sample.length < SAMPLE) sample.append(text.trim().take(SAMPLE - sample.length)).append('\n')
        }

        /** The words of the element the reader is on and of all it holds, as one line. */
        private fun words(): String {
            val out = StringBuilder()
            var depth = 1
            while (depth > 0) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        deep()
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> out.append(p.text)
                    XmlPullParser.END_DOCUMENT -> throw Fb2Unreadable("The file ends before its book does")
                }
            }
            return out.toString().replace(SPACES, " ").trim()
        }

        private fun attribute(name: String): String? = p.getAttributeValue(null, name)?.trim()?.takeIf { it.isNotEmpty() }

        /** Where the element the reader is on links to: its "href", whatever prefix the file gives it. */
        private fun href(): String? {
            for (i in 0 until p.attributeCount) {
                val name = p.getAttributeName(i)
                if (name == "href" || name.endsWith(":href")) return p.getAttributeValue(i)?.trim()?.takeIf { it.isNotEmpty() }
            }
            return null
        }

        private fun titleInfo() {
            described = true
            each { name ->
                when (name) {
                    "book-title" -> title = words()
                    "author" -> author()?.let(authors::add)
                    "lang" -> language = words()
                    "annotation" -> annotation = Fb2Group(Fb2Group.Kind.ANNOTATION, blocks(counted = false))
                    "coverpage" -> each { inner ->
                        if (inner == "image" && cover == null) cover = href()?.removePrefix("#")
                        skip()
                    }
                    else -> skip()
                }
            }
        }

        /** An author's name as it is written on a cover: first, middle and last name, or failing those the name they go by. */
        private fun author(): String? {
            var first = ""
            var middle = ""
            var last = ""
            var nickname = ""
            each { name ->
                when (name) {
                    "first-name" -> first = words()
                    "middle-name" -> middle = words()
                    "last-name" -> last = words()
                    "nickname" -> nickname = words()
                    else -> skip()
                }
            }
            return listOf(first, middle, last).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { nickname }.takeIf { it.isNotEmpty() }
        }

        private fun body(): Fb2Body? {
            val name = attribute("name")
            if (!keepBodies) {
                skip(counted = true)
                return null
            }
            return Fb2Body(name, Fb2Section(blocks()))
        }

        private fun binary() {
            val id = attribute("id")
            val sink = binary
            val isCover = id != null && id == cover
            if (sink == null || id == null || !wants(id, isCover)) return skip()
            sink(Fb2Binary(id, attribute("content-type").orEmpty(), p.nextText(), isCover))
        }

        /**
         * The pieces inside the element the reader is on. Words set straight into it, where only
         * paragraphs belong, are a paragraph of their own.
         */
        private fun blocks(counted: Boolean = true): List<Fb2Node> {
            val out = ArrayList<Fb2Node>()
            while (true) {
                when (p.next()) {
                    XmlPullParser.TEXT -> tidy(listOf(Fb2Span(p.text)), counted).takeIf { it.isNotEmpty() }?.let { out += Fb2Text(Fb2Text.Kind.PARAGRAPH, it) }
                    XmlPullParser.START_TAG -> {
                        deep()
                        node(p.name, counted)?.let(out::add)
                    }
                    XmlPullParser.END_TAG -> return out
                    XmlPullParser.END_DOCUMENT -> throw Fb2Unreadable("The file ends before its book does")
                }
            }
        }

        private fun node(name: String, counted: Boolean): Fb2Node? {
            val id = attribute("id")
            fun text(kind: Fb2Text.Kind): Fb2Node? {
                val spans = spans(counted)
                return when {
                    spans.isNotEmpty() -> Fb2Text(kind, spans, id)
                    // A paragraph with nothing in it is what some files leave a line empty with.
                    kind == Fb2Text.Kind.PARAGRAPH -> Fb2Gap()
                    else -> null
                }
            }
            fun group(kind: Fb2Group.Kind) = Fb2Group(kind, blocks(counted), id)
            return when (name) {
                "p" -> text(Fb2Text.Kind.PARAGRAPH)
                "subtitle" -> text(Fb2Text.Kind.SUBTITLE)
                "v" -> text(Fb2Text.Kind.VERSE)
                "text-author" -> text(Fb2Text.Kind.TEXT_AUTHOR)
                "date" -> text(Fb2Text.Kind.DATE)
                "empty-line" -> {
                    skip()
                    Fb2Gap()
                }
                "image" -> {
                    val ref = href()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
                    val alt = attribute("alt") ?: attribute("title")
                    skip()
                    ref?.let { Fb2Image(it, alt.orEmpty(), id) }
                }
                "title" -> group(Fb2Group.Kind.TITLE)
                "epigraph" -> group(Fb2Group.Kind.EPIGRAPH)
                "cite" -> group(Fb2Group.Kind.CITE)
                "poem" -> group(Fb2Group.Kind.POEM)
                "stanza" -> group(Fb2Group.Kind.STANZA)
                "annotation" -> group(Fb2Group.Kind.ANNOTATION)
                "table" -> table(id, counted)
                "section" -> Fb2Section(blocks(counted), id)
                // What isn't known is read for the words in it rather than left out.
                else -> group(Fb2Group.Kind.PLAIN).takeIf { it.children.isNotEmpty() }
            }
        }

        private fun table(id: String?, counted: Boolean): Fb2Table {
            val rows = ArrayList<List<Fb2Cell>>()
            each { row ->
                if (row != "tr") return@each skip()
                val cells = ArrayList<Fb2Cell>()
                each { cell ->
                    if (cell != "td" && cell != "th") return@each skip()
                    fun span(name: String) = attribute(name)?.toIntOrNull()?.coerceIn(1, MOST_SPAN) ?: 1
                    val colspan = span("colspan")
                    val rowspan = span("rowspan")
                    val align = attribute("align")?.lowercase(Locale.ROOT)?.takeIf { it in ALIGNMENTS }
                    cells += Fb2Cell(cell == "th", spans(counted), colspan, rowspan, align)
                }
                if (cells.isNotEmpty()) rows += cells
            }
            return Fb2Table(rows, id)
        }

        private class Mode(val style: Int, val link: Fb2Link?)

        /** The words of the element the reader is on, with how each stretch of them is set. */
        private fun spans(counted: Boolean): List<Fb2Span> {
            val out = ArrayList<Fb2Span>()
            val open = ArrayList<Mode>()
            var mode = Mode(0, null)
            val depth = p.depth
            while (true) {
                when (p.next()) {
                    XmlPullParser.TEXT -> out += Fb2Span(p.text, mode.style, mode.link)
                    XmlPullParser.START_TAG -> {
                        deep()
                        open += mode
                        mode = when (p.name) {
                            "strong" -> Mode(mode.style or Fb2Span.STRONG, mode.link)
                            "emphasis" -> Mode(mode.style or Fb2Span.EMPHASIS, mode.link)
                            "strikethrough" -> Mode(mode.style or Fb2Span.STRIKE, mode.link)
                            "sub" -> Mode(mode.style or Fb2Span.SUB, mode.link)
                            "sup" -> Mode(mode.style or Fb2Span.SUP, mode.link)
                            "code" -> Mode(mode.style or Fb2Span.CODE, mode.link)
                            "a" -> href()?.let { Mode(mode.style, Fb2Link(it, attribute("type")?.lowercase(Locale.ROOT) == "note")) } ?: mode
                            "image" -> {
                                href()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let { out += Fb2Span("", mode.style, mode.link, image = it) }
                                mode
                            }
                            // "style", and what isn't known: its words, set as those around it.
                            else -> mode
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.depth == depth) return tidy(out, counted)
                        mode = open.removeAt(open.lastIndex)
                    }
                    XmlPullParser.END_DOCUMENT -> throw Fb2Unreadable("The file ends before its book does")
                }
            }
        }

        /**
         * [spans] as a paragraph holds them: a run of spaces and line breaks (the file's own
         * layout) is one space, there is none at either end, and stretches set alike are one.
         */
        private fun tidy(spans: List<Fb2Span>, counted: Boolean): List<Fb2Span> {
            val out = ArrayList<Fb2Span>(spans.size)
            var spaced = true
            for (span in spans) {
                if (span.image != null) {
                    out += span
                    spaced = false
                    continue
                }
                val text = StringBuilder(span.text.length)
                for (c in span.text) {
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                        if (!spaced) text.append(' ')
                        spaced = true
                    } else {
                        text.append(c)
                        spaced = false
                    }
                }
                if (text.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && last.image == null && last.style == span.style && last.link === span.link) {
                    out[out.lastIndex] = last.copy(text = last.text + text)
                } else {
                    out += span.copy(text = text.toString())
                }
            }
            while (out.isNotEmpty()) {
                val last = out.last()
                if (last.image != null) break
                val trimmed = last.text.trimEnd(' ')
                if (trimmed.isEmpty()) {
                    out.removeAt(out.lastIndex)
                } else {
                    out[out.lastIndex] = last.copy(text = trimmed)
                    break
                }
            }
            if (counted) for (span in out) if (span.image == null) count(span.text)
            return out
        }

        private companion object {
            val SPACES = Regex("\\s+")
            val ALIGNMENTS = setOf("left", "right", "center")
            const val MOST_SPAN = 100
        }
    }

    /** Lets no more than [left] bytes be read: what is longer isn't a book, and what unzips to more is a trap. */
    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) spend(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) spend(it) }

        override fun skip(count: Long): Long = super.skip(count).also { if (it > 0) spend(it.toInt()) }

        override fun markSupported(): Boolean = false

        private fun spend(count: Int) {
            left -= count
            if (left < 0) throw Fb2Unreadable("This file is bigger than any book Jacket takes in")
        }
    }
}
