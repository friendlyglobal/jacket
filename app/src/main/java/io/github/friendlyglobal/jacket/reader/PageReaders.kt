package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.AnimationSpec
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
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.abs

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
            PagedList(pdf.pageCount, book.position, pdf.firstPageAspect, page, nav, haptics, onProgress, onCenterTap) { index, width ->
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
            PagedList(comic.pages.size, book.position, 0.66f, page, nav, haptics, onProgress, onCenterTap) { index, width ->
                comic.render(index, width)
            }
        }
    }
}

@Composable
private fun PagedList(
    count: Int,
    initial: Int,
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
                    nav.cameFrom = Origin(first * 100 / (count - 1).coerceAtLeast(1)) { scope.launch { listState.scrollToItem(first) } }
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
    TrackProgress(listState, count, onProgress)

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
    TrackProgress(listState, text.size, onProgress)

    // Read aloud: which paragraph is being spoken, and which characters of it so far.
    val listen = nav.listen
    var spokenParagraph by remember { mutableIntStateOf(-1) }
    var wordStart by remember { mutableIntStateOf(0) }
    var wordEnd by remember { mutableIntStateOf(0) }
    val token = remember { intArrayOf(0) }
    val context = LocalContext.current
    val controller = remember(book.id, speaker, text) {
        object : ListenController {
            override val previousLabel = R.string.listen_previous_paragraph
            override val nextLabel = R.string.listen_next_paragraph

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
                if (!speaker.acquire()) {
                    listen.active = true
                    listen.playing = false
                    return
                }
                listen.active = true
                listen.playing = true
                token[0]++
                say(index, from, false)
            }

            override fun start() {
                if (speaker.unavailable) {
                    haptics.reject()
                    Toast.makeText(context, R.string.listen_no_voice, Toast.LENGTH_LONG).show()
                    return
                }
                speaker.setLanguage(null)
                play(listState.firstVisibleItemIndex, 0)
            }

            override fun toggle() = if (listen.playing) pause() else resume()

            fun pause() {
                token[0]++
                speaker.stop()
                speaker.release()
                listen.playing = false
            }

            fun resume() {
                if (spokenParagraph < 0) start() else play(spokenParagraph, wordStart)
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
                token[0]++
                speaker.stop()
                speaker.release()
                listen.active = false
                listen.playing = false
                listen.phrase = ""
                spokenParagraph = -1
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
                // The first piece of a paragraph queues the next one: no gap between paragraphs.
                if (utterance.first) controller.say(utterance.paragraph + 1, 0, true)
            }

            override fun onRange(utterance: Utterance, start: Int, end: Int) {
                if (utterance.token != token[0]) return
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
        onDispose {
            speaker.listener = null
            nav.listenController = null
        }
    }
    // Keep the spoken paragraph on screen.
    LaunchedEffect(spokenParagraph) {
        if (spokenParagraph < 0 || !listen.playing) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == spokenParagraph }
        if (item == null || item.offset + item.size > info.viewportEndOffset - 120) listState.animateScrollToItem(spokenParagraph)
    }

    val family = rememberReadingFontFamily(settings.font)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = family, fontSize = 18.sp * settings.textScale, lineHeight = 29.sp * settings.textScale, color = page.text,
    )
    val unread = SpanStyle(color = page.text.copy(alpha = 0.4f))
    val currentWord = SpanStyle(background = page.link.copy(alpha = 0.24f))
    val step = { listState.layoutInfo.viewportSize.height * 0.85f }
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
                Text(
                    spoken, style = style,
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 7.dp),
                )
            }
        }
    }
}

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

/** Turns list scrolling into a saved position and an overall 0..1 progress, once scrolling settles. */
@Composable
private fun TrackProgress(listState: LazyListState, count: Int, onProgress: ProgressSink) {
    val sink by rememberUpdatedState(onProgress)
    LaunchedEffect(listState, count) {
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
