package io.github.friendlyglobal.jacket.importer

import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * An FB2 book as a book.
 *
 * An FB2 file (see Fb2.kt) is read through a small EPUB made from it, the way a text file is (see
 * TextBook.kt): Readium then lays it out in pages, reads it aloud, and lets it be highlighted,
 * searched and bookmarked, exactly as it does a book that came as an EPUB. The FB2 file stays the
 * book: it is what the library keeps, what a backup names, and what is added again after a
 * restore. The EPUB is only its pages, kept where no backup looks and made again whenever they
 * are missing or were made by an older Jacket.
 *
 * What the file says of the book becomes what an EPUB says of one:
 *
 *  - its title, authors, language and annotation, and its cover;
 *  - its sections, as chapters: each of the book's top sections starts on a page of its own, and
 *    every section with a title is a line of the contents, as deep as it is nested;
 *  - emphasis, epigraphs, quotations, poems, subtitles, tables and pictures, set as such;
 *  - its notes (the body named "notes"), at the back, each marked as a note and every link to
 *    one marked as a note's number: a tap on the number then shows the note over the page, and
 *    the voice passes the number over, as in any book that marks its notes.
 *
 * The pages are made the same way every time from the same file: every element is named by its
 * number in the book ("b12"), so a place, a bookmark or a highlight finds the same words in pages
 * made again. All the markup is written here, from words that are escaped as they are written:
 * nothing of the file's own markup, and no script, can come through.
 */

/** An FB2 book laid out as chapter files. Made by [of]. */
internal class Fb2Book private constructor(
    private val document: Fb2Document,
    /** Where each picture the file holds is in the pages ("images/i1.jpg"), by the name the file gives it. */
    private val pictures: Map<String, String>,
    private val about: About,
) {
    /** What the book says of itself on its pages and to a media player. */
    class About(val id: String, val title: String, val author: String = "", val language: String? = null)

    /** A line of the contents. [level] is 0 at the top. */
    class Entry(val title: String, val level: Int, val href: String)

    internal enum class Kind { BOOK_TITLE, BOOK_AUTHOR, HEADING, BLOCK, NOTE }

    /** One thing set down the page: a heading, a paragraph, a poem, a note. */
    internal class Item(
        val kind: Kind,
        val node: Fb2Node?,
        /** How deep the section it heads is (-1 for a body's own title). */
        val depth: Int,
        val element: String,
        val letters: Int,
        /** The section that begins with it, by how deep it is; -1 when none does. */
        val begins: Int,
        val inNotes: Boolean,
    ) {
        var part = 0
    }

    /** One chapter file. */
    inner class Part internal constructor(val index: Int, private val items: List<Item>) {
        val href: String get() = hrefOf(index)

        /** The file's markup. */
        fun page(): String = pageOf(items)
    }

    private class Target(val part: Int, val element: String, val note: Boolean)
    private class Back(val part: Int, val ref: String)

    val parts: List<Part>
    val contents: List<Entry>

    /** The name each piece has in the pages. */
    private val names = HashMap<Fb2Node, String>()

    /** Where the names the file gives its pieces are in the pages. */
    private val targets = HashMap<String, Target>()

    /** The links that lead to notes, each with the name it has in the pages (so its note can lead back to it). */
    private val refs = HashMap<Fb2Link, String>()

    /** For each note, the first link in the book that leads to it. */
    private val backs = HashMap<String, Back>()

    private val rightToLeft = about.language?.substringBefore('-')?.lowercase(Locale.ROOT) in TextBook.RIGHT_TO_LEFT

    init {
        val items = ArrayList<Item>()
        // Names the file gives to sections, waiting for the first thing set down in them.
        val waiting = ArrayList<Pair<String, Boolean>>()
        val named = ArrayList<Triple<String, Item, Boolean>>()
        var count = 0
        var begins = -1
        fun name(): String = "b${count++}"

        /** Names [node] and all it holds, in the order they are set down. */
        fun nameAll(node: Fb2Node) {
            names[node] = name()
            when (node) {
                is Fb2Group -> node.children.forEach(::nameAll)
                is Fb2Section -> node.children.forEach(::nameAll)
                else -> {}
            }
        }

        fun add(kind: Kind, node: Fb2Node?, depth: Int, inNotes: Boolean, element: String? = null): Item {
            if (node != null && element == null) nameAll(node)
            val item = Item(kind, node, depth, element ?: names.getValue(node!!), node?.let(::lettersOf) ?: 0, begins, inNotes)
            begins = -1
            items += item
            waiting.forEach { (id, note) -> named += Triple(id, item, note) }
            waiting.clear()
            return item
        }

        fun begin(depth: Int) {
            begins = if (begins < 0) depth else minOf(begins, depth)
        }

        // A page of the book's own, where it has something to say of itself: its annotation.
        document.annotation?.let { annotation ->
            begin(0)
            add(Kind.BOOK_TITLE, null, -1, inNotes = false, element = name())
            if (about.author.isNotBlank()) add(Kind.BOOK_AUTHOR, null, -1, inNotes = false, element = name())
            add(Kind.BLOCK, annotation, -1, inNotes = false)
        }

        fun section(section: Fb2Section, depth: Int) {
            begin(depth.coerceAtLeast(0))
            section.id?.let { waiting += it to false }
            for (child in section.children) {
                when {
                    child is Fb2Section -> section(child, depth + 1)
                    child is Fb2Group && child.kind == Fb2Group.Kind.TITLE -> if (lettersOf(child) > 0) add(Kind.HEADING, child, depth, inNotes = false)
                    else -> add(Kind.BLOCK, child, depth, inNotes = false)
                }
            }
        }

        /** The notes: a section that holds others is a heading over them; one that holds none is a note. */
        fun notes(section: Fb2Section, depth: Int) {
            for (child in section.children) {
                when {
                    child is Fb2Section && child.children.any { it is Fb2Section } -> {
                        child.id?.let { waiting += it to false }
                        notes(child, depth + 1)
                    }
                    child is Fb2Section -> {
                        val element = name()
                        child.children.forEach(::nameAll)
                        val item = add(Kind.NOTE, child, depth + 1, inNotes = true, element = element)
                        child.id?.let { named += Triple(it, item, true) }
                    }
                    child is Fb2Group && child.kind == Fb2Group.Kind.TITLE -> if (lettersOf(child) > 0) add(Kind.HEADING, child, depth, inNotes = true)
                    else -> add(Kind.BLOCK, child, depth, inNotes = true)
                }
            }
        }

        // The book first, then its notes, wherever in the file they stand.
        for (body in document.bodies.filterNot { it.notes }) {
            begin(0)
            section(body.content, -1)
        }
        for (body in document.bodies.filter { it.notes }) {
            begin(0)
            notes(body.content, 0)
        }
        waiting.clear()

        // Chapter files. Each of the book's top sections starts one, so a chapter begins on a
        // page of its own; a section further in starts one once the file before it has a few
        // pages to it; and no file holds more than a web view lays out without effort. (A file
        // cut into a great many sections is no book of chapters: they follow one another.)
        val eager = items.count { it.begins == 0 } <= MOST_CHAPTER_FILES
        val files = ArrayList<Part>()
        var current = ArrayList<Item>()
        var letters = 0
        fun close() {
            if (current.isEmpty()) return
            current.forEach { it.part = files.size }
            files += Part(files.size, current)
            current = ArrayList()
            letters = 0
        }
        for (item in items) {
            val fresh = item.begins >= 0 && ((item.begins <= 0 && eager) || letters >= NEW_FILE_AT_SECTION)
            val full = letters > 0 && letters + item.letters > MOST_IN_A_FILE
            if (fresh || full) close()
            current += item
            letters += item.letters
        }
        close()
        parts = files

        // Where everything the file names is, for the links to it.
        for ((id, item, note) in named) targets.putIfAbsent(id, Target(item.part, item.element, note))
        for (item in items) {
            fun visit(node: Fb2Node) {
                node.id?.let { id -> names[node]?.let { targets.putIfAbsent(id, Target(item.part, it, item.inNotes)) } }
                when (node) {
                    is Fb2Group -> node.children.forEach(::visit)
                    is Fb2Section -> node.children.forEach(::visit)
                    else -> {}
                }
            }
            item.node?.takeIf { item.kind != Kind.NOTE }?.let(::visit)
            if (item.kind == Kind.NOTE) (item.node as Fb2Section).children.forEach(::visit)
        }

        // The links that lead to notes: marked as such by the file, or leading into its notes.
        var linked = 0
        for (item in items) {
            item.node?.let { node ->
                eachSpan(node) { span ->
                    val link = span.link ?: return@eachSpan
                    if (link in refs || !link.href.startsWith("#")) return@eachSpan
                    val target = targets[link.href.substring(1)] ?: return@eachSpan
                    if (!link.note && !target.note) return@eachSpan
                    val ref = "r${linked++}"
                    refs[link] = ref
                    if (!item.inNotes) backs.putIfAbsent(target.element, Back(item.part, ref))
                }
            }
        }

        // The contents: every titled section, as deep as it is nested. No level is skipped on the way down.
        var deepest = -1
        var before = -1
        contents = items.mapNotNull { item ->
            val first = item.part != before
            before = item.part
            if (item.kind != Kind.HEADING || item.depth < 0) return@mapNotNull null
            val title = titleOf(item.node as Fb2Group).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val level = item.depth.coerceIn(0, deepest + 1)
            deepest = level
            Entry(title, level, if (first) hrefOf(item.part) else "${hrefOf(item.part)}#${item.element}")
        }
    }

    /** The book's annotation as plain words, for what the pages say of the book. */
    val description: String?
        get() = document.annotation?.let { annotation ->
            buildString { eachText(annotation) { if (isNotEmpty()) append(' '); append(it.text) } }.take(LONGEST_DESCRIPTION).takeIf { it.isNotBlank() }
        }

    // ---------------------------------------------------------------------------------------------
    // The pages
    // ---------------------------------------------------------------------------------------------

    private fun pageOf(items: List<Item>) = buildString {
        val language = about.language?.takeIf { it.isNotBlank() }?.let(::escaped)
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"")
        if (language != null) append(" lang=\"$language\" xml:lang=\"$language\"")
        if (rightToLeft) append(" dir=\"rtl\"")
        append(">\n<head><meta charset=\"utf-8\"/><title>").append(escaped(about.title)).append("</title>")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"../$STYLE\"/></head>\n<body>\n")
        for (item in items) {
            when (item.kind) {
                Kind.BOOK_TITLE -> append("<h1 class=\"book-title\" id=\"${item.element}\" dir=\"auto\">").append(escaped(about.title)).append("</h1>\n")
                Kind.BOOK_AUTHOR -> append("<p class=\"book-author\" id=\"${item.element}\" dir=\"auto\">").append(escaped(about.author)).append("</p>\n")
                Kind.HEADING -> heading(this, item)
                Kind.BLOCK -> block(this, item.node!!)
                Kind.NOTE -> note(this, item)
            }
        }
        append("</body>\n</html>\n")
    }

    /** A section's title: its lines, one under the other. A body's own (the book's name) is the page's first heading. */
    private fun heading(out: StringBuilder, item: Item) {
        val tag = "h${(item.depth + 2).coerceIn(1, 6)}"
        out.append("<$tag id=\"${item.element}\" dir=\"auto\">")
        var lines = 0
        eachText(item.node!!) { text ->
            if (lines++ > 0) out.append("<br/>")
            spans(out, text.spans)
        }
        out.append("</$tag>\n")
    }

    /**
     * A note, marked as one. Its number (the section's title) is the way back to where the book
     * calls it; shown over the page, the note comes without it.
     */
    private fun note(out: StringBuilder, item: Item) {
        val section = item.node as Fb2Section
        out.append("<div class=\"note\" epub:type=\"footnote\" role=\"doc-footnote\" id=\"${item.element}\">\n")
        val title = section.title?.let(::titleOf).orEmpty()
        val back = backs[item.element]
        if (title.isNotEmpty() || back != null) {
            out.append("<p class=\"note-title\" dir=\"auto\">")
            if (back != null) out.append("<a epub:type=\"backlink\" role=\"doc-backlink\" href=\"${fileOf(back.part)}#${back.ref}\">")
            out.append(escaped(title.ifEmpty { "↩" }))
            if (back != null) out.append("</a>")
            out.append("</p>\n")
        }
        for (child in section.children) if (child !== section.title) block(out, child)
        out.append("</div>\n")
    }

    private fun block(out: StringBuilder, node: Fb2Node) {
        val named = "id=\"${names[node]}\""
        when (node) {
            is Fb2Text -> {
                val kind = when (node.kind) {
                    Fb2Text.Kind.PARAGRAPH -> ""
                    Fb2Text.Kind.SUBTITLE -> " class=\"subtitle\""
                    Fb2Text.Kind.VERSE -> " class=\"v\""
                    Fb2Text.Kind.TEXT_AUTHOR -> " class=\"text-author\""
                    Fb2Text.Kind.DATE -> " class=\"date\""
                }
                // Each paragraph leans the way its own words run, whatever the rest of the book does.
                out.append("<p$kind $named dir=\"auto\">")
                spans(out, node.spans)
                out.append("</p>\n")
            }
            // (Opened and closed in full: read as HTML, as the voice reads a page, an element closed in its own tag swallows what follows.)
            is Fb2Gap -> out.append("<div class=\"gap\" $named></div>\n")
            is Fb2Image -> pictures[node.ref]?.let { out.append("<div class=\"image\" $named><img src=\"../$it\" alt=\"").append(escaped(node.alt)).append("\"/></div>\n") }
            is Fb2Group -> {
                val (tag, kind) = when (node.kind) {
                    Fb2Group.Kind.EPIGRAPH -> "blockquote" to "epigraph"
                    Fb2Group.Kind.CITE -> "blockquote" to "cite"
                    Fb2Group.Kind.POEM -> "div" to "poem"
                    Fb2Group.Kind.STANZA -> "div" to "stanza"
                    Fb2Group.Kind.ANNOTATION -> "div" to "annotation"
                    Fb2Group.Kind.TITLE -> "div" to "title"
                    Fb2Group.Kind.PLAIN -> "div" to "plain"
                }
                out.append("<$tag class=\"$kind\" $named>\n")
                node.children.forEach { block(out, it) }
                out.append("</$tag>\n")
            }
            is Fb2Table -> {
                out.append("<table $named>\n")
                for (row in node.rows) {
                    out.append("<tr>")
                    for (cell in row) {
                        val tag = if (cell.header) "th" else "td"
                        out.append("<$tag")
                        if (cell.colspan > 1) out.append(" colspan=\"${cell.colspan}\"")
                        if (cell.rowspan > 1) out.append(" rowspan=\"${cell.rowspan}\"")
                        if (cell.align != null) out.append(" style=\"text-align: ${cell.align}\"")
                        out.append(" dir=\"auto\">")
                        spans(out, cell.spans)
                        out.append("</$tag>")
                    }
                    out.append("</tr>\n")
                }
                out.append("</table>\n")
            }
            // (A section where only paragraphs belong: its pieces, one after the other.)
            is Fb2Section -> {
                out.append("<div class=\"plain\" $named>\n")
                node.children.forEach { block(out, it) }
                out.append("</div>\n")
            }
        }
    }

    /** [spans] as they are set: each the way it says, and those of one link inside the one link. */
    private fun spans(out: StringBuilder, spans: List<Fb2Span>) {
        var at = 0
        while (at < spans.size) {
            val link = spans[at].link
            var end = at
            while (end < spans.size && spans[end].link === link) end++
            val anchor = link?.let(::anchorOf)
            if (anchor != null) out.append(anchor)
            for (index in at until end) {
                val span = spans[index]
                if (span.image != null) {
                    pictures[span.image]?.let { out.append("<img class=\"inline\" src=\"../$it\" alt=\"\"/>") }
                    continue
                }
                val tags = STYLES.filter { span.style and it.first != 0 }
                tags.forEach { out.append('<').append(it.second).append('>') }
                out.append(escaped(span.text))
                tags.asReversed().forEach { out.append("</").append(it.second).append('>') }
            }
            if (anchor != null) out.append("</a>")
            at = end
        }
    }

    /**
     * The opening of [link] in the pages; null for one that leads nowhere this book can go (a
     * name the file doesn't have, an address that is neither a web page's nor a letter's): its
     * words are then set as plain words.
     */
    private fun anchorOf(link: Fb2Link): String? {
        val href = link.href
        if (href.startsWith("#")) {
            val target = targets[href.substring(1)] ?: return null
            val to = "${fileOf(target.part)}#${target.element}"
            val ref = refs[link] ?: return "<a href=\"$to\">"
            return "<a class=\"noteref\" epub:type=\"noteref\" role=\"doc-noteref\" id=\"$ref\" href=\"$to\">"
        }
        return if (OUTSIDE.containsMatchIn(href)) "<a href=\"${escaped(href)}\">" else null
    }

    // ---------------------------------------------------------------------------------------------
    // The pages, as an EPUB
    // ---------------------------------------------------------------------------------------------

    /** Writes everything but the pictures (which [Fb2Pages] writes as it reads them) into [zip]. */
    internal fun write(zip: ZipOutputStream, pictureTypes: Map<String, String>) {
        fun file(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name).apply { time = WRITTEN })
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        file("META-INF/container.xml", CONTAINER)
        file(PACKAGE, packageOf(pictureTypes))
        if (contents.isNotEmpty()) file(NAV, navOf())
        file(STYLE, CSS)
        for (part in parts) file(part.href, part.page())
    }

    private fun packageOf(pictureTypes: Map<String, String>) = buildString {
        val cover = document.cover?.let(pictures::get)
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"id\">urn:jacket:fb2:").append(escaped(about.id)).append("</dc:identifier>\n")
        append("<dc:title>").append(escaped(about.title)).append("</dc:title>\n")
        if (about.author.isNotBlank()) append("<dc:creator>").append(escaped(about.author)).append("</dc:creator>\n")
        // A book whose language couldn't be told says nothing: the reader then goes by the phone's.
        if (!about.language.isNullOrBlank()) append("<dc:language>").append(escaped(about.language)).append("</dc:language>\n")
        description?.let { append("<dc:description>").append(escaped(it)).append("</dc:description>\n") }
        append("<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n")
        if (cover != null) append("<meta name=\"cover\" content=\"${itemOf(cover)}\"/>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"style\" href=\"$STYLE\" media-type=\"text/css\"/>\n")
        if (contents.isNotEmpty()) append("<item id=\"nav\" href=\"$NAV\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        for ((path, type) in pictureTypes) {
            append("<item id=\"${itemOf(path)}\" href=\"$path\" media-type=\"$type\"")
            if (path == cover) append(" properties=\"cover-image\"")
            append("/>\n")
        }
        for (part in parts) append("<item id=\"part${part.index + 1}\" href=\"${part.href}\" media-type=\"application/xhtml+xml\"/>\n")
        append("</manifest>\n")
        append(if (rightToLeft) "<spine page-progression-direction=\"rtl\">\n" else "<spine>\n")
        for (part in parts) append("<itemref idref=\"part${part.index + 1}\"/>\n")
        append("</spine>\n</package>\n")
    }

    private fun navOf() = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
        append("<head><meta charset=\"utf-8\"/><title>").append(escaped(about.title)).append("</title></head>\n")
        append("<body>\n<nav epub:type=\"toc\">\n<ol>\n")
        contents.forEachIndexed { index, entry ->
            append("<li><a href=\"").append(entry.href).append("\">").append(escaped(entry.title)).append("</a>")
            val next = contents.getOrNull(index + 1)?.level ?: 0
            when {
                // The entries under this one are a list inside it.
                next > entry.level -> append("\n<ol>\n")
                else -> {
                    append("</li>\n")
                    repeat(entry.level - next) { append("</ol>\n</li>\n") }
                }
            }
        }
        append("</ol>\n</nav>\n</body>\n</html>\n")
    }

    companion object {
        /** Goes up whenever the same file would be given other pages: pages made by an older number are made again. */
        const val VERSION = 1

        /** A section further in than the book's top ones begins a new chapter file once the file before it holds this much; and no file holds more than the other. */
        const val NEW_FILE_AT_SECTION = 4_000
        const val MOST_IN_A_FILE = 60_000

        /** A book has no more chapters than this: past it, its top sections no longer start a file each. */
        const val MOST_CHAPTER_FILES = 2_000

        private const val LONGEST_TITLE = 200
        private const val LONGEST_DESCRIPTION = 4_000

        private const val PACKAGE = "content.opf"
        private const val NAV = "nav.xhtml"
        private const val STYLE = "style.css"

        /** The same for every book, so the same file always gives the same pages. */
        internal const val WRITTEN = 1_767_225_600_000L

        /** The only addresses outside the book a link is let lead to. */
        private val OUTSIDE = Regex("^(?:https?://|mailto:)", RegexOption.IGNORE_CASE)

        private val STYLES = listOf(
            Fb2Span.STRONG to "strong", Fb2Span.EMPHASIS to "em", Fb2Span.STRIKE to "del",
            Fb2Span.SUB to "sub", Fb2Span.SUP to "sup", Fb2Span.CODE to "code",
        )

        /** The book [document] is, with its pictures where [pictures] says they are in the pages. */
        fun of(document: Fb2Document, about: About, pictures: Map<String, String> = emptyMap()): Fb2Book = Fb2Book(document, pictures, about)

        /** The chapter file numbered [index] (from 0), as the book's own files name it; and as its other chapter files do. */
        fun hrefOf(index: Int): String = "text/" + fileOf(index)
        private fun fileOf(index: Int): String = "part%04d.xhtml".format(Locale.ROOT, index + 1)

        private fun itemOf(path: String): String = "img-" + path.substringAfterLast('/').substringBefore('.')

        private fun lettersOf(node: Fb2Node): Int {
            var letters = 0
            eachText(node) { letters += it.spans.sumOf { span -> span.text.length } }
            return letters
        }

        /** Every line of words in [node], in order. */
        private fun eachText(node: Fb2Node, visit: (Fb2Text) -> Unit) {
            when (node) {
                is Fb2Text -> visit(node)
                is Fb2Group -> node.children.forEach { eachText(it, visit) }
                is Fb2Section -> node.children.forEach { eachText(it, visit) }
                else -> {}
            }
        }

        /** Every stretch of words in [node], those in its tables too. */
        private fun eachSpan(node: Fb2Node, visit: (Fb2Span) -> Unit) {
            when (node) {
                is Fb2Text -> node.spans.forEach(visit)
                is Fb2Table -> node.rows.forEach { row -> row.forEach { it.spans.forEach(visit) } }
                is Fb2Group -> node.children.forEach { eachSpan(it, visit) }
                is Fb2Section -> node.children.forEach { eachSpan(it, visit) }
                else -> {}
            }
        }

        /** A title as one line: "Chapter one. The meeting" for a title of two lines. */
        private fun titleOf(title: Fb2Group): String {
            val out = StringBuilder()
            eachText(title) { text ->
                val words = text.text.trim()
                if (words.isEmpty()) return@eachText
                if (out.isNotEmpty()) out.append(if (out.last().isLetterOrDigit()) ". " else " ")
                out.append(words)
            }
            return out.toString().take(LONGEST_TITLE)
        }

        private fun escaped(text: String): String {
            val safe = TextBook.xmlSafe(text)
            if (safe.none { it == '&' || it == '<' || it == '>' || it == '"' }) return safe
            return buildString(safe.length + 16) {
                for (c in safe) when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }

        private const val CONTAINER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "<rootfiles><rootfile full-path=\"$PACKAGE\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n</container>\n"

        // Set the way these books are printed: paragraphs begun with an indent and no space
        // between them, headings in the middle, an epigraph to one side, a poem line by line.
        private const val CSS = "p { margin: 0; text-indent: 1.4em; overflow-wrap: break-word; }\n" +
            "h1, h2, h3, h4, h5, h6 { margin: 1.6em 0 0.8em; line-height: 1.25; text-align: center; text-indent: 0; overflow-wrap: break-word; }\n" +
            "h1 { font-size: 1.5em; }\nh2 { font-size: 1.35em; }\nh3 { font-size: 1.2em; }\nh4, h5, h6 { font-size: 1.05em; }\n" +
            "p.subtitle { margin: 1em 0 0.6em; text-align: center; text-indent: 0; font-weight: bold; }\n" +
            "div.gap { height: 1em; }\n" +
            "blockquote.epigraph { margin: 1em 0 1.2em 35%; font-style: italic; }\n" +
            "blockquote.cite { margin: 1em 1.5em; }\n" +
            "blockquote p, div.poem p, div.note p, div.annotation p, div.title p { text-indent: 0; }\n" +
            "p.text-author, p.date { margin-top: 0.3em; text-align: right; text-indent: 0; font-style: italic; }\n" +
            "div.poem { margin: 1em 0 1em 2em; }\n" +
            "div.stanza { margin: 0 0 0.9em; }\n" +
            "div.poem p.v { margin-left: 1.5em; text-indent: -1.5em; text-align: left; }\n" +
            "div.title p { margin: 0.6em 0 0.4em; font-weight: bold; }\n" +
            "div.image { margin: 1em 0; text-align: center; }\n" +
            "img { max-width: 100%; height: auto; }\nimg.inline { vertical-align: middle; }\n" +
            "table { margin: 1em auto; border-collapse: collapse; }\ntd, th { padding: 0.2em 0.5em; border: 1px solid; }\n" +
            "div.note { margin: 0 0 1em; }\np.note-title { font-weight: bold; }\n" +
            "a.noteref { font-size: 0.75em; line-height: 0; vertical-align: super; text-decoration: none; }\n" +
            "a.noteref sup { font-size: 1em; vertical-align: baseline; }\n" +
            "h1.book-title { margin-top: 2em; }\np.book-author { text-align: center; text-indent: 0; }\n" +
            "div.annotation { margin: 2em 0 0; font-style: italic; }\ndiv.annotation p { margin: 0 0 0.6em; }\n" +
            "code { font-family: monospace; }\n"
    }
}

/** Making and keeping the pages an FB2 book is read through. */
internal object Fb2Pages {
    private const val STAMP = "jacket-fb2-book"

    /** No more pictures than this are taken from one file, and none bigger than this (as the file writes it). */
    private const val MOST_PICTURES = 4_000
    private const val BIGGEST_PICTURE = 32 * 1024 * 1024

    /** What tells that pages kept in a file were made from [source] as it is now, by this version. */
    private fun stamp(source: File): String = "$STAMP ${Fb2Book.VERSION} ${source.length()}"

    /** Whether [pages] holds the pages of [source] as this version makes them. */
    fun isCurrent(pages: File, source: File): Boolean =
        pages.lastModified() >= source.lastModified() && runCatching { ZipFile(pages).use { it.comment == stamp(source) } }.getOrDefault(false)

    /**
     * Reads the FB2 book in [source] and writes its pages to [out] (and closes it). [about] says
     * what the library calls the book; the language is the file's own where it names one.
     * Returns the book as it was read. Throws [Fb2Unreadable] for a file that can't be read.
     */
    fun make(source: File, out: OutputStream, about: (Fb2Document) -> Fb2Book.About): Fb2Document =
        Fb2.open(source) { input -> write(input, out, stamp(source), about) }

    /** The same, from the XML itself; [comment] is kept with the pages. */
    internal fun write(input: java.io.InputStream, out: OutputStream, comment: String, about: (Fb2Document) -> Fb2Book.About): Fb2Document =
        ZipOutputStream(out).use { zip ->
            zip.setComment(comment)
            // First, and stored as it is: that is how an EPUB is known.
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                    time = Fb2Book.WRITTEN
                },
            )
            zip.write(mime)
            zip.closeEntry()
            // The pictures are written as they are read: none is held longer than it takes to write it.
            val paths = LinkedHashMap<String, String>()
            val types = LinkedHashMap<String, String>()
            val document = Fb2.read(input, wants = { id, _ -> id !in paths && paths.size < MOST_PICTURES }) { binary ->
                if (binary.base64.length > BIGGEST_PICTURE) return@read
                val bytes = runCatching { Base64.getMimeDecoder().decode(binary.base64) }.getOrNull() ?: return@read
                val type = pictureType(bytes) ?: return@read
                val path = "images/i${paths.size + 1}.${type.second}"
                zip.putNextEntry(ZipEntry(path).apply { time = Fb2Book.WRITTEN })
                zip.write(bytes)
                zip.closeEntry()
                paths[binary.id] = path
                types[path] = type.first
            }
            Fb2Book.of(document, about(document), paths).write(zip, types)
            document
        }

    /**
     * What kind of picture [bytes] are, as its media type and the ending of its file's name,
     * told from the bytes themselves; null for anything but the kinds a page shows as a plain
     * picture (so nothing that could carry a script).
     */
    internal fun pictureType(bytes: ByteArray): Pair<String, String>? {
        fun starts(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        return when {
            starts(0xFF, 0xD8, 0xFF) -> "image/jpeg" to "jpg"
            starts(0x89, 'P'.code, 'N'.code, 'G'.code) -> "image/png" to "png"
            starts('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif" to "gif"
            starts('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp" to "webp"
            else -> null
        }
    }
}
