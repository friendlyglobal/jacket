package io.github.friendlyglobal.jacket.reader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Keeping the reader's place in an EPUB: the decisions, apart from Readium so they can be tested.
 *
 * A place in a chapter file is its progression: 0 at the top of the file, 1 at the bottom. Readium
 * lays a file out in pages and keeps the page *number* when the pages re-flow (a fold, a new text
 * size), not the place: page 74 of 115 becomes page 74 of 60. In a book whose chapters are long
 * files that is dozens of pages away, so Jacket keeps the place itself.
 */
internal object Pages {
    /**
     * Readium tells where a page starts from the web view's scroll offset, which comes out a few
     * pixels to either side of the page's true edge. This much of a page is no difference.
     */
    const val EDGE = 0.02

    /** Scrolling (no pages), two places this close in a file are the same place. */
    const val NEAR = 0.01

    /** The page, counting from 0, of [pages] that holds [progression]. */
    fun holding(progression: Double, pages: Int): Int =
        floor(progression * pages + EDGE).toInt().coerceIn(0, (pages - 1).coerceAtLeast(0))

    /**
     * A progression that opens the page holding [progression]. Readium opens the page *nearest* to
     * a progression, which can be the page after the one holding it: aim a quarter page in.
     */
    fun aim(progression: Double, pages: Int): Double = (holding(progression, pages) + 0.25) / pages

    /** Whether a page Readium reports as starting at [reported] is the one that holds [kept]. */
    fun shows(reported: Double, kept: Double, pages: Int): Boolean =
        (reported * pages).roundToInt() == holding(kept, pages)

    /** The same, where there are no pages to count (scrolling, or a page count not yet known). */
    fun near(reported: Double, kept: Double): Boolean = abs(reported - kept) <= NEAR
}

/**
 * Whose place is the book's. While the voice is speaking it is the voice's: the sentence being
 * read, wherever the page is (shut in a pocket, the page can't turn to follow). The rest of the
 * time, paused included, it is the page in front of the reader.
 */
internal fun voiceHoldsThePlace(listening: Boolean, speaking: Boolean): Boolean = listening && speaking

/** How a book is opened at the place it was left. */
internal sealed interface Reopen {
    /** At the saved Readium locator, as it is. */
    data object Saved : Reopen

    /** The locator can't be used: this far through the whole book, which was saved beside it. */
    data class AtProgress(val progress: Double) : Reopen

    /** Nor is that known: at the Readium position (about a page of text) saved with them. */
    data class AtPosition(val index: Int) : Reopen

    /** A book last read in Jacket 0.2, which kept a chapter number and how far down it. */
    data class AtChapter(val chapter: Int, val offset: Double) : Reopen

    /** Never opened: from the first page. */
    data object Start : Reopen
}

/**
 * Where to open a book. A saved locator that can't be read, or that names a file the book doesn't
 * have, must not send the reader back to page one: everything else saved with it says where they
 * were, to within a page or so.
 *
 * [hasLocator] is whether a locator was saved at all, [usable] whether it can be read and names a
 * file in the book; [position] of [positions] and [progress] are what [Library.updateLocator]
 * saved beside it, and [offset] is the place in the chapter that Jacket 0.2 kept.
 */
internal fun reopen(hasLocator: Boolean, usable: Boolean, position: Int, positions: Int, progress: Float, offset: Float): Reopen = when {
    hasLocator && usable -> Reopen.Saved
    // The progress is the finer of the two: it was worked out from the very place in the file.
    hasLocator && progress.isFinite() && progress > 0f -> Reopen.AtProgress(progress.coerceIn(0f, 1f).toDouble())
    hasLocator && position in 1 until positions -> Reopen.AtPosition(position)
    hasLocator -> Reopen.Start
    position > 0 || offset > 0f -> Reopen.AtChapter(position, offset.coerceIn(0f, 1f).toDouble())
    else -> Reopen.Start
}

/**
 * Which of a chapter file's blocks of text read-aloud starts from, when it is asked to start at
 * the page in view: the block showing [wanted] words (the first block on the page, as the page
 * itself gives it), and where the same words come more than once, the one nearest [around] (how
 * far down the file the page is). Without a match, the last block that starts at or before
 * [around]. Null when the file has no text.
 *
 * [texts] are the file's blocks in order, [progressions] how far down the file each starts.
 */
internal fun startBlock(texts: List<String>, progressions: List<Double>, wanted: String?, around: Double?): Int? {
    if (texts.isEmpty()) return null
    val at = around ?: 0.0
    fun nearest(candidates: List<Int>) = candidates.minByOrNull { abs((progressions.getOrNull(it) ?: 0.0) - at) }
    val words = wanted?.let(::squeezed).orEmpty()
    if (words.isNotEmpty()) {
        // The start of the block is enough to know it by, and survives the two readings of the
        // file disagreeing further on (a line break one of them reads as a space).
        val head = words.take(START_WORDS)
        val same = texts.indices.filter { squeezed(texts[it]).take(START_WORDS) == head }
        nearest(same)?.let { return it }
    }
    return texts.indices.lastOrNull { (progressions.getOrNull(it) ?: 0.0) <= at } ?: 0
}

/** How much of a block's start it is known by. */
private const val START_WORDS = 48

/**
 * Which of a chapter file's blocks of text holds the first words on the page: [top] is what the
 * page shows from its first word on, running on into the blocks after it. That is where read-aloud
 * starts when it starts "at the page in view", whatever the page's first element is (a rule, a
 * picture, a box around the whole chapter have no words of their own to be found by).
 *
 * The words are looked for in the file's text as a whole, spaces aside: the page and Readium's
 * reading of the file don't put them in the same places. Null when [top] is too short to tell by,
 * isn't found, or is found in more than one place; [startBlock] decides then.
 */
internal fun blockShowing(texts: List<String>, top: String?): Int? {
    val words = top?.let(::bare)?.take(TOP_CHARS) ?: return null
    if (words.length < FEWEST_TOP_CHARS) return null
    val starts = IntArray(texts.size)
    val whole = StringBuilder()
    texts.forEachIndexed { block, text ->
        starts[block] = whole.length
        whole.append(bare(text))
    }
    val at = whole.indexOf(words)
    if (at < 0 || whole.indexOf(words, at + 1) >= 0) return null
    // The block the words begin in: the last one that starts at or before them and isn't empty.
    return texts.indices.lastOrNull { starts[it] <= at && bare(texts[it]).isNotEmpty() }
}

/** Where a tap fell in a chapter file's text: which of its blocks, and how many letters into it (spaces aside). */
internal data class TextSpot(val block: Int, val offset: Int)

/**
 * Which of a chapter file's blocks of text a tap fell in, and where in it: [before] and [after]
 * are the page's words either side of the tap, running on across the blocks around it. They are
 * looked for in the file's text as a whole, spaces aside, as [blockShowing] does: a little of
 * each side first, then less, then one side alone, for where the page and Readium's reading of
 * the file disagree (a footnote mark one of them leaves out, say). Null when the words aren't
 * found, or are found in more than one place.
 */
internal fun tappedAt(texts: List<String>, before: String, after: String): TextSpot? {
    val behind = bare(before)
    val ahead = bare(after)
    val starts = IntArray(texts.size)
    val whole = StringBuilder()
    texts.forEachIndexed { block, text ->
        starts[block] = whole.length
        whole.append(bare(text))
    }
    for ((back, on) in TAP_CONTEXT) {
        val head = behind.takeLast(back)
        val words = head + ahead.take(on)
        if (words.length < FEWEST_TOP_CHARS) continue
        val at = whole.indexOf(words)
        if (at < 0 || whole.indexOf(words, at + 1) >= 0) continue
        val tap = at + head.length
        // The block the tap is in: the last one that starts at or before it and isn't empty.
        val block = texts.indices.lastOrNull { starts[it] <= tap && bare(texts[it]).isNotEmpty() } ?: continue
        return TextSpot(block, tap - starts[block])
    }
    return null
}

/** How many letters behind and ahead of a tap it is looked for by, in the order tried. */
private val TAP_CONTEXT = listOf(60 to 60, 24 to 24, 0 to 40, 40 to 0)

/** How much of the top of the page a block is found by, and the least that says anything. */
private const val TOP_CHARS = 64
private const val FEWEST_TOP_CHARS = 12

/** Text without its spaces, line breaks and unseen characters (soft hyphens, joiners), as it is searched. */
internal fun bare(text: String): String = text.filterNot { it.isWhitespace() || Character.isSpaceChar(it) || Character.getType(it) == Character.FORMAT.toInt() }

private val Spaces = Regex("\\s+")

/** Text with every run of spaces and line breaks as one space, as it is compared. */
private fun squeezed(text: String): String = text.replace(Spaces, " ").trim()
