package io.github.friendlyglobal.jacket.reader

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastForEach
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.importer.PdfPlace
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.motionRemoved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Pages side by side, as an open book shows them: a spread at a time, both pages whole, turned
 * sideways like a book's (the other way for a book that reads right to left). Which pages go
 * together, and where they lie, is worked out in PagePairs.kt.
 *
 * Everything the one-page reader does is done here too, on either page: turning by tap, swipe,
 * keys and the volume keys; pinch and double-tap zoom (the spread as one); a PDF's highlights,
 * notes, search results, words chosen with a finger and its read-aloud mark, with the spread
 * turning when the voice reads on past it; the slider, the page numbers, "Back to…", and the
 * saved place. Only the spread in view is drawn (the next one is made as a finger brings it on),
 * so no more pictures are held than the pages on screen.
 */

@Composable
internal fun SpreadPages(
    rows: PageRows,
    aspects: FloatArray,
    kept: KeptPage,
    rightToLeft: Boolean,
    /** The fold of a phone held half-open like a book, in the window: the spine the two pages lie either side of. */
    spine: IntRect?,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
    aloud: PdfAloud?,
    held: () -> Boolean,
    render: suspend (index: Int, width: Int) -> Bitmap?,
) {
    val count = rows.pageCount
    val pager = rememberPagerState(initialPage = rows.rowOf(kept.page)) { rows.count }
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val turning = motion.defaultSpatialSpec<Float>()
    val zoom = remember(pager) { SpreadZoom(scope) }
    zoom.spring = motion.defaultSpatialSpec()
    val resources = LocalResources.current

    /** To spread [to], on the theme's spring (at once with animations removed); a spread zoomed into comes back whole. */
    fun turnTo(to: Int) {
        val target = to.coerceIn(0, rows.count - 1)
        if (target == pager.targetPage) return
        zoom.out()
        scope.launch { if (motionRemoved()) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = turning) }
    }

    LaunchedEffect(pager, nav) { snapshotFlow { pager.settledPage >= rows.count - 1 }.collect { nav.atEnd = it } }
    // Folded straight after a turn (before the place was saved), the one page opens on the spread that was in view.
    DisposableEffect(pager, rows) {
        onDispose {
            val page = rows.pageIn(pager.currentPage, kept.page)
            if (page != kept.page) kept.keep(page, 0f)
        }
    }
    SideEffect {
        val current = pager.currentPage
        val shown = rows.pages(current)
        val at = rows.pageIn(current, kept.page)
        nav.sectionCount = count
        nav.section = at
        nav.title = if (shown.first == shown.last) {
            resources.getString(R.string.reader_page_of, shown.first + 1, count)
        } else {
            resources.getString(R.string.reader_pages_of, shown.first + 1, shown.last + 1, count)
        }
        nav.footer = nav.title
        nav.sectionName = { resources.getString(R.string.reader_page_number, it + 1) }
        nav.jumpTo = { target ->
            if (target in 0 until count) {
                val to = rows.rowOf(target)
                if (to != current) {
                    // Further than the next spread: worth a way back.
                    if (abs(to - current) > 1) {
                        val offset = kept.offset.takeIf { kept.page == at } ?: 0f
                        nav.cameFrom = Origin(at * 100 / (count - 1).coerceAtLeast(1), page = at + 1) {
                            kept.keep(at, offset)
                            scope.launch { pager.scrollToPage(current) }
                        }
                    }
                    kept.keep(target, 0f)
                    turnTo(to)
                }
            }
        }
        nav.pageKey = shown.first
        nav.place = {
            Place(null, at, if (count > 1) at / (count - 1f) else 0f, "", resources.getString(R.string.reader_page_number, at + 1))
        }
        nav.onScreen = { mark -> mark.section in shown }
        nav.goTo = { mark -> if (aloud?.marking?.goTo?.invoke(mark) != true) nav.jumpTo(mark.section) }
        nav.turnPage = { delta -> turnTo(pager.targetPage + delta) }
        nav.canTurn = { delta -> pager.targetPage + delta in 0 until rows.count }
        // A comic's previous and next are a spread's (a PDF's are its chapters', or the voice's: see PdfAloud).
        if (aloud == null) {
            nav.step = { nav.turnPage(it) }
            nav.canStep = { nav.canTurn(it) }
        }
    }
    SpreadTicks(pager, haptics)
    TrackSpreads(pager, rows, kept, held, onProgress)

    val frames = remember(pager, rows) { PageFrames { rows.pages(pager.currentPage).toList() } }
    val density = LocalDensity.current
    val words = remember(pager, aloud, density) { PageWords(aloud, frames, density) }
    val viewport = remember(pager) { arrayOf(Size(1f, 1f)) }
    if (aloud != null) FollowVoiceBySpread(pager, rows, kept, aloud, nav, frames, zoom, words, viewport, turning) { turnTo(it) }
    val marking = aloud?.marking

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                zoom.size = Size(it.width.toFloat().coerceAtLeast(1f), it.height.toFloat().coerceAtLeast(1f))
                viewport[0] = zoom.size
            }
            .onGloballyPositioned { words.origin[0] = it.positionInWindow() }
            // Choosing words comes first: a finger that is selecting doesn't turn, zoom or tap.
            .chooseWords(words, haptics)
            .pointerInput(zoom) { spreadZoomGestures(zoom) }
            .tapZones(
                onPrevious = { nav.turnPage(-1) },
                onNext = { nav.turnPage(1) },
                onCenter = onCenterTap,
                onDoubleTap = { at ->
                    haptics.tick()
                    zoom.toggle(at)
                },
                onPage = { at -> words.tap(at, haptics, nav) },
                rightToLeft = rightToLeft,
            ),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val layout = remember(widthPx, heightPx, density, rightToLeft, spine) {
            PairLayout(widthPx, heightPx, density.density, rightToLeft, spine?.left?.toFloat(), spine?.right?.toFloat())
        }
        // The pages lie as a book's whichever language the phone is in: a book's direction is its own.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.panX
                    translationY = zoom.panY
                },
                // A book that reads right to left turns the other way: the next spread comes in from the left.
                reverseLayout = rightToLeft,
                beyondViewportPageCount = 0,
                // Zoomed in, a finger moves about the spread instead.
                userScrollEnabled = !zoom.zoomed,
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = turning),
                key = { rows.first(it) },
            ) { row ->
                Box(Modifier.fillMaxSize()) {
                    for (box in layout.row(rows.pages(row)) { aspects.getOrElse(it) { 0f } }) {
                        val width = box.width.roundToInt().coerceAtLeast(1)
                        val height = box.height.roundToInt().coerceAtLeast(1)
                        val sized = with(density) {
                            Modifier.absoluteOffset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }.size(width.toDp(), height.toDp())
                        }
                        PageImage(box.page, width, width / height.toFloat(), page, render, aloud, frames, { zoom.scale }, sized, ownSize = false) { nav.contentReady = true }
                    }
                }
            }
        }
        // The menu over words that are selected: by them, wherever the spread has got to.
        if (marking != null) {
            PdfSelectionMenu(
                marking, haptics,
                moving = { pager.isScrollInProgress to listOf(zoom.scale, zoom.panX, zoom.panY, pager.currentPage) },
                selectionInWindow = { words.selectionInWindow(Size(widthPx, heightPx)) },
            )
        }
    }
}

/**
 * Zoom for a spread: pinch, or double-tap to zoom in on a spot and again to step back. The spread
 * is scaled as one sheet, and a finger moves about it while zoomed in, kept within it. Let go past
 * either limit and it springs back. Turned, a zoomed spread comes back whole: the next one opens
 * as it is.
 *
 * The scale and pan are only read by the layer that draws the pages: zooming recomposes nothing.
 */
@Stable
internal class SpreadZoom(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set
    var size = Size(1f, 1f)
    var spring: AnimationSpec<Float> = spring()
    private var job: Job? = null

    val zoomed: Boolean get() = scale > 1.01f

    /** The furthest the sheet can sit left and up at [atScale] without showing past its far edges. */
    private fun minX(atScale: Float) = (size.width - size.width * atScale).coerceAtMost(0f)
    private fun minY(atScale: Float) = (size.height - size.height * atScale).coerceAtMost(0f)
    private val middle: Offset get() = Offset(size.width / 2f, size.height / 2f)

    /** Two fingers: zoom about their midpoint and move with it. A little past the limits is allowed, and resisted. */
    fun pinch(centroid: Offset, zoom: Float, pan: Offset) {
        job?.cancel()
        val target = (scale * zoom).coerceIn(MIN_ZOOM * 0.75f, MAX_ZOOM * 1.3f)
        panX = centroid.x - (centroid.x - panX) * target / scale + pan.x
        panY = centroid.y - (centroid.y - panY) * target / scale + pan.y
        scale = target
    }

    /** One finger, zoomed in: the sheet moves with it. */
    fun drag(by: Offset) {
        job?.cancel()
        panX = (panX + by.x).coerceIn(minX(scale), 0f)
        panY = (panY + by.y).coerceIn(minY(scale), 0f)
    }

    /** Fingers up: back within the limits, on a spring. */
    fun settle() {
        val target = scale.coerceIn(MIN_ZOOM, MAX_ZOOM).let { if (it < 1.04f) 1f else it }
        if (target != scale || panX !in minX(target)..0f || panY !in minY(target)..0f) glide(target, middle)
    }

    /** Double tap: in on that spot, or back out to the whole spread. */
    fun toggle(at: Offset) = glide(if (zoomed) 1f else DOUBLE_TAP_ZOOM, at)

    /** Back to the whole spread, for one being turned. */
    fun out() {
        if (scale != 1f || panX != 0f || panY != 0f) glide(1f, middle)
    }

    /**
     * Zoomed in, moves the sheet so that [line] (where it is drawn now, in the spread's own box)
     * is in sight: a line being read aloud, kept in the upper part of the screen as it is on one page.
     */
    fun bring(line: Rect) {
        if (!zoomed) return
        val high = line.top < size.height * LINE_NO_HIGHER || line.bottom > size.height * LINE_NO_LOWER
        val across = line.left < 0f || line.right > size.width
        if (!high && !across) return
        val dy = if (high) size.height * LINE_AT - line.top else 0f
        val dx = if (across) (if (line.width < size.width) (size.width - line.width) / 2f - line.left else -line.left) else 0f
        val fromX = panX
        val fromY = panY
        val toX = (panX + dx).coerceIn(minX(scale), 0f)
        val toY = (panY + dy).coerceIn(minY(scale), 0f)
        job?.cancel()
        job = scope.launch {
            if (motionRemoved()) {
                panX = toX
                panY = toY
                return@launch
            }
            animate(0f, 1f, animationSpec = spring) { fraction, _ ->
                panX = fromX + (toX - fromX) * fraction
                panY = fromY + (toY - fromY) * fraction
            }
        }
    }

    private fun glide(target: Float, focal: Offset) {
        job?.cancel()
        job = scope.launch {
            val from = scale
            val startX = panX
            val startY = panY
            val endX = (focal.x - (focal.x - startX) * target / from).coerceIn(minX(target), 0f)
            val endY = (focal.y - (focal.y - startY) * target / from).coerceIn(minY(target), 0f)
            if (motionRemoved()) {
                scale = target
                panX = endX
                panY = endY
                return@launch
            }
            animate(0f, 1f, animationSpec = spring) { fraction, _ ->
                // (The spring runs a little past where it is going: never so far as to turn the sheet inside out.)
                scale = (from + (target - from) * fraction).coerceAtLeast(0.5f)
                panX = startX + (endX - startX) * fraction
                panY = startY + (endY - startY) * fraction
            }
        }
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}

/** Feeds pinches, and drags while zoomed in, to [zoom]. Everything else is left to the spreads (a swipe turns them). */
private suspend fun PointerInputScope.spreadZoomGestures(zoom: SpreadZoom) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var pinched = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val fingers = event.changes.count { it.pressed }
        if (fingers == 0) break
        if (fingers >= 2) {
            pinched = true
            zoom.pinch(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan())
            // The spreads must not turn under a pinch.
            event.changes.fastForEach { it.consume() }
        } else if (!pinched && zoom.zoomed) {
            event.changes.fastForEach {
                val moved = it.positionChange()
                if (it.pressed && moved != Offset.Zero) {
                    zoom.drag(moved)
                    // (A finger that moved about the spread was no tap.)
                    it.consume()
                }
            }
        }
    }
    zoom.settle()
}

/**
 * The spreads follow the voice: when it reads on past the spread in view, the next one is turned
 * to (on the theme's spring, or at once with animations removed). Zoomed in, the sheet moves to
 * keep the line being read in sight. The reader's place goes with the voice, so a phone folded
 * mid-sentence opens its one page where the voice is.
 *
 * A listener who turns the spreads themselves has gone to look at something: the spreads stop
 * following, and the player offers the way back to the voice.
 */
@Composable
private fun FollowVoiceBySpread(
    pager: PagerState,
    rows: PageRows,
    kept: KeptPage,
    aloud: PdfAloud,
    nav: ReaderNav,
    frames: PageFrames,
    zoom: SpreadZoom,
    words: PageWords,
    viewport: Array<Size>,
    turning: AnimationSpec<Float>,
    turnTo: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(turnTo)
    SideEffect {
        aloud.top = {
            val row = pager.currentPage
            if (kept.page in rows.pages(row)) PdfPlace(kept.page, kept.offset) else PdfPlace(rows.first(row), 0f)
        }
        aloud.goTo = { place ->
            kept.keep(place.page, place.within)
            turn(rows.rowOf(place.page))
        }
        // To a line of a page (a search result, a highlight): its spread, at once, as a jump goes.
        aloud.show = { place ->
            kept.keep(place.page, place.within)
            zoom.out()
            scope.launch { pager.scrollToPage(rows.rowOf(place.page)) }
        }
        aloud.inView = { place ->
            when {
                place.page !in rows.pages(pager.currentPage) -> false
                !zoom.zoomed -> true
                else -> {
                    // Zoomed in, only what is in sight.
                    val frame = frames.of(place.page)
                    val window = Rect(words.origin[0], viewport[0])
                    frame != null && (frame.top + place.within * frame.height) in window.top..window.bottom && frame.right > window.left && frame.left < window.right
                }
            }
        }
        aloud.lineInWindow = { line -> frames.lineInWindow(line, aloud.layout?.widthOf(line.page) ?: 0f) }
    }
    LaunchedEffect(pager, aloud) {
        // (A reader that opens on a voice already reading is put at the book's place first: that is not the listener moving.)
        aloud.followedAt = SystemClock.uptimeMillis()
        snapshotFlow { Triple(aloud.line(), aloud.listen.offPage, aloud.calls) }.collectLatest { (line, elsewhere, _) ->
            if (line == null || elsewhere || !aloud.listen.active) return@collectLatest
            val height = aloud.layout?.heightOf(line.page)?.takeIf { it > 0f } ?: return@collectLatest
            kept.keep(line.page, line.top / height)
            val to = rows.rowOf(line.page)
            if (to == pager.targetPage) {
                // On the spread already: zoomed in, the line is kept in sight.
                if (zoom.zoomed) aloud.lineInWindow(line)?.let { zoom.bring(it.translate(-words.origin[0])) }
                return@collectLatest
            }
            aloud.following = true
            try {
                aloud.followedAt = SystemClock.uptimeMillis()
                zoom.out()
                if (motionRemoved()) pager.scrollToPage(to) else pager.animateScrollToPage(to, animationSpec = turning)
            } finally {
                aloud.followedAt = SystemClock.uptimeMillis()
                aloud.following = false
            }
        }
    }
    LaunchedEffect(pager, aloud) {
        snapshotFlow { pager.settledPage }.drop(1).collect {
            val now = SystemClock.uptimeMillis()
            val moved = !aloud.following && now - aloud.followedAt > FOLLOW_SETTLE_MS
            if (moved) nav.movedAt = now
            if (moved && aloud.listen.active && !aloud.listen.offPage) aloud.listen.offPage = true
        }
    }
}

/** A light tick each time a new spread comes into view. A fling through many gets a few, spaced out, rather than a buzz. */
@Composable
private fun SpreadTicks(pager: PagerState, haptics: Haptics) {
    LaunchedEffect(pager) {
        var previous = -1
        var tickedAt = 0L
        snapshotFlow { pager.currentPage }.collect { row ->
            val now = SystemClock.uptimeMillis()
            if (previous >= 0 && row != previous && now - tickedAt >= TICK_GAP_MS) {
                haptics.tick()
                tickedAt = now
            }
            previous = row
        }
    }
}

/**
 * Turns the spread in view into a saved place, once it has settled: the page the reader's place
 * is on (see [PageRows.pageIn]: the first page of the spread, unless the place was on its second
 * already), and how far down it, which is the top unless it was somewhere else already. The book's
 * progress is that page, or the whole book on the last spread.
 *
 * As on one page, with each place goes whether the reader went back through the book to get to it
 * (see [ScrolledPlace]), and nothing is saved while the place is [held] by the voice.
 */
@Composable
private fun TrackSpreads(pager: PagerState, rows: PageRows, kept: KeptPage, held: () -> Boolean, onProgress: ProgressSink) {
    val sink by rememberUpdatedState(onProgress)
    val holds by rememberUpdatedState(held)
    LaunchedEffect(pager, rows) {
        val place = ScrolledPlace()
        // The spread the book opens on holds its place.
        place.at(kept.page, kept.offset)
        snapshotFlow { pager.settledPage }.collectLatest { row ->
            delay(300)
            if (holds()) {
                // Wherever the spreads are when the place is the reader's again is where it is then.
                place.shownAgain()
                return@collectLatest
            }
            val page = rows.pageIn(row, kept.page)
            if (page != kept.page) kept.keep(page, 0f)
            val offset = kept.offset
            val progress = if (row >= rows.count - 1) 1f else ((page + offset) / rows.pageCount).coerceIn(0f, 1f)
            sink(page, offset, progress, rows.pageCount, place.wentBackTo(page, offset))
        }
    }
}
