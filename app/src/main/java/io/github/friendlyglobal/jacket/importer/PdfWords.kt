package io.github.friendlyglobal.jacket.importer

import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import io.github.friendlyglobal.jacket.voice.guessLanguage
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * A PDF's words, from the phone's own PDF reader.
 *
 * Android draws PDF pages itself, and since Android 15 it also hands over a page's text and says
 * where any stretch of it is drawn. Phones on Android 12, 13 and 14 have the same reader where
 * Google's system updates have brought it (it comes with them, as "SDK extension 13"). Older
 * phones, and those that never had the update, have no way to get at a PDF's words short of
 * carrying a whole PDF reader inside Jacket, which it doesn't: there, PDFs can't be read aloud,
 * and Jacket says so. Nothing here needs the internet, and no library is added for it.
 *
 * The words are made into paragraphs (PdfText.kt) and written as a small book of plain pages, the
 * same way a text file is (TextBook.kt). The voice reads that book: so a PDF is read by the same
 * voice and the same player as every other book, with the screen off or the book shut. The PDF
 * stays the book; these pages are only what is read from, kept so they needn't be made again, and
 * made again whenever they are missing or were made by an older Jacket.
 */

/** Whether this phone's Android can hand over the words of a PDF. */
internal object PdfWords {
    val available: Boolean by lazy {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_EXTENSION
            else -> false
        }
    }

    /** The Android system update ("SDK extension") that brought the PDF reader's text to Android 12 to 14. */
    const val PDF_EXTENSION = 13
}

/** What the PDF reader says of a stretch of a page's text: the words, and the boxes they are drawn in (one for each line). */
private class Selected(val text: String, val boxes: List<RectF>)

/** A page open in the PDF reader, whichever Android it is from. */
private interface OpenPage {
    val width: Int
    val height: Int

    /** All of the page's text, its lines parted by line breaks. */
    fun text(): String

    /** The text from [from] until [to], by the reader's own count; null if it has none there. */
    fun select(from: Int, to: Int): Selected?
}

private interface OpenPdf : Closeable {
    val pageCount: Int
    fun <T> page(index: Int, read: (OpenPage) -> T): T
}

/** Opens a PDF with [open], which may be given a password: Android says a PDF is locked, or the password wrong, by a SecurityException. */
private inline fun <T> opening(file: File, password: String?, open: (ParcelFileDescriptor) -> T): T {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        open(pfd)
    } catch (e: SecurityException) {
        pfd.close()
        throw PdfLocked(wrong = password != null)
    } catch (t: Throwable) {
        pfd.close()
        throw t
    }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private class Android15Pdf(file: File, password: String?) : OpenPdf {
    private val renderer = opening(file, password) { pfd ->
        if (password == null) PdfRenderer(pfd) else PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
    }
    override val pageCount: Int get() = renderer.pageCount

    override fun <T> page(index: Int, read: (OpenPage) -> T): T = renderer.openPage(index).use { page ->
        read(object : OpenPage {
            override val width get() = page.width
            override val height get() = page.height
            override fun text() = page.textContents.joinToString("") { it.text }
            override fun select(from: Int, to: Int): Selected? =
                page.selectContent(SelectionBoundary(from), SelectionBoundary(to))?.selectedTextContents?.let { found ->
                    Selected(found.joinToString("") { it.text }, found.flatMap { it.bounds })
                }
        })
    }

    override fun close() = renderer.close()
}

/** The same reader as Android 15's, as Google's system updates bring it to Android 12, 13 and 14. */
@RequiresExtension(extension = Build.VERSION_CODES.S, version = PdfWords.PDF_EXTENSION)
private class EarlierPdf(file: File, password: String?) : OpenPdf {
    private val renderer = opening(file, password) { pfd ->
        if (password == null) PdfRendererPreV(pfd) else PdfRendererPreV(pfd, LoadParams.Builder().setPassword(password).build())
    }
    override val pageCount: Int get() = renderer.pageCount

    override fun <T> page(index: Int, read: (OpenPage) -> T): T = renderer.openPage(index).use { page ->
        read(object : OpenPage {
            override val width get() = page.width
            override val height get() = page.height
            override fun text() = page.textContents.joinToString("") { it.text }
            override fun select(from: Int, to: Int): Selected? =
                page.selectContent(SelectionBoundary(from), SelectionBoundary(to))?.selectedTextContents?.let { found ->
                    Selected(found.joinToString("") { it.text }, found.flatMap { it.bounds })
                }
        })
    }

    override fun close() = renderer.close()
}

/**
 * A PDF opened for its words; with [password], one that is locked with it (throws [PdfLocked]
 * for a locked PDF given none, or the wrong one). Not for more than one thread at a time: the PDF
 * reader has one page open at once.
 */
internal class PdfWordsFile(file: File, password: String? = null) : Closeable {
    private val pdf: OpenPdf = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> Android15Pdf(file, password)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PdfWords.PDF_EXTENSION -> EarlierPdf(file, password)
        else -> throw UnsupportedOperationException("This Android can't read a PDF's text")
    }

    val pageCount: Int get() = pdf.pageCount

    /** The text of page [index]: its lines, each with the box it is drawn in. A page that can't be read has none. */
    fun page(index: Int): PdfPageText = runCatching { pdf.page(index, ::linesOf) }.getOrElse { PdfPageText(0f, 0f, emptyList()) }

    /**
     * Where the stretches [ranges] of page [index]'s text are drawn, each given by the reader's
     * own count (see [PdfLine.start]): for each, a box for every line it is on. Empty where the
     * reader can't say.
     */
    fun boxes(index: Int, ranges: List<IntRange>): List<List<RectF>> = runCatching {
        pdf.page(index) { page -> ranges.map { range -> runCatching { page.select(range.first, range.last + 1)?.boxes }.getOrNull().orEmpty() } }
    }.getOrElse { ranges.map { emptyList() } }

    override fun close() {
        runCatching { pdf.close() }
    }

    /**
     * The lines of [page]. The reader gives the page's text whole, and is then asked, line by
     * line, where each is drawn. It is asked by its own count of the text, which is not quite the
     * text's: a word broken at the end of a line is counted as if the break weren't there. So each
     * answer is checked against the line it was asked for, and the count kept right as it goes;
     * where the two can't be squared, the lines from there on keep their words and lose their
     * boxes (they are read, in the order the file has them, and not marked on the page).
     */
    private fun linesOf(page: OpenPage): PdfPageText {
        val text = page.text()
        val lines = ArrayList<PdfLine>()
        var count = 0
        var counted = true
        var at = 0
        var last: RectF? = null
        while (at < text.length) {
            val end = text.indexOfAny(LINE_BREAKS, at).let { if (it < 0) text.length else it }
            val broken = when {
                end >= text.length -> 0
                text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n' -> 2
                else -> 1
            }
            val words = text.substring(at, end)
            val letters = words.codePointCount(0, words.length)
            at = end + broken
            if (words.isBlank()) {
                count += letters + broken
                continue
            }
            var found: Selected? = null
            if (counted) {
                // Where it should be; failing that, a little either side.
                for (off in NEARBY) {
                    if (count + off < 0) continue
                    val tried = runCatching { page.select(count + off, count + off + letters) }.getOrNull()
                    if (tried != null && tried.text.startsWith(words)) {
                        count += off
                        found = tried
                        break
                    }
                }
            }
            val box = found?.boxes?.takeIf { it.isNotEmpty() }?.let { boxes -> RectF(boxes.first()).apply { boxes.forEach(::union) } }
            if (found == null || box == null) {
                // Lost: set under the last line that was found, to be read after it.
                counted = false
                val under = last?.let { RectF(it.left, it.bottom + 2f, it.right, it.bottom + 2f + it.height()) } ?: RectF(0f, 0f, page.width.toFloat(), 10f)
                lines += PdfLine(words, under.left, under.top, under.right, under.bottom, start = PdfLine.UNPLACED)
                last = under
                continue
            }
            // Letters outside the basic plane are two to the text and one to the reader: such a line is marked whole.
            lines += PdfLine(words, box.left, box.top, box.right, box.bottom, start = if (letters == words.length) count else -1)
            last = box
            // A word broken at the line's end comes back with its line break: the reader counts the two as one.
            count += letters + if (found.text.length > words.length) 0 else broken
        }
        return PdfPageText(page.width.toFloat(), page.height.toFloat(), lines)
    }

    private companion object {
        val LINE_BREAKS = charArrayOf('\r', '\n')
        val NEARBY = listOf(0, 1, -1, 2, -2, 3, -3, 4, -4)
    }
}

/** The pages made of a PDF for the voice to read: the [file] Readium opens, and where their paragraphs are on the PDF's own pages. */
internal class PdfPages(val file: File, val layout: PdfLayout)

/** A PDF with no words in it: every page is a picture, as a scan's are. */
class NoWordsInPdf(name: String) : Exception("No text in $name")

/** A PDF this phone's Android can't get the words of (see [PdfWords.available]). */
class PdfWordsUnavailable : Exception("This Android can't read a PDF's text")

/** Making, keeping and reading back the pages a PDF is read aloud from. */
internal object PdfSpokenBook {
    /** Goes up whenever the same PDF would be given other pages: those made by an older number are made again. */
    private const val VERSION = 1
    private const val LAYOUT = "jacket/layout.bin"
    private const val PACKAGE = "content.opf"
    private const val NAV = "nav.xhtml"

    /** How many of a PDF's first words its language is told from. */
    private const val LANGUAGE_SAMPLE = 20_000

    /** What tells that pages kept in a file were made from [source] as it is now, by this version. */
    private fun stamp(source: File): String = "$STAMP $VERSION.${PdfLayout.VERSION} ${source.length()}"

    private const val STAMP = "jacket-pdf-book"

    /** Ends the stamp of pages made of a PDF that is locked with a password. */
    private const val LOCKED = " locked"

    /** The layout kept in [pages], if they were made from [source] by this version; null if they must be made again. */
    fun kept(pages: File, source: File): PdfLayout? = runCatching {
        ZipFile(pages).use { zip ->
            if (zip.comment?.removeSuffix(LOCKED) != stamp(source)) return null
            DataInputStream(zip.getInputStream(zip.getEntry(LAYOUT)).buffered()).use(PdfLayout::read)
        }
    }.getOrNull()

    /**
     * Whether the pages kept in [pages] are those of a PDF locked with a password. They hold its
     * words unlocked, so they are kept only while its password is remembered, and read only with
     * the password to hand (see Library.pdfPages).
     */
    fun locked(pages: File): Boolean = runCatching { ZipFile(pages).use { zip -> zip.comment?.let { it.startsWith(STAMP) && it.endsWith(LOCKED) } == true } }.getOrDefault(false)

    /** Whether the pages kept in [pages] say that their PDF has no words: they were made, and came out empty. */
    fun wordless(pages: File): Boolean = runCatching {
        ZipFile(pages).use { zip -> zip.comment?.startsWith(STAMP) == true && zip.getEntry(LAYOUT) != null && zip.getEntry(PACKAGE) == null }
    }.getOrDefault(false)

    /**
     * Reads the words of the PDF [source] and writes the pages made of them to [out] (and closes
     * it): a small EPUB, a file for each chapter of the PDF's outline, every paragraph named by
     * its number. A PDF with no words gives a file with no pages, which says so the next time.
     *
     * With [password], the PDF is one locked with it. Throws [PdfLocked] for a locked PDF given
     * none, or the wrong one.
     */
    fun make(source: File, out: OutputStream, id: String, title: String, author: String, language: String?, password: String? = null): PdfLayout {
        val pages = PdfWordsFile(source, password).use { pdf -> List(pdf.pageCount) { pdf.page(it) } }
        val paragraphs = PdfReading.paragraphs(pages)
        val layout = PdfLayout.of(pages, paragraphs, if (paragraphs.isEmpty()) emptyList() else PdfOutline.read(source))
        val said = language ?: runCatching {
            guessLanguage(buildString { for (p in paragraphs) { if (length > LANGUAGE_SAMPLE) break; append(p.text).append('\n') } })
        }.getOrNull()
        ZipOutputStream(out).use { zip ->
            zip.setComment(stamp(source) + if (password != null) LOCKED else "")
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            zip.closeEntry()
            fun file(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(LAYOUT))
            DataOutputStream(zip).also(layout::write).flush()
            zip.closeEntry()
            if (paragraphs.isEmpty()) return@use
            // The chapters the voice can be sent to: those that open at a paragraph.
            val chapters = layout.chapters.filter { it.paragraph >= 0 }
            file("META-INF/container.xml", CONTAINER)
            file(PACKAGE, packageOf(layout, id, title, author, said, chapters.isNotEmpty()))
            if (chapters.isNotEmpty()) file(NAV, navOf(layout, chapters, title))
            for (part in 0 until layout.fileCount) file(PdfLayout.hrefOfFile(part), pageOf(layout, paragraphs, part, title, said))
        }
        return layout
    }

    private fun packageOf(layout: PdfLayout, id: String, title: String, author: String, language: String?, contents: Boolean) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"id\">urn:jacket:pdf:").append(escaped(id)).append("</dc:identifier>\n")
        append("<dc:title>").append(escaped(title)).append("</dc:title>\n")
        if (author.isNotBlank()) append("<dc:creator>").append(escaped(author)).append("</dc:creator>\n")
        // A PDF whose language couldn't be told says nothing: the voice then goes by the phone's.
        if (!language.isNullOrBlank()) append("<dc:language>").append(escaped(language)).append("</dc:language>\n")
        append("<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n")
        append("</metadata>\n<manifest>\n")
        if (contents) append("<item id=\"nav\" href=\"$NAV\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        for (part in 0 until layout.fileCount) append("<item id=\"part${part + 1}\" href=\"${PdfLayout.hrefOfFile(part)}\" media-type=\"application/xhtml+xml\"/>\n")
        append("</manifest>\n<spine>\n")
        for (part in 0 until layout.fileCount) append("<itemref idref=\"part${part + 1}\"/>\n")
        append("</spine>\n</package>\n")
    }

    private fun navOf(layout: PdfLayout, chapters: List<PdfChapter>, title: String) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
        append("<head><meta charset=\"utf-8\"/><title>").append(escaped(title)).append("</title></head>\n")
        append("<body>\n<nav epub:type=\"toc\">\n<ol>\n")
        // No level is skipped on the way down, whatever the outline does.
        var deepest = -1
        val levels = chapters.map { chapter -> chapter.level.coerceIn(0, deepest + 1).also { deepest = it } }
        chapters.forEachIndexed { index, chapter ->
            val first = layout.firstIn(layout.fileOf(chapter.paragraph)) == chapter.paragraph
            val href = layout.hrefOf(chapter.paragraph) + if (first) "" else "#" + layout.idOf(chapter.paragraph)
            append("<li><a href=\"").append(href).append("\">").append(escaped(chapter.title)).append("</a>")
            val next = levels.getOrNull(index + 1) ?: 0
            if (next > levels[index]) {
                append("\n<ol>\n")
            } else {
                append("</li>\n")
                repeat(levels[index] - next) { append("</ol>\n</li>\n") }
            }
        }
        append("</ol>\n</nav>\n</body>\n</html>\n")
    }

    private fun pageOf(layout: PdfLayout, paragraphs: List<PdfParagraph>, part: Int, title: String, language: String?) = buildString {
        val said = language?.takeIf { it.isNotBlank() }?.let(::escaped)
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\"")
        if (said != null) append(" lang=\"$said\" xml:lang=\"$said\"")
        append(">\n<head><meta charset=\"utf-8\"/><title>").append(escaped(title)).append("</title></head>\n<body>\n")
        for (index in paragraphs.indices) {
            if (layout.fileOf(index) != part) continue
            append("<p id=\"").append(layout.idOf(index)).append("\" dir=\"auto\">").append(escaped(paragraphs[index].text)).append("</p>\n")
        }
        append("</body>\n</html>\n")
    }

    /** [text] as it is written in an XML file. (What such a file can't hold at all was left out when the paragraphs were made.) */
    private fun escaped(text: String): String = buildString(text.length + 16) {
        for (c in TextBook.xmlSafe(text)) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private const val CONTAINER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
        "<rootfiles><rootfile full-path=\"$PACKAGE\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n</container>\n"
}
