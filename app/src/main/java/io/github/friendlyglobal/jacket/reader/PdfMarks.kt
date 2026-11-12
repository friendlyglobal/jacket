package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.importer.PdfReading
import io.github.friendlyglobal.jacket.importer.PdfSpot
import io.github.friendlyglobal.jacket.importer.PdfTextLine
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.hypot

/*
 * Highlights and notes on a PDF: what of them is apart from Android, so it can be tested.
 *
 * A PDF's page is drawn as a picture, and its words are known beside it: the paragraphs they are
 * read aloud as, and where each line of them lies on the page (see PdfText.kt and PdfLayout.kt).
 * So words are chosen on the picture by where a finger is (the line under it, and the word of
 * that line), and what is chosen is a stretch of those paragraphs, in the order they are read.
 *
 * Where a highlight is must mean the same tomorrow, in a later Jacket and on another device.
 * A paragraph's number means none of that: the paragraphs are made by Jacket from the page's
 * lines, and are numbered differently whenever it gets better at telling the order they are read
 * in. A highlight is kept as what a person would go by: the page it is on, its words, and a
 * little of what stands before and after them ([PdfAnchor]). It is found again by looking for
 * those words in the page's own (see [PdfPageWords]), spaces and the hyphens of broken words
 * aside, so that the same page read a little differently still has it.
 */

/**
 * Where a highlight is in a PDF: the [page] its words begin on (from 0), and a little of what
 * stands [before] and [after] them, which tells them from the same words elsewhere on the page.
 * The words themselves are the highlight's own text. It is what a PDF highlight keeps as its
 * place (in [io.github.friendlyglobal.jacket.data.Annotation.locator]), and the same on every device.
 */
internal data class PdfAnchor(val page: Int, val before: String = "", val after: String = "") {
    fun write(): String = JSONObject().put(KIND, 1).put("page", page).put("before", before).put("after", after).toString()

    companion object {
        /** How much of what stands either side of a highlight is kept with it. */
        const val AROUND = 40

        private const val KIND = "pdf"

        /** The place kept in [locator], if it is a PDF highlight's; null for anything else (a Readium locator, nothing at all). */
        fun read(locator: String?): PdfAnchor? {
            if (locator.isNullOrEmpty() || !locator.contains(KIND)) return null
            return runCatching {
                val json = JSONObject(locator)
                if (!json.has(KIND) || !json.has("page")) return null
                PdfAnchor(json.getInt("page").coerceAtLeast(0), json.optString("before"), json.optString("after"))
            }.getOrNull()
        }

        /**
         * The place of [ranges], stretches of a PDF's paragraphs in the order they are read,
         * that begin on [page]: with what stands just before the first and just after the last,
         * running back into the paragraph before and on into the one after. [text] gives a
         * paragraph's words (null where there is no such paragraph).
         */
        fun of(page: Int, ranges: List<PdfSentence>, text: (Int) -> String?): PdfAnchor {
            val first = ranges.firstOrNull() ?: return PdfAnchor(page)
            val last = ranges.last()
            val before = StringBuilder(text(first.paragraph).orEmpty().let { it.substring(0, first.from.coerceIn(0, it.length)) }.takeLast(AROUND))
            if (before.length < AROUND) text(first.paragraph - 1)?.let { before.insert(0, it.takeLast(AROUND - before.length) + " ") }
            val after = StringBuilder(text(last.paragraph).orEmpty().let { it.substring(last.to.coerceIn(0, it.length)) }.take(AROUND))
            if (after.length < AROUND) text(last.paragraph + 1)?.let { after.append(' ').append(it.take(AROUND - after.length)) }
            return PdfAnchor(page, before.toString().trim(), after.toString().trim())
        }
    }
}

/** The page (from 0) a highlight made in a PDF is on; null for a bookmark, and for a highlight in any other book. */
internal val Annotation.pdfPage: Int? get() = if (kind == AnnotationKind.HIGHLIGHT) PdfAnchor.read(locator)?.page else null

/** The words of [ranges] as a highlight keeps them: each paragraph's on a line of its own. */
internal inline fun pdfWordsOf(ranges: List<PdfSentence>, text: (Int) -> String?): String =
    ranges.mapNotNull { range ->
        text(range.paragraph)?.let { it.substring(range.from.coerceIn(0, it.length), range.to.coerceIn(0, it.length)).trim() }?.takeIf { it.isNotEmpty() }
    }.joinToString("\n")

/**
 * The words of a page (or of a few pages together) as a highlight is looked for in them:
 * [paragraphs], each with its number, in the order they are read.
 *
 * They are compared by their letters alone. Spaces, the hyphens of words broken at a line's end
 * and marks that show nothing are left out, and letters written as one sign are taken apart ("ﬁ"
 * is "fi"): two readings of the same page differ in these, and in little else. Each letter is
 * remembered with the paragraph and the place in it that it came from, so what is found comes
 * back as stretches of the paragraphs as they are now.
 */
internal class PdfPageWords(private val paragraphs: List<Pair<Int, String>>) {
    private val letters: String
    private val paragraphOf: IntArray
    private val offsetOf: IntArray

    init {
        val all = StringBuilder()
        var from = IntArray(1024)
        var of = IntArray(1024)
        paragraphs.forEachIndexed { index, (_, text) ->
            lettersOf(text) { letter, at ->
                if (all.length == from.size) {
                    from = from.copyOf(from.size * 2)
                    of = of.copyOf(of.size * 2)
                }
                of[all.length] = index
                from[all.length] = at
                all.append(letter)
            }
        }
        letters = all.toString()
        paragraphOf = of
        offsetOf = from
    }

    /**
     * Where [words] are, a highlight's kept with [anchor]: as stretches of the paragraphs, one
     * for each paragraph they are in. Where the same words stand more than once, the place
     * with the most of what stood before and after them. A highlight of several paragraphs that
     * is no longer found whole (they are read in another order now) is looked for a paragraph
     * at a time. Empty when none of it is found.
     */
    fun find(words: String, anchor: PdfAnchor): List<PdfSentence> {
        val before = bare(anchor.before)
        val after = bare(anchor.after)
        locate(bare(words), before, after)?.let { return stretches(it) }
        val parts = words.split('\n').map(::bare).filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        return parts.mapIndexedNotNull { index, part -> locate(part, if (index == 0) before else "", if (index == parts.lastIndex) after else "") }
            .flatMap(::stretches)
            .sortedWith(compareBy({ it.paragraph }, { it.from }))
    }

    /** Where [wanted] is among the letters: of every place it stands, the one that best agrees with what stood [before] and [after] it. */
    private fun locate(wanted: String, before: String, after: String): IntRange? {
        if (wanted.isEmpty()) return null
        var best: IntRange? = null
        var agreed = -1
        var seen = 0
        var at = letters.indexOf(wanted)
        while (at >= 0 && seen < MOST_PLACES) {
            val end = at + wanted.length
            var same = 0
            while (same < before.length && same < at && letters[at - 1 - same] == before[before.length - 1 - same]) same++
            var following = 0
            while (following < after.length && end + following < letters.length && letters[end + following] == after[following]) following++
            if (same + following > agreed) {
                agreed = same + following
                best = at until end
            }
            seen++
            at = letters.indexOf(wanted, at + 1)
        }
        return best
    }

    /** The letters [found] as stretches of the paragraphs they came from. */
    private fun stretches(found: IntRange): List<PdfSentence> {
        val out = ArrayList<PdfSentence>()
        var i = found.first
        while (i <= found.last) {
            var last = i
            while (last < found.last && paragraphOf[last + 1] == paragraphOf[i]) last++
            val (number, text) = paragraphs[paragraphOf[i]]
            val end = offsetOf[last]
            val whole = if (text[end].isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate()) 2 else 1
            out += PdfSentence(number, offsetOf[i], end + whole)
            i = last + 1
        }
        return out
    }

    private companion object {
        /** No more places than this are weighed for the same words: a page hasn't that many. */
        const val MOST_PLACES = 500

        fun bare(text: String): String = buildString(text.length) { lettersOf(text) { letter, _ -> append(letter) } }

        /** The letters of [text], each with where in it it came from. */
        inline fun lettersOf(text: CharSequence, take: (letter: Char, at: Int) -> Unit) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    // A character outside the basic plane is two halves, kept together.
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                        take(c, i)
                        take(text[i + 1], i)
                        i++
                    }
                    aside(c) -> {}
                    c.code < 0x80 -> take(c, i)
                    else -> for (part in Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)) if (!aside(part)) take(part, i)
                }
                i++
            }
        }

        /** Left out of the comparing: spaces of every kind, marks that show nothing (a soft hyphen among them), and the hyphens that break words. */
        fun aside(c: Char): Boolean =
            c.isWhitespace() || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT.toInt() || c == '-' || c == '\u2010' || c == '\u2011' || c.isSurrogate()
    }
}

/** A word of a line: the letters [from] until [to] of the line's paragraph, drawn between [left] and [right] (in the page's units). */
internal class PdfWordBox(val from: Int, val to: Int, val left: Float, val right: Float)

/**
 * What is selected on a PDF's pages: its words from the letter at [start] to just before the
 * one at [end], in the order they are read (so, down one column and then the next, and on over a
 * page's edge).
 */
internal data class PdfSelection(val start: PdfSpot, val end: PdfSpot) {
    /** The selection as stretches of the paragraphs it is in, where [lengthOf] says how many letters a paragraph has. */
    fun ranges(lengthOf: (Int) -> Int): List<PdfSentence> = (start.paragraph..end.paragraph).mapNotNull { paragraph ->
        val from = if (paragraph == start.paragraph) start.offset else 0
        val to = if (paragraph == end.paragraph) end.offset else lengthOf(paragraph)
        if (to > from) PdfSentence(paragraph, from, to) else null
    }

    /**
     * The selection after one of its handles was taken to [word]: the one at its end ([atEnd]), or
     * the one at its start. A handle taken past the other one carries on from there: the other
     * end stays where it was, and the handle held becomes the selection's other end. Returns
     * the selection, and whether the handle held is now the one at its end.
     */
    fun handleAt(atEnd: Boolean, word: PdfSentence): Pair<PdfSelection, Boolean> {
        val from = PdfSpot(word.paragraph, word.from)
        val to = PdfSpot(word.paragraph, word.to)
        return if (atEnd) {
            if (ORDER.compare(to, start) > 0) PdfSelection(start, to) to true else PdfSelection(from, start) to false
        } else {
            if (ORDER.compare(from, end) < 0) PdfSelection(from, end) to false else PdfSelection(end, to) to true
        }
    }

    companion object {
        private val ORDER = compareBy<PdfSpot>({ it.paragraph }, { it.offset })

        /** The selection that holds the words [a] and [b] and all that is read between them. */
        fun spanning(a: PdfSentence, b: PdfSentence): PdfSelection = PdfSelection(
            minOf(PdfSpot(a.paragraph, a.from), PdfSpot(b.paragraph, b.from), ORDER),
            maxOf(PdfSpot(a.paragraph, a.to), PdfSpot(b.paragraph, b.to), ORDER),
        )
    }
}

/** Choosing words on a PDF's page by where a finger is. */
internal object PdfSelecting {
    /** A finger this near a line (in the page's units) is on it. */
    const val SLACK = 3f

    /**
     * The words among the letters [from] until [to] of [text]: each run of letters with no space
     * in it, its commas and full stops with it. In writing that puts no spaces between its words
     * (Chinese, Japanese) each character is taken by itself.
     */
    fun words(text: String, from: Int = 0, to: Int = text.length): List<IntRange> {
        val out = ArrayList<IntRange>()
        val end = to.coerceAtMost(text.length)
        var i = from.coerceAtLeast(0)
        while (i < end) {
            val point = text.codePointAt(i)
            val size = Character.charCount(point)
            if (blank(point)) {
                i += size
                continue
            }
            var last = i + size
            if (!unspaced(point)) {
                while (last < end) {
                    val next = text.codePointAt(last)
                    if (blank(next) || unspaced(next)) break
                    last += Character.charCount(next)
                }
            }
            out += i until last.coerceAtMost(end)
            i = last
        }
        return out
    }

    /**
     * [word] of [text] without the marks around it: what a press on a word selects is the word,
     * not its comma or its bracket. (All of it where it is nothing but marks.)
     */
    fun bareWord(text: String, word: IntRange): IntRange {
        var first = word.first
        var last = word.last
        while (first <= last && !text[first].isLetterOrDigit() && !text[first].isSurrogate()) first++
        while (last >= first && !text[last].isLetterOrDigit() && !text[last].isSurrogate()) last--
        return if (first > last) word else first..last
    }

    /** The line of [lines] that ([x], [y]) is on, in the page's units; null where there is none (a margin, a picture, the white between paragraphs). */
    fun lineAt(lines: List<PdfTextLine>, x: Float, y: Float): PdfTextLine? =
        lines.filter { x >= it.left - SLACK && x <= it.right + SLACK && y >= it.top - SLACK && y <= it.bottom + SLACK }.minByOrNull { distance(it, x, y) }

    /** The line of [lines] nearest to ([x], [y]): where a handle being dragged is taken to be, wherever on the page the finger is. */
    fun lineNearest(lines: List<PdfTextLine>, x: Float, y: Float): PdfTextLine? = lines.minByOrNull { distance(it, x, y) }

    private fun distance(line: PdfTextLine, x: Float, y: Float): Float {
        val dx = if (x < line.left) line.left - x else if (x > line.right) x - line.right else 0f
        val dy = if (y < line.top) line.top - y else if (y > line.bottom) y - line.bottom else 0f
        // (Of two lines the finger is on at once, the one whose middle is nearer.)
        return if (dx == 0f && dy == 0f) -1f / (1f + kotlin.math.abs(y - (line.top + line.bottom) / 2f)) else hypot(dx, dy)
    }

    /** The word of [words] at [x] along its line, or the nearest to it. */
    fun wordNearest(words: List<PdfWordBox>, x: Float): PdfWordBox? = words.minByOrNull { word ->
        val left = minOf(word.left, word.right)
        val right = maxOf(word.left, word.right)
        if (x < left) left - x else if (x > right) x - right else 0f
    }

    /**
     * Where the words [words] of [line] are when the PDF reader can't say: set out along the line
     * by how many letters come before each. Near enough to choose by.
     */
    fun spread(line: PdfTextLine, words: List<IntRange>): List<PdfWordBox> {
        val letters = (line.end - line.start).coerceAtLeast(1)
        val width = line.right - line.left
        return words.map { word ->
            PdfWordBox(
                word.first, word.last + 1,
                line.left + width * (word.first - line.start).coerceIn(0, letters) / letters,
                line.left + width * (word.last + 1 - line.start).coerceIn(0, letters) / letters,
            )
        }
    }

    /**
     * Whether a paragraph that reads [text] on [lines] lines is a heading rather than a paragraph:
     * a line by itself that ends no sentence. "The first paragraph on this page" is the first
     * that isn't one.
     */
    fun isHeading(text: String, lines: Int): Boolean = lines <= 1 && text.length <= LONGEST_HEADING && !PdfReading.endsSentence(text)

    private const val LONGEST_HEADING = 90

    private fun blank(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.getType(point) == Character.FORMAT.toInt()

    private fun unspaced(point: Int): Boolean = when (Character.UnicodeScript.of(point)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> point in 0x3000..0x303F || point in 0xFF00..0xFF60
    }
}
