package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.graphics.get
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.PdfPlace
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.voice.Respelling
import io.github.friendlyglobal.jacket.importer.Comic
import io.github.friendlyglobal.jacket.importer.DocumentCloser
import io.github.friendlyglobal.jacket.importer.Pdf
import io.github.friendlyglobal.jacket.importer.PdfDocument
import io.github.friendlyglobal.jacket.importer.PdfLocked
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.PageColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Opens a document off the main thread for as long as the caller is composed. It is closed even
 * if the reader goes away while the document is still opening.
 */
@Composable
private fun <T : Closeable> rememberDocument(key: Any, open: () -> T): Result<T>? {
    val doc by produceState<Result<T>?>(null, key) {
        // (Opened anew for another key: what the one before came to says nothing of this one.)
        value = null
        val opened = AtomicReference<T?>()
        try {
            value = withContext(Dispatchers.IO) { runCatching { open().also(opened::set) } }
            awaitCancellation()
        } finally {
            opened.get()?.close()
        }
    }
    return doc
}

/**
 * Left and right edges page back and forward ([rightToLeft]: forward and back, as a manga turns);
 * the middle toggles the reader controls. With [onDoubleTap] (pages that zoom), a tap waits a
 * moment to see whether a second one follows.
 */
@Composable
internal fun Modifier.tapZones(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCenter: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)? = null,
    /** Asked first: a tap that was on something of the page's own (a sentence, while it is read aloud) is that, and nothing else. */
    onPage: (Offset) -> Boolean = { false },
    rightToLeft: Boolean = false,
): Modifier {
    val backward by rememberUpdatedState(rightToLeft)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val center by rememberUpdatedState(onCenter)
    val double by rememberUpdatedState(onDoubleTap)
    val taken by rememberUpdatedState(onPage)
    return pointerInput(onDoubleTap != null) {
        detectTapGestures(
            onDoubleTap = if (onDoubleTap != null) { at -> double?.invoke(at) } else null,
            onTap = { offset ->
                val x = offset.x / size.width
                when {
                    taken(offset) -> {}
                    x < 0.28f -> if (backward) next() else previous()
                    x > 0.72f -> if (backward) previous() else next()
                    else -> center()
                }
            },
        )
    }
}

/**
 * Zoom for pages that are pictures (PDF, comics): pinch, or double-tap to zoom in on a spot and
 * again to step back. The whole column of pages is scaled as one sheet, so up-and-down stays the
 * list's own scrolling and fling; sideways is a pan, kept within the page. Let go past either
 * limit and it springs back.
 *
 * The scale and pan are only read by the layer that draws the pages: zooming recomposes nothing.
 */
@Stable
private class PageZoom(private val scope: CoroutineScope, private val list: LazyListState) {
    var scale by mutableFloatStateOf(1f)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var width = 1f
    var spring: AnimationSpec<Float> = spring()
    private var job: Job? = null

    val zoomed: Boolean get() = scale > 1.01f

    /** The furthest left the sheet can sit at [atScale] without showing past its right edge. */
    private fun minPan(atScale: Float) = (width - width * atScale).coerceAtMost(0f)

    /** Scales to [target] keeping the page under [focal] where it is, scrolling the list to match. */
    private fun zoomTo(target: Float, focal: Offset) {
        val before = scale
        panX = focal.x - (focal.x - panX) * target / before
        // Up and down belongs to the list: move it by what the focal point would have shifted.
        list.dispatchRawDelta(focal.y * (1f / before - 1f / target))
        scale = target
    }

    /** Two fingers: zoom about their midpoint and pan with it. A little past the limits is allowed, and resisted. */
    fun pinch(centroid: Offset, zoom: Float, pan: Offset) {
        job?.cancel()
        zoomTo((scale * zoom).coerceIn(MIN_ZOOM * 0.75f, MAX_ZOOM * 1.3f), centroid)
        panX += pan.x
        list.dispatchRawDelta(-pan.y / scale)
    }

    /** One finger, zoomed in: sideways moves the sheet (the list takes care of up and down). */
    fun drag(dx: Float) {
        job?.cancel()
        panX = (panX + dx).coerceIn(minPan(scale), 0f)
    }

    /** Fingers up: back within the limits, on a spring. */
    fun settle() {
        val target = scale.coerceIn(MIN_ZOOM, MAX_ZOOM).let { if (it < 1.04f) 1f else it }
        if (target != scale || panX !in minPan(target)..0f) glide(target, Offset(width / 2f, list.layoutInfo.viewportSize.height / 2f))
    }

    /** Double tap: in on that spot, or back out to the whole page. */
    fun toggle(at: Offset) = glide(if (zoomed) 1f else DOUBLE_TAP_ZOOM, at)

    private fun glide(target: Float, focal: Offset) {
        job?.cancel()
        job = scope.launch {
            val from = scale
            val startPan = panX
            val endPan = (focal.x - (focal.x - startPan) * target / from).coerceIn(minPan(target), 0f)
            animate(0f, 1f, animationSpec = spring) { fraction, _ ->
                val step = (from + (target - from) * fraction).coerceAtLeast(0.5f)
                list.dispatchRawDelta(focal.y * (1f / scale - 1f / step))
                scale = step
                panX = startPan + (endPan - startPan) * fraction
            }
        }
    }

    private companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}

/** Feeds pinches, and sideways drags while zoomed in, to [zoom]. Everything else is left to the list. */
private suspend fun PointerInputScope.zoomGestures(zoom: PageZoom) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var pinched = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val fingers = event.changes.count { it.pressed }
        if (fingers == 0) break
        if (fingers >= 2) {
            pinched = true
            zoom.pinch(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan())
            // The list must not scroll or fling under a pinch.
            event.changes.fastForEach { it.consume() }
        } else if (!pinched && zoom.zoomed) {
            // Not consumed: the list scrolls with the same finger's up-and-down.
            event.changes.fastForEach { if (it.pressed) zoom.drag(it.positionChange().x) }
        }
    }
    zoom.settle()
}

@Composable
fun PdfReader(
    book: Book,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    /** The book's bookmarks, highlights and notes: its highlights are drawn on its pages. */
    annotations: List<Annotation>,
    /** "Say it this way": how the voice is to say words, in this book and in every book. */
    respellings: List<Respelling>,
    /** The fold of a phone held half-open like a book, in the window: the spine between two pages side by side. */
    spine: IntRect?,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
) {
    val library = LocalContext.current.library
    // A PDF locked with a password is tried with the one Jacket has for it, then with what the
    // reader types (see PdfLock.kt); any other PDF opens as it always did.
    var typed by remember(book.id) { mutableStateOf<PdfAttempt?>(null) }
    val result = rememberDocument(book.id to typed) { library.openPdf(book, typed) }
    val failure = result?.exceptionOrNull()
    when {
        result == null -> ReaderLoading()
        // Locked: the password is asked for, where this phone's Android can open a locked PDF at all.
        failure is PdfLocked ->
            if (Pdf.canUnlock) LockedPdf(failure.wrong, haptics) { typed = it } else ReaderError(stringResource(R.string.pdf_locked_unsupported))
        failure != null -> ReaderError(stringResource(R.string.reader_error_pdf))
        else -> {
            val pdf = result.getOrThrow()
            // Read aloud by the app's voice, like any other book (see PdfAloud): the pages turn
            // with it and mark the sentence it is on.
            val aloud = rememberPdfAloud(book, settings, page, nav, respellings, annotations, haptics)
            // While the voice speaks, the book's place is the sentence it is on, which the voice
            // saves: the page that happens to be in view (moved there to follow it) isn't saved over it.
            val held = { aloud.holdsThePlace || SystemClock.uptimeMillis() - aloud.followedAt <= FOLLOW_SETTLE_MS }
            val shown = PageShowing(book.rightToLeft ?: false, settings.sideBySide, spine, pdf::aspects)
            PagedList(pdf.pageCount, book.position, book.positionOffset, pdf.firstPageAspect, shown, page, nav, haptics, onProgress, onCenterTap, aloud, held) { index, width ->
                pdf.render(index, width)
            }
        }
    }
}

private class ComicDocument(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val lock = Mutex()
    private var closed = false
    val pages = Comic.pageNames(zip)

    /** The comic says it is read right to left (manga, in its ComicInfo.xml). */
    val rightToLeft: Boolean = runCatching { Comic.info(zip)?.rightToLeft == true }.getOrDefault(false)

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = lock.withLock {
            check(!closed) { "Document closed" }
            zip.getInputStream(zip.getEntry(pages[index])).use { it.readBytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * How wide each page is for its height (0 where that can't be told): what pages side by side
     * are laid out by. Only the start of each picture is read, where its size is written.
     */
    suspend fun aspects(): FloatArray = withContext(Dispatchers.IO) {
        FloatArray(pages.size) { index ->
            lock.withLock {
                check(!closed) { "Document closed" }
                runCatching { zip.getInputStream(zip.getEntry(pages[index])).use(Comic::aspectOf) }.getOrDefault(0f)
            }
        }
    }

    override fun close() {
        DocumentCloser.launch {
            lock.withLock {
                closed = true
                runCatching { zip.close() }
            }
        }
    }
}

@Composable
fun ComicReader(
    book: Book,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    /** The fold of a phone held half-open like a book, in the window: the spine between two pages side by side. */
    spine: IntRect?,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
) {
    val library = LocalContext.current.library
    val result = rememberDocument(book.id) { ComicDocument(library.fileOf(book)) }
    when {
        result == null -> ReaderLoading()
        result.isFailure || result.getOrThrow().pages.isEmpty() -> ReaderError(stringResource(R.string.reader_error_comic))
        else -> {
            val comic = result.getOrThrow()
            // Right to left as the reader chose for this book, or else as the comic says of itself.
            val shown = PageShowing(book.rightToLeft ?: comic.rightToLeft, settings.sideBySide, spine, comic::aspects)
            PagedList(comic.pages.size, book.position, book.positionOffset, 0.66f, shown, page, nav, haptics, onProgress, onCenterTap) { index, width ->
                comic.render(index, width)
            }
        }
    }
}

/**
 * How a book of pages is to be shown: whether it reads [rightToLeft], whether two pages may be
 * shown [sideBySide] where the window is wide enough, the [spine] of a phone held half-open, and
 * how to learn the pages' [shapes] (each one's width for its height), which pages side by side
 * are laid out by.
 */
internal class PageShowing(
    val rightToLeft: Boolean,
    val sideBySide: Boolean,
    val spine: IntRect?,
    val shapes: suspend () -> FloatArray,
)

/**
 * A book of pages, as the window allows: two side by side where it is wide enough for that and
 * the reader hasn't switched it off (see [SideBySide] and PageSpreads.kt), else one after another,
 * scrolled through. The reader's place ([KeptPage]) is kept apart from either, so folding,
 * unfolding or switching from one to the other keeps it.
 *
 * Which pages go together hangs on every page's shape (a double-page scan stands alone, and moves
 * the pairs after it on by one), so the shapes are learnt once the window could show two. Until
 * they are known, the book opens as soon as they are (or after [SHAPES_WAIT_MS], one page at a
 * time); a book already open stays as it is until then.
 */
@Composable
private fun PagedList(
    count: Int,
    initial: Int,
    /** How far down the page at [initial] the top of the screen was (0..1). */
    initialOffset: Float,
    placeholderAspect: Float,
    shown: PageShowing,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
    /** A PDF's read-aloud: the pages follow the voice and mark its sentence. Null for pages that are never read aloud (a comic's). */
    aloud: PdfAloud? = null,
    /** The place is somebody else's just now (the voice's, while it reads): the page in view isn't saved as the reader's. */
    held: () -> Boolean = { false },
    render: suspend (index: Int, width: Int) -> Bitmap?,
) {
    val kept = remember { KeptPage(initial.coerceIn(0, (count - 1).coerceAtLeast(0)), initialOffset.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) }
    var shapes by remember { mutableStateOf<FloatArray?>(null) }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SHAPES_WAIT_MS)
        waited = true
    }
    val rightToLeft = shown.rightToLeft
    SideEffect { nav.rightToLeft = rightToLeft }
    // Back takes the selection off before it does anything else.
    val marking = aloud?.marking
    BackHandler(enabled = marking?.selection != null) { marking?.clear() }
    // A tap waits to see if it's a double tap; a little less long than the system's own patience,
    // so the page edges still answer briskly.
    val platform = LocalViewConfiguration.current
    val brisk = remember(platform) {
        object : ViewConfiguration by platform {
            override val doubleTapTimeoutMillis: Long get() = minOf(platform.doubleTapTimeoutMillis, 250L)
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides brisk) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wanted = shown.sideBySide && SideBySide.mayFit(maxWidth.value)
            LaunchedEffect(wanted) {
                if (wanted && shapes == null) shapes = runCatching { shown.shapes() }.getOrNull() ?: FloatArray(0)
            }
            val aspects = shapes?.takeIf { it.size == count }
            val rows = remember(aspects) { aspects?.let(PageRows::paired) }
            val typical = remember(aspects) { aspects?.let(SideBySide::typical) ?: 0f }
            val paired = wanted && aspects != null && rows != null && SideBySide.fits(maxWidth.value, maxHeight.value, typical)
            SideEffect {
                nav.sideBySide = paired
                // A mouse wheel turns two pages side by side, as it turns any book of pages; it scrolls one after another.
                nav.paged = paired
            }
            when {
                paired -> SpreadPages(rows!!, aspects!!, kept, rightToLeft, shown.spine, page, nav, haptics, onProgress, onCenterTap, aloud, held, render)
                // (Nothing yet: the book's cover is still over the page while it opens.)
                wanted && shapes == null && !nav.contentReady && !waited -> Box(Modifier.fillMaxSize())
                else -> ScrolledPages(count, kept, placeholderAspect, rightToLeft, page, nav, haptics, onProgress, onCenterTap, aloud, held, render)
            }
        }
    }
}

/** How long a book that could open with pages side by side waits for its pages' shapes before it opens one page at a time. */
private const val SHAPES_WAIT_MS = 1_500L

/** One page after another, as wide as the window (to a point), scrolled through: as on any phone. */
@Composable
private fun ScrolledPages(
    count: Int,
    kept: KeptPage,
    placeholderAspect: Float,
    rightToLeft: Boolean,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
    aloud: PdfAloud?,
    held: () -> Boolean,
    render: suspend (index: Int, width: Int) -> Bitmap?,
) {
    val start = remember { kept.page to kept.offset }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start.first)
    val scope = rememberCoroutineScope()
    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    // The last page is on screen when the list can't scroll any further (pages are often shorter
    // than the screen, so the last one never becomes the first visible).
    LaunchedEffect(listState, nav) { snapshotFlow { listState.atEnd() }.collect { nav.atEnd = it } }
    // Shown another way (unfolded, say), the pages open where these were.
    DisposableEffect(listState) {
        onDispose {
            if (listState.layoutInfo.totalItemsCount > 0) listState.topOfScreen().let { (at, fraction) -> kept.keep(at, fraction) }
        }
    }
    val resources = LocalResources.current
    SideEffect {
        nav.sectionCount = count
        nav.section = first
        nav.title = resources.getString(R.string.reader_page_of, first + 1, count)
        nav.footer = nav.title
        nav.sectionName = { resources.getString(R.string.reader_page_number, it + 1) }
        nav.jumpTo = { target ->
            if (target in 0 until count) {
                // Further than the next page: worth a way back.
                if (abs(target - first) > 1) {
                    nav.cameFrom = Origin(first * 100 / (count - 1).coerceAtLeast(1), page = first + 1) { scope.launch { listState.scrollToItem(first) } }
                }
                scope.launch { listState.animateScrollToItem(target) }
            }
        }
        nav.pageKey = first
        nav.place = {
            Place(null, first, if (count > 1) first / (count - 1f) else 0f, "", resources.getString(R.string.reader_page_number, first + 1))
        }
        nav.onScreen = { mark -> mark.section == first }
        // (A highlight that was found on the pages is gone to where it is on its page.)
        nav.goTo = { mark -> if (aloud?.marking?.goTo?.invoke(mark) != true) nav.jumpTo(mark.section) }
        nav.turnPage = { delta -> nav.jumpTo(first + delta) }
        nav.canTurn = { delta -> first + delta in 0 until count }
        // A comic's previous and next are a page's (a PDF's are its chapters', or the voice's: see PdfAloud).
        if (aloud == null) {
            nav.step = { nav.turnPage(it) }
            nav.canStep = { nav.canTurn(it) }
        }
    }
    PageTicks(listState, haptics)

    val zoom = remember(listState) { PageZoom(scope, listState) }
    zoom.spring = MaterialTheme.motionScheme.defaultSpatialSpec()
    TrackProgress(listState, count, start.first, start.second, kept, held, { zoom.scale }, onProgress)
    // Where each page in view is in the window, and where the pages' own box is: for what is
    // asked in the window's terms (a tap on a line, where the line being read is, the words under
    // a finger). Not state: nothing is laid out again for it.
    val frames = remember(listState) { PageFrames { listState.layoutInfo.visibleItemsInfo.map { it.index } } }
    val density = LocalDensity.current
    val words = remember(listState, aloud, density) { PageWords(aloud, frames, density) }
    if (aloud != null) FollowVoice(listState, aloud, nav, frames) { zoom.scale }
    // Highlights and choosing words, on a PDF whose words are to hand (see PdfHighlights.kt).
    val marking = aloud?.marking
    // A handle dragged to the top or the foot of the screen takes the pages with it.
    if (marking != null) {
        LaunchedEffect(marking, marking.dragging) {
            if (!marking.dragging) {
                words.finger[0] = null
                return@LaunchedEffect
            }
            val edge = with(density) { EDGE_SCROLL.toPx() }
            while (true) {
                withFrameNanos { }
                val at = words.finger[0] ?: continue
                val height = listState.layoutInfo.viewportSize.height
                val past = when {
                    at.y < edge -> at.y - edge
                    at.y > height - edge -> at.y - (height - edge)
                    else -> 0f
                }
                if (past == 0f) continue
                val moved = listState.dispatchRawDelta((past * EDGE_SCROLL_SPEED).coerceIn(-edge, edge) / zoom.scale)
                if (moved != 0f) words.pointTo(marking, at)
            }
        }
    }
    // A screenful of what's showing: less of the list, zoomed in.
    val step = { listState.layoutInfo.viewportSize.height * 0.85f / zoom.scale }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { zoom.width = it.width.toFloat().coerceAtLeast(1f) }
            .onGloballyPositioned { words.origin[0] = it.positionInWindow() }
            // Choosing words comes first: a finger that is selecting doesn't scroll, zoom or tap.
            .chooseWords(words, haptics)
            .pointerInput(zoom) { zoomGestures(zoom) }
            .tapZones(
                onPrevious = { scope.launch { listState.animateScrollBy(-step()) } },
                onNext = { scope.launch { listState.animateScrollBy(step()) } },
                onCenter = onCenterTap,
                onDoubleTap = { at ->
                    haptics.tick()
                    zoom.toggle(at)
                },
                onPage = { at -> words.tap(at, haptics, nav) },
                rightToLeft = rightToLeft,
            ),
    ) {
        val widthPx = with(LocalDensity.current) { min(maxWidth, 900.dp).roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.panX
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 64.dp),
        ) {
            // The first page to render lets the opening cover lift.
            items(count) { index -> PageImage(index, widthPx, placeholderAspect, page, render, aloud, frames, { zoom.scale }) { nav.contentReady = true } }
        }
        // The menu over words that are selected: by them, wherever the pages have got to.
        if (marking != null) {
            PdfSelectionMenu(
                marking, haptics,
                moving = { listState.isScrollInProgress to listOf(zoom.scale, zoom.panX, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) },
                selectionInWindow = { words.selectionInWindow(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())) },
            )
        }
    }
}

/** How near the top or the foot of the screen a finger choosing words makes the pages move, and how fast (of how far past that it is, each frame). */
private val EDGE_SCROLL = 56.dp
private const val EDGE_SCROLL_SPEED = 0.12f

/** A finger this near the middle of a selection handle takes hold of it. */
private val HANDLE_REACH = 26.dp

/**
 * Choosing words on a PDF's pages, and what a tap on one is (a highlight, a line for the voice to
 * read on from), whichever way the pages are shown: all of it is asked in the window's terms, of
 * where the pages are drawn ([frames]).
 */
@Stable
internal class PageWords(val aloud: PdfAloud?, val frames: PageFrames, private val density: Density) {
    /** Where the pages' own box is in the window. */
    val origin = arrayOf(Offset.Zero)

    /** The finger that is choosing words: where it is (in the pages' box), and where it points, which for a finger on a handle is the line the handle hangs from, not the handle. */
    val finger = arrayOf<Offset?>(null)
    val pointing = arrayOf(Offset.Zero)

    /** The reader has been told why nothing can be highlighted: once is enough (a finger resting on the page isn't asking). */
    val told = booleanArrayOf(false)

    val marking: PdfMarking? get() = aloud?.marking

    /** How wide [page] is in its own units; 0 until the PDF's words are to hand. */
    fun widthOf(page: Int): Float = aloud?.layout?.widthOf(page) ?: 0f

    /** Where a handle is in the window: the middle of the drop that is drawn (see PdfMarking.drawHandles). */
    fun handleInWindow(marks: PdfMarking, handle: SelectionHandle): Offset? {
        val at = marks.handlePoint(handle) ?: return null
        val tip = frames.inWindow(at.page, at.x, at.bottom + PdfMarking.LINE_ROOM, widthOf(at.page)) ?: return null
        val radius = with(density) { PdfMarking.HANDLE_RADIUS.toPx() }
        return Offset(if (at.hangsLeft) tip.x - radius else tip.x + radius, tip.y + radius)
    }

    fun pointTo(marks: PdfMarking, at: Offset) {
        finger[0] = at
        frames.nearest(at + pointing[0] + origin[0], ::widthOf)?.let { marks.moveTo(it.page, it.x, it.y) }
    }

    /**
     * A tap at [at] (in the pages' box), asked before it turns a page or brings the controls up:
     * true if it was something else. A tap with words selected lets them go, and does nothing
     * else. A tap on a highlight opens it. And while the PDF is read aloud, a tap on a line has the
     * voice read on from there.
     */
    fun tap(at: Offset, haptics: Haptics, nav: ReaderNav): Boolean {
        val marking = marking
        val tapped = if (aloud?.layout == null) null else frames.under(at + origin[0], ::widthOf)
        val mark = if (tapped == null) null else marking?.markAt(tapped.page, tapped.x, tapped.y)
        return when {
            marking?.selection != null -> {
                marking.clear()
                true
            }
            mark != null -> {
                haptics.tick()
                nav.editing = mark
                true
            }
            aloud == null || tapped == null -> false
            else -> aloud.onTap(tapped.page, tapped.x, tapped.y).also { if (it) haptics.tick() }
        }
    }

    /** As much of what is selected as is in sight, in the window: where its menu goes. [size] is the pages' box's. */
    fun selectionInWindow(size: Size): Rect? {
        val marking = marking ?: return null
        val window = Rect(origin[0], size)
        return marking.selected.mapNotNull { line ->
            val from = frames.inWindow(line.page, line.left, line.top, widthOf(line.page)) ?: return@mapNotNull null
            val to = frames.inWindow(line.page, line.right, line.bottom, widthOf(line.page)) ?: return@mapNotNull null
            Rect(from, to).takeIf { it.overlaps(window) }?.intersect(window)
        }.reduceOrNull { all, one -> Rect(minOf(all.left, one.left), minOf(all.top, one.top), maxOf(all.right, one.right), maxOf(all.bottom, one.bottom)) }
    }
}

/**
 * Choosing words with a finger: press and hold on one, then drag either handle. A finger that is
 * selecting doesn't scroll, zoom or tap. Where nothing can be highlighted, a press and hold says
 * why, once.
 */
@Composable
internal fun Modifier.chooseWords(words: PageWords, haptics: Haptics): Modifier {
    val context = LocalContext.current
    val marking = words.marking
    val cantMark = words.aloud?.cantMark
    return when {
        marking != null -> pointerInput(marking) {
            selectGestures(
                handleAt = { at ->
                    val reach = HANDLE_REACH.toPx()
                    SelectionHandle.entries.asReversed().firstOrNull { handle ->
                        words.handleInWindow(marking, handle)?.let { (it - (at + words.origin[0])).getDistance() <= reach } == true
                    }
                },
                wordsAt = { at -> words.frames.under(at + words.origin[0], words::widthOf)?.let { marking.wordsAt(it.page, it.x, it.y) } == true },
                onPress = { at ->
                    haptics.longPress()
                    words.finger[0] = at
                    words.pointing[0] = Offset.Zero
                    words.frames.under(at + words.origin[0], words::widthOf)?.let { marking.press(it.page, it.x, it.y) }
                },
                onGrab = { handle, at ->
                    words.finger[0] = at
                    // The finger is on the handle, under the words: it points at the middle of their line.
                    words.pointing[0] = marking.handlePoint(handle)
                        ?.let { words.frames.inWindow(it.page, it.x, it.middle, words.widthOf(it.page)) }
                        ?.let { it - (at + words.origin[0]) } ?: Offset.Zero
                    marking.grab(handle)
                },
                onMove = { at -> words.pointTo(marking, at) },
                onRelease = {
                    words.finger[0] = null
                    marking.release()
                },
            )
        }
        cantMark != null -> pointerInput(cantMark) {
            selectGestures(
                handleAt = { null },
                wordsAt = { !words.told[0] },
                onPress = {
                    words.told[0] = true
                    haptics.reject()
                    Toast.makeText(context, cantMark, Toast.LENGTH_LONG).show()
                },
                onGrab = { _, _ -> },
                onMove = {},
                onRelease = {},
            )
        }
        else -> this
    }
}

/** A point of a page in view, in the page's own units. */
internal class PagePoint(val page: Int, val x: Float, val y: Float)

/**
 * Where the pages in view are in the window as they are drawn now, scrolled and zoomed: all of
 * each page, whether or not all of it is in sight. What a point of the window is over, and where
 * in the window a point of a page is, are both told from it. [inView] tells which pages are in view.
 */
internal class PageFrames(private val inView: () -> List<Int>) {
    /**
     * Each page's picture, as it is laid out. Asked where it is when that is wanted: it moves
     * without being laid out again. (A list uses a picture's place again for another page, so
     * one kept here for a page that has gone out of view may be another page's by now: only the
     * pages in view are answered for.)
     */
    val pages = HashMap<Int, LayoutCoordinates>()

    /** Where page [index] is in the window; null unless it is in view. */
    fun of(index: Int): Rect? {
        val page = pages[index]?.takeIf { it.isAttached && index in inView() } ?: return null
        return Rect(page.localToWindow(Offset.Zero), page.localToWindow(Offset(page.size.width.toFloat(), page.size.height.toFloat())))
    }

    /** The page under [inWindow] and the point of it there, where [width] says how wide a page is in its own units; null between pages, and off them. */
    fun under(inWindow: Offset, width: (Int) -> Float): PagePoint? {
        for (index in inView()) {
            val frame = of(index)?.takeIf { it.contains(inWindow) && it.width > 0f } ?: continue
            val scale = width(index) / frame.width
            return if (scale > 0f) PagePoint(index, (inWindow.x - frame.left) * scale, (inWindow.y - frame.top) * scale) else null
        }
        return null
    }

    /** The page nearest to [inWindow], and the point of it there (which may be off its edge): where a finger dragging a handle is, wherever it has got to. */
    fun nearest(inWindow: Offset, width: (Int) -> Float): PagePoint? {
        fun off(from: Float, to: Float, at: Float) = if (at < from) from - at else if (at > to) at - to else 0f
        val (index, frame) = inView().mapNotNull { index -> of(index)?.takeIf { it.width > 0f && width(index) > 0f }?.let { index to it } }
            // (Pages side by side are told apart across; pages one above another, up and down.)
            .minByOrNull { (_, frame) -> off(frame.top, frame.bottom, inWindow.y) + off(frame.left, frame.right, inWindow.x) } ?: return null
        val scale = width(index) / frame.width
        return PagePoint(index, (inWindow.x - frame.left) * scale, (inWindow.y - frame.top) * scale)
    }

    /** Where in the window the point ([x], [y]) of [page] is, the page being [width] of its own units wide; null unless the page is in view. */
    fun inWindow(page: Int, x: Float, y: Float, width: Float): Offset? {
        val frame = of(page)?.takeIf { width > 0f } ?: return null
        val scale = frame.width / width
        return Offset(frame.left + x * scale, frame.top + y * scale)
    }

    /** Where [line] of a page in view is in the window, the page being [width] of its own units wide; null unless it is in view. */
    fun lineInWindow(line: MarkedLine, width: Float): Rect? {
        val frame = of(line.page)
        if (frame == null || width <= 0f) return null
        val scale = frame.width / width
        return Rect(frame.left + line.left * scale, frame.top + line.top * scale, frame.left + line.right * scale, frame.top + line.bottom * scale)
    }
}

/**
 * Whether the last of the list is in sight: it can scroll no further. Never before the list has
 * been laid out, when it can't scroll at all: asked then, and not asked again until a page was
 * turned, a book looked finished on its first page and "The end" came up as it opened.
 */
private fun LazyListState.atEnd(): Boolean = layoutInfo.totalItemsCount > 0 && !canScrollForward

@Composable
internal fun PageImage(
    index: Int,
    widthPx: Int,
    placeholderAspect: Float,
    page: PageColors,
    render: suspend (Int, Int) -> Bitmap?,
    aloud: PdfAloud?,
    frames: PageFrames,
    /** How many times their size the pages are drawn just now. */
    zoomed: () -> Float,
    modifier: Modifier = Modifier,
    /** The page is as wide as the list (to a point), at its own shape; false where [modifier] places and sizes it (pages side by side). */
    ownSize: Boolean = true,
    onRendered: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
        value = runCatching { render(index, widthPx) }.getOrNull()?.asImageBitmap()
        onRendered()
    }
    // A page that is white behind its words takes the veil over the words still to be read
    // aloud; on any other it would show as pale patches, and is left off.
    val paper = remember(bitmap) { bitmap?.let(::looksLikePaper) == true }
    val aspect = bitmap?.let { it.width / it.height.toFloat() } ?: placeholderAspect
    // The page develops on its placeholder rather than popping onto it.
    val developed = animateFloatAsState(if (bitmap != null) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "page")
    Box(
        modifier
            .then(if (ownSize) Modifier.widthIn(max = 900.dp).padding(horizontal = 8.dp).fillMaxWidth().aspectRatio(aspect) else Modifier)
            // The handles of what is selected hang from its ends, over the page's edge if they must: drawn outside what clips the page.
            .drawWithContent {
                drawContent()
                aloud?.marking?.run { aloud.layout?.widthOf(index)?.let { width -> drawHandles(index, width, aloud.tint, zoomed()) } }
            }
            .shadow(2.dp)
            .background(page.secondary.copy(alpha = 0.12f))
            .drawBehind { drawRect(Color.White, alpha = developed.value) },
    ) {
        bitmap?.let {
            Image(
                it, stringResource(R.string.reader_page_number, index + 1),
                Modifier
                    .fillMaxSize()
                    .then(if (aloud == null) Modifier else Modifier.onGloballyPositioned { at -> frames.pages[index] = at })
                    .graphicsLayer { alpha = developed.value }
                    // The sentence being read aloud, marked where it is drawn (only what is drawn changes as it is read).
                    .drawWithContent {
                        drawContent()
                        aloud?.run {
                            layout?.widthOf(index)?.let { width ->
                                // The reader's highlights, under the mark of the sentence being read; what is selected, over everything.
                                marking?.run { drawHighlights(index, width, paper) }
                                drawMarks(index, width, paper)
                                drawFound(index, width)
                                marking?.run { drawSelection(index, width, tint) }
                            }
                        }
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Whether a page's picture is white (or near it) around its edges: a printed page, not a slide on a colour or a dark scan. */
private fun looksLikePaper(image: ImageBitmap): Boolean = runCatching {
    val bitmap = image.asAndroidBitmap()
    val w = bitmap.width
    val h = bitmap.height
    if (w < 8 || h < 8) return false
    val spots = listOf(2 to 2, w / 2 to 2, w - 3 to 2, 2 to h / 2, w - 3 to h / 2, 2 to h - 3, w / 2 to h - 3, w - 3 to h - 3)
    spots.count { (x, y) -> Color(bitmap[x, y]).luminance() > 0.85f } >= spots.size - 2
}.getOrDefault(false)

/**
 * The pages follow the voice. The line holding the next word to be spoken is kept in the upper
 * part of the screen: the pages move when it is about to leave it (so, from one page to the
 * next, with the voice), on the theme's own spring, or at once with animations removed.
 *
 * A listener who moves the pages themselves has gone to look at something: the pages stop
 * following, and the player offers the way back to the voice.
 */
@Composable
private fun FollowVoice(listState: LazyListState, aloud: PdfAloud, nav: ReaderNav, frames: PageFrames, zoomed: () -> Float) {
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val scope = rememberCoroutineScope()
    SideEffect {
        aloud.top = {
            // (The page the list counts as its first, which is not always the first it has laid
            // out: the foot of the page before can show in the room kept clear above it.)
            val index = listState.firstVisibleItemIndex
            val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            PdfPlace(index, if (size > 0) (listState.firstVisibleItemScrollOffset.toFloat() / size).coerceIn(0f, 1f) else 0f)
        }
        // To a place on a page (a chapter that opens part of the way down one), as a jump from the contents goes.
        aloud.goTo = { place ->
            scope.launch {
                val size = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.size } ?: 0
                listState.animateScrollToItem(place.page, (place.within * size).toInt())
            }
        }
        // To a line of a page (a search result), set where the line being read aloud is kept:
        // clear of the controls, with what leads up to it in sight.
        aloud.show = { place ->
            scope.launch {
                val info = listState.layoutInfo
                val size = info.visibleItemsInfo.firstOrNull { it.index == place.page }?.size ?: info.visibleItemsInfo.maxOfOrNull { it.size } ?: 0
                val above = (info.viewportSize.height / zoomed() * LINE_AT).toInt()
                listState.scrollToItem(place.page, (place.within * size).toInt() - above)
                // (A page not yet laid out is another height than the ones in view: once it is, the line is put right.)
                val laid = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == place.page }?.size ?: return@launch
                if (laid != size) listState.scrollToItem(place.page, (place.within * laid).toInt() - above)
            }
        }
        aloud.inView = { place ->
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == place.page }
            if (item == null) {
                false
            } else {
                val y = item.offset + place.within * item.size
                y >= info.viewportStartOffset && y < info.viewportStartOffset + info.viewportSize.height / zoomed()
            }
        }
        aloud.lineInWindow = { line -> frames.lineInWindow(line, aloud.layout?.widthOf(line.page) ?: 0f) }
    }
    LaunchedEffect(listState, aloud) {
        // (A reader that opens on a voice already reading is put at the book's place first: that is not the listener moving.)
        aloud.followedAt = SystemClock.uptimeMillis()
        snapshotFlow { Triple(aloud.line(), aloud.listen.offPage, aloud.calls) }.collectLatest { (line, elsewhere, _) ->
            if (line == null || elsewhere || !aloud.listen.active) return@collectLatest
            val height = aloud.layout?.heightOf(line.page)?.takeIf { it > 0f } ?: return@collectLatest
            val still = motionRemoved()
            aloud.following = true
            try {
                // A page that isn't on screen is gone to first; then the line is put in its place.
                for (attempt in 0 until 3) {
                    val info = listState.layoutInfo
                    val seen = info.viewportSize.height / zoomed()
                    val item = info.visibleItemsInfo.firstOrNull { it.index == line.page }
                    aloud.followedAt = SystemClock.uptimeMillis()
                    if (item == null) {
                        val size = info.visibleItemsInfo.maxOfOrNull { it.size } ?: break
                        listState.scrollToItem(line.page, (line.top / height * size - seen * LINE_AT).toInt())
                        continue
                    }
                    val top = item.offset + line.top / height * item.size - info.viewportStartOffset
                    val bottom = item.offset + line.bottom / height * item.size - info.viewportStartOffset
                    if (top >= seen * LINE_NO_HIGHER && bottom <= seen * LINE_NO_LOWER) break
                    val by = top - seen * LINE_AT
                    if (still) listState.scrollBy(by) else listState.animateScrollBy(by, spring)
                    break
                }
            } finally {
                aloud.followedAt = SystemClock.uptimeMillis()
                aloud.following = false
            }
        }
    }
    LaunchedEffect(listState, aloud) {
        var window = listState.layoutInfo.viewportSize
        var resizedAt = 0L
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.layoutInfo.viewportSize) }.drop(1).collect { (_, _, size) ->
            val now = SystemClock.uptimeMillis()
            if (size != window) {
                // The window changed (the phone was folded, unfolded or turned): the pages are laid
                // out anew, and go back to the voice by themselves.
                window = size
                resizedAt = now
                aloud.calls++
                return@collect
            }
            val moved = !aloud.following && now - aloud.followedAt > FOLLOW_SETTLE_MS && now - resizedAt > FOLLOW_SETTLE_MS
            if (moved) nav.movedAt = now
            if (moved && aloud.listen.active && !aloud.listen.offPage) aloud.listen.offPage = true
        }
    }
}

/** Where on the screen the line being read aloud is put (of its height, from the top), and how far up or down it may be before the pages move. */
internal const val LINE_AT = 0.25f
internal const val LINE_NO_HIGHER = 0.1f
internal const val LINE_NO_LOWER = 0.6f

/**
 * A light tick each time a new page scrolls into first place. A fling through many pages gets a
 * few, spaced out, rather than a buzz.
 */
@Composable
private fun PageTicks(listState: LazyListState, haptics: Haptics) {
    LaunchedEffect(listState) {
        var previous = -1
        var tickedAt = 0L
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val now = SystemClock.uptimeMillis()
            if (previous >= 0 && index != previous && now - tickedAt >= TICK_GAP_MS) {
                haptics.tick()
                tickedAt = now
            }
            previous = index
        }
    }
}

internal const val TICK_GAP_MS = 160L

/**
 * Turns list scrolling into a saved position and an overall 0..1 progress, once scrolling settles.
 * The place saved is an item (a page, a paragraph) and how far down it the top of the screen was:
 * the list opens on item [start], and is first put [startOffset] of the way down it, so a tall
 * page or a long paragraph isn't begun again from its top.
 *
 * With each place goes whether the reader went back through the pages to get to it (see
 * [ScrolledPlace]): the one thing that lets the book's progress fall. The page the list opens on,
 * and the pages as they lie after the window or the zoom ([zoomed]) has changed, are the same
 * place shown again. While the place is [held] by somebody else (the voice), nothing is saved.
 * Whatever is saved, the top of the screen is [kept], for the pages to open there if they are
 * shown another way.
 */
@Composable
private fun TrackProgress(
    listState: LazyListState,
    count: Int,
    start: Int,
    startOffset: Float,
    kept: KeptPage,
    held: () -> Boolean,
    zoomed: () -> Float,
    onProgress: ProgressSink,
) {
    val sink by rememberUpdatedState(onProgress)
    val holds by rememberUpdatedState(held)
    val zoom by rememberUpdatedState(zoomed)
    LaunchedEffect(listState, count) {
        val place = ScrolledPlace()
        val index = start.coerceIn(0, (count - 1).coerceAtLeast(0))
        if (startOffset > 0f && startOffset.isFinite()) {
            // Known once the item has been laid out: its height differs from one screen to another.
            // (Not waited for long: nothing is saved until this is done.)
            val item = withTimeoutOrNull(2_000) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }.first { it != null }
            }
            if (item != null) listState.scrollToItem(index, ceil(startOffset.coerceAtMost(1f) * item.size).toInt())
        }
        // The page the book opens on is where its place is.
        place.at(index, if (startOffset.isFinite()) startOffset else 0f)
        // Folded, unfolded, turned or zoomed, the pages lie differently, and the top of the screen
        // is another fraction of the page it was on: the same place, to be counted from where it is now.
        launch {
            snapshotFlow { listState.layoutInfo.viewportSize to zoom() }.drop(1).collect {
                // (Once the pages have been laid out in the new window.)
                withFrameNanos { }
                val (page, fraction) = listState.topOfScreen()
                if (holds()) place.shownAgain() else place.at(page, fraction)
            }
        }
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollForward) }
            .collectLatest { (_, _, more) ->
                delay(300)
                listState.topOfScreen().let { (page, fraction) -> kept.keep(page, fraction) }
                if (holds()) {
                    // Wherever the pages are when the place is the reader's again is where it is then.
                    place.shownAgain()
                    return@collectLatest
                }
                val (page, fraction) = listState.topOfScreen()
                val progress = if (!more) 1f else ((page + fraction) / count).coerceIn(0f, 1f)
                sink(page, fraction, progress, count, place.wentBackTo(page, fraction))
            }
    }
}

/**
 * The page at the top of the screen, and how far down it the top is (0..1). (The page the list
 * counts as its first, which is not always the first it has laid out: the foot of the page before
 * can show in the room kept clear above it.)
 */
private fun LazyListState.topOfScreen(): Pair<Int, Float> {
    val index = firstVisibleItemIndex
    val size = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size?.coerceAtLeast(1) ?: 1
    return index to (firstVisibleItemScrollOffset / size.toFloat()).coerceIn(0f, 1f)
}
