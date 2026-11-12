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

    /** Words found this far (of the file) from where their place was are some other words like them. */
    const val BY_WORDS = 0.05

    /**
     * Whether a page Readium reports as starting at [reported] can be the one holding a place that
     * is kept by its words, [kept] of the way down the file: a page's first words, or the sentence
     * the voice is on. The words are somewhere on the page, so it starts at them or up to a page
     * before them ([pages] in the file, when that is known), give or take [BY_WORDS].
     */
    fun holdsWords(reported: Double, kept: Double, pages: Int?): Boolean {
        val page = if (pages != null && pages > 0) 1.0 / pages else 0.0
        return kept >= reported - BY_WORDS && kept <= reported + page + BY_WORDS
    }
}

/**
 * How far down a chapter file its text is, by the letters that come before: 0 at the file's first
 * letter, 1 after its last. It is how the voice's place is told in the terms a page's place is.
 *
 * Readium's read-aloud names a sentence by the block of text it is in, and says how far down the
 * file that block is by counting blocks: the 30th of 120 is "a quarter of the way down". But a
 * file's blocks are a line here and three pages there (a list of contents, verse, a licence at
 * the end), so a quarter of the blocks down can be a tenth of the way through the pages. Taken
 * for a place on the pages, that count opens the book chapters away from the sentence it was
 * left at, and counts the listener twice as far through the book as the pages do, or half. The
 * letters before a block are where it falls on the pages, to within a picture or the white space
 * around a heading.
 *
 * [texts] are the file's blocks of text in order, as Readium's read-aloud reads them.
 */
internal class TextPlaces(private val texts: List<String>) {
    private val starts = IntArray(texts.size)
    private val letters: Int

    init {
        var before = 0
        texts.forEachIndexed { block, text ->
            starts[block] = before
            before += text.count { !unseen(it) }
        }
        letters = before
    }

    /** How far down the file [block] starts. */
    fun of(block: Int): Double = if (letters == 0 || block !in texts.indices) 0.0 else starts[block].toDouble() / letters

    /** How far down the file the sentence [sentence] of [block] starts: the block's own start, if it isn't found in it. */
    fun of(block: Int, sentence: String?): Double {
        if (letters == 0 || block !in texts.indices) return 0.0
        val opening = opening(sentence)
        val into = if (opening.isEmpty()) 0 else bare(texts[block]).indexOf(opening).coerceAtLeast(0)
        return (starts[block] + into).toDouble() / letters
    }

    /** Whether [sentence] is one of [block]'s: voices cut and space sentences their own way, so it is known by its opening. */
    fun holds(block: Int, sentence: String?): Boolean {
        val opening = opening(sentence)
        return opening.isNotEmpty() && block in texts.indices && bare(texts[block]).contains(opening)
    }

    /**
     * Which block a sentence of the voice's is in. [exact] is the block Readium's reading of the
     * file names outright, when the sentence comes straight from it. A sentence that was saved
     * (and so carries its place in the file, [around], rather than Readium's count of blocks) is
     * looked for by its words: among the blocks that go by the name it carries, [named], the one
     * nearest where it was; failing that, the one block in the file that holds them.
     */
    fun blockOf(sentence: String?, exact: Int?, named: List<Int>, around: Double?): Int? {
        if (exact != null && exact in texts.indices && (opening(sentence).isEmpty() || holds(exact, sentence))) return exact
        val among = named.filter { holds(it, sentence) }
        if (among.isNotEmpty()) return among.minByOrNull { abs(of(it, sentence) - (around ?: 0.0)) }
        return texts.indices.singleOrNull { holds(it, sentence) }
    }

    /**
     * The letter [progression] of the way down the file: which block it is in, and how many
     * letters into it. Null for a file with no text.
     */
    fun spotAt(progression: Double): TextSpot? {
        if (letters == 0) return null
        val letter = (progression.coerceIn(0.0, 1.0) * letters).toInt().coerceAtMost(letters - 1)
        // The block the letter is in: the last one that starts at or before it (an empty block
        // starts where the next one does, so it is never the last of those).
        val block = texts.indices.lastOrNull { starts[it] <= letter } ?: return null
        return TextSpot(block, letter - starts[block])
    }

    private fun opening(sentence: String?): String = sentence?.let(::bare)?.take(SENTENCE_OPENING).orEmpty()
}

/** Two voices' sentences are the same one if they open with this many of the same letters. */
internal const val SENTENCE_OPENING = 24

/**
 * How much of [words] to ask a page for, so that it finds them. Readium's pages look for a
 * sentence by its words, give or take a few (the page has a line break where the voice's reading
 * of the file has a space). The search they use (approx-string-match 1.1.0) loses any words whose
 * length is a multiple of 32 characters, from 64 up: it finds where they end and takes them for
 * no words at all. About one sentence in thirty is such a length. Asked for one character fewer,
 * the page finds them.
 */
internal fun findableLength(words: String): Int = if (words.length >= 64 && words.length % 32 == 0) words.length - 1 else words.length

/**
 * Whose place is the book's. While the voice is speaking it is the voice's: the sentence being
 * read, wherever the page is (shut in a pocket, the page can't turn to follow). The rest of the
 * time, paused included, it is the page in front of the reader.
 */
internal fun voiceHoldsThePlace(listening: Boolean, speaking: Boolean): Boolean = listening && speaking

/**
 * The place to keep while the pages re-flow (a fold, an unfold, a new text size) and to open a new
 * navigator at: the page in front of the reader, [page]. Unless the voice has read on with the
 * page out of sight ([unseen]: the phone shut, the screen off, another app in front). The page
 * can't turn there, so it is still the one it was left on, however long ago, and the place is the
 * sentence the voice has reached, [spoken]. Put back at that old page, the book loses everything
 * heard since: its place is saved from whatever page is showing as soon as the voice stops.
 */
internal fun <T : Any> placeKept(spoken: T?, unseen: Boolean, page: () -> T?): T? = spoken?.takeIf { unseen } ?: page()

/**
 * How far down its chapter file the voice's place is counted, for the book's progress. A page
 * counts from where it starts, and so does the voice while its sentence is on the page in front of
 * the listener, which starts [page] of the way down: listening and reading then give the same
 * number, and starting or stopping the voice doesn't move it.
 *
 * Out of sight there is no page to ask ([page] is null), and the count is made from the
 * sentence's own place, [told] (see [TextPlaces]). The two don't quite agree (pictures and white
 * space take room on a page that words don't), so the sentence's place is carried on from where a
 * page last had it: [lead] is what the page's count was ahead of the sentence's when the voice
 * came onto that page, in this file. And it is counted from where its page would start, of the
 * file's [pages], as a page is. The number then holds still while the voice stays on a page it
 * can't be seen on, goes up a page at a time as it reads on, and is the same again when the page
 * comes back into view.
 */
internal fun spokenWithin(told: Double, page: Double?, lead: Double?, pages: Int?): Double {
    if (page != null) return page
    val at = (told + (lead ?: 0.0)).coerceIn(0.0, 1.0)
    return if (pages != null && pages > 0) Pages.holding(at, pages).toDouble() / pages else at
}

/**
 * The lead the voice is counted by out of sight (see [spokenWithin]), now that its sentence, which
 * is [told] of the way down the file by its own count, is seen on a page that starts [page] of the
 * way down. All a page says of a sentence on it is that the sentence comes after the page's
 * start, so the lead is at least [page] less [told]. [known] is the lead held so far, and unless
 * the voice has [moved] on to another page of the same pages (when the lead is the new page's),
 * the greater of the two is the nearer the truth: the same page says less of a sentence further
 * down it, and pages newly laid out by a fold say no more of the place than the old ones did.
 */
internal fun pageLead(page: Double, told: Double, known: Double?, moved: Boolean): Double =
    if (known == null || moved) page - told else maxOf(known, page - told)

/**
 * Whether the voice's place is all of the book: 100%, as the last page is to someone reading it.
 * A page counts from where it starts, so by that alone the last one never says 100% (in a book of
 * three short chapters it says 67%). Read by eye it is counted as the whole book, and it is for
 * the voice too while its sentence is on that page in front of the listener ([onPage], and that
 * page the [lastPage] of the book): listening there and reading there give the same number, and
 * pressing Listen on the last page doesn't take the book back from 100%.
 *
 * And a voice that has read the book to its end ([ended]) has read all of it, wherever its last
 * sentence falls on the pages, and whether or not a page can be seen: the phone may be in a pocket.
 */
internal fun spokenIsAllOfTheBook(ended: Boolean, onPage: Boolean, lastPage: Boolean): Boolean = ended || (onPage && lastPage)

/**
 * A page as Readium reports it: the chapter [file] it is in (its number in the book), how far down
 * the file it [starts], and how many [pages] the file is laid out in (null scrolling, or before
 * Readium has said).
 */
internal data class PageSeen(val file: Int, val starts: Double, val pages: Int?) {
    /**
     * Whether this page comes before [other] in the book. Pages of one file say so only when they
     * are of the same laying-out of it: where a page starts moves with every fold and text size,
     * so a page of 13 says nothing of a page of 26.
     */
    fun isBefore(other: PageSeen): Boolean = when {
        file < 0 || other.file < 0 -> false
        file != other.file -> file < other.file
        pages != other.pages -> false
        // (Readium tells a page's start to within a few pixels: half a page apart is another page.)
        pages != null && pages > 0 -> (other.starts - starts) * pages > 0.5
        else -> other.starts - starts > Pages.NEAR
    }

    /** Whether this is [other] over again: the same page of the same laying-out. */
    fun isSameAs(other: PageSeen): Boolean = file == other.file && pages == other.pages && !isBefore(other) && !other.isBefore(this)
}

/**
 * Whether the reader has gone back through the book: the one thing that lets its progress fall.
 *
 * One place is counted more than one way. A page counts from where it starts, and where a page
 * starts depends on how the pages fall: folded or not, the size of the text. The voice, out of
 * sight of any page, counts from its sentence's own place in the file. The counts of one place are
 * up to a page apart. So a place counted again (the book reopened, its pages laid out anew, the
 * voice handed to a reader or back) took the number down as often as up, though nobody had gone
 * anywhere: 43% on the widget while the voice read with the book shut, 42% once the book was open.
 * A reader who once watched a book fall from 42% to 20% can't tell that from the place being
 * lost. So the library never takes a book's progress down unless it is told the reader went back
 * (see Book.leftAt), and this is what tells it.
 *
 * The pages say. [on] is the page the book's place was last counted on, as Readium reported it. A
 * page taken as the reader's place that comes before it is the reader turning back, or jumping
 * back. But only pages of the same laying-out can be told apart that way, and only pages the
 * reader went to: pages laid out anew (a fold, a text size) and a page Jacket itself put in front
 * of the reader (the book opening at its place, the page sent after the voice) are the same place
 * shown again. So the first page seen after either is where the place is now, not somewhere the
 * reader went ([unsettled]).
 *
 * Two things the pages can't tell are said outright. A jump the reader asked for, in case it
 * lands before the pages are known ([jumped]). And the listener having left the voice's page to
 * look elsewhere, when the voice stops with them there ([choseAPage]): the page they chose is
 * their place then, wherever the voice had got to.
 */
internal class PagePlace {
    /** The page the book's place was last counted on; null when it isn't known in the pages as they now fall. */
    var on: PageSeen? = null
        private set
    private var jumpedAt = Long.MIN_VALUE
    private var chose = false

    /** The pages are being laid out anew, or put somewhere by Jacket itself: the next page seen is the place, shown again. */
    fun unsettled() {
        on = null
    }

    /** The book's place is on [page]: it opened there, was put back there, or the voice's sentence is seen there. */
    fun at(page: PageSeen) {
        on = page
    }

    /** The reader asked for a jump at [now] (uptime millis): the contents, the scrubber, a bookmark, the way back. */
    fun jumped(now: Long) {
        jumpedAt = now
    }

    /** The listener is on a page of their own choosing, and the voice has stopped: that page is their place. */
    fun choseAPage() {
        chose = true
    }

    /** Whether [page] is the one the book's place was last counted on. */
    fun isOn(page: PageSeen): Boolean = on?.isSameAs(page) == true

    /** Whether a jump was asked for a moment before [now], and hasn't been seen to land yet. */
    fun jumping(now: Long): Boolean = jumpedAt != Long.MIN_VALUE && now - jumpedAt in 0..JUMP_LANDS_MS

    /** [page] is taken as the reader's place at [now]: whether they went back through the book to get to it. */
    fun wentBackTo(page: PageSeen, now: Long): Boolean {
        val from = on
        val back = chose || jumping(now) || (from != null && page.isBefore(from))
        chose = false
        jumpedAt = Long.MIN_VALUE
        // The same page reported again a hair off (scrolling, a line up) is no move: the page the
        // count was made on stands, so that many small steps back add up to one.
        on = if (!back && from != null && from.isSameAs(page)) from else page
        return back
    }

    private companion object {
        /** A jump lands within this long: to another chapter file, Readium is sent twice (see the reader's jumpTo). */
        const val JUMP_LANDS_MS = 6_000L
    }
}

/**
 * Whether a sentence of the voice's comes before another: each is told by the chapter file it is
 * in (its number in the book) and the letters before it in that file (see [TextPlaces]), so one
 * is before the other by exactly that. It is how a listener sending the voice back (the previous
 * sentence, an earlier one tapped, the chapter before) is told from sending it on.
 */
internal fun comesBefore(file: Int, told: Double, otherFile: Int, otherTold: Double): Boolean =
    file >= 0 && otherFile >= 0 && (file < otherFile || (file == otherFile && told < otherTold))

/**
 * Whether a sentence the book was left on was saved by Jacket 1.0.0, which told it by a count of
 * its own, far from the pages' (see [TextPlaces]): [saved] is how far down its file it was saved
 * as, [placed] how far down it is. Such a place is counted over once, and the number goes to the
 * true one, up or down. That is the one count that may take it down without the reader going back.
 */
internal fun savedTheOldWay(saved: Double?, placed: Double?): Boolean =
    saved != null && placed != null && abs(saved - placed) > 1e-6

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

/**
 * Which of a chapter file's blocks of text is "the first paragraph on this page": what a
 * highlight made without selecting anything marks, while nothing is being read aloud (a screen
 * reader and a keyboard can't press and hold on the page). [top] is what the page shows from its
 * first word on, running on into the blocks after it; the block that word is in is found by
 * those words, as a tap on them would find it (see [tappedAt] and [blockShowing]), and failing
 * that by how far down the file the page is ([around], with [progressions] where each block
 * starts). A heading ([heading]) is no paragraph: the first block after it that isn't one is.
 *
 * Null when the page shows no words (a picture, a blank page), or the file has none.
 */
internal fun firstParagraphOn(
    texts: List<String>,
    progressions: List<Double>,
    top: String?,
    around: Double?,
    heading: (Int) -> Boolean = { false },
): Int? {
    val words = top?.takeIf { it.isNotBlank() } ?: return null
    val first = tappedAt(texts, "", words)?.block ?: blockShowing(texts, words) ?: startBlock(texts, progressions, words, around) ?: return null
    return (first until texts.size).firstOrNull { !heading(it) && bare(texts[it]).isNotEmpty() }
}

/** A highlight's words as they are kept and shown: every run of spaces and line breaks as one space. */
internal fun highlightWords(text: String): String = squeezed(text)

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

/**
 * Where in a chapter file the voice starts when there is no page to start from: started from a
 * headphone button, the notification, the widget or the car, it carries on from the place the
 * book was left at. That place is one of two things.
 *
 * The sentence the voice stopped on: [block] is the one of the file's blocks it is in (see
 * [TextPlaces.blockOf]), [words] the sentence, and the voice starts with it.
 *
 * Or the page a reader was on: [words] are the page's first words, where enough of them were
 * kept to tell it by, and the voice starts with the sentence they are in, as it would with the
 * page in front of it. Failing them (too few, not found, or found twice), the place is known by
 * how far down the file it is, [around], and the voice starts with the sentence that far down.
 *
 * [texts] are the file's blocks of text in order, [places] where each is in the file. Null for a
 * file with no words.
 */
internal fun resumeSpot(texts: List<String>, places: TextPlaces, words: String?, around: Double?, block: Int? = null): TextSpot? {
    if (block != null && block in texts.indices) {
        val opening = words?.let(::bare)?.take(SENTENCE_OPENING).orEmpty()
        return TextSpot(block, if (opening.isEmpty()) 0 else bare(texts[block]).indexOf(opening).coerceAtLeast(0))
    }
    return words?.let { tappedAt(texts, "", it) } ?: places.spotAt(around ?: 0.0)
}

/**
 * What the voice's count of the book's place leads its sentence's own by, when it starts with no
 * page in sight (see [spokenWithin] and [pageLead]). A page seen would say; with none, the book's
 * place as it was last counted stands in for one: [counted] of the way through the book, which is
 * [within] of the way down its file, while the sentence the voice starts with is [told] of the way
 * down the file by its own count ([own] through the book). So the number the book shows doesn't
 * move when the voice is started from a headphone button, and goes up from there as it reads.
 *
 * Only where the two are counts of the same place: in the same file ([sameFile]), and no further
 * apart than pages and their pictures account for. A voice that had to start somewhere else (the
 * saved place wasn't found) is counted by its own sentence alone; null then.
 */
internal fun carriedLead(sameFile: Boolean, counted: Double, own: Double, within: Double, told: Double): Double? =
    if (sameFile && abs(counted - own) <= CARRY_LIMIT) pageLead(within, told, known = null, moved = false) else null

/** Two counts of a place this far apart (of the whole book) are not of the same place. */
internal const val CARRY_LIMIT = 0.02

/**
 * Whether the chapter the sleep timer was waiting on has ended. It started with the voice in the
 * chapter file [startFile] and the page in the chapter [startChapter] of the contents (-1 for
 * either while unknown; the chapter is always -1 with no reader open). Whichever comes first: the
 * voice moving into another chapter file ([file]), or the page, following the voice, reaching
 * another chapter of the contents ([chapter]).
 */
internal fun chapterEnded(startFile: Int, startChapter: Int, file: Int, chapter: Int): Boolean =
    (file != startFile && file >= 0 && startFile >= 0) || chapter != startChapter

/** How many letters behind and ahead of a tap it is looked for by, in the order tried. */
private val TAP_CONTEXT = listOf(60 to 60, 24 to 24, 0 to 40, 40 to 0)

/** How much of the top of the page a block is found by, and the least that says anything. */
private const val TOP_CHARS = 64
private const val FEWEST_TOP_CHARS = 12

/** Text without its spaces, line breaks and unseen characters (soft hyphens, joiners), as it is searched. */
internal fun bare(text: String): String = text.filterNot(::unseen)

private fun unseen(char: Char): Boolean = char.isWhitespace() || Character.isSpaceChar(char) || Character.getType(char) == Character.FORMAT.toInt()

private val Spaces = Regex("\\s+")

/** Text with every run of spaces and line breaks as one space, as it is compared. */
private fun squeezed(text: String): String = text.replace(Spaces, " ").trim()
