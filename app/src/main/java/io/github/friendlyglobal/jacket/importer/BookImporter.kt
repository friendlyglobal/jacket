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
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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

/** What tells one file from any other: how many bytes it has, and their SHA-256 (as hex). */
data class FileContent(val size: Long, val sha256: String)

/** The file being added is one the library already has: [book] was made from the very same bytes. */
class AlreadyInLibrary(val book: Book) : Exception("Already in the library")

/** The text file being added has nothing in it to read. */
class NothingToRead(name: String) : Exception("Nothing to read in $name")

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

    /** What a file is called while it is being copied in: it is a book only once it is whole. */
    private const val PARTIAL = ".part"

    /**
     * Clears away copies that were never finished: Jacket was closed (or the phone turned off)
     * while a book was coming in. Nothing else would ever remove them, and a book is big. Only
     * for when no import is under way.
     */
    fun clearUnfinished(booksDir: File) {
        booksDir.listFiles { file -> file.isFile && file.name.endsWith(PARTIAL) }?.forEach { it.delete() }
    }

    /** Reads [input] to its end, copying it to [out] if there is one, and says what was in it. */
    internal fun read(input: InputStream, out: OutputStream? = null): FileContent {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var size = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out?.write(buffer, 0, n)
            digest.update(buffer, 0, n)
            size += n
        }
        return FileContent(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** What is in [file]; null when it can't be read (a book whose file isn't on this phone). */
    fun contentOf(file: File): FileContent? = runCatching { file.inputStream().use { read(it) } }.getOrNull()

    /** Copies [uri] to [dest] (via a temporary file, so a failed copy leaves nothing behind). */
    fun copyInto(context: Context, uri: Uri, dest: File) {
        val partial = File(dest.parentFile, dest.name + PARTIAL)
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

    /**
     * Copies the file into app storage, reads its metadata and cover, and returns the new [Book].
     *
     * Unless it is a file the library already has, which only the copy can tell: a file is the
     * same file by its bytes, not by its name ("book (1).epub" is "book.epub" downloaded again)
     * or by the size the app handing it over gives, which some don't. [known] is asked for the
     * book made from a file with that content. If there is one, nothing is added: its own file is
     * put back from this copy if it was missing (a book restored from a backup) and that book is
     * returned, and otherwise [AlreadyInLibrary] is thrown.
     */
    fun import(context: Context, uri: Uri, name: String, library: Library, known: (FileContent) -> Book? = { null }): Book {
        val id = UUID.randomUUID().toString()
        val partial = File(library.booksDir, "$id$PARTIAL")
        var stored: File? = null
        try {
            // Inside the try: a copy cut short (the phone ran out of room, the download behind the
            // file failed) must not leave what it had written behind, least of all on a full phone.
            val input = context.contentResolver.openInputStream(uri) ?: error("Can't open $uri")
            val content = input.use { i -> partial.outputStream().use { read(i, it) } }
            known(content)?.let { same ->
                val file = library.fileOf(same)
                if (file.exists()) throw AlreadyInLibrary(same)
                check(partial.renameTo(file)) { "Couldn't store $name" }
                if (library.coverOf(same) == null) runCatching { restoreCover(same, library) }
                return same
            }

            val format = detectFormat(name, context.contentResolver.getType(uri), partial)
                ?: error("Unsupported file: $name")
            // A text file with nothing in it would be a book that only ever opens on a message
            // saying so: it is refused here, with the same message.
            if (format == BookFormat.TXT && !hasText(partial)) throw NothingToRead(name)
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
                sha256 = content.sha256,
            )
        } catch (t: Throwable) {
            partial.delete()
            stored?.delete()
            File(library.coversDir, "$id.jpg").delete()
            throw t
        }
    }

    /**
     * Whether a text file has anything in it to read: a letter, a digit, a mark, anything but
     * spaces and line breaks (and the mark some editors put at the very start of a file). The
     * reader makes its paragraphs of the same, so a file without any opens on "This file is
     * empty or couldn't be read". Stops at the first thing it finds, however long the file.
     */
    internal fun hasText(file: File): Boolean = runCatching {
        file.reader(Charsets.UTF_8).buffered().use { text ->
            var c = text.read()
            if (c == BYTE_ORDER_MARK) c = text.read()
            while (c >= 0 && c.toChar().isWhitespace()) c = text.read()
            c >= 0
        }
    }.getOrDefault(false)

    private const val BYTE_ORDER_MARK = 0xFEFF

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
