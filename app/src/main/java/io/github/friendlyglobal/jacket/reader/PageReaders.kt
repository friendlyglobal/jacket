package io.github.friendlyglobal.jacket.reader

import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

/** Left and right edges page back and forward; the middle toggles the reader controls. */
@Composable
private fun Modifier.tapZones(onPrevious: () -> Unit, onNext: () -> Unit, onCenter: () -> Unit): Modifier {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val center by rememberUpdatedState(onCenter)
    return pointerInput(Unit) {
        detectTapGestures { offset ->
            val x = offset.x / size.width
            when {
                x < 0.28f -> previous()
                x > 0.72f -> next()
                else -> center()
            }
        }
    }
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
        nav.jumpTo = { target -> if (target in 0 until count) scope.launch { listState.animateScrollToItem(target) } }
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

    val step = { listState.layoutInfo.viewportSize.height * 0.85f }
    BoxWithConstraints(
        Modifier.fillMaxSize().tapZones(
            onPrevious = { scope.launch { listState.animateScrollBy(-step()) } },
            onNext = { scope.launch { listState.animateScrollBy(step()) } },
            onCenter = onCenterTap,
        ),
    ) {
        val widthPx = with(LocalDensity.current) { min(maxWidth, 900.dp).roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 64.dp),
        ) {
            items(count) { index -> PageImage(index, widthPx, placeholderAspect, page, render) }
        }
    }
}

@Composable
private fun PageImage(index: Int, widthPx: Int, placeholderAspect: Float, page: PageColors, render: suspend (Int, Int) -> Bitmap?) {
    val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
        value = runCatching { render(index, widthPx) }.getOrNull()?.asImageBitmap()
    }
    val aspect = bitmap?.let { it.width / it.height.toFloat() } ?: placeholderAspect
    Box(
        Modifier
            .widthIn(max = 900.dp)
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .shadow(2.dp)
            .background(if (bitmap == null) page.secondary.copy(alpha = 0.12f) else Color.White),
    ) {
        bitmap?.let { Image(it, stringResource(R.string.reader_page_number, index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
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
        nav.jumpTo = { target -> if (target in text.indices) scope.launch { listState.scrollToItem(target) } }
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

/** A light tick each time a new page scrolls into first place. */
@Composable
private fun PageTicks(listState: LazyListState, haptics: Haptics) {
    LaunchedEffect(listState) {
        var previous = -1
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (previous >= 0 && index != previous) haptics.tick()
            previous = index
        }
    }
}

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
