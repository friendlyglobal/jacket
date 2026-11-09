package io.github.friendlyglobal.jacket.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

object Pdf {
    fun readMeta(file: File): BookMeta {
        val (title, author) = sniffInfo(file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val cover = if (renderer.pageCount > 0) renderer.openPage(0).use { render(it, 900) } else null
                BookMeta(title, author, cover, renderer.pageCount)
            }
        }
    }

    fun render(page: PdfRenderer.Page, width: Int): Bitmap {
        val height = (width * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
        return createBitmap(width, height).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    /**
     * Best-effort title/author from the document Info dictionary or XMP packet. Only looks at
     * plain-text regions near the start and end of the file; compressed metadata is skipped.
     */
    private fun sniffInfo(file: File): Pair<String?, String?> = runCatching {
        val text = RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            fun chunk(from: Long, size: Int): String {
                val n = minOf(size.toLong(), len - from).toInt().coerceAtLeast(0)
                val bytes = ByteArray(n)
                raf.seek(from); raf.readFully(bytes)
                return String(bytes, Charsets.ISO_8859_1)
            }
            chunk(0, 512 * 1024) + chunk((len - 256 * 1024).coerceAtLeast(0), 256 * 1024)
        }
        fun xmp(tag: String) = Regex("<dc:$tag>.*?<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }
        fun info(key: String) = Regex("/$key\\s*\\(((?:\\\\.|[^\\\\)])*)\\)").find(text)?.groupValues?.get(1)?.let(::decodePdfString)
        val title = (xmp("title") ?: info("Title"))?.trim()?.takeIf { it.isNotEmpty() && !it.equals("untitled", true) }
        val author = (xmp("creator") ?: info("Author"))?.trim()?.takeIf { it.isNotEmpty() }
        title to author
    }.getOrDefault(null to null)

    private fun decodePdfString(raw: String): String {
        val unescaped = raw.replace(Regex("\\\\([()\\\\])"), "$1")
        val bytes = unescaped.toByteArray(Charsets.ISO_8859_1)
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        } else unescaped
    }
}

/** A PDF opened for reading. [PdfRenderer] allows one open page at a time, hence the lock. */
class PdfDocument(file: File) : Closeable {
    private val renderer: PdfRenderer = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).let { pfd ->
        // The renderer owns the descriptor from here on, and closes it with itself.
        try { PdfRenderer(pfd) } catch (t: Throwable) { pfd.close(); throw t }
    }
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount
    val firstPageAspect: Float = if (pageCount > 0) renderer.openPage(0).use { it.width / it.height.toFloat() } else 0.7f

    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        check(!closed) { "Document closed" }
        withContext(Dispatchers.IO) { renderer.openPage(index).use { Pdf.render(it, width) } }
    }

    /** Waits for a page still rendering, then closes off the main thread. */
    override fun close() {
        DocumentCloser.launch {
            lock.withLock {
                closed = true
                runCatching { renderer.close() }
            }
        }
    }
}

/** Closes documents after the reader that used them is gone. */
internal object DocumentCloser : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
