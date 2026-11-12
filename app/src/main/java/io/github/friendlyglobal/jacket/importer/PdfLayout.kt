package io.github.friendlyglobal.jacket.importer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/*
 * Where a PDF's paragraphs (see PdfText.kt) are on its pages.
 *
 * The voice reads paragraphs; the reader shows pages. This is the way from one to the other and
 * back, kept while the book is open: the page a sentence is on and where on it its words are
 * drawn (to turn to it and mark it), the paragraph that begins where a page is in view (to start
 * reading there), and how far through the book a place is.
 *
 * A place in a PDF has always been kept as a page and how far down it. It still is: the sentence
 * the voice is on is saved as the page it is drawn on and how far down that page its line is.
 */

/** A place in a PDF: the page (from 0), and how far down it (0 at its top, 1 at its foot). */
internal data class PdfPlace(val page: Int, val within: Float)

/** A place in the words that are read aloud: a paragraph (from 0), and a letter of it. */
internal data class PdfSpot(val paragraph: Int, val offset: Int)

/**
 * Part of one line that a stretch of a paragraph takes up: the paragraph's letters from [start]
 * to [end], on [page]. [from] and [to] are those letters by the PDF reader's own count of the
 * page's text, which is how it is asked where exactly they are drawn; -1 where that count isn't
 * known, and the line's own box ([left] to [bottom], in the page's units) is all there is.
 */
internal class PdfMark(
    val page: Int,
    val start: Int,
    val end: Int,
    val from: Int,
    val to: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A line of a PDF's contents: its [title], how deep it is (0 at the top), the [page] it opens on, and the first paragraph read aloud from there (-1 if none). */
internal class PdfChapter(val title: String, val level: Int, val page: Int, val paragraph: Int)

/** A line of a PDF's own outline: the [page] it points at (from 0), and how far down it ([within], 0..1) where the file says. */
internal class PdfOutlineEntry(val title: String, val level: Int, val page: Int, val within: Float? = null)

internal class PdfLayout private constructor(
    private val widths: FloatArray,
    private val heights: FloatArray,
    /** The first run of each paragraph, and one past the last of all. */
    private val first: IntArray,
    /** The file of the pages made for the voice that each paragraph is in. */
    private val files: IntArray,
    private val runAt: IntArray,
    private val runLength: IntArray,
    private val runPage: IntArray,
    private val runCount: IntArray,
    private val boxes: FloatArray,
    val chapters: List<PdfChapter>,
) {
    val pageCount: Int get() = widths.size
    val paragraphs: Int get() = first.size - 1

    /** How many files the pages made for the voice are in. */
    val fileCount: Int get() = if (files.isEmpty()) 0 else files.last() + 1

    fun widthOf(page: Int): Float = widths.getOrElse(page) { 0f }
    fun heightOf(page: Int): Float = heights.getOrElse(page) { 0f }

    /** The file [paragraph] is in, and the paragraph's name there. */
    fun fileOf(paragraph: Int): Int = files.getOrElse(paragraph) { 0 }
    fun hrefOf(paragraph: Int): String = hrefOfFile(fileOf(paragraph))
    fun idOf(paragraph: Int): String = "p$paragraph"

    /** The paragraph an element of those pages names ("p12"); null if it isn't one. */
    fun paragraphOf(id: String?): Int? = ID.matchEntire(id.orEmpty().removePrefix("#"))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0 until paragraphs }

    /** Which of those files [href] names (from 0); -1 if it isn't one of them. */
    fun fileNamed(href: String): Int = FILE.find(href.substringBefore('#'))?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { it in 0 until fileCount } ?: -1

    /** The first paragraph of [file]; -1 if it has none. */
    fun firstIn(file: Int): Int = files.indexOf(file)

    /**
     * The chapter (of [chapters]) that [paragraph] is in: the last one that opens at or before it;
     * -1 before the first, or in a PDF without an outline.
     */
    fun chapterOf(paragraph: Int): Int = chapters.indexOfLast { it.paragraph in 0..paragraph }

    /** The chapter the top of [page] is in; -1 before the first. */
    fun chapterAt(page: Int): Int = chapters.indexOfLast { it.page <= page }

    /** The run holding the letter [offset] of [paragraph]: between two runs (a space that joins two lines), the one before. */
    private fun runOf(paragraph: Int, offset: Int): Int {
        if (paragraph !in 0 until paragraphs) return -1
        var found = first[paragraph]
        for (run in first[paragraph] until first[paragraph + 1]) if (runAt[run] <= offset) found = run else break
        return found.takeIf { it < first[paragraph + 1] } ?: -1
    }

    /** Where on the pages the letter [offset] of [paragraph] is: its page, and how far down it its line begins. */
    fun placeOf(paragraph: Int, offset: Int = 0): PdfPlace? {
        val run = runOf(paragraph, offset)
        if (run < 0) return null
        val page = runPage[run]
        val height = heights[page]
        return PdfPlace(page, if (height > 0f) (boxes[run * 4 + 1] / height).coerceIn(0f, 1f) else 0f)
    }

    /**
     * How far through the book [place] is (0..1): the pages before it, and how far down its own.
     * It is the same sum the reader makes of the page in view, so a book listened to and a book
     * read count alike.
     */
    fun progressOf(place: PdfPlace): Float = if (pageCount == 0) 0f else ((place.page + place.within.coerceIn(0f, 1f)) / pageCount).coerceIn(0f, 1f)

    /**
     * The words that are read first with [place] at the top of the screen: the first line, in
     * reading order, that begins at it or below it on its page; on a page with none left (or none
     * at all, a scan), the first of the next page that has any. Null after the book's last words.
     */
    fun spotAt(place: PdfPlace): PdfSpot? {
        var later: PdfSpot? = null
        var laterPage = Int.MAX_VALUE
        for (paragraph in 0 until paragraphs) {
            for (run in first[paragraph] until first[paragraph + 1]) {
                val page = runPage[run]
                if (page == place.page && boxes[run * 4 + 1] >= place.within * heights[page] - LINE_SLACK) return PdfSpot(paragraph, runAt[run])
                if (page > place.page && page < laterPage) {
                    laterPage = page
                    later = PdfSpot(paragraph, runAt[run])
                }
            }
        }
        return later
    }

    /**
     * The words at ([x], [y]) on [page], in the page's units: the line there, and about how far
     * along it. Null where there is no line (a margin, a picture, the white between paragraphs).
     */
    fun spotNear(page: Int, x: Float, y: Float): PdfSpot? {
        for (paragraph in 0 until paragraphs) {
            var run = first[paragraph]
            while (run < first[paragraph + 1]) {
                val left = boxes[run * 4]; val top = boxes[run * 4 + 1]; val right = boxes[run * 4 + 2]; val bottom = boxes[run * 4 + 3]
                // The stretches of one line come one after the other, with the line's box each.
                var last = run
                while (last + 1 < first[paragraph + 1] && runPage[last + 1] == runPage[run] && boxes[(last + 1) * 4 + 1] == top && boxes[(last + 1) * 4] == left) last++
                val on = runPage[run] == page && runCount[run] != PdfLine.UNPLACED && right > left &&
                    x >= left - TAP_SLACK && x <= right + TAP_SLACK && y >= top - TAP_SLACK && y <= bottom + TAP_SLACK
                if (on) {
                    val letters = (run..last).sumOf { runLength[it] }
                    var along = (((x - left) / (right - left)).coerceIn(0f, 1f) * letters).toInt().coerceAtMost(letters - 1)
                    for (part in run..last) {
                        if (along < runLength[part]) return PdfSpot(paragraph, runAt[part] + along)
                        along -= runLength[part]
                    }
                }
                run = last + 1
            }
        }
        return null
    }

    /** How many letters [paragraph] has. */
    fun lengthOf(paragraph: Int): Int =
        if (paragraph !in 0 until paragraphs) 0 else (first[paragraph + 1] - 1).let { runAt[it] + runLength[it] }

    /**
     * The words the voice goes to for "next page", when it is on the letters [from] until [to] of
     * [paragraph]: the first on the next page that has any. A sentence that runs over onto that
     * page is the one being read already, so then it is the sentence after it. Null on the last
     * page with words.
     */
    fun nextPageFrom(paragraph: Int, from: Int, to: Int): PdfSpot? {
        val page = placeOf(paragraph, from)?.page ?: return null
        val spot = firstOn(page + 1) ?: return null
        if (placeOf(spot.paragraph, spot.offset)?.page == page) return null
        if (spot.paragraph != paragraph || spot.offset !in from until to) return spot
        return if (to < lengthOf(paragraph)) PdfSpot(paragraph, to) else PdfSpot(paragraph + 1, 0).takeIf { paragraph + 1 < paragraphs }
    }

    /** The first words on [page], or on the next page that has any. */
    fun firstOn(page: Int): PdfSpot? = spotAt(PdfPlace(page, 0f))

    /** Whether [page] has any words to read: a scanned page has none. */
    fun hasText(page: Int): Boolean = runPage.any { it == page }

    /** The pages before [page] that have words, nearest first: for stepping back a page. */
    fun textPageBefore(page: Int): Int? = (page - 1 downTo 0).firstOrNull(::hasText)

    /** Where the letters [from] until [to] of [paragraph] are drawn: a mark for each line, or part of a line, they take up. */
    fun marks(paragraph: Int, from: Int, to: Int): List<PdfMark> {
        if (paragraph !in 0 until paragraphs || to <= from) return emptyList()
        val out = ArrayList<PdfMark>()
        for (run in first[paragraph] until first[paragraph + 1]) {
            val start = maxOf(from, runAt[run])
            val end = minOf(to, runAt[run] + runLength[run])
            if (start >= end) continue
            val counted = runCount[run]
            // A line the PDF reader couldn't place is read, and not marked.
            if (counted == PdfLine.UNPLACED) continue
            out += PdfMark(
                page = runPage[run], start = start, end = end,
                from = if (counted < 0) -1 else counted + (start - runAt[run]),
                to = if (counted < 0) -1 else counted + (end - runAt[run]),
                left = boxes[run * 4], top = boxes[run * 4 + 1], right = boxes[run * 4 + 2], bottom = boxes[run * 4 + 3],
            )
        }
        return out
    }

    fun write(out: DataOutputStream) {
        out.writeInt(VERSION)
        fun ints(values: IntArray) {
            out.writeInt(values.size)
            values.forEach(out::writeInt)
        }
        fun floats(values: FloatArray) {
            out.writeInt(values.size)
            values.forEach(out::writeFloat)
        }
        floats(widths); floats(heights); ints(first); ints(files)
        ints(runAt); ints(runLength); ints(runPage); ints(runCount); floats(boxes)
        out.writeInt(chapters.size)
        for (chapter in chapters) {
            out.writeUTF(chapter.title.take(LONGEST_TITLE))
            out.writeInt(chapter.level); out.writeInt(chapter.page); out.writeInt(chapter.paragraph)
        }
    }

    companion object {
        /** Goes up whenever the same PDF would be given other paragraphs or another layout: what was kept is then made again. */
        const val VERSION = 1

        private const val LONGEST_TITLE = 300

        /** A tap this near a line (in the page's units) is on it. */
        private const val TAP_SLACK = 3f

        /** A line this little above a place (in the page's units) is still at it: a place is kept as a fraction, and comes back a hair off. */
        private const val LINE_SLACK = 1f
        private const val MOST = 50_000_000

        /** A file of the pages made for the voice holds about this many letters, and a chapter of the outline begins a new one. */
        private const val LETTERS_IN_A_FILE = 40_000

        private val ID = Regex("p(\\d+)")
        private val FILE = Regex("(?:^|/)text/part(\\d{4,})\\.xhtml$")

        fun hrefOfFile(file: Int): String = "text/part%04d.xhtml".format(java.util.Locale.ROOT, file + 1)

        /**
         * The layout of a PDF whose pages hold [pages] and are read as [paragraphs], with its
         * [outline] if it has one. Each line of the outline is given the paragraph that is read
         * first from where it points.
         */
        fun of(pages: List<PdfPageText>, paragraphs: List<PdfParagraph>, outline: List<PdfOutlineEntry> = emptyList()): PdfLayout {
            val runs = paragraphs.sumOf { it.runs.size }
            val first = IntArray(paragraphs.size + 1)
            val runAt = IntArray(runs)
            val runLength = IntArray(runs)
            val runPage = IntArray(runs)
            val runCount = IntArray(runs)
            val boxes = FloatArray(runs * 4)
            var at = 0
            paragraphs.forEachIndexed { index, paragraph ->
                first[index] = at
                for (run in paragraph.runs) {
                    val line = pages[run.page].lines[run.line]
                    runAt[at] = run.at
                    runLength[at] = run.length
                    runPage[at] = run.page
                    runCount[at] = if (line.start < 0) line.start else line.start + run.from
                    boxes[at * 4] = line.left; boxes[at * 4 + 1] = line.top; boxes[at * 4 + 2] = line.right; boxes[at * 4 + 3] = line.bottom
                    at++
                }
            }
            first[paragraphs.size] = at

            // Each line of the outline opens at the first paragraph that begins where it points, or after.
            fun opening(entry: PdfOutlineEntry): Int {
                val top = (entry.within ?: 0f) * pages.getOrNull(entry.page)?.height.let { it ?: 0f }
                return paragraphs.indices.firstOrNull { index ->
                    val run = paragraphs[index].runs.first()
                    run.page > entry.page || (run.page == entry.page && pages[run.page].lines[run.line].bottom > top + 0.5f)
                } ?: -1
            }
            val chapters = outline.filter { it.page in pages.indices && it.title.isNotBlank() }.map { PdfChapter(it.title, it.level, it.page, opening(it)) }

            val starts = chapters.mapTo(HashSet()) { it.paragraph }
            val files = IntArray(paragraphs.size)
            var file = 0
            var letters = 0
            paragraphs.forEachIndexed { index, paragraph ->
                if (letters > 0 && (index in starts || letters + paragraph.text.length > LETTERS_IN_A_FILE)) {
                    file++
                    letters = 0
                }
                files[index] = file
                letters += paragraph.text.length
            }
            return PdfLayout(
                FloatArray(pages.size) { pages[it].width }, FloatArray(pages.size) { pages[it].height },
                first, files, runAt, runLength, runPage, runCount, boxes, chapters,
            )
        }

        /** Reads what [write] wrote. Throws if it is something else, or was written by another version. */
        fun read(input: DataInputStream): PdfLayout {
            if (input.readInt() != VERSION) throw IOException("Another version's layout")
            fun size(): Int = input.readInt().also { if (it !in 0..MOST) throw IOException("Not a layout") }
            fun ints(): IntArray = IntArray(size()) { input.readInt() }
            fun floats(): FloatArray = FloatArray(size()) { input.readFloat() }
            val widths = floats(); val heights = floats(); val first = ints(); val files = ints()
            val runAt = ints(); val runLength = ints(); val runPage = ints(); val runCount = ints(); val boxes = floats()
            val chapters = List(size()) { PdfChapter(input.readUTF(), input.readInt(), input.readInt(), input.readInt()) }
            val runs = runAt.size
            val sound = heights.size == widths.size && first.isNotEmpty() && first.last() == runs && files.size == first.size - 1 &&
                runLength.size == runs && runPage.size == runs && runCount.size == runs && boxes.size == runs * 4 &&
                runPage.all { it in widths.indices } && (1 until first.size).all { first[it] > first[it - 1] }
            if (!sound) throw IOException("Not a layout")
            return PdfLayout(widths, heights, first, files, runAt, runLength, runPage, runCount, boxes, chapters)
        }
    }
}

/**
 * Where in [paragraph] the sentence [sentence] begins, one of the voice's: voices cut and space
 * sentences their own way, so it is found by how it opens, and, where the same words open more
 * than one sentence of the paragraph, by what stands [before] it. 0 if it isn't found.
 */
internal fun sentenceOffset(paragraph: String, sentence: String, before: String? = null): Int {
    val words = sentence.trimStart()
    // By as much of its opening as is found: a voice may run two short sentences together.
    for (length in OPENINGS) {
        val opening = words.take(length)
        if (opening.isEmpty()) return 0
        val found = ArrayList<Int>()
        var at = paragraph.indexOf(opening)
        while (at >= 0 && found.size < 64) {
            found += at
            at = paragraph.indexOf(opening, at + 1)
        }
        if (found.isEmpty()) {
            if (words.length <= length) return 0
            continue
        }
        if (found.size == 1) return found.first()
        val tail = before?.filterNot { it.isWhitespace() }?.takeLast(BEFORE_TAIL).orEmpty()
        if (tail.isEmpty()) return found.first()
        return found.firstOrNull { paragraph.substring(0, it).filterNot { c -> c.isWhitespace() }.endsWith(tail) } ?: found.first()
    }
    return 0
}

private val OPENINGS = listOf(32, 20, 12)
private const val BEFORE_TAIL = 16
