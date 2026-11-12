package io.github.friendlyglobal.jacket.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.use
import java.io.ByteArrayInputStream

/*
 * The small numbers that point to notes, for the voice to pass over when it reads aloud.
 *
 * Read aloud, a paragraph reaches the voice as its words alone: "…as he said.12 Then…", with
 * nothing to tell the 12 of a note from any other number. What tells them apart is in the chapter
 * file, where the number is a link to its note. So each chapter file the voice comes to is read
 * for those links (the same ones a tap shows as a note over the page: see Notes.kt), and each is
 * kept as the number with the words of its paragraph that come just before it. A paragraph the
 * voice is about to read is then looked through for them.
 */

/**
 * A note's number where the book calls the note: the number as written ([mark]) and the words of
 * its paragraph just before it ([before]), which are all of the paragraph up to it when [opens].
 */
internal data class NoteMark(val before: String, val mark: String, val opens: Boolean)

/**
 * The numbers of notes in a chapter file. [document] is the file read the way Readium's read-aloud
 * reads it (as HTML), so that the words kept with each number are the words the voice is given.
 * [leadsToNote] says whether a link that looks like a note's number, and that the book doesn't mark
 * as one, leads to a note.
 */
internal fun noteMarksIn(document: Document, leadsToNote: (href: String, link: TappedLink) -> Boolean): List<NoteMark> {
    val finder = MarkFinder(leadsToNote)
    NodeTraversor.traverse(finder, document.body())
    return finder.marks
}

/** Where the notes' numbers [marks] are in [paragraph], a paragraph (or a part of one) as the voice is given it. */
internal fun noteMarksIn(paragraph: String, marks: Collection<NoteMark>): List<IntRange> {
    if (marks.isEmpty() || paragraph.isEmpty()) return emptyList()
    val found = ArrayList<IntRange>()
    val lead = paragraph.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    for (mark in marks) {
        val words = mark.before + mark.mark
        if (mark.opens) {
            if (paragraph.startsWith(words, lead)) found += numberAt(paragraph, lead + mark.before.length, mark.mark.length)
        } else {
            var at = paragraph.indexOf(words)
            while (at >= 0) {
                found += numberAt(paragraph, at + mark.before.length, mark.mark.length)
                at = paragraph.indexOf(words, at + 1)
            }
        }
    }
    if (found.size < 2) return found
    val sorted = found.sortedBy { it.first }
    return sorted.filterIndexed { i, range -> i == 0 || range.first > sorted[i - 1].last }
}

/** The [length] characters at [start], with the brackets around them if the link left them out: "[" 12 "]". */
private fun numberAt(text: String, start: Int, length: Int): IntRange {
    val end = start + length
    val bracketed = start > 0 && end < text.length &&
        ((text[start - 1] == '[' && text[end] == ']') || (text[start - 1] == '(' && text[end] == ')'))
    return if (bracketed) start - 1..end else start until end
}

/**
 * Goes through a chapter file as Readium's own reading of it does (its `HtmlResourceContentIterator`),
 * gathering each stretch of text the same way: started again at every block, line break and
 * picture, and wherever the language changes; runs of spaces made one. A link that is a note's
 * number is kept with the words gathered before it.
 */
private class MarkFinder(private val leadsToNote: (String, TappedLink) -> Boolean) : NodeVisitor {
    val marks = ArrayList<NoteMark>()
    private val text = StringBuilder()
    private var language: String? = null
    private var link: Element? = null
    private var linkStart = 0

    override fun head(node: Node, depth: Int) {
        if (node !is Element) return
        val tag = node.normalName()
        if (node.isBlock || tag in BREAKS) startAgain()
        if (tag == "a" && link == null && '#' in node.attr("href")) {
            link = node
            linkStart = text.length
        }
    }

    override fun tail(node: Node, depth: Int) {
        if (node is TextNode && node.wholeText.isNotBlank()) {
            val spoken = languageOf(node)
            if (spoken != language) {
                startAgain()
                language = spoken
            }
            append(Parser.unescapeEntities(node.wholeText, false))
        } else if (node is Element) {
            if (node === link) keep(node)
            if (node.isBlock) startAgain()
        }
    }

    private fun startAgain() {
        text.setLength(0)
        // A link cut in two by it is no number.
        link = null
    }

    private fun keep(a: Element) {
        link = null
        val raw = text.substring(linkStart.coerceAtMost(text.length))
        val mark = raw.trim()
        if (mark.isEmpty() || !looksLikeNoteMark(mark)) return
        val start = linkStart + raw.indexOf(mark)
        val window = (start - WORDS_BEFORE).coerceAtLeast(0)
        val opens = window == 0
        val before = text.substring(window, start).let { if (opens) it.trimStart() else it }
        // A number that opens its paragraph is the note's own, in front of the note: that is read.
        if (before.isBlank()) return
        val tapped = tappedLink(a, mark)
        if (tapped.marked || leadsToNote(a.attr("href"), tapped)) marks += NoteMark(before, mark, opens)
    }

    /** As Readium's reading does it: HTML's white space made one space, the unseen characters dropped. */
    private fun append(words: String) {
        var lastWasWhite = text.lastOrNull() == ' '
        var i = 0
        while (i < words.length) {
            val c = words.codePointAt(i)
            if (c == ' '.code || c == '\t'.code || c == '\n'.code || c == 0x0C || c == '\r'.code) {
                if (!lastWasWhite) text.append(' ')
                lastWasWhite = true
            } else if (c != 0x200B && c != 0xAD) {
                text.appendCodePoint(c)
                lastWasWhite = false
            }
            i += Character.charCount(c)
        }
    }

    private fun languageOf(node: Node): String? {
        var at: Node? = node
        while (at != null) {
            at.attr("xml:lang").takeUnless { it.isBlank() }?.let { return it }
            at.attr("lang").takeUnless { it.isBlank() }?.let { return it }
            at = at.parent()
        }
        return null
    }

    private companion object {
        val BREAKS = setOf("br", "img", "audio", "video")

        /** How many of the words before a number are kept with it: enough to know its place in the paragraph by. */
        const val WORDS_BEFORE = 32
    }
}

/**
 * [a] as the page would tell of it when tapped (see NoteLinks): what the book says of it and of
 * what it sits in, its words, and the ids around it that a note may link back to.
 */
private fun tappedLink(a: Element, words: String): TappedLink {
    val parent = a.parent()
    val ids = ArrayList<String>()
    var up: Element? = a
    var steps = 0
    while (up != null && steps < 3 && up.normalName() != "body") {
        up.id().takeIf { it.isNotEmpty() }?.let(ids::add)
        up = up.parent()
        steps++
    }
    a.attr("name").takeIf { it.isNotEmpty() }?.let(ids::add)
    a.select("[id]").filter { it !== a }.take(3).forEach { ids += it.id() }
    a.previousElementSibling()?.takeIf { it.id().isNotEmpty() && it.text().isBlank() }?.let { ids += it.id() }
    return TappedLink(
        types = a.attr("epub:type") + " " + parent?.attr("epub:type").orEmpty(),
        role = a.attr("role") + " " + parent?.attr("role").orEmpty(),
        text = words,
        ids = ids,
    )
}

/**
 * The notes' numbers of the chapter files the voice is in and near. A file is read for them before
 * the voice reaches it ([prepare]); a paragraph is then looked through without waiting ([marksIn]).
 */
internal class SpokenNotes(private val publication: Publication) {
    @Volatile
    private var kept: Map<String, List<NoteMark>> = emptyMap()
    private val reading = Mutex()

    /** The files notes were last looked for in: a chapter's notes are mostly in one. */
    private val noteFiles = LinkedHashMap<String, NoteFile>()

    /** Where the notes' numbers are in [paragraph], if its chapter file has been read for them. */
    fun marksIn(paragraph: String): List<IntRange> {
        val files = kept
        if (files.isEmpty()) return emptyList()
        val marks = if (files.size == 1) files.values.first() else files.values.flatten()
        return noteMarksIn(paragraph, marks)
    }

    /** Reads the chapter file [file] for its notes' numbers, unless that has been done. */
    suspend fun prepare(file: Url) {
        val name = file.removeFragment().toString()
        if (name in kept) return
        withContext(Dispatchers.Default) {
            reading.withLock {
                if (name in kept) return@withLock
                val marks = runCatching { read(file.removeFragment()) }.getOrDefault(emptyList())
                val next = LinkedHashMap(kept)
                next[name] = marks
                while (next.size > KEPT) next.remove(next.keys.first())
                kept = next
            }
        }
    }

    private suspend fun read(file: Url): List<NoteMark> {
        val bytes = publication.get(file)?.use { it.read().getOrNull() } ?: return emptyList()
        // No link to a place within a file: no notes called from here.
        if ('#'.code.toByte() !in bytes) return emptyList()
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        // Which links lead to notes is asked of the files they lead into, read once each.
        val asked = ArrayList<Pair<String, TappedLink>>()
        noteMarksIn(document) { href, link -> asked.add(href to link); false }
        val notes = HashSet<Pair<String, TappedLink>>()
        for ((href, link) in asked.take(MOST_LINKS)) if (leadsToNote(file, bytes, href, link)) notes += href to link
        return noteMarksIn(document) { href, link -> (href to link) in notes }
    }

    private suspend fun leadsToNote(file: Url, bytes: ByteArray, href: String, link: TappedLink): Boolean {
        val target: Url
        val fragment: String
        if (href.startsWith("#")) {
            target = file
            fragment = runCatching { java.net.URLDecoder.decode(href.drop(1).replace("+", "%2B"), "UTF-8") }.getOrDefault(href.drop(1))
        } else {
            val url = Url(href)?.let(file::resolve) ?: return false
            target = url.removeFragment()
            fragment = url.fragment.orEmpty()
        }
        if (fragment.isEmpty()) return false
        val name = target.toString()
        val notes = noteFiles[name] ?: run {
            val read = if (target.isEquivalent(file)) bytes else publication.get(target)?.use { it.read().getOrNull() } ?: return false
            readNotes(read).also {
                noteFiles[name] = it
                while (noteFiles.size > NOTE_FILES) noteFiles.remove(noteFiles.keys.first())
            }
        }
        return findNote(notes, fragment, link) != null
    }

    private companion object {
        /** The chapter files whose notes are kept: the one being read, and those next to it. */
        const val KEPT = 5
        const val NOTE_FILES = 2

        /** No chapter is asked about more links than this: one with more is a list of them, not a text with notes. */
        const val MOST_LINKS = 3_000
    }
}
