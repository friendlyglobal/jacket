package io.github.friendlyglobal.jacket.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
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

/**
 * The PDF is locked with a password, and wasn't given the one that opens it: it was given none,
 * or ([wrong]) one that isn't it.
 */
class PdfLocked(val wrong: Boolean) : Exception(if (wrong) "That password doesn't open this PDF" else "This PDF is locked with a password")

/** A PDF's pages, drawn by the phone's own PDF reader, whichever Android it is from. One page at a time. */
internal interface DrawnPdf : Closeable {
    val pageCount: Int

    /** How wide page [index] is for its height. */
    fun aspect(index: Int): Float

    /** Page [index] as a picture [width] pixels wide. */
    fun render(index: Int, width: Int): Bitmap
}

object Pdf {
    /**
     * Whether this phone's Android can open a PDF that is locked with a password: Android 15, or
     * Android 12 to 14 where Google's system updates have brought the newer PDF reader. They are
     * the same Androids that hand over a PDF's words (see [PdfWords.available]): one reader does both.
     */
    val canUnlock: Boolean get() = PdfWords.available

    /**
     * What a PDF says of itself, and its first page as its cover. A PDF locked with a password
     * says nothing until it is opened (its title is locked up with the rest): it is named by its
     * file, and given a plain cover, like any book that comes without one.
     */
    fun readMeta(file: File): BookMeta = try {
        val (title, author) = sniffInfo(file)
        open(file).use { pdf -> BookMeta(title, author, if (pdf.pageCount > 0) pdf.render(0, 900) else null, pdf.pageCount) }
    } catch (_: PdfLocked) {
        BookMeta(null, null, null, 0)
    }

    /**
     * Opens [file] to draw its pages; with [password], as a PDF locked with one. Throws
     * [PdfLocked] for a locked PDF given no password, or the wrong one, or on a phone whose
     * Android can't open locked PDFs at all (see [canUnlock]).
     */
    internal fun open(file: File, password: String? = null): DrawnPdf {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        // The reader owns the descriptor once it has opened, and closes it with itself.
        return try {
            when {
                password == null -> Drawn(PdfRenderer(pfd))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> unlocked(pfd, password)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PdfWords.PDF_EXTENSION -> unlockedEarlier(pfd, password)
                else -> throw PdfLocked(wrong = false)
            }
        } catch (e: SecurityException) {
            // How Android says a PDF needs a password, or was given the wrong one.
            pfd.close()
            throw PdfLocked(wrong = password != null)
        } catch (t: Throwable) {
            pfd.close()
            throw t
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun unlocked(pfd: ParcelFileDescriptor, password: String): DrawnPdf = Drawn(PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build()))

    /** Android 12 to 14 open a locked PDF with the reader Google's system updates brought them, and draw it with that reader too. */
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PdfWords.PDF_EXTENSION)
    private fun unlockedEarlier(pfd: ParcelFileDescriptor, password: String): DrawnPdf = DrawnEarlier(PdfRendererPreV(pfd, LoadParams.Builder().setPassword(password).build()))

    private class Drawn(private val renderer: PdfRenderer) : DrawnPdf {
        override val pageCount: Int get() = renderer.pageCount
        override fun aspect(index: Int): Float = renderer.openPage(index).use { it.width / it.height.toFloat() }
        override fun render(index: Int, width: Int): Bitmap = renderer.openPage(index).use { render(it, width) }
        override fun close() = renderer.close()
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = PdfWords.PDF_EXTENSION)
    private class DrawnEarlier(private val renderer: PdfRendererPreV) : DrawnPdf {
        override val pageCount: Int get() = renderer.pageCount
        override fun aspect(index: Int): Float = renderer.openPage(index).use { it.width / it.height.toFloat() }

        override fun render(index: Int, width: Int): Bitmap = renderer.openPage(index).use { page ->
            val height = (width * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            createBitmap(width, height).also {
                it.eraseColor(Color.WHITE)
                page.render(it, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
            }
        }

        override fun close() = renderer.close()
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
     * So is that of an encrypted PDF (one locked with a password, or only kept from being
     * changed): what it says of itself is encrypted too, and would read as noise.
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
        if (ENCRYPTED.containsMatchIn(text)) return null to null
        fun xmp(tag: String) = Regex("<dc:$tag>.*?<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }
        fun info(key: String) = Regex("/$key\\s*\\(((?:\\\\.|[^\\\\)])*)\\)").find(text)?.groupValues?.get(1)?.let(::decodePdfString)
        val title = (xmp("title") ?: info("Title"))?.trim()?.takeIf { it.isNotEmpty() && !it.equals("untitled", true) }
        val author = (xmp("creator") ?: info("Author"))?.trim()?.takeIf { it.isNotEmpty() }
        title to author
    }.getOrDefault(null to null)

    /** How a PDF's own table of where everything is says the file is encrypted. */
    private val ENCRYPTED = Regex("/Encrypt\\s*\\d+\\s+\\d+\\s+R|/Encrypt\\s*<<")

    private fun decodePdfString(raw: String): String {
        val unescaped = raw.replace(Regex("\\\\([()\\\\])"), "$1")
        val bytes = unescaped.toByteArray(Charsets.ISO_8859_1)
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        } else unescaped
    }
}

/**
 * A PDF opened for reading; with [password], one that is locked with it (throws [PdfLocked] as
 * [Pdf.open] does). The phone's PDF reader allows one open page at a time, hence the lock.
 */
class PdfDocument(file: File, password: String? = null) : Closeable {
    private val renderer: DrawnPdf = Pdf.open(file, password)
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount
    val firstPageAspect: Float = if (pageCount > 0) renderer.aspect(0) else 0.7f

    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        check(!closed) { "Document closed" }
        withContext(Dispatchers.IO) { renderer.render(index, width) }
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
