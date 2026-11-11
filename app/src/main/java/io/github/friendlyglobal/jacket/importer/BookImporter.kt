package io.github.friendlyglobal.jacket.importer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.palette.graphics.Palette
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.voice.guessLanguage
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** What a format parser can tell us about a file. */
class BookMeta(
    val title: String?,
    val author: String?,
    val cover: Bitmap?,
    val sections: Int,
    /** The language the book is in, when the file says (EPUB) or it can be told from the text. */
    val language: String? = null,
)

object BookImporter {
    /** Offered to the system file picker. Generic types are included because many providers report EPUB/CBZ as zip or octet-stream. */
    val MIME_TYPES = arrayOf(
        "application/epub+zip",
        "application/pdf",
        "application/vnd.comicbook+zip",
        "application/x-cbz",
        "application/zip",
        "text/plain",
        "application/octet-stream",
    )

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun size(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    /** Copies [uri] to [dest] (via a temporary file, so a failed copy leaves nothing behind). */
    fun copyInto(context: Context, uri: Uri, dest: File) {
        val partial = File(dest.parentFile, dest.name + ".part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Can't open $uri")
            input.use { i -> partial.outputStream().use { i.copyTo(it) } }
            check(partial.renameTo(dest)) { "Couldn't store ${dest.name}" }
        } finally {
            partial.delete()
        }
    }

    /** Makes the cover again for a book whose file came back after a restore (covers aren't backed up). */
    fun restoreCover(book: Book, library: Library) {
        val coverName = book.coverFile ?: return
        val file = library.fileOf(book)
        val meta = runCatching {
            when (book.format) {
                BookFormat.EPUB -> Epub.readMeta(file)
                BookFormat.PDF -> Pdf.readMeta(file)
                BookFormat.CBZ -> Comic.readMeta(file)
                BookFormat.TXT -> BookMeta(null, null, null, 0)
            }
        }.getOrNull()
        val cover = meta?.cover ?: TitleCard.render(book.title, book.author)
        Bitmaps.saveJpeg(cover, File(library.coversDir, coverName), maxHeight = 1400)
    }

    /** Copies the file into app storage, reads its metadata and cover, and returns the new [Book]. */
    fun import(context: Context, uri: Uri, name: String, library: Library): Book {
        val id = UUID.randomUUID().toString()
        val partial = File(library.booksDir, "$id.part")
        val input = context.contentResolver.openInputStream(uri) ?: error("Can't open $uri")
        input.use { i -> partial.outputStream().use { i.copyTo(it) } }

        var stored: File? = null
        try {
            val format = detectFormat(name, context.contentResolver.getType(uri), partial)
                ?: error("Unsupported file: $name")
            stored = File(library.booksDir, "$id.${format.extension}")
            check(partial.renameTo(stored)) { "Couldn't store $name" }

            val meta = when (format) {
                BookFormat.EPUB -> Epub.readMeta(stored)
                BookFormat.PDF -> Pdf.readMeta(stored)
                BookFormat.CBZ -> Comic.readMeta(stored)
                // A text file doesn't say what language it is in: its first pages do.
                BookFormat.TXT -> BookMeta(null, null, null, 0, runCatching { guessLanguage(readStart(stored)) }.getOrNull())
            }
            val title = meta.title?.trim()?.takeIf { it.isNotEmpty() } ?: prettifyFileName(context, name)
            val author = meta.author?.trim().orEmpty()
            val cover = meta.cover ?: TitleCard.render(title, author)
            val coverName = "$id.jpg"
            Bitmaps.saveJpeg(cover, File(library.coversDir, coverName), maxHeight = 1400)

            return Book(
                id = id,
                title = title,
                author = author,
                format = format,
                fileName = stored.name,
                coverFile = coverName,
                seedColor = seedFrom(cover),
                sourceName = name,
                sizeBytes = stored.length(),
                addedAt = System.currentTimeMillis(),
                sectionCount = meta.sections,
                language = meta.language,
            )
        } catch (t: Throwable) {
            partial.delete()
            stored?.delete()
            throw t
        }
    }

    /** The first few pages of a text file, read as the reader will read it. */
    private fun readStart(file: File): String {
        val bytes = file.inputStream().use { input -> ByteArray(24_000).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private fun detectFormat(name: String, mime: String?, file: File): BookFormat? {
        when (name.substringAfterLast('.', "").lowercase()) {
            "epub" -> return BookFormat.EPUB
            "pdf" -> return BookFormat.PDF
            "cbz" -> return BookFormat.CBZ
            "txt", "text", "md", "markdown" -> return BookFormat.TXT
        }
        when (mime) {
            "application/epub+zip" -> return BookFormat.EPUB
            "application/pdf" -> return BookFormat.PDF
            "application/vnd.comicbook+zip", "application/x-cbz" -> return BookFormat.CBZ
        }
        if (mime?.startsWith("text/") == true) return BookFormat.TXT
        // Sniff the content as a last resort.
        val head = ByteArray(4).also { b -> file.inputStream().use { it.read(b) } }
        if (head.decodeToString().startsWith("%PDF")) return BookFormat.PDF
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return runCatching {
                ZipFile(file).use { zip ->
                    when {
                        zip.getEntry("META-INF/container.xml") != null -> BookFormat.EPUB
                        Comic.pageNames(zip).isNotEmpty() -> BookFormat.CBZ
                        else -> null
                    }
                }
            }.getOrNull()
        }
        return null
    }

    private fun prettifyFileName(context: Context, name: String) =
        name.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            .ifEmpty { context.getString(R.string.book_untitled) }

    private fun seedFrom(cover: Bitmap): Int? {
        val palette = Palette.from(cover).maximumColorCount(16).generate()
        return (palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch)?.rgb
    }
}
