package io.github.friendlyglobal.jacket.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream

/*
 * Footnotes in a pop-up: telling a link to a note from any other link, and reading the note out of
 * its chapter file.
 *
 * A book that marks its notes says so on the link (epub:type "noteref", role "doc-noteref") or on
 * the note (epub:type "footnote", "endnote" or "rearnote"; role "doc-footnote" or "doc-endnote").
 * Many books mark neither: a small number in the text links to a paragraph at the back, which
 * links back to the number. Those are told by exactly that: a link that looks like a note's mark,
 * to a short block of text that links back to it. Anything less sure is an ordinary link, and is
 * followed as ever.
 */

/** A link on the page, as the reader tapped it: what the book says of it, and what it shows. */
internal data class TappedLink(
    /** Its `epub:type`, and its parent's (some books mark the superscript around the link). */
    val types: String = "",
    val role: String = "",
    /** The words of the link: "12", "[3]", "*". */
    val text: String = "",
    /** The ids a note may link back to: the link's own, and those of what is around and inside it. */
    val ids: List<String> = emptyList(),
) {
    /** The book says this link leads to a note. */
    val marked: Boolean get() = "noteref" in tokens(types) || "doc-noteref" in tokens(role)
}

/** A note to show over the page: its mark ("12", or empty when it has none) and its text. */
internal data class Note(val mark: String, val paragraphs: List<NoteParagraph>)

/** One paragraph of a note; [italic] and [bold] are the stretches of [text] set that way. */
internal data class NoteParagraph(val text: String, val italic: List<IntRange> = emptyList(), val bold: List<IntRange> = emptyList())

/** A chapter file, read for the notes in it (see [readNotes]). */
internal class NoteFile(val document: Document) {
    /** The file marks at least one of its notes: a link into it may be to a note though the link says nothing. */
    val marksNotes: Boolean by lazy { document.allElements.any { it.isNote() } }
}

/**
 * Reads a chapter file. Books are XHTML, which is read as XML: read as HTML, an empty `<a id="n1"/>`
 * or `<div/>` swallows everything after it, and a note found inside one would be the whole chapter.
 * A file that isn't well-formed enough for that to find anything in is read as HTML.
 */
internal fun readNotes(bytes: ByteArray): NoteFile {
    val asXml = runCatching { Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser()) }.getOrNull()
    if (asXml != null && asXml.selectFirst("body, html") != null) return NoteFile(asXml)
    return NoteFile(Jsoup.parse(ByteArrayInputStream(bytes), null, ""))
}

/** For tests, and for a note Readium has already cut out of its file. */
internal fun readNotes(markup: String): NoteFile = readNotes(markup.toByteArray(Charsets.UTF_8))

/**
 * The note that a link to [fragment] (an element's id) in [file] leads to; null if it isn't a note,
 * or can't be told to be one: the link is then followed like any other.
 *
 * [link] is the link that was tapped, when the page could say which it was; [marked] when the link
 * is already known to be marked as leading to a note.
 */
internal fun findNote(file: NoteFile, fragment: String, link: TappedLink?, marked: Boolean = false): Note? {
    val document = file.document
    val target = document.getElementById(fragment)
        ?: document.getElementsByAttributeValue("name", fragment).firstOrNull()
        ?: return null
    // The note the book marks as one: the target itself, or what it sits in.
    val markedNote = generateSequence(target) { it.parent() }.takeWhile { it.normalName() !in setOf("body", "html") }.firstOrNull { it.isNote() }
    val saidByLink = marked || link?.marked == true
    val block = markedNote ?: noteBlock(target) ?: return null
    val backIds = link?.ids.orEmpty().toSet()
    if (markedNote == null && !saidByLink) {
        // Nothing says it's a note. It is taken for one only when it could hardly be anything else.
        if (link == null || !looksLikeNoteMark(link.text)) return null
        if (block.normalName() in HEADINGS || block.select(HEADINGS.joinToString(",")).isNotEmpty()) return null
        if (block.select("a[href]").none { it.linksBackTo(backIds) }) return null
        if (block.text().length > LONGEST_UNMARKED) return null
    }
    val copy = block.clone()
    // The way back to the text is no part of the note; nor is the note's own number in front of it.
    var mark = ""
    var ledWithMark = false
    for (back in copy.select("a").filter { it.isBackLink(backIds) }) {
        val words = back.text().trim()
        val leads = copy.textBefore(back).isBlank()
        if (looksLikeNoteMark(words) && words.none { it in RETURN_ARROWS }) {
            if (mark.isEmpty()) mark = words
            if (leads) ledWithMark = true
        }
        var gone: Element = back
        // With it go the wrappers that held nothing else.
        while (gone !== copy && gone.parent() != null && gone.parent() !== copy && gone.parent()!!.text().trim() == gone.text().trim()) gone = gone.parent()!!
        if (gone !== copy) gone.remove()
    }
    val paragraphs = paragraphsOf(copy, dropLeadingPunctuation = ledWithMark)
    if (paragraphs.isEmpty()) return null
    if (mark.isEmpty()) mark = link?.text?.trim()?.takeIf(::looksLikeNoteMark).orEmpty()
    return Note(bareMark(mark), paragraphs)
}

/** The note as a single piece of markup holds it (what Readium hands over for a link it knows to be a note's). */
internal fun noteOf(markup: String, link: TappedLink?): Note? {
    val body = Jsoup.parseBodyFragment(markup).body()
    val paragraphs = paragraphsOf(body, dropLeadingPunctuation = false)
    if (paragraphs.isEmpty()) return null
    return Note(bareMark(link?.text?.trim()?.takeIf(::looksLikeNoteMark).orEmpty()), paragraphs)
}

/**
 * Whether a link's words look like the mark of a note: a number, a star or a dagger, a letter, a
 * small roman numeral, with or without brackets ("12", "[3]", "*", "†", "a", "iv", "[* 1]").
 */
internal fun looksLikeNoteMark(text: String): Boolean {
    val mark = bareMark(text)
    if (mark.isEmpty() || mark.length > LONGEST_MARK) return false
    val signs = mark.filterNot { it.isWhitespace() }
    if (signs.isEmpty()) return false
    if (signs.all { it.isDigit() || it in MARK_SIGNS }) return true
    // A lone letter, or a roman numeral; with a star or a dagger before it, perhaps.
    val letters = signs.trimStart { it in MARK_SIGNS }
    return letters.all { it.isLetter() } && (letters.length == 1 || letters.all { it in ROMAN })
}

/** A note's mark without the brackets around it. */
private fun bareMark(text: String): String =
    text.trim().trim { it in "[](){}" || it.isWhitespace() }

private fun tokens(value: String): Set<String> = value.lowercase().split(WHITESPACE).filterTo(HashSet()) { it.isNotEmpty() }

private fun Element.isNote(): Boolean =
    tokens(attr("epub:type")).any { it in NOTE_TYPES } || tokens(attr("role")).any { it in NOTE_ROLES }

/** The fragment of a link's address, without what a URL does to its letters. */
private fun Element.linkFragment(): String {
    val raw = attr("href").substringAfter('#', "")
    return runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
}

private fun Element.linksBackTo(ids: Set<String>): Boolean = ids.isNotEmpty() && hasAttr("href") && linkFragment() in ids

/** A note's link back to where it was called from: marked as one, an arrow by itself, or pointing at the link that was tapped. */
private fun Element.isBackLink(ids: Set<String>): Boolean {
    if (normalName() != "a") return false
    if ("backlink" in tokens(attr("epub:type")) || "doc-backlink" in tokens(attr("role")) || "mw:referencedby" in tokens(attr("rel"))) return true
    if (linksBackTo(ids)) return true
    val words = text().trim()
    return hasAttr("href") && words.isNotEmpty() && words.all { it in RETURN_ARROWS || it == '︎' || it == '️' }
}

/** The text of [this] that comes before [element], one of its descendants. */
private fun Element.textBefore(element: Element): String {
    val out = StringBuilder()
    var reached = false
    fun walk(node: Node) {
        if (reached) return
        if (node === element) {
            reached = true
            return
        }
        if (node is TextNode) out.append(node.text())
        node.childNodes().forEach(::walk)
    }
    walk(this)
    return out.toString()
}

/**
 * The block of text a note is, for a target the book doesn't mark as one. A target with words of
 * its own is the note, or sits in it (a number inside its paragraph). An empty one (`<a id="n1"/>`)
 * is a place-holder: the note is the paragraph it sits in, or the one that follows it.
 */
private fun noteBlock(target: Element): Element? {
    if (target.normalName() == "dt") return target.nextElementSibling()?.takeIf { it.normalName() == "dd" } ?: target
    if (!target.hasText()) {
        val parent = target.parent() ?: return null
        if (parent.normalName() !in setOf("body", "html") && parent.hasText() && parent.children().none { it.normalName() in BLOCKS }) return parent
        return generateSequence(target.nextElementSibling()) { it.nextElementSibling() }.firstOrNull { it.hasText() }
    }
    var block = target
    while (block.normalName() !in BLOCKS) block = block.parent()?.takeIf { it.normalName() !in setOf("body", "html") } ?: return block
    return block
}

/** [root]'s text as paragraphs, with the stretches set in italics and in bold. */
private fun paragraphsOf(root: Element, dropLeadingPunctuation: Boolean): List<NoteParagraph> {
    val paragraphs = ArrayList<NoteParagraph>()
    val text = StringBuilder()
    val italic = ArrayList<IntRange>()
    val bold = ArrayList<IntRange>()
    var first = dropLeadingPunctuation

    fun flush() {
        // Trimmed, with the styled stretches moved to match.
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start >= 0) {
            val end = text.indexOfLast { !it.isWhitespace() } + 1
            fun List<IntRange>.within() = mapNotNull { range ->
                val from = (range.first - start).coerceAtLeast(0)
                val to = (range.last - start).coerceAtMost(end - start - 1)
                if (to >= from) from..to else null
            }
            paragraphs += NoteParagraph(text.substring(start, end), italic.within(), bold.within())
        }
        text.setLength(0)
        italic.clear()
        bold.clear()
    }

    fun mark(ranges: ArrayList<IntRange>, from: Int, to: Int) {
        if (to <= from) return
        val last = ranges.lastOrNull()
        if (last != null && last.last + 1 >= from) ranges[ranges.lastIndex] = last.first until to else ranges += from until to
    }

    fun walk(node: Node, slanted: Boolean, heavy: Boolean) {
        when (node) {
            is TextNode -> {
                var words = node.text().replace(WHITESPACE, " ")
                if (first && words.isNotBlank()) {
                    // What followed the note's number: "1. The note" is shown as "The note".
                    words = words.trimStart { it.isWhitespace() || it in AFTER_A_MARK }
                    first = false
                }
                // Runs of spaces are one space, across the pieces of a paragraph too.
                if (words.startsWith(' ') && (text.isEmpty() || text.last().isWhitespace())) words = words.drop(1)
                if (words.isEmpty()) return
                val from = text.length
                text.append(words)
                if (slanted) mark(italic, from, text.length)
                if (heavy) mark(bold, from, text.length)
            }
            is Element -> {
                val name = node.normalName()
                if (name in UNSEEN) return
                if (name == "br") {
                    text.append('\n')
                    return
                }
                val block = name in BLOCKS
                if (block) flush()
                node.childNodes().forEach { walk(it, slanted || name in ITALICS, heavy || name in BOLDS) }
                if (block) flush()
            }
        }
    }
    walk(root, slanted = false, heavy = false)
    flush()
    return paragraphs
}

private val WHITESPACE = Regex("\\s+")

/** What a book calls a note, on the note itself. (The lists they come in, "footnotes" and "endnotes", aren't notes.) */
private val NOTE_TYPES = setOf("footnote", "endnote", "rearnote")
private val NOTE_ROLES = setOf("doc-footnote", "doc-endnote")

private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

/** Elements that are a paragraph of their own, or hold several. */
private val BLOCKS = HEADINGS + setOf(
    "p", "div", "li", "ul", "ol", "dl", "dd", "dt", "aside", "section", "article", "blockquote", "table", "tr", "td", "th",
    "figure", "figcaption", "pre", "header", "footer", "body",
)
private val ITALICS = setOf("i", "em", "cite", "dfn", "var")
private val BOLDS = setOf("b", "strong")
private val UNSEEN = setOf("script", "style", "img", "svg", "audio", "video", "head", "title")

/** The signs books mark notes with beside numbers and letters, superscript digits among them. */
private const val MARK_SIGNS = "*†‡§¶‖#⁂·.,¹²³⁰⁴⁵⁶⁷⁸⁹"
private const val ROMAN = "ivxlcIVXLC"
private const val LONGEST_MARK = 6

/** The arrows a note's way back is drawn with. */
private const val RETURN_ARROWS = "↩↑^⤴↵⏎⮐⬆←"

/** What stands between a note's number and its words. */
private const val AFTER_A_MARK = ".:)]–—-"

/** A block longer than this, that nothing marks as a note, is a passage of the book. */
private const val LONGEST_UNMARKED = 6_000
