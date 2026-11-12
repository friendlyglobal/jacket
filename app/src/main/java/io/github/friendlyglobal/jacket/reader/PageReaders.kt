package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.toArgb
import io.github.friendlyglobal.jacket.ui.readableVeil
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.Comic
import io.github.friendlyglobal.jacket.importer.DocumentCloser
import io.github.friendlyglobal.jacket.importer.PdfDocument
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.rememberReadingFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil
import io.github.friendlyglobal.jacket.voice.withPhoneRegion
import androidx.compose.foundation.interaction.DragInteraction

/**
 * Opens a document off the main thread for as long as the caller is composed. It is closed even
 * if the reader goes away while the document is still opening.
 */
@Composable
private fun <T : Closeable> rememberDocument(key: Any, open: () -> T): Result<T>? {
    val doc by produceState<Result<T>?>(null, key) {
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
 * Left and right edges page back and forward; the middle toggles the reader controls. With
 * [onDoubleTap] (pages that zoom), a tap waits a moment to see whether a second one follows.
 */
@Composable
private fun Modifier.tapZones(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCenter: () -> Unit,
    onDoubleTap: ((Offset) -> Unit)? = null,
): Modifier {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val center by rememberUpdatedState(onCenter)
    val double by rememberUpdatedState(onDoubleTap)
    return pointerInput(onDoubleTap != null) {
        detectTapGestures(
            onDoubleTap = if (onDoubleTap != null) { at -> double?.invoke(at) } else null,
            onTap = { offset ->
                val x = offset.x / size.width
                when {
                    x < 0.28f -> previous()
                    x > 0.72f -> next()
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
fun PdfReader(book: Book, page: PageColors, nav: ReaderNav, haptics: Haptics, onProgress: ProgressSink, onCenterTap: () -> Unit) {
    val library = LocalContext.current.library
    val result = rememberDocument(book.id) { PdfDocument(library.fileOf(book)) }
    when {
        result == null -> ReaderLoading()
        result.isFailure -> ReaderError(stringResource(R.string.reader_error_pdf))
        else -> {
            val pdf = result.getOrThrow()
            PagedList(pdf.pageCount, book.position, book.positionOffset, pdf.firstPageAspect, page, nav, haptics, onProgress, onCenterTap) { index, width ->
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
fun ComicReader(book: Book, page: PageColors, nav: ReaderNav, haptics: Haptics, onProgress: ProgressSink, onCenterTap: () -> Unit) {
    val library = LocalContext.current.library
    val result = rememberDocument(book.id) { ComicDocument(library.fileOf(book)) }
    when {
        result == null -> ReaderLoading()
        result.isFailure || result.getOrThrow().pages.isEmpty() -> ReaderError(stringResource(R.string.reader_error_comic))
        else -> {
            val comic = result.getOrThrow()
            PagedList(comic.pages.size, book.position, book.positionOffset, 0.66f, page, nav, haptics, onProgress, onCenterTap) { index, width ->
                comic.render(index, width)
            }
        }
    }
}

@Composable
private fun PagedList(
    count: Int,
    initial: Int,
    /** How far down the page at [initial] the top of the screen was (0..1). */
    initialOffset: Float,
    placeholderAspect: Float,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
    render: suspend (index: Int, width: Int) -> Bitmap?,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, (count - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    // The last page is on screen when the list can't scroll any further (pages are often shorter
    // than the screen, so the last one never becomes the first visible).
    val atEnd by remember { derivedStateOf { count > 0 && !listState.canScrollForward } }
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
        nav.atEnd = atEnd
        nav.pageKey = first
        nav.place = {
            Place(null, first, if (count > 1) first / (count - 1f) else 0f, "", resources.getString(R.string.reader_page_number, first + 1))
        }
        nav.onScreen = { mark -> mark.section == first }
        nav.goTo = { mark -> nav.jumpTo(mark.section) }
        nav.turnPage = { delta -> nav.jumpTo(first + delta) }
    }
    PageTicks(listState, haptics)
    TrackProgress(listState, count, initial, initialOffset, onProgress)

    val zoom = remember(listState) { PageZoom(scope, listState) }
    zoom.spring = MaterialTheme.motionScheme.defaultSpatialSpec()
    // A screenful of what's showing: less of the list, zoomed in.
    val step = { listState.layoutInfo.viewportSize.height * 0.85f / zoom.scale }
    // A tap waits to see if it's a double tap; a little less long than the system's own patience,
    // so the page edges still answer briskly.
    val platform = LocalViewConfiguration.current
    val brisk = remember(platform) {
        object : ViewConfiguration by platform {
            override val doubleTapTimeoutMillis: Long get() = minOf(platform.doubleTapTimeoutMillis, 250L)
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides brisk) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { zoom.width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(zoom) { zoomGestures(zoom) }
            .tapZones(
                onPrevious = { scope.launch { listState.animateScrollBy(-step()) } },
                onNext = { scope.launch { listState.animateScrollBy(step()) } },
                onCenter = onCenterTap,
                onDoubleTap = { at ->
                    haptics.tick()
                    zoom.toggle(at)
                },
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
            items(count) { index -> PageImage(index, widthPx, placeholderAspect, page, render) { nav.contentReady = true } }
        }
    }
    }
}

@Composable
private fun PageImage(
    index: Int,
    widthPx: Int,
    placeholderAspect: Float,
    page: PageColors,
    render: suspend (Int, Int) -> Bitmap?,
    onRendered: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
        value = runCatching { render(index, widthPx) }.getOrNull()?.asImageBitmap()
        onRendered()
    }
    val aspect = bitmap?.let { it.width / it.height.toFloat() } ?: placeholderAspect
    // The page develops on its placeholder rather than popping onto it.
    val developed = animateFloatAsState(if (bitmap != null) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "page")
    Box(
        Modifier
            .widthIn(max = 900.dp)
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .shadow(2.dp)
            .background(page.secondary.copy(alpha = 0.12f))
            .drawBehind { drawRect(Color.White, alpha = developed.value) },
    ) {
        bitmap?.let {
            Image(
                it, stringResource(R.string.reader_page_number, index + 1),
                Modifier.fillMaxSize().graphicsLayer { alpha = developed.value }, contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
fun TextReader(
    book: Book,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    speaker: Speaker,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
) {
    val library = LocalContext.current.library
    val paragraphs by produceState<List<String>?>(null, book.id) {
        value = withContext(Dispatchers.IO) { runCatching { readParagraphs(library.fileOf(book)) }.getOrDefault(emptyList()) }
    }
    val text = paragraphs ?: return ReaderLoading()
    if (text.isEmpty()) return ReaderError(stringResource(R.string.reader_error_text))

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = book.position.coerceIn(0, text.lastIndex))
    // The opening cover lifts once the first paragraphs are laid out.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        nav.contentReady = true
    }
    val scope = rememberCoroutineScope()
    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val textAtEnd by remember { derivedStateOf { !listState.canScrollForward } }
    val percent = book.percent
    val resources = LocalResources.current
    SideEffect {
        nav.sectionCount = text.size
        nav.section = first
        nav.title = resources.getString(R.string.reader_percent_read, percent)
        nav.footer = ""
        nav.sectionName = { resources.getString(R.string.percent, it * 100 / text.size) }
        nav.jumpTo = { target ->
            if (target in text.indices) {
                if (abs(target - first) > 1) {
                    nav.cameFrom = Origin(first * 100 / text.size) { scope.launch { listState.scrollToItem(first) } }
                }
                scope.launch { listState.scrollToItem(target) }
            }
        }
        nav.atEnd = textAtEnd
        nav.pageKey = first
        nav.place = { Place(null, first, first / text.size.toFloat(), "", text[first].take(160)) }
        nav.onScreen = { mark -> mark.section == first }
        nav.goTo = { mark -> nav.jumpTo(mark.section) }
        nav.turnPage = { delta ->
            scope.launch { listState.animateScrollBy(delta * listState.layoutInfo.viewportSize.height * 0.85f) }
        }
    }
    TrackProgress(listState, text.size, book.position, book.positionOffset, onProgress)

    // Read aloud: which paragraph is being spoken, and which characters of it so far.
    val listen = nav.listen
    // The place saved is the paragraph at the top of the screen, which keeps up with the voice
    // while it can be seen. With the screen off or another app in front it can't: the text stays
    // where it was left while the voice reads on. Once the voice is past what that screen showed,
    // the paragraph it has reached is saved as the place itself, and the text is taken to it when
    // it is seen again (whether or not the voice is still speaking). -1 while the text is keeping up.
    val owner = LocalLifecycleOwner.current
    val savePlace by rememberUpdatedState(onProgress)
    val unseenAt = remember { intArrayOf(-1) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val reached = unseenAt[0]
        unseenAt[0] = -1
        if (reached in text.indices) scope.launch { listState.scrollToItem(reached) }
    }
    var spokenParagraph by remember { mutableIntStateOf(-1) }
    var wordStart by remember { mutableIntStateOf(0) }
    var wordEnd by remember { mutableIntStateOf(0) }
    val token = remember { intArrayOf(0) }
    val context = LocalContext.current
    // Where the paragraph being spoken is laid out: the controls keep off the line the voice is on.
    val spokenBox = remember { SpokenBox() }
    // "You were here": the sentence the voice stopped on stays marked for a moment after listening
    // ends, on the theme's fades; nothing else on the page changes.
    var hereParagraph by remember { mutableIntStateOf(-1) }
    var hereSentence by remember { mutableStateOf(IntRange.EMPTY) }
    val hereStrength = remember { Animatable(0f) }
    val hereJob = remember { arrayOfNulls<Job>(1) }
    val hereAppears = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val hereFades = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    fun markHere(paragraph: Int, at: Int) {
        hereJob[0]?.cancel()
        hereJob[0] = scope.launch {
            hereSentence = sentenceAround(text[paragraph], at)
            hereParagraph = paragraph
            try {
                hereStrength.snapTo(HERE_FROM)
                hereStrength.animateTo(1f, hereAppears)
                delay(HERE_HOLD_MS)
                hereStrength.animateTo(0f, hereFades)
            } finally {
                hereParagraph = -1
            }
        }
    }
    val controller = remember(book.id, speaker, text) {
        object : ListenController {
            override val previousLabel = R.string.listen_previous_paragraph
            override val nextLabel = R.string.listen_next_paragraph

            /** Where the voice stopped: the paragraph, and the word in it; and when (elapsed-time millis). */
            var stoppedParagraph = -1
            var stoppedWord = 0
            var stoppedAt = 0L

            /** When it was last paused (elapsed-time millis); 0 while it speaks. */
            var pausedAt = 0L

            fun say(index: Int, from: Int, queue: Boolean) {
                if (index !in text.indices) {
                    if (!queue) stop()
                    return
                }
                if (!queue) {
                    // Remember where we asked to start, so pause/resume works before the first word.
                    spokenParagraph = index
                    wordStart = from
                    wordEnd = from
                }
                val start = from.coerceIn(0, text[index].length)
                speaker.speak(token[0], index, start, text[index].substring(start), queue)
            }

            fun play(index: Int, from: Int) {
                // With the reader's accent: "pt" on a Brazilian phone is read as "pt-BR".
                speaker.setLanguage(withPhoneRegion(book.language))
                if (!speaker.acquire()) {
                    listen.active = true
                    listen.playing = false
                    return
                }
                // The voice is back: the mark of where it stopped has done its work.
                hereJob[0]?.cancel()
                pausedAt = 0L
                listen.active = true
                listen.playing = true
                listen.preparing = speaker.slowToStart
                token[0]++
                say(index, from, false)
            }

            override fun start() {
                if (speaker.unavailable) {
                    haptics.reject()
                    Toast.makeText(context, R.string.listen_no_voice, Toast.LENGTH_LONG).show()
                    return
                }
                // Starting reads from the paragraph in view: the listener is where the voice is. If the
                // voice stopped on this screen, it carries on from the sentence it stopped in.
                listen.offPage = false
                val shown = listState.layoutInfo.visibleItemsInfo.any { it.index == stoppedParagraph }
                if (shown && stoppedParagraph in text.indices) {
                    // After a long while, from a sentence or two before it: the listener finds the thread.
                    val back = sentencesToRewind(SystemClock.elapsedRealtime() - stoppedAt)
                    val (paragraph, from) = sentencesBack(text, stoppedParagraph, stoppedWord, back)
                    play(paragraph, from)
                } else {
                    play(listState.firstVisibleItemIndex, 0)
                }
            }

            override fun toggle() = if (listen.playing) pause() else resume()

            fun pause() {
                token[0]++
                speaker.stop()
                speaker.release()
                if (listen.playing) pausedAt = SystemClock.elapsedRealtime()
                listen.playing = false
                listen.preparing = false
            }

            fun resume() {
                if (spokenParagraph < 0) return start()
                // From the word it stopped on; after a long pause, from a sentence or two before.
                val back = if (pausedAt > 0L) sentencesToRewind(SystemClock.elapsedRealtime() - pausedAt) else 0
                if (back == 0) {
                    play(spokenParagraph, wordStart)
                } else {
                    val (paragraph, from) = sentencesBack(text, spokenParagraph, wordStart, back)
                    play(paragraph, from)
                }
            }

            override fun skip(delta: Int) {
                speaker.stop()
                play((spokenParagraph.coerceAtLeast(listState.firstVisibleItemIndex) + delta).coerceAtLeast(0), 0)
            }

            override fun setRate(rate: Float) {
                speaker.setRate(rate)
                // Re-speak from the current word at the new pace.
                if (listen.playing) resume()
            }

            override fun stop() {
                val wasListening = listen.active
                val elsewhere = listen.offPage
                val paragraph = spokenParagraph
                val word = wordStart
                token[0]++
                speaker.stop()
                speaker.release()
                listen.active = false
                listen.playing = false
                listen.preparing = false
                listen.offPage = false
                listen.phrase = ""
                spokenParagraph = -1
                pausedAt = 0L
                if (paragraph in text.indices) {
                    stoppedParagraph = paragraph
                    stoppedWord = word
                    stoppedAt = SystemClock.elapsedRealtime()
                }
                // The text stays where it is. Following the voice, the sentence it stopped on is in
                // front of you, and is marked; gone to look elsewhere, you are offered the way back to it.
                if (!wasListening || paragraph !in text.indices) return
                if (elsewhere) {
                    nav.cameFrom = Origin(paragraph * 100 / text.size) {
                        scope.launch {
                            listState.scrollToItem(paragraph)
                            markHere(paragraph, word)
                        }
                    }
                } else {
                    markHere(paragraph, word)
                }
            }

            override fun backToVoice() {
                listen.offPage = false
                if (spokenParagraph >= 0) scope.launch { listState.animateScrollToItem(spokenParagraph) }
            }

            /** "Read from here": the voice goes to the sentence [from] characters into paragraph [index], paused or not before. */
            fun listenFrom(index: Int, from: Int) {
                listen.offPage = false
                speaker.stop()
                play(index, from)
            }

            override suspend fun spokenLine(): Rect? {
                if (!listen.active || listen.offPage || spokenBox.paragraph != spokenParagraph) return null
                val coordinates = spokenBox.coordinates?.takeIf { it.isAttached } ?: return null
                val layout = spokenBox.layout ?: return null
                val line = layout.getLineForOffset(wordStart.coerceIn(0, layout.layoutInput.text.length))
                val at = coordinates.positionInWindow()
                return Rect(at.x, at.y + layout.getLineTop(line), at.x + coordinates.size.width, at.y + layout.getLineBottom(line))
            }
        }
    }
    DisposableEffect(speaker, controller) {
        speaker.listener = object : Speaker.Listener {
            override fun onStart(utterance: Utterance) {
                if (utterance.token != token[0]) return
                spokenParagraph = utterance.paragraph
                wordStart = utterance.offset
                wordEnd = utterance.offset
                // Out of sight, the text can't follow (unless the listener had left it to look elsewhere).
                val unseen = !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !listen.offPage
                val leftBehind = unseen && listState.layoutInfo.visibleItemsInfo.none { it.index == utterance.paragraph }
                if (leftBehind && utterance.paragraph != unseenAt[0] && utterance.paragraph in text.indices) {
                    unseenAt[0] = utterance.paragraph
                    savePlace(utterance.paragraph, 0f, utterance.paragraph / text.size.toFloat(), text.size)
                }
                // The first piece of a paragraph queues the next one: no gap between paragraphs.
                if (utterance.first) controller.say(utterance.paragraph + 1, 0, true)
            }

            override fun onRange(utterance: Utterance, start: Int, end: Int) {
                if (utterance.token != token[0]) return
                // Its first word: it's reading now.
                listen.preparing = false
                spokenParagraph = utterance.paragraph
                wordStart = utterance.offset + start
                wordEnd = utterance.offset + end
                // The sentence around the word, for the cover of a phone folded shut.
                val paragraph = text.getOrNull(utterance.paragraph) ?: return
                val sentence = sentenceAround(paragraph, wordStart)
                listen.phrase = paragraph.substring(sentence.first, sentence.last + 1)
                listen.spoken = (wordEnd - sentence.first).coerceIn(0, listen.phrase.length)
            }

            override fun onDone(utterance: Utterance) {
                if (utterance.token == token[0] && utterance.last && utterance.paragraph >= text.lastIndex) {
                    haptics.confirm()
                    controller.stop()
                }
            }

            override fun onFailed() {
                if (listen.playing) controller.toggle()
            }

            override fun onInterrupted() {
                if (listen.playing) controller.toggle()
            }
        }
        nav.listenController = controller
        // A new voice was chosen while listening: it takes over from the word the last one reached.
        if (listen.active && listen.playing && spokenParagraph >= 0) controller.resume()
        onDispose {
            speaker.listener = null
            nav.listenController = null
        }
    }
    // A finger on the text while it's read aloud: the listener went to look elsewhere, and the
    // text stops following the voice until they go back (the app's own scrolling isn't a drag).
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && listen.active) listen.offPage = true
        }
    }
    // Keep the spoken paragraph on screen, clear of the footer line the mini player sits in.
    val footerRoom = with(LocalDensity.current) { SPOKEN_CLEAR_OF_FOOTER.roundToPx() }
    LaunchedEffect(spokenParagraph) {
        if (spokenParagraph < 0 || !listen.playing || listen.offPage) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == spokenParagraph }
        if (item == null || item.offset + item.size > info.viewportEndOffset - footerRoom) listState.animateScrollToItem(spokenParagraph)
    }

    val family = rememberReadingFontFamily(settings.font)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = family, fontSize = 18.sp * settings.textScale, lineHeight = 29.sp * settings.textScale, color = page.text,
    )
    // The words still to come are paler, and still readable (see readableVeil).
    val unread = SpanStyle(color = page.text.copy(alpha = 1f - readableVeil(page.text.toArgb(), page.background.toArgb())))
    val currentWord = SpanStyle(background = page.link.copy(alpha = 0.24f))
    val step = { listState.layoutInfo.viewportSize.height * 0.85f }
    val besideWords = 24.dp
    Box(
        Modifier.fillMaxSize().tapZones(
            onPrevious = { scope.launch { listState.animateScrollBy(-step()) } },
            onNext = { scope.launch { listState.animateScrollBy(step()) } },
            onCenter = onCenterTap,
        ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 40.dp, bottom = 72.dp),
        ) {
            itemsIndexed(text) { index, paragraph ->
                // While listening: read text in full ink, unread text soft, the current word marked.
                val spoken = when {
                    !listen.active && index == hereParagraph -> buildAnnotatedString {
                        append(paragraph)
                        val from = hereSentence.first.coerceIn(0, paragraph.length)
                        val to = (hereSentence.last + 1).coerceIn(from, paragraph.length)
                        if (to > from) addStyle(SpanStyle(background = page.link.copy(alpha = HERE_ALPHA * hereStrength.value)), from, to)
                    }
                    !listen.active || spokenParagraph < 0 || index < spokenParagraph -> AnnotatedString(paragraph)
                    index > spokenParagraph -> AnnotatedString(paragraph, unread)
                    else -> buildAnnotatedString {
                        append(paragraph)
                        val end = wordEnd.coerceIn(0, paragraph.length)
                        addStyle(unread, end, paragraph.length)
                        val start = wordStart.coerceIn(0, end)
                        if (end > start) addStyle(currentWord, start, end)
                    }
                }
                val speaking = listen.active && index == spokenParagraph
                // How it was laid out, for finding the sentence under a tap.
                val laid = remember { arrayOfNulls<TextLayoutResult>(1) }
                Text(
                    spoken, style = style,
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 7.dp)
                        .then(
                            // While the book is read aloud, a tap on a sentence reads on from there.
                            // Beside the words (past a short line's end) it's the page's ordinary tap.
                            if (listen.active) {
                                Modifier.pointerInput(index, paragraph) {
                                    detectTapGestures { tap ->
                                        val layout = laid[0]
                                        val line = layout?.getLineForVerticalPosition(tap.y)
                                        if (layout == null || line == null || tap.x > layout.getLineRight(line) + besideWords.toPx()) {
                                            onCenterTap()
                                            return@detectTapGestures
                                        }
                                        haptics.tick()
                                        controller.listenFrom(index, sentenceAround(paragraph, layout.getOffsetForPosition(tap)).first)
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (speaking) {
                                Modifier.onGloballyPositioned {
                                    spokenBox.paragraph = index
                                    spokenBox.coordinates = it
                                }
                            } else {
                                Modifier
                            },
                        ),
                    onTextLayout = {
                        laid[0] = it
                        if (speaking) spokenBox.layout = it
                    },
                )
            }
        }
    }
}

/** Where the paragraph being read aloud is laid out, and how its lines fall. */
private class SpokenBox {
    var paragraph = -1
    var coordinates: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

/** The foot of the screen the paragraph being read is kept out of: the footer line and the system's bar under it. */
private val SPOKEN_CLEAR_OF_FOOTER = 96.dp

/** The mark left on the sentence where the voice stopped: its wash at full strength, and the strength it starts from... */
private const val HERE_ALPHA = 0.3f
private const val HERE_FROM = 0.6f

/** ...and how long it stays before it fades: about two seconds in all. */
private const val HERE_HOLD_MS = 1_800L

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

private const val TICK_GAP_MS = 160L

/**
 * Turns list scrolling into a saved position and an overall 0..1 progress, once scrolling settles.
 * The place saved is an item (a page, a paragraph) and how far down it the top of the screen was:
 * the list opens on item [start], and is first put [startOffset] of the way down it, so a tall
 * page or a long paragraph isn't begun again from its top.
 */
@Composable
private fun TrackProgress(listState: LazyListState, count: Int, start: Int, startOffset: Float, onProgress: ProgressSink) {
    val sink by rememberUpdatedState(onProgress)
    LaunchedEffect(listState, count) {
        if (startOffset > 0f && startOffset.isFinite()) {
            // Known once the item has been laid out: its height differs from one screen to another.
            // (Not waited for long: nothing is saved until this is done.)
            val index = start.coerceIn(0, (count - 1).coerceAtLeast(0))
            val item = withTimeoutOrNull(2_000) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } }.first { it != null }
            }
            if (item != null) listState.scrollToItem(index, ceil(startOffset.coerceAtMost(1f) * item.size).toInt())
        }
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollForward) }
            .collectLatest { (index, offset, more) ->
                delay(300)
                val size = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
                val fraction = (offset / size.toFloat()).coerceIn(0f, 1f)
                val progress = if (!more) 1f else ((index + fraction) / count).coerceIn(0f, 1f)
                sink(index, fraction, progress, count)
            }
    }
}

/** The sentence of [paragraph] that holds the character at [at]: from after the last full stop to the next. */
internal fun sentenceAround(paragraph: String, at: Int): IntRange {
    if (paragraph.isEmpty()) return IntRange.EMPTY
    val here = at.coerceIn(0, paragraph.lastIndex)
    fun ends(index: Int) = paragraph[index] in ".!?…" && (index == paragraph.lastIndex || paragraph[index + 1].isWhitespace())
    var start = here
    while (start > 0 && !ends(start - 1)) start--
    while (start < here && paragraph[start].isWhitespace()) start++
    var end = here
    while (end < paragraph.lastIndex && !ends(end)) end++
    return start..end
}

private fun readParagraphs(file: File): List<String> {
    val bytes = file.readBytes()
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charsets.ISO_8859_1)
    }.removePrefix("\uFEFF").replace("\r\n", "\n")
    val blocks = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    // Files without blank lines between paragraphs: fall back to one paragraph per line.
    val paragraphs = if (blocks.size <= 1) text.lines().map { it.trim() }.filter { it.isNotEmpty() } else blocks
    return paragraphs.map { it.replace(Regex("\\s*\n\\s*"), " ") }
}
