package io.github.friendlyglobal.jacket.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.importer.Comic
import io.github.friendlyglobal.jacket.importer.PdfDocument
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.importer.DocumentCloser
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
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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

@Composable
fun PdfReader(book: Book, nav: ReaderNav, onProgress: ProgressSink) {
    val library = LocalContext.current.library
    val result = rememberDocument(book.id) { PdfDocument(library.fileOf(book)) }
    when {
        result == null -> ReaderLoading()
        result.isFailure -> ReaderError("This PDF couldn't be opened. Password-protected PDFs aren't supported yet.")
        else -> {
            val pdf = result.getOrThrow()
            PagedList(pdf.pageCount, book.position, pdf.firstPageAspect, nav, onProgress) { index, width -> pdf.render(index, width) }
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
fun ComicReader(book: Book, nav: ReaderNav, onProgress: ProgressSink) {
    val library = LocalContext.current.library
    val result = rememberDocument(book.id) { ComicDocument(library.fileOf(book)) }
    when {
        result == null -> ReaderLoading()
        result.isFailure || result.getOrThrow().pages.isEmpty() -> ReaderError("This comic couldn't be opened.")
        else -> {
            val comic = result.getOrThrow()
            PagedList(comic.pages.size, book.position, 0.66f, nav, onProgress) { index, width -> comic.render(index, width) }
        }
    }
}

@Composable
private fun PagedList(
    count: Int,
    initial: Int,
    placeholderAspect: Float,
    nav: ReaderNav,
    onProgress: ProgressSink,
    render: suspend (index: Int, width: Int) -> Bitmap?,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial.coerceIn(0, (count - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    SideEffect {
        nav.canPrevious = first > 0
        nav.canNext = first < count - 1
        nav.label = "${first + 1} / $count"
        nav.onPrevious = { scope.launch { listState.animateScrollToItem((first - 1).coerceAtLeast(0)) } }
        nav.onNext = { scope.launch { listState.animateScrollToItem((first + 1).coerceAtMost(count - 1)) } }
    }
    TrackProgress(listState, count, onProgress)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPage = min(maxWidth, 900.dp)
        val widthPx = with(LocalDensity.current) { maxPage.roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        ) {
            items(count) { index -> PageImage(index, widthPx, placeholderAspect, render) }
        }
    }
}

@Composable
private fun PageImage(index: Int, widthPx: Int, placeholderAspect: Float, render: suspend (Int, Int) -> Bitmap?) {
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
            .background(Color.White),
    ) {
        bitmap?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
fun TextReader(book: Book, textScale: Float, nav: ReaderNav, onProgress: ProgressSink) {
    val library = LocalContext.current.library
    val paragraphs by produceState<List<String>?>(null, book.id) {
        value = withContext(Dispatchers.IO) { runCatching { readParagraphs(library.fileOf(book)) }.getOrDefault(emptyList()) }
    }
    val text = paragraphs ?: return ReaderLoading()
    if (text.isEmpty()) return ReaderError("This file is empty or couldn't be read.")

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = book.position.coerceIn(0, text.lastIndex))
    val scope = rememberCoroutineScope()
    val pct = (book.progress * 100).toInt()
    // Read here (not inside SideEffect) so changes recompose and refresh the toolbar.
    val canBack = listState.canScrollBackward
    val canForward = listState.canScrollForward
    SideEffect {
        nav.canPrevious = canBack
        nav.canNext = canForward
        nav.label = "$pct%"
        val page = { listState.layoutInfo.viewportSize.height * 0.85f }
        nav.onPrevious = { scope.launch { listState.animateScrollBy(-page()) } }
        nav.onNext = { scope.launch { listState.animateScrollBy(page()) } }
    }
    TrackProgress(listState, text.size, onProgress)

    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Serif, fontSize = 18.sp * textScale, lineHeight = 29.sp * textScale,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 16.dp, bottom = 150.dp),
    ) {
        items(text) { paragraph ->
            Text(
                paragraph, style = style,
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 7.dp),
            )
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
