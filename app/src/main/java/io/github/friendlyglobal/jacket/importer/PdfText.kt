package io.github.friendlyglobal.jacket.importer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * A PDF's words, in the order a person reads them.
 *
 * A PDF says where every letter is drawn and nothing of what it is: a running title, a page
 * number, a caption and the sentence they interrupt are all just lines. The phone's own PDF
 * reader hands a page over as its lines of text, in the order the file draws them, and says where
 * any stretch of them lies on the page. From that, and nothing more, the lines are put in
 * reading order here and made into paragraphs a voice can read:
 *
 *  - a line that says the same thing at the same place at the top or the foot of other pages (a
 *    running title), or counts upward there (a page number), is left out;
 *  - side by side columns are read one after the other, left to right;
 *  - a word broken at the end of a line is one word, and a paragraph that runs on into the next
 *    column or the next page is one paragraph, so a sentence is never cut at a page's edge;
 *  - a figure's caption that sits in the middle of a sentence is read after the paragraph.
 *
 * It is the page's geometry and a few marks of punctuation, not an understanding of the page: a
 * table read row by row is still a list of numbers. Everything here is apart from Android, so it
 * can be tested.
 */

/**
 * One line of a page, as the PDF reader gives it: its words and the box they are drawn in, in the
 * page's own units from its top left corner. [start] is where the line begins in the page's text
 * as the PDF reader counts it (it is asked where a few letters are drawn by that count); -1 where
 * the count couldn't be made out, and only the line's box is known ([PdfLine.UNPLACED] where not
 * even that is).
 */
internal class PdfLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val start: Int = -1,
) {
    val middle: Float get() = (top + bottom) / 2f

    companion object {
        /** For [start]: not even the line's box is known (it is a guess, under the line before it), so it is read and never marked. */
        const val UNPLACED = -2
    }
}

/** The text of one page: its size, and its lines in the order the file draws them. A scanned page has none. */
internal class PdfPageText(val width: Float, val height: Float, val lines: List<PdfLine>)

/**
 * Where a stretch of a paragraph is on the pages: its letters from [at] on, [length] of them, are
 * the letters of line [line] of page [page] from [from] on, one for one.
 */
internal class PdfRun(val at: Int, val length: Int, val page: Int, val line: Int, val from: Int)

/** A paragraph as it is read aloud, and where its letters are on the pages. */
internal class PdfParagraph(val text: String, val runs: List<PdfRun>, val caption: Boolean = false) {
    /** The page it starts on. */
    val page: Int get() = runs.first().page
}

internal object PdfReading {
    /** The paragraphs of a PDF whose pages hold [pages], in reading order. Empty when no page has any text. */
    fun paragraphs(pages: List<PdfPageText>): List<PdfParagraph> {
        val running = runningLines(pages)
        val stretches = pages.mapIndexed { index, page ->
            readingOrder(page, page.lines.indices.filter { it !in running[index] && page.lines[it].text.any { c -> !blank(c) } })
        }
        // The usual step from one line to the next, as most of the book's pages have it: what a
        // stretch of two or three lines (a title page, a caption) is measured by.
        val usual = pages.indices.mapNotNull { index -> stretches[index].flatMap { steps(pages[index], it) }.sorted().takeIf { it.size >= FEWEST_STEPS }?.let { it[it.size / 4] } }
            .sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val blocks = ArrayList<Block>()
        pages.forEachIndexed { index, page -> for (stretch in stretches[index]) blocks += blocksOf(index, page, stretch, usual) }
        return joined(blocks)
    }

    /** The steps down the page from each line of [stretch] to the next. */
    private fun steps(page: PdfPageText, stretch: List<Int>): List<Float> =
        (1 until stretch.size).map { page.lines[stretch[it]].middle - page.lines[stretch[it - 1]].middle }.filter { it > 0f }

    // ---------------------------------------------------------------------------------------------
    // Running titles and page numbers
    // ---------------------------------------------------------------------------------------------

    /**
     * For each page, the lines that are not part of what it says: running titles and page numbers.
     *
     * Going in from the top of the page, and from its foot: a line that another page close by has
     * too, at the same height and with the same words (numbers aside, so "Page 3 of 9" is "Page 4
     * of 9"), is one. So is a lone number there, whether or not it is repeated. The first line
     * that is neither ends it, and a page has no more than a few of them at either end: whatever
     * else is repeated from page to page is the book's own.
     */
    fun runningLines(pages: List<PdfPageText>): List<Set<Int>> {
        class Edge(val line: Int, val shape: String, val at: Float, val foot: Boolean)

        val edges = pages.map { page ->
            page.lines.indices.mapNotNull { i ->
                val line = page.lines[i]
                if (page.height <= 0f || line.text.length > LONGEST_RUNNING_LINE) return@mapNotNull null
                val at = line.middle / page.height
                when {
                    at <= EDGE -> Edge(i, shape(line.text), at, foot = false)
                    at >= 1f - EDGE -> Edge(i, shape(line.text), at, foot = true)
                    else -> null
                }
            }.filter { it.shape.isNotEmpty() }
        }
        return pages.indices.map { p ->
            val page = pages[p]
            val out = HashSet<Int>()
            if (page.height <= 0f) return@map out
            fun repeated(line: Int): Boolean {
                val edge = edges[p].firstOrNull { it.line == line } ?: return false
                return (p - NEARBY_PAGES..p + NEARBY_PAGES).any { q ->
                    q != p && q in pages.indices && edges[q].any { it.foot == edge.foot && it.shape == edge.shape && abs(it.at - edge.at) <= SAME_HEIGHT }
                }
            }
            val worded = page.lines.indices.filter { i -> page.lines[i].text.any { !blank(it) } }.sortedBy { page.lines[it].middle }
            fun take(inward: List<Int>, foot: Boolean) {
                var taken = 0
                for (line in inward) {
                    val at = page.lines[line].middle / page.height
                    val near = if (foot) 1f - at else at
                    val number = near <= NUMBER_EDGE && worded.size > 1 && PAGE_NUMBER.matches(page.lines[line].text.trim())
                    if (taken >= MOST_RUNNING_LINES || near > EDGE || !(number || repeated(line))) break
                    out += line
                    taken++
                }
            }
            take(worded, foot = false)
            take(worded.asReversed(), foot = true)
            out
        }
    }

    /** What a line says with its numbers taken for one another: how a running title is known from page to page. */
    private fun shape(text: String): String {
        val squeezed = text.trim().lowercase().replace(SPACES, " ")
        if (ROMAN.matches(squeezed)) return "#"
        return squeezed.replace(DIGITS, "#")
    }

    // ---------------------------------------------------------------------------------------------
    // Columns
    // ---------------------------------------------------------------------------------------------

    /**
     * The lines [kept] of [page] in the order they are read, as stretches: each a run of lines
     * that follow one another down the page or down one column. A paragraph may run on from one
     * stretch into the next.
     *
     * The file's own order is kept wherever it can be: down a column it is nearly always right.
     * Only where the page clearly has columns side by side (a clear strip of white with enough
     * lines on both sides of it, and next to nothing across it) are they taken one after the
     * other, left to right, with any line that spans them (a title, a wide caption) in its place
     * between what is above it and what is below.
     */
    fun readingOrder(page: PdfPageText, kept: List<Int>, depth: Int = 0): List<List<Int>> {
        if (kept.isEmpty()) return emptyList()
        val gutter = if (depth < DEEPEST_COLUMNS) gutterOf(page, kept) else null
        if (gutter == null) return listOf(kept)
        val lines = page.lines
        val left = kept.filter { lines[it].right <= gutter.first + GUTTER_SLACK }
        val right = kept.filter { lines[it].left >= gutter.second - GUTTER_SLACK }
        val across = kept.filter { it !in left && it !in right }.sortedBy { lines[it].middle }
        // What lies between two lines that span the columns is read before the lower of them.
        fun band(line: Int): Int = across.count { lines[it].middle < lines[line].middle }
        val out = ArrayList<List<Int>>()
        var spanning = ArrayList<Int>()
        for (b in 0..across.size) {
            val leftHere = left.filter { band(it) == b }
            val rightHere = right.filter { band(it) == b }
            if (leftHere.isNotEmpty() || rightHere.isNotEmpty()) {
                if (spanning.isNotEmpty()) out += spanning
                spanning = ArrayList()
                out += readingOrder(page, leftHere, depth + 1)
                out += readingOrder(page, rightHere, depth + 1)
            }
            across.getOrNull(b)?.let { spanning += it }
        }
        if (spanning.isNotEmpty()) out += spanning
        return out
    }

    /** The strip of white between two columns of [kept], as its left and right edges; null where there are no columns. */
    private fun gutterOf(page: PdfPageText, kept: List<Int>): Pair<Float, Float>? {
        if (kept.size < 2 * FEWEST_COLUMN_LINES) return null
        val lines = page.lines
        val from = kept.minOf { lines[it].left }
        val to = kept.maxOf { lines[it].right }
        val width = to - from
        if (width <= 0f) return null
        // How many lines lie over each thin slice of the block's width.
        val over = IntArray(SLICES)
        for (i in kept) {
            val first = (((lines[i].left + GUTTER_SLACK - from) / width) * SLICES).toInt().coerceIn(0, SLICES - 1)
            val last = (((lines[i].right - GUTTER_SLACK - from) / width) * SLICES).toInt().coerceIn(0, SLICES - 1)
            for (s in first..last) over[s]++
        }
        // The emptiest strip of the middle half: a gutter is not at the page's edge.
        val middle = SLICES / 4 until SLICES * 3 / 4
        val fewest = middle.minOf { over[it] }
        var best = IntRange.EMPTY
        var run = IntRange.EMPTY
        for (s in middle) {
            run = if (over[s] == fewest) (if (run.isEmpty()) s..s else run.first..s) else IntRange.EMPTY
            if (run.count() > best.count()) best = run
        }
        if (best.isEmpty()) return null
        val edges = from + best.first * width / SLICES to from + (best.last + 1) * width / SLICES
        if (edges.second - edges.first < max(NARROWEST_GUTTER, page.width * 0.008f)) return null
        val left = kept.filter { lines[it].right <= edges.first + GUTTER_SLACK }
        val right = kept.filter { lines[it].left >= edges.second - GUTTER_SLACK }
        val across = kept.size - left.size - right.size
        if (left.size < FEWEST_COLUMN_LINES || right.size < FEWEST_COLUMN_LINES || across > kept.size * MOST_ACROSS) return null
        // Side by side, not one above the other: a short heading over a narrow list is no column.
        val top = max(left.minOf { lines[it].top }, right.minOf { lines[it].top })
        val bottom = min(left.maxOf { lines[it].bottom }, right.maxOf { lines[it].bottom })
        val shorter = min(left.maxOf { lines[it].bottom } - left.minOf { lines[it].top }, right.maxOf { lines[it].bottom } - right.minOf { lines[it].top })
        if (shorter <= 0f || (bottom - top) < shorter * 0.5f) return null
        return edges
    }

    // ---------------------------------------------------------------------------------------------
    // Paragraphs
    // ---------------------------------------------------------------------------------------------

    /** A run of lines that belong together, before it is known whether it carries on from the one before. */
    private class Block(
        /** It opens its stretch: the top of a column, or of a page. */
        val first: Boolean,
        /** Its last line runs to the margin. */
        var full: Boolean,
        /** Its first line is set in from the margin. */
        val indented: Boolean,
    ) {
        val words = StringBuilder()
        val runs = ArrayList<PdfRun>()
        var caption = false
    }

    /**
     * The lines of one [stretch] of [page] as blocks: a new one begins after more white than there
     * is between lines, at a first line set in from the margin, after a short last line, and at a
     * bullet. Whether a block is a paragraph of its own, or runs on from the stretch before, is
     * settled by [joined].
     */
    private fun blocksOf(index: Int, page: PdfPageText, stretch: List<Int>, usual: Float): List<Block> {
        if (stretch.isEmpty()) return emptyList()
        val lines = stretch.map { page.lines[it] }
        val steps = steps(page, stretch).sorted()
        // The usual step from one line to the next: the smaller ones, since the larger are the
        // white between paragraphs. A stretch of a few lines has too few to tell, and goes by
        // the book's (unless its own are closer still: small print).
        val own = if (steps.isEmpty()) 0f else steps[steps.size / 4]
        val pitch = if (steps.size >= FEWEST_STEPS || usual <= 0f) own else if (own > 0f) min(own, usual) else usual
        val height = lines.map { it.bottom - it.top }.sorted()[lines.size / 2].coerceAtLeast(1f)
        val leftEdge = lines.minOf { it.left }
        val rightEdge = lines.maxOf { it.right }
        val width = (rightEdge - leftEdge).coerceAtLeast(1f)
        val indent = max(height * 0.6f, width * 0.012f)
        fun full(line: PdfLine) = line.right >= rightEdge - width * SHORT_BY
        fun short(line: PdfLine) = line.right < leftEdge + width * VERY_SHORT
        // Set in the middle: well clear of both margins, and as far from one as from the other.
        fun centred(line: PdfLine): Boolean {
            val before = line.left - leftEdge
            val after = rightEdge - line.right
            return before >= width * CENTRED_CLEAR && after >= width * CENTRED_CLEAR && abs(before - after) <= max(width * CENTRED_EVEN, 4f)
        }

        val out = ArrayList<Block>()
        var start = 0
        var letters = 0
        fun close(end: Int) {
            if (end <= start) return
            val block = Block(
                first = start == 0,
                full = full(lines[end - 1]),
                // Set in from the margin its own second line stands at, or (a lone line) from the stretch's.
                indented = lines[start].left - (if (start + 1 < end) lines[start + 1].left else leftEdge) >= indent,
            )
            for (i in start until end) {
                val text = lines[i].text
                join(block, text.firstOrNull { !blank(it) } ?: continue)
                append(block, index, stretch[i], text)
            }
            block.caption = isCaption(block.words)
            if (block.words.isNotEmpty()) out += block
            start = end
            letters = 0
        }
        for (i in 1 until lines.size) {
            val before = lines[i - 1]
            val line = lines[i]
            letters += before.text.length
            val step = line.middle - before.middle
            val ended = endsSentence(before.text)
            val opening = line.text.firstOrNull { !blank(it) }
            val starts = when {
                // Back up the page, or more white than between two lines.
                step <= 0f -> true
                pitch > 0f && step > pitch * PARAGRAPH_GAP + 0.5f -> true
                // A first line set in, after a line that could be a paragraph's last.
                line.left - before.left >= indent && (ended || !full(before)) -> true
                // A short last line.
                ended && !full(before) -> true
                // A line set in the middle, over one that isn't: a title, a byline.
                centred(before) && !centred(line) -> true
                // A line that stops well short and is followed by a capital: a heading, a line of verse, an address.
                short(before) && opening != null && (opening.isUpperCase() || opening.isDigit()) && hyphenAt(before.text.trimEnd()) == null -> true
                opening != null && opening in BULLETS && (ended || !full(before)) -> true
                // A page with no paragraphs to be seen: cut at a sentence's end, now and then.
                letters >= LONG_BLOCK && ended -> true
                else -> false
            }
            if (starts) close(i)
        }
        close(lines.size)
        return out
    }

    /**
     * The blocks as paragraphs. One that begins a column or a page carries on the paragraph before
     * it where that one was left open: its last line ran to the margin (or broke a word) and ended
     * no sentence. And a caption that follows an open paragraph waits until the paragraph is done:
     * the sentence it sits in the middle of is finished first. So a picture in the middle of a
     * paragraph, with or without a caption, doesn't cut the paragraph in two.
     */
    private fun joined(blocks: List<Block>): List<PdfParagraph> {
        val out = ArrayList<PdfParagraph>()
        var current: Block? = null
        val waiting = ArrayList<Block>()
        fun done() {
            current?.let { out += PdfParagraph(it.words.toString(), it.runs.toList(), it.caption) }
            waiting.forEach { out += PdfParagraph(it.words.toString(), it.runs.toList(), caption = true) }
            waiting.clear()
            current = null
        }
        for (block in blocks) {
            val before = current
            if (before != null && block.caption && !before.caption && open(before) && waiting.size < MOST_WAITING) {
                waiting += block
                continue
            }
            // (Or, further down the same column, takes up in small letters what was left open
            // above it: a picture stood between them, whose caption the file may draw elsewhere.)
            val interrupted = before != null && open(before) && block.words.first().isLowerCase()
            if (before != null && !block.caption && !before.caption && (block.first || waiting.isNotEmpty() || interrupted) && carriesOn(before, block)) {
                join(before, block.words.first())
                val shift = before.words.length
                before.words.append(block.words)
                block.runs.forEach { before.runs += PdfRun(it.at + shift, it.length, it.page, it.line, it.from) }
                before.full = block.full
                continue
            }
            done()
            current = block
        }
        done()
        return out
    }

    /** [block] has been left open: its words end no sentence, and its last line ran to the margin or broke a word. */
    private fun open(block: Block): Boolean = !endsSentence(block.words) && (block.full || hyphenAt(block.words) != null)

    private fun carriesOn(before: Block, block: Block): Boolean {
        if (before.words.length + block.words.length > LONGEST_PARAGRAPH) return false
        if (hyphenAt(before.words) != null && block.words.first().isLetter()) return true
        if (endsSentence(before.words) || block.indented) return false
        return before.full || block.words.first().isLowerCase()
    }

    /**
     * Makes [block] ready for a line that opens with [first]: a space after what is there, or
     * nothing where a word was broken at the end of the line before. A word broken at a line's end
     * is one word; a hyphen before a capital or a number is the word's own ("Jean-" "Paul", "1914-" "18").
     */
    private fun join(block: Block, first: Char) {
        val words = block.words
        if (words.isEmpty()) return
        when (hyphenAt(words)) {
            Hyphen.SOFT -> dropLast(block)
            Hyphen.HARD -> when {
                first.isLowerCase() && words[words.length - 2].isLetter() -> dropLast(block)
                first.isLetterOrDigit() -> {}
                else -> words.append(' ')
            }
            // Writing that puts no spaces between its words has none between its lines either.
            null -> if (!(unspaced(words.last()) && unspaced(first))) words.append(' ')
        }
    }

    /**
     * Adds the words of [line] of [page], which reads [text], to [block]. Spaces are kept single
     * and what a page can't show is left out; [PdfRun]s say where each stretch that went in whole
     * came from, letter for letter.
     */
    private fun append(block: Block, page: Int, line: Int, text: String) {
        val words = block.words
        var from = 0
        var to = text.length
        while (from < to && blank(text[from])) from++
        while (to > from && blank(text[to - 1])) to--
        var at = -1
        var begun = 0
        fun close() {
            if (at >= 0 && words.length > at) block.runs += PdfRun(at, words.length - at, page, line, begun)
            at = -1
        }
        fun take(i: Int, c: Char) {
            if (at < 0) {
                at = words.length
                begun = i
            }
            words.append(c)
        }
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                // A character outside the basic plane is two halves: kept together, or not at all.
                c.isHighSurrogate() && i + 1 < to && text[i + 1].isLowSurrogate() -> {
                    take(i, c)
                    words.append(text[i + 1])
                    i += 2
                }
                unseen(c) -> {
                    close()
                    i++
                }
                blank(c) -> {
                    var next = i + 1
                    while (next < to && blank(text[next])) next++
                    // One space for however many there are.
                    if (words.isNotEmpty() && words.last() != ' ') take(i, ' ')
                    if (next > i + 1) close()
                    i = next
                }
                else -> {
                    take(i, c)
                    i++
                }
            }
        }
        close()
    }

    private enum class Hyphen { SOFT, HARD }

    /** The hyphen [words] end with, if it may be one that breaks a word: straight after a letter or a number. */
    private fun hyphenAt(words: CharSequence): Hyphen? {
        if (words.length < 2) return null
        if (!words[words.length - 2].isLetterOrDigit()) return null
        return when (words[words.length - 1]) {
            '\u00AD' -> Hyphen.SOFT
            '-', '\u2010', '\u2011' -> Hyphen.HARD
            else -> null
        }
    }

    private fun dropLast(block: Block) {
        block.words.setLength(block.words.length - 1)
        val last = block.runs.lastOrNull() ?: return
        if (last.at + last.length > block.words.length) {
            block.runs.removeAt(block.runs.size - 1)
            val length = block.words.length - last.at
            if (length > 0) block.runs += PdfRun(last.at, length, last.page, last.line, last.from)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Words
    // ---------------------------------------------------------------------------------------------

    /** Whether [text] ends a sentence: its last mark, closing quotes and brackets aside, is a full stop or the like. */
    fun endsSentence(text: CharSequence): Boolean {
        var end = text.length
        while (end > 0 && (blank(text[end - 1]) || text[end - 1] in CLOSERS)) end--
        return end > 0 && text[end - 1] in SENTENCE_ENDS
    }

    /** Whether [text] opens as a figure's or a table's caption does: "Figure 3.", "Table 2:", "Fig. 4 Growth", "图 5". */
    fun isCaption(text: CharSequence): Boolean = text.length <= LONGEST_CAPTION && CAPTION.containsMatchIn(text)

    /** A space, a line break, or one of the marks the PDF reader puts where a line ends. */
    private fun blank(c: Char): Boolean = c.isWhitespace() || c == ' ' || c == ' ' || c == ' ' || c == '﻿'

    /** A character with nothing to show or say: a control character, half a character, a mark of the PDF reader's own. */
    private fun unseen(c: Char): Boolean = (c < ' ' && !c.isWhitespace()) || c == '￾' || c == '￿' || c == '​' || c.isSurrogate()

    /** Chinese, Japanese and Thai are written without spaces between words. */
    private fun unspaced(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI -> true
        else -> c in '　'..'〿' || c in '＀'..'｠'
    }

    /** How near the top or the foot a line is looked at as a running title (of the page's height), and as a lone page number. */
    private const val EDGE = 0.15f
    private const val NUMBER_EDGE = 0.1f

    /** A running title is the same line on a page this near, at a height no further off than this (of the page's height). */
    private const val NEARBY_PAGES = 6
    private const val SAME_HEIGHT = 0.02f
    private const val LONGEST_RUNNING_LINE = 120
    private const val MOST_RUNNING_LINES = 3

    /** Columns: how finely the page's width is looked at, and what makes a strip of white a gutter. */
    private const val SLICES = 240
    private const val GUTTER_SLACK = 1.5f
    private const val NARROWEST_GUTTER = 4f
    private const val FEWEST_COLUMN_LINES = 4
    private const val MOST_ACROSS = 0.25f
    private const val DEEPEST_COLUMNS = 3

    /** More than this many times the usual step between lines is the white between paragraphs; a stretch with fewer steps than this goes by the book's. */
    private const val PARAGRAPH_GAP = 1.4f
    private const val FEWEST_STEPS = 5

    /** A line that stops this much of the width short of the margin is a short one; one that ends before this much of it, very short. */
    private const val SHORT_BY = 0.08f
    private const val VERY_SHORT = 0.6f

    /** A line is set in the middle if it keeps this much of the width clear on both sides, the two alike to within this much. */
    private const val CENTRED_CLEAR = 0.1f
    private const val CENTRED_EVEN = 0.03f

    /** A block with no paragraph breaks to be seen is cut at a sentence's end once it is this long; and no paragraph is joined past this. */
    private const val LONG_BLOCK = 2_500
    private const val LONGEST_PARAGRAPH = 6_000

    private const val LONGEST_CAPTION = 1_200
    private const val MOST_WAITING = 4

    private const val CLOSERS = "\"'”’»›)]}】」』）"
    private const val SENTENCE_ENDS = ".!?…:。！？؟۔։।"
    private const val BULLETS = "•◦▪▫■□●○‣⁃∙◆◇➢➤✓✔"

    private val SPACES = Regex("\\s+")
    private val DIGITS = Regex("\\d+")
    private val ROMAN = Regex("[ivxlcdm]{1,7}")

    /** A page's number as a line of its own: "12", "- 12 -", "Page 12", "12 / 300", "xiv". */
    private val PAGE_NUMBER = Regex(
        "[-–—·•|\\[(\\s]*(?:(?iu:page|pág\\.?|página|pagina|seite|strona|стр\\.?|sayfa|halaman|p\\.)\\s*)?(?:\\d{1,4}|[ivxlcdm]{1,7}|[IVXLCDM]{1,7})" +
            "(?:\\s*(?iu:/|of|de|di|von|van|sur|z|из|із)\\s*\\d{1,4})?[-–—·•|\\])\\s]*",
    )

    /**
     * How a caption opens: what it is, its number, and then a mark, a capital, or nothing more.
     * ("Figure 2 shows that..." is a sentence about a figure, not its caption.)
     */
    private val CAPTION = Regex(
        "^(?:(?iu:fig(?:ure|ura|uur|\\.)?|table|tabla|tabela|tabelle|tableau|tabel|tablo|tab\\.|chart|plate|illustration|scheme|diagram|exhibit|listing|" +
            "photo|image|imagem|imagen|gráfico|grafico|graphique|cuadro|quadro|abbildung|abb\\.|grafik|afbeelding|rysunek|rys\\.|wykres|" +
            "рисунок|рис\\.|таблица|табл\\.|схема|малюнок|мал\\.|таблиця|şekil|resim|gambar|चित्र|तालिका|شكل|جدول)\\s*\\.?\\s*" +
            "(?:\\d{1,4}|[IVXLC]{1,6}|[A-Z])(?:[.\\-–]\\d{1,3})*(?:\\s*[.:：|)\\-–—]|\\s+(?=[\\p{Lu}\\p{Lo}(])|\\s*$)" +
            "|[图圖図表]\\s*[0-9０-９一二三四五六七八九十]{1,4}|(?:그림|표)\\s*\\d{1,4})",
    )
}
