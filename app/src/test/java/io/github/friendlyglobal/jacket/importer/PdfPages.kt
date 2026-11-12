package io.github.friendlyglobal.jacket.importer

/*
 * Pages of text to test with, set the way a PDF sets them: lines of words with a box around
 * each, in the order a file would draw them. Every letter is [LETTER] wide and lines are [PITCH]
 * apart, so a test can say where anything is.
 */

internal const val LETTER = 5f
internal const val PITCH = 14f
internal const val PAGE_WIDTH = 420f
internal const val PAGE_HEIGHT = 600f

/** Builds one page: what is added is drawn in the order it is added, as in a file. */
internal class PageBuilder(private val width: Float = PAGE_WIDTH, private val height: Float = PAGE_HEIGHT) {
    private val lines = ArrayList<PdfLine>()
    private var count = 0

    /** One line of [text] with its left edge at [left] and its top at [top]. */
    fun line(text: String, left: Float, top: Float, size: Float = 10f): PageBuilder {
        lines += PdfLine(text, left, top, left + text.length * LETTER * size / 10f, top + size, start = count)
        // (As the phone's PDF reader counts: a line and the two characters that end it.)
        count += text.length + 2
        return this
    }

    /** [text] centred on the page at [top]: a running title, a page number. */
    fun centred(text: String, top: Float, size: Float = 10f): PageBuilder = line(text, (width - text.length * LETTER * size / 10f) / 2f, top, size)

    /**
     * [paragraphs] set between [left] and [right] from [top] down, each with its first line set
     * in by [indent]; a word that doesn't fit is broken with a hyphen where [hyphenate] allows.
     * Returns the top of the line after the last, so more can follow.
     */
    fun column(
        paragraphs: List<String>,
        left: Float,
        right: Float,
        top: Float,
        indent: Float = 15f,
        gap: Float = 0f,
        hyphenate: Boolean = false,
        /** The first paragraph carries on from another column or page: its first line isn't set in. */
        carriedOn: Boolean = false,
    ): Float {
        var y = top
        paragraphs.forEachIndexed { index, paragraph ->
            var first = !(carriedOn && index == 0)
            val words = ArrayDeque(paragraph.split(' '))
            while (words.isNotEmpty()) {
                val from = if (first) left + indent else left
                val room = ((right - from) / LETTER).toInt()
                val text = StringBuilder()
                while (words.isNotEmpty()) {
                    val word = words.first()
                    val length = text.length + (if (text.isEmpty()) 0 else 1) + word.length
                    if (length <= room) {
                        if (text.isNotEmpty()) text.append(' ')
                        text.append(words.removeFirst())
                        continue
                    }
                    // Broken after its third letter at least, with three or more to follow.
                    val fits = room - text.length - (if (text.isEmpty()) 0 else 1) - 1
                    if (hyphenate && fits >= 3 && word.length - fits >= 3 && word.all { it.isLetter() }) {
                        if (text.isNotEmpty()) text.append(' ')
                        text.append(word.take(fits)).append('-')
                        words[0] = word.drop(fits)
                    }
                    break
                }
                if (text.isEmpty()) text.append(words.removeFirst())
                line(text.toString(), from, y)
                y += PITCH
                first = false
            }
            y += gap
        }
        return y
    }

    fun build(): PdfPageText = PdfPageText(width, height, lines.toList())
}

internal fun page(build: PageBuilder.() -> Unit): PdfPageText = PageBuilder().apply(build).build()
