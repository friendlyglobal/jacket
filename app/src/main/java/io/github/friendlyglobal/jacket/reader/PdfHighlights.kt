package io.github.friendlyglobal.jacket.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.HighlightColor
import io.github.friendlyglobal.jacket.importer.PdfLayout
import io.github.friendlyglobal.jacket.importer.PdfPlace
import io.github.friendlyglobal.jacket.importer.PdfSpot
import io.github.friendlyglobal.jacket.importer.PdfTextLine
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.voice.SayIt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/*
 * Highlights and notes on a PDF, as its reader shows them.
 *
 * A PDF's page is a picture, so nothing on it can be selected the way words on an EPUB's page
 * are. Where the phone's Android hands over the PDF's words (see PdfWords.kt), Jacket knows where
 * every line of them is drawn (see PdfLayout.kt), and does the selecting itself: a press and hold
 * on a word selects it, two handles stretch the selection, and a menu offers what an EPUB's does.
 * What is highlighted is drawn on the pages, in its colour, where its words are; a tap on it opens
 * it, to change its colour, write a note on it or take it off.
 *
 * What is apart from Android of all this (where a highlight is kept, how it is found again, which
 * word a finger is on) is in PdfMarks.kt.
 */

/** A highlight as it is drawn on a PDF's pages: the stretches of the paragraphs it is, and a box for each line it takes up. */
internal class PdfDrawnMark(val id: String, val color: Color, val noted: Boolean, val ranges: List<PdfSentence>, val lines: List<MarkedLine>)

/** The two ends of what is selected, each with a handle to drag it by. */
internal enum class SelectionHandle { START, END }

/**
 * Where a handle is on its page, in the page's units: [x] at the edge of the selected words,
 * the [middle] of their line (where a finger dragging the handle is taken to point) and the
 * line's [bottom], which the handle hangs from, to the left ([hangsLeft]) or to the right.
 */
internal class HandlePoint(val page: Int, val x: Float, val middle: Float, val bottom: Float, val hangsLeft: Boolean)

/** What the menu over selected words offers. */
internal sealed interface SelectionChoice {
    class Highlight(val color: HighlightColor) : SelectionChoice
    data object Note : SelectionChoice
    data object Copy : SelectionChoice
    data object Share : SelectionChoice
    data object Say : SelectionChoice
    data object Listen : SelectionChoice

    /** One of the phone's own text actions (Translate, and the like). */
    class Process(val activity: ResolveInfo) : SelectionChoice
}

/**
 * The highlights of a PDF on its pages, and what is selected on them. Held by the PDF's reader
 * for as long as the PDF's words are to hand; everything here is said in the page's own units,
 * and the pages say where that is on the screen.
 */
@Stable
internal class PdfMarking(
    val layout: PdfLayout,
    private val source: PdfWordsSource,
    /** The words of one of the files the PDF is read aloud from, each paragraph with its number. */
    private val paragraphs: suspend (file: Int) -> List<Pair<Int, String>>,
) {
    /** The highlights that were found on the pages. */
    var drawn: List<PdfDrawnMark> by mutableStateOf(emptyList())
        private set

    /** What is selected, and a box for each line it takes up; null and empty while nothing is. */
    var selection: PdfSelection? by mutableStateOf(null)
        private set
    var selected: List<MarkedLine> by mutableStateOf(emptyList())
        private set

    /** A finger is choosing words (it pressed and hasn't lifted, or holds a handle): the menu waits for it. */
    var dragging by mutableStateOf(false)
        private set

    /** What was chosen from the menu over the selection. */
    var onChoice: (SelectionChoice) -> Unit = {}

    /** Takes the pages to a highlight; false if it isn't one that was found on them. */
    var goTo: (Annotation) -> Boolean = { false }

    private sealed interface Move {
        class Press(val page: Int, val x: Float, val y: Float) : Move
        class Grab(val handle: SelectionHandle) : Move
        class To(val page: Int, val x: Float, val y: Float) : Move
        data object Release : Move
        data object Clear : Move
    }

    /** What the finger did, in the order it did it: finding the word under it takes a moment, and a later move mustn't overtake an earlier one. */
    private val moves = Channel<Move>(Channel.UNLIMITED)

    /** The word first pressed, which a finger that goes on moving selects from; and the handle held. */
    private var pressed: PdfSentence? = null
    private var held: SelectionHandle? = null

    /** The selection's first words, and its last, are written right to left: the handles change sides. */
    private var startsRightToLeft = false
    private var endsRightToLeft = false

    private val texts = HashMap<Int, Map<Int, String>>()
    private val reading = Mutex()
    private val wordBoxes = object : LinkedHashMap<Long, List<PdfWordBox>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<PdfWordBox>>?): Boolean = size > KEPT_LINES
    }

    /** Where each highlight was found (or wasn't: no stretches), by its id, with what it was found by. */
    private class Found(val locator: String?, val text: String, val ranges: List<PdfSentence>, val lines: List<MarkedLine>)

    private val found = HashMap<String, Found>()

    /** The words of [paragraph], as they are read aloud; null if there is no such paragraph. */
    suspend fun text(paragraph: Int): String? {
        if (paragraph !in 0 until layout.paragraphs) return null
        val file = layout.fileOf(paragraph)
        return reading.withLock { texts[file] ?: paragraphs(file).toMap().also { texts[file] = it } }[paragraph]
    }

    // ---------------------------------------------------------------------------------------------
    // Highlights
    // ---------------------------------------------------------------------------------------------

    /**
     * Finds [highlights], this PDF's, on its pages, and has them drawn. Each is looked for once:
     * by the words it keeps, on the page it says (see [PdfPageWords]), and on the pages either
     * side of that if they aren't there.
     */
    suspend fun show(highlights: List<Annotation>) {
        val pages = HashMap<List<Int>, PdfPageWords>()
        suspend fun wordsOn(which: List<Int>): PdfPageWords = pages[which] ?: run {
            val read = which.flatMap(layout::paragraphsOn).distinct().sorted().mapNotNull { paragraph -> text(paragraph)?.let { paragraph to it } }
            withContext(Dispatchers.Default) { PdfPageWords(read) }.also { pages[which] = it }
        }
        val marks = ArrayList<PdfDrawnMark>()
        for (highlight in highlights) {
            val known = found[highlight.id]?.takeIf { it.locator == highlight.locator && it.text == highlight.text } ?: run {
                val anchor = PdfAnchor.read(highlight.locator) ?: return@run null
                val page = anchor.page.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0))
                var ranges = withContext(Dispatchers.Default) { wordsOn(listOf(page)).find(highlight.text, anchor) }
                if (ranges.isEmpty()) {
                    val around = listOf(page - 1, page, page + 1).filter { it in 0 until layout.pageCount }
                    ranges = withContext(Dispatchers.Default) { wordsOn(around).find(highlight.text, anchor) }
                }
                Found(highlight.locator, highlight.text, ranges, joined(source.lines(layout, ranges))).also { found[highlight.id] = it }
            } ?: continue
            if (known.lines.isNotEmpty()) marks += PdfDrawnMark(highlight.id, Color(highlight.color.argb), highlight.note.isNotBlank(), known.ranges, known.lines)
        }
        found.keys.retainAll(highlights.mapTo(HashSet()) { it.id })
        drawn = marks
    }

    /** [highlight] was just made of [ranges], drawn on [lines]: it needn't be looked for. */
    fun made(highlight: Annotation, ranges: List<PdfSentence>, lines: List<MarkedLine>) {
        found[highlight.id] = Found(highlight.locator, highlight.text, ranges, lines)
    }

    /** Where on the pages the highlight [id] begins; null if it wasn't found on them. */
    fun placeOf(id: String): PdfPlace? = found[id]?.ranges?.firstOrNull()?.let { layout.placeOf(it.paragraph, it.from) }

    /** The highlight at ([x], [y]) of [page], in the page's units: the shortest of them, where one lies inside another. Null where there is none. */
    fun markAt(page: Int, x: Float, y: Float): String? = drawn
        .filter { mark -> mark.lines.any { it.page == page && x >= it.left - TAP_ROOM && x <= it.right + TAP_ROOM && y >= it.top - TAP_ROOM && y <= it.bottom + TAP_ROOM } }
        .minByOrNull { mark -> mark.ranges.sumOf { it.to - it.from } }?.id

    /** Where [ranges] are drawn: a box for each line. */
    suspend fun linesOf(ranges: List<PdfSentence>): List<MarkedLine> = joined(source.lines(layout, ranges))

    // ---------------------------------------------------------------------------------------------
    // Selecting
    // ---------------------------------------------------------------------------------------------

    /** Whether there are words at ([x], [y]) of [page]: a press and hold there selects one. */
    fun wordsAt(page: Int, x: Float, y: Float): Boolean = PdfSelecting.lineAt(layout.linesOn(page), x, y) != null

    /** A finger pressed and held at ([x], [y]) of [page]: the word there is selected. */
    fun press(page: Int, x: Float, y: Float) {
        dragging = true
        moves.trySend(Move.Press(page, x, y))
    }

    /** A finger took hold of [handle]. */
    fun grab(handle: SelectionHandle) {
        dragging = true
        moves.trySend(Move.Grab(handle))
    }

    /** The finger that pressed, or holds a handle, points at ([x], [y]) of [page] now. */
    fun moveTo(page: Int, x: Float, y: Float) {
        moves.trySend(Move.To(page, x, y))
    }

    /** The finger lifted: what is selected stays, and the menu comes up over it. */
    fun release() {
        moves.trySend(Move.Release)
    }

    /** Nothing is selected any more. */
    fun clear() {
        selection = null
        selected = emptyList()
        dragging = false
        moves.trySend(Move.Clear)
    }

    /** Where [handle] is on the pages; null while nothing is selected. */
    fun handlePoint(handle: SelectionHandle): HandlePoint? {
        val start = handle == SelectionHandle.START
        val line = (if (start) selected.firstOrNull() else selected.lastOrNull()) ?: return null
        // (Written left to right, the selection starts at its first line's left and ends at its last line's right.)
        val atLeft = if (start) !startsRightToLeft else endsRightToLeft
        return HandlePoint(line.page, if (atLeft) line.left else line.right, (line.top + line.bottom) / 2f, line.bottom, hangsLeft = atLeft)
    }

    /** Acts on what the finger does, one thing after another, for as long as the PDF's reader is open. */
    suspend fun run() {
        var waiting: Move? = null
        while (true) {
            var move = waiting ?: moves.receive()
            waiting = null
            // Of a run of the finger's moves, the last is where it is.
            while (move is Move.To) {
                val next = moves.tryReceive().getOrNull() ?: break
                if (next is Move.To) move = next else {
                    waiting = next
                    break
                }
            }
            try {
                act(move)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // (A page that couldn't be read just now: the finger's next move is tried afresh.)
            }
        }
    }

    private suspend fun act(move: Move) {
        when (move) {
            is Move.Press -> {
                held = null
                pressed = null
                val (word, text) = wordAt(move.page, move.x, move.y, nearest = false) ?: return
                val bare = PdfSelecting.bareWord(text, word.from until word.to)
                val chosen = PdfSentence(word.paragraph, bare.first, bare.last + 1)
                pressed = chosen
                select(PdfSelection(PdfSpot(chosen.paragraph, chosen.from), PdfSpot(chosen.paragraph, chosen.to)))
            }
            is Move.Grab -> {
                held = move.handle
                pressed = null
            }
            is Move.To -> {
                val (word, _) = wordAt(move.page, move.x, move.y, nearest = true) ?: return
                val now = selection
                val handle = held
                val from = pressed
                if (handle != null && now != null) {
                    val (next, atEnd) = now.handleAt(handle == SelectionHandle.END, word)
                    held = if (atEnd) SelectionHandle.END else SelectionHandle.START
                    if (next != now) select(next)
                } else if (from != null) {
                    PdfSelection.spanning(from, word).let { if (it != now) select(it) }
                }
            }
            Move.Release -> {
                held = null
                pressed = null
                dragging = false
            }
            Move.Clear -> {
                held = null
                pressed = null
                selection = null
                selected = emptyList()
            }
        }
    }

    /** The word at ([x], [y]) of [page], with its paragraph's words; with [nearest], the word nearest to there, wherever on the page that is. */
    private suspend fun wordAt(page: Int, x: Float, y: Float, nearest: Boolean): Pair<PdfSentence, String>? {
        val lines = layout.linesOn(page)
        val line = (if (nearest) PdfSelecting.lineNearest(lines, x, y) else PdfSelecting.lineAt(lines, x, y)) ?: return null
        val text = text(line.paragraph) ?: return null
        val key = (line.paragraph.toLong() shl 32) or line.start.toLong()
        val words = wordBoxes[key] ?: source.wordsOf(layout, line, text).also { wordBoxes[key] = it }
        val word = PdfSelecting.wordNearest(words, x) ?: return null
        return PdfSentence(line.paragraph, word.from, word.to) to text
    }

    private suspend fun select(next: PdfSelection) {
        val ranges = next.ranges(layout::lengthOf)
        // (More than anybody means to select: the handle has run away with the page.)
        if (ranges.isEmpty() || ranges.sumOf { it.to - it.from } > MOST_LETTERS) return
        val lines = joined(source.lines(layout, ranges))
        if (lines.isEmpty()) return
        val first = ranges.first()
        val last = ranges.last()
        startsRightToLeft = text(first.paragraph)?.let { words -> rightToLeft(words.substring(first.from.coerceIn(0, words.length), first.to.coerceIn(0, words.length))) } == true
        endsRightToLeft = text(last.paragraph)?.let { words -> rightToLeft(words.substring(last.from.coerceIn(0, words.length), last.to.coerceIn(0, words.length)).reversed()) } == true
        selection = next
        selected = lines
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    /**
     * Draws the highlights on [page], whose image is being drawn [width] units wide: a wash of
     * each one's colour over its lines, as a marker pen leaves, with a dotted line under those
     * that carry a note. On a white page ([paper]) the ink under it stays ink.
     */
    fun DrawScope.drawHighlights(page: Int, width: Float, paper: Boolean) {
        if (width <= 0f) return
        val scale = size.width / width
        for (mark in drawn) {
            for (line in mark.lines) {
                if (line.page != page) continue
                val left = (line.left - WORD_ROOM) * scale
                val top = (line.top - LINE_ROOM) * scale
                val right = (line.right + WORD_ROOM) * scale
                val bottom = (line.bottom + LINE_ROOM) * scale
                drawRect(mark.color, Offset(left, top), Size(right - left, bottom - top), alpha = WASH, blendMode = if (paper) BlendMode.Multiply else BlendMode.SrcOver)
                if (mark.noted) {
                    val thick = NOTE_LINE * scale
                    drawLine(mark.color, Offset(left, bottom), Offset(right, bottom), strokeWidth = thick, pathEffect = PathEffect.dashPathEffect(floatArrayOf(thick, thick)))
                }
            }
        }
    }

    /** Draws what is selected on [page] (drawn [width] units wide), in [tint]. */
    fun DrawScope.drawSelection(page: Int, width: Float, tint: Color) {
        if (width <= 0f) return
        val scale = size.width / width
        for (line in selected) {
            if (line.page != page) continue
            val left = (line.left - WORD_ROOM) * scale
            val top = (line.top - LINE_ROOM) * scale
            drawRect(tint, Offset(left, top), Size((line.right + WORD_ROOM) * scale - left, (line.bottom - line.top + 2 * LINE_ROOM) * scale), alpha = SELECTED)
        }
    }

    /**
     * Draws the selection's handles that are on [page] (drawn [width] units wide), in [tint]: a
     * drop hanging from each end of what is selected. The pages are drawn [zoom] times their
     * size; the handles stay the size of a fingertip whatever that is.
     */
    fun DrawScope.drawHandles(page: Int, width: Float, tint: Color, zoom: Float) {
        if (selection == null || width <= 0f) return
        val scale = size.width / width
        val radius = HANDLE_RADIUS.toPx() / zoom.coerceAtLeast(0.1f)
        for (handle in SelectionHandle.entries) {
            val at = handlePoint(handle)?.takeIf { it.page == page } ?: continue
            val x = at.x * scale
            val y = (at.bottom + LINE_ROOM) * scale
            val centre = Offset(if (at.hangsLeft) x - radius else x + radius, y + radius)
            drawCircle(tint, radius, centre)
            // Its corner is at the words.
            drawRect(tint, Offset(if (at.hangsLeft) centre.x else centre.x - radius, centre.y - radius), Size(radius, radius))
        }
    }

    companion object {
        /** How much room a mark takes around a line and its words, in the page's units (as the sentence being read aloud has). */
        const val LINE_ROOM = 1.5f
        const val WORD_ROOM = 1f

        /** A tap this near a highlight (in the page's units) is on it. */
        private const val TAP_ROOM = 2.5f

        /** The strength of a highlight's wash, and of the selection's; and how thick the line under a highlight with a note is, in the page's units. */
        private const val WASH = 0.42f
        private const val SELECTED = 0.3f
        private const val NOTE_LINE = 1.2f

        /** A handle's size on the screen. */
        val HANDLE_RADIUS = 11.dp

        private const val KEPT_LINES = 400
        private const val MOST_LETTERS = 60_000

        /** Boxes that lie side by side on one line, as one box. */
        private fun joined(lines: List<MarkedLine>): List<MarkedLine> {
            val out = ArrayList<MarkedLine>()
            for (line in lines) {
                val last = out.lastOrNull()
                if (last != null && last.page == line.page && last.top == line.top && last.bottom == line.bottom) {
                    out[out.lastIndex] = MarkedLine(last.page, minOf(last.left, line.left), last.top, maxOf(last.right, line.right), last.bottom, last.start, line.end)
                } else {
                    out += line
                }
            }
            return out
        }

        /** Whether [text] opens with writing that runs right to left (Arabic, Hebrew). */
        private fun rightToLeft(text: String): Boolean {
            for (c in text) {
                when (Character.getDirectionality(c)) {
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                }
            }
            return false
        }
    }
}

/**
 * Selecting words on a page with a finger: a press and hold where [wordsAt] says there are words
 * selects ([onPress]), and for as long as that finger stays down it goes on choosing ([onMove]);
 * a finger that comes down on a handle ([handleAt]) takes hold of it ([onGrab]) and drags it.
 * While it chooses, nothing else acts on the finger: the pages don't scroll under it, and lifting
 * it ([onRelease]) is no tap.
 */
internal suspend fun PointerInputScope.selectGestures(
    handleAt: (Offset) -> SelectionHandle?,
    wordsAt: (Offset) -> Boolean,
    onPress: (Offset) -> Unit,
    onGrab: (SelectionHandle, Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val handle = handleAt(down.position)
    if (handle != null) {
        down.consume()
        onGrab(handle, down.position)
    } else {
        if (!wordsAt(down.position)) return@awaitEachGesture
        // Held still, by one finger, for as long as a long press takes.
        val slop = viewConfiguration.touchSlop
        val gaveUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val finger = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size > 1 || finger == null || !finger.pressed || (finger.position - down.position).getDistance() > slop) break
            }
            true
        }
        if (gaveUp == true) return@awaitEachGesture
        onPress(down.position)
    }
    var at = down.position
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val finger = event.changes.firstOrNull { it.id == down.id }
        event.changes.fastForEach { it.consume() }
        if (finger == null || !finger.pressed) break
        if (finger.position != at) {
            at = finger.position
            onMove(at)
        }
    }
    onRelease()
}

/**
 * The reader's highlights on the pages of the PDF [book], and choosing words on them: has
 * [annotations]' highlights found and drawn, acts on what is chosen from the menu over selected
 * words, and gives the reader's controls the highlight made without selecting anything (see
 * [ReaderNav.highlightHere]). [readFrom] has the voice read on from a place in the PDF's words.
 */
@Composable
internal fun rememberPdfMarking(
    book: Book,
    nav: ReaderNav,
    aloud: PdfAloud,
    open: OpenBook,
    layout: PdfLayout,
    voice: ReadAloud,
    words: PdfWordsSource,
    annotations: List<Annotation>,
    haptics: Haptics,
    readFrom: (PdfSpot) -> Unit,
): PdfMarking {
    val context = LocalContext.current
    val library = context.library
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val marking = remember(open, layout, words) { PdfMarking(layout, words) { file -> open.pdfParagraphs(file) } }
    LaunchedEffect(marking) { marking.run() }
    val highlights = remember(annotations) { annotations.filter { it.kind == AnnotationKind.HIGHLIGHT } }
    LaunchedEffect(marking, highlights) { marking.show(highlights) }
    DisposableEffect(marking) {
        aloud.marking = marking
        onDispose { if (aloud.marking === marking) aloud.marking = null }
    }

    /** Highlights [ranges] (drawn on [lines], where that is known already) in [color], or in the colour last used. */
    suspend fun highlight(ranges: List<PdfSentence>, lines: List<MarkedLine>?, color: HighlightColor?): Annotation? {
        val first = ranges.firstOrNull() ?: return null
        val place = layout.placeOf(first.paragraph, first.from) ?: return null
        // The words, and a little of what stands either side of them: what it is found again by.
        val texts = (ranges.map { it.paragraph } + (first.paragraph - 1) + (ranges.last().paragraph + 1)).distinct().associateWith { marking.text(it) }
        val text = pdfWordsOf(ranges) { texts[it] }
        if (text.isEmpty()) return null
        val made = Annotation(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            kind = AnnotationKind.HIGHLIGHT,
            locator = PdfAnchor.of(place.page, ranges) { texts[it] }.write(),
            section = place.page,
            progress = layout.progressOf(place),
            chapter = layout.chapters.getOrNull(layout.chapterOf(first.paragraph))?.title.orEmpty(),
            text = text,
            color = color ?: library.state.value.annotations.lastOrNull { it.bookId == book.id && it.kind == AnnotationKind.HIGHLIGHT }?.color ?: HighlightColor.YELLOW,
            createdAt = System.currentTimeMillis(),
        )
        marking.made(made, ranges, lines ?: marking.linesOf(ranges))
        library.addAnnotation(made)
        return made
    }

    SideEffect {
        marking.onChoice = choice@{ choice ->
            val selection = marking.selection ?: return@choice
            val ranges = selection.ranges(layout::lengthOf)
            val lines = marking.selected
            marking.clear()
            scope.launch {
                val text = pdfWordsOf(ranges) { marking.text(it) }
                if (text.isEmpty()) return@launch
                when (choice) {
                    is SelectionChoice.Highlight -> {
                        haptics.confirm()
                        highlight(ranges, lines, choice.color)
                    }
                    SelectionChoice.Note -> {
                        haptics.confirm()
                        highlight(ranges, lines, null)?.let { nav.editing = it.id }
                    }
                    SelectionChoice.Copy -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(book.title, text))
                    // "Say it this way": how the voice is to say the word, or the name, selected.
                    SelectionChoice.Say -> SayIt.wordFrom(text).takeIf { it.isNotEmpty() }?.let {
                        haptics.tick()
                        nav.sayWord = it
                    }
                    SelectionChoice.Share -> runCatching {
                        val quote = if (book.author.isNotBlank()) {
                            resources.getString(R.string.reader_share_quote_author, text, book.title, book.author)
                        } else {
                            resources.getString(R.string.reader_share_quote, text, book.title)
                        }
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote)
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    SelectionChoice.Listen -> {
                        haptics.confirm()
                        readFrom(PdfSpot(ranges.first().paragraph, ranges.first().from))
                    }
                    is SelectionChoice.Process -> runCatching {
                        val info = choice.activity.activityInfo
                        context.startActivity(
                            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                                .setClassName(info.packageName, info.name)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        // A highlight chosen from the list: to its own line, not only its page, with the way back on offer.
        marking.goTo = goTo@{ mark ->
            val place = marking.placeOf(mark.id) ?: return@goTo false
            if (!aloud.inView(place)) {
                val from = aloud.top() ?: PdfPlace(nav.section, 0f)
                nav.cameFrom = Origin((layout.progressOf(from) * 100).toInt().coerceIn(0, 100), page = from.page + 1) { aloud.goTo(from) }
                nav.movedAt = SystemClock.uptimeMillis()
                aloud.show(place)
            }
            true
        }
    }

    // Highlights without selecting anything, for a screen reader and a keyboard, which can't press
    // and hold on the page: the sentence being read aloud, or else the first paragraph on the page.
    DisposableEffect(marking, voice) {
        suspend fun firstParagraph(): PdfSentence? {
            val spot = layout.spotAt(aloud.top() ?: PdfPlace(nav.section, 0f)) ?: return null
            if (layout.placeOf(spot.paragraph, spot.offset)?.let(aloud.inView) != true) return null
            // A heading is no paragraph: the first after it that isn't one, if that is in view too.
            var paragraph = spot.paragraph
            for (step in 0 until MOST_HEADINGS) {
                val text = marking.text(paragraph) ?: break
                val next = paragraph + 1
                if (!PdfSelecting.isHeading(text, layout.lineCountOf(paragraph)) || layout.placeOf(next)?.let(aloud.inView) != true) break
                paragraph = next
            }
            return PdfSentence(paragraph, 0, layout.lengthOf(paragraph))
        }
        nav.highlightHere = here@{ note ->
            val range = voice.pdfSentence?.takeIf { nav.listen.active } ?: firstParagraph() ?: return@here Marked.NOTHING
            val text = pdfWordsOf(listOf(range)) { marking.text(it) }
            val page = layout.placeOf(range.paragraph, range.from)?.page
            if (text.isEmpty() || page == null) return@here Marked.NOTHING
            // Marked already: that highlight is opened, to be given a note, another colour, or taken off.
            val marked = library.state.value.annotations.firstOrNull { old ->
                old.bookId == book.id && old.kind == AnnotationKind.HIGHLIGHT && old.text == text && PdfAnchor.read(old.locator)?.page == page
            }
            if (marked != null) {
                nav.editing = marked.id
                return@here Marked.ALREADY
            }
            val made = highlight(listOf(range), null, null) ?: return@here Marked.NOTHING
            if (note) nav.editing = made.id
            Marked.DONE
        }
        onDispose { nav.highlightHere = null }
    }
    return marking
}

/** How many headings in a row are passed over to get to "the first paragraph on this page". */
private const val MOST_HEADINGS = 3

/**
 * The menu over words selected on a PDF's page: the five colours to highlight them in, Note,
 * Copy, Share, "Say it this way", "Read aloud from here", and the phone's own text actions
 * (Translate, and the like), as the menu over an EPUB's selection has.
 *
 * It is put by the selection ([selectionInWindow] says where that is), and waits while a finger
 * is choosing or the pages are moving ([moving] is read for whatever tells that they are, and
 * for where they have got to): it comes back where the selection is then. A selection that has
 * been scrolled out of sight has no menu until it is back.
 */
@Composable
internal fun PdfSelectionMenu(marking: PdfMarking, haptics: Haptics, moving: () -> Pair<Boolean, Any>, selectionInWindow: () -> Rect?) {
    var at by remember(marking) { mutableStateOf<IntRect?>(null) }
    LaunchedEffect(marking) {
        snapshotFlow { Triple(marking.selected, marking.dragging, moving()) }.collectLatest { (selected, dragging, pages) ->
            at = null
            if (selected.isEmpty() || dragging || pages.first) return@collectLatest
            // (Once the pages have come to rest: a zoom springs on for a moment after the fingers lift.)
            delay(MENU_SETTLE_MS)
            withFrameNanos { }
            at = selectionInWindow()?.let { IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        }
    }
    val rect = at ?: return
    val context = LocalContext.current
    // The phone's own text actions, as the menu over an EPUB's selection lists them.
    val processors = remember {
        runCatching { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0) }
            .getOrDefault(emptyList()).filter { it.activityInfo.packageName != context.packageName }.take(MOST_PROCESSORS)
    }
    val density = LocalDensity.current
    val position = remember(rect, density) {
        with(density) { BySelection(rect, margin = 8.dp.roundToPx(), handle = (PdfMarking.HANDLE_RADIUS * 2).roundToPx() + 6.dp.roundToPx()) }
    }
    val choose: (SelectionChoice) -> Unit = { marking.onChoice(it) }
    // Not focusable: a finger outside it still reaches the page, and the handles under it.
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        val motion = MaterialTheme.motionScheme
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(6.dp).widthIn(min = 200.dp, max = 320.dp).width(IntrinsicSize.Max),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    val highlight = stringResource(R.string.selection_highlight)
                    Text(
                        highlight, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    // Each colour highlights at once, in that colour: a dot with a name, and a full-size target around it.
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        HighlightColor.entries.forEach { color ->
                            val name = stringResource(color.label)
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable(onClickLabel = highlight, role = Role.Button) { choose(SelectionChoice.Highlight(color)) }
                                    .semantics { contentDescription = name },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(Color(color.argb))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Choice(JacketIcons.Note, stringResource(R.string.selection_note)) { choose(SelectionChoice.Note) }
                    Choice(JacketIcons.Copy, stringResource(android.R.string.copy)) {
                        haptics.tick()
                        choose(SelectionChoice.Copy)
                    }
                    Choice(JacketIcons.Share, stringResource(R.string.selection_share)) { choose(SelectionChoice.Share) }
                    Choice(JacketIcons.Voice, stringResource(R.string.say_title)) { choose(SelectionChoice.Say) }
                    Choice(JacketIcons.Play, stringResource(R.string.selection_read_from_here)) { choose(SelectionChoice.Listen) }
                    processors.forEach { processor ->
                        val label = remember(processor) { processor.loadLabel(context.packageManager).toString() }
                        Choice(null, label) { choose(SelectionChoice.Process(processor)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector?, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = if (icon != null) { { Icon(icon, null) } } else null,
        onClick = onClick,
    )
}

/**
 * Puts the menu by the [selection] (in the window): above it where there is room, else below it
 * and its handles, else beside it; and failing all that at the foot of the window, over it.
 */
private class BySelection(private val selection: IntRect, private val margin: Int, private val handle: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val centred = (selection.center.x - width / 2).coerceIn(margin, (windowSize.width - width - margin).coerceAtLeast(margin))
        val above = selection.top - margin - height
        val below = selection.bottom + handle + margin
        val level = (selection.center.y - height / 2).coerceIn(margin, (windowSize.height - height - margin).coerceAtLeast(margin))
        return when {
            above >= margin -> IntOffset(centred, above)
            below + height <= windowSize.height - margin -> IntOffset(centred, below)
            selection.right + margin + width <= windowSize.width - margin -> IntOffset(selection.right + margin, level)
            selection.left - margin - width >= margin -> IntOffset(selection.left - margin - width, level)
            else -> IntOffset(centred, (windowSize.height - height - margin).coerceAtLeast(margin))
        }
    }
}

/** How long the pages must have been still before the menu comes back over the selection. */
private const val MENU_SETTLE_MS = 140L

/** No more of the phone's own text actions than this are listed (as over an EPUB's selection). */
private const val MOST_PROCESSORS = 3
