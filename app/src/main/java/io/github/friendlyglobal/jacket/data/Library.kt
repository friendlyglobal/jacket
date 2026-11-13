package io.github.friendlyglobal.jacket.data

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.importer.AlreadyInLibrary
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.importer.Fb2
import io.github.friendlyglobal.jacket.importer.Fb2Book
import io.github.friendlyglobal.jacket.importer.Fb2Pages
import io.github.friendlyglobal.jacket.importer.FileContent
import io.github.friendlyglobal.jacket.importer.NoWordsInPdf
import io.github.friendlyglobal.jacket.importer.NothingToRead
import io.github.friendlyglobal.jacket.importer.PdfLocked
import io.github.friendlyglobal.jacket.importer.PdfPages
import io.github.friendlyglobal.jacket.importer.PdfSpokenBook
import io.github.friendlyglobal.jacket.importer.PdfWords
import io.github.friendlyglobal.jacket.importer.PdfWordsUnavailable
import io.github.friendlyglobal.jacket.importer.TextBook
import io.github.friendlyglobal.jacket.importer.TextDecoding
import io.github.friendlyglobal.jacket.importer.TextLayout
import io.github.friendlyglobal.jacket.importer.TextSpot
import io.github.friendlyglobal.jacket.voice.SayIt
import io.github.friendlyglobal.jacket.voice.guessLanguage
import io.github.friendlyglobal.jacket.widget.WidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.friendlyglobal.jacket.importer.Bitmaps
import java.io.File
import java.io.FileOutputStream

/**
 * What came of adding files: the books they are ([imported]), and the names of the files that
 * couldn't be read ([failed]). Some of [imported] may have been in the library before, their
 * files with them ([already]): they are there to be opened, and nothing was added for them.
 * And some of [failed] may be text files with nothing in them ([empty]).
 */
data class ImportReport(
    val imported: List<Book>,
    val failed: List<String>,
    val already: List<Book> = emptyList(),
    val empty: List<String> = emptyList(),
) {
    /** What happened, for a snackbar; empty when nothing was imported or failed. */
    fun message(resources: Resources): String {
        val here = already.distinctBy { it.id }
        val new = imported.filter { book -> here.none { it.id == book.id } }
        val added = when (new.size) {
            0 -> ""
            1 -> resources.getString(R.string.import_added_one, new[0].title)
            else -> resources.getQuantityString(R.plurals.import_added_books, new.size, new.size)
        }
        val alreadyHere = when (here.size) {
            0 -> ""
            1 -> resources.getString(R.string.import_already_one, here[0].title)
            else -> resources.getQuantityString(R.plurals.import_already_books, here.size, here.size)
        }
        val couldNotRead = when {
            failed.isEmpty() -> ""
            // One file with nothing in it, and nothing else to say: what the reader says of such a file.
            imported.isEmpty() && failed.size == 1 && empty.size == 1 -> resources.getString(R.string.reader_error_text)
            else -> resources.getString(R.string.import_failed, failed.joinToString())
        }
        // Each is a sentence of its own; whichever there are, are said one after another.
        return listOf(added, alreadyHere, couldNotRead).filter { it.isNotEmpty() }
            .reduceOrNull { said, next -> resources.getString(R.string.import_added_and_failed, said, next) }.orEmpty()
    }
}

/**
 * The book among [books] that was made from a file with this [content], if there is one: the same
 * number of bytes and the same SHA-256, whatever the file was called. A book added before Jacket
 * kept its SHA-256 has its file read for it ([read]; null when the file isn't on this phone), and
 * only if its size is right: no other file is read. So a book restored from a backup, still
 * waiting for its file, is known by its bytes only if the backup kept their SHA-256.
 *
 * Books that are in the library twice already stay as they are: the first is the one found.
 */
internal fun bookMadeFrom(books: List<Book>, content: FileContent, read: (Book) -> FileContent?): Book? =
    books.firstOrNull { book -> book.sizeBytes == content.size && (book.sha256 ?: read(book)?.sha256) == content.sha256 }

/**
 * A text book ready to be read: the pages made of its file ([file], an EPUB; see TextBook.kt),
 * where its paragraphs are in them ([layout]), and where in them the place kept as a paragraph is
 * ([saved]; null for a book never opened).
 */
internal class TextPages(val file: File, val layout: TextLayout, val saved: TextSpot?)

/**
 * The on-device library: imported files live in app storage, metadata in one JSON file.
 * Nothing leaves the device.
 */
class Library(private val context: Context) : LibraryOrganizer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private val stateFile = AtomicFile(File(context.filesDir, "library.json"))

    /** The library as it was when Jacket last started: what is read if [stateFile] can't be. */
    private val previousFile = File(context.filesDir, "library.previous.json")
    private val importLock = Mutex()

    /** A save has been asked for. However many changes come while it waits, one save takes them all. */
    private val saves = Channel<Unit>(Channel.CONFLATED)

    /** The state last written to [stateFile] (or read from it, whole, at the start). */
    @Volatile private var written: LibraryState? = null

    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * The pages made of text books. They can always be made again from the text files, so they
     * are kept out of backups and are no part of the library.
     */
    private val textPagesDir = File(context.noBackupFilesDir, "text-books")
    private val textPagesLock = Mutex()

    /** The pages made of FB2 books (see Fb2Book.kt): like a text book's, made again from the file whenever they are missing. */
    private val fb2PagesDir = File(context.noBackupFilesDir, "fb2-books")
    private val fb2PagesLock = Mutex()

    /** The pages PDFs are read aloud from (see PdfWords.kt): made from the PDFs, and made again whenever they are missing. */
    private val pdfPagesDir = File(context.noBackupFilesDir, "pdf-books")
    private val pdfPagesLock = Mutex()

    /**
     * PDFs found to have no words in them (scans). Their format can be read aloud, they can't: a
     * Play from a headphone button passes them over for a book that can. Known from the pages
     * kept of them, so it is still known the next time Jacket starts.
     */
    private val _wordless = MutableStateFlow<Set<String>>(emptySet())
    val wordless: StateFlow<Set<String>> = _wordless.asStateFlow()

    /**
     * The passwords of PDFs locked with one: held while Jacket runs, and kept on this phone only
     * where the reader asked (see [PdfPasswords]). No part of the library, a backup or catch-up.
     */
    internal val passwords = PdfPasswords(File(context.noBackupFilesDir, "pdf-passwords.json"), KeystoreSeal)

    /** The books a Play can't read: their file isn't on this phone, they are PDFs with no words, or locked PDFs nobody has opened yet. */
    val unreadable: Set<String> get() = _missing.value + _wordless.value + passwords.waiting.value

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Books whose file isn't on this device (e.g. after restoring a backup): add the file again. */
    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** What the open reader is doing right now (see [ReadingMoment]); null while no book is open. */
    private val _moment = MutableStateFlow<ReadingMoment?>(null)
    val moment: StateFlow<ReadingMoment?> = _moment.asStateFlow()

    /** The reader reports in as the chapter, the place in it or read-aloud changes. */
    fun report(moment: ReadingMoment) {
        val before = _moment.value
        _moment.value = moment
        // The widget shows the chapter and whether you're listening, not the place in the chapter.
        if (before?.copy(chapterProgress = moment.chapterProgress) != moment) WidgetSync.request(context)
    }

    /** The reader of [bookId] has closed. Another book's reader may already have reported in. */
    fun endMoment(bookId: String) {
        if (_moment.value?.bookId != bookId) return
        _moment.value = null
        WidgetSync.request(context)
    }

    init {
        // Changes are saved half a second after the first of them. A change never puts a waiting
        // save off: turned in a hurry, pages would otherwise go unsaved for as long as they turn.
        scope.launch {
            for (ask in saves) {
                delay(SAVE_AFTER_MS)
                withContext(disk) { writeState(_state.value) }
            }
        }
        // Jacket 0.2 unpacked EPUBs to disk; Readium reads the files directly, so free that space.
        scope.launch(disk) { File(context.filesDir, "unpacked").deleteRecursively() }
        scope.launch(disk) { _missing.value = scanMissing(_state.value) }
        // Pages left behind by text books that are gone, or by a making of them that was cut short.
        scope.launch(disk) {
            // (Not while pages are being made: theirs is a file here that isn't a book's yet.)
            textPagesLock.withLock {
                val kept = _state.value.books.mapTo(HashSet()) { textPagesOf(it.id).name }
                textPagesDir.listFiles()?.filter { it.name !in kept }?.forEach { it.delete() }
            }
        }
        scope.launch(disk) {
            fb2PagesLock.withLock {
                val kept = _state.value.books.mapTo(HashSet()) { fb2PagesOf(it.id).name }
                fb2PagesDir.listFiles()?.filter { it.name !in kept }?.forEach { it.delete() }
            }
        }
        scope.launch(disk) {
            pdfPagesLock.withLock {
                passwords.load()
                val kept = _state.value.books.mapTo(HashSet()) { pdfPagesOf(it.id).name }
                pdfPagesDir.listFiles()?.filter { it.name !in kept }?.forEach { it.delete() }
                // The words of a locked PDF are kept only while its password is remembered: one
                // opened with a password that wasn't to be kept leaves nothing behind it.
                val remembered = passwords.remembered.value
                _state.value.books.filter { it.format == BookFormat.PDF && it.id !in remembered }.map { pdfPagesOf(it.id) }
                    .filter(PdfSpokenBook::locked).forEach { it.delete() }
                val scans = _state.value.books.filter { it.format == BookFormat.PDF && PdfSpokenBook.wordless(pdfPagesOf(it.id)) }.map { it.id }
                _wordless.update { it + scans }
            }
        }
        // Widgets placed before this process started (after an update, a restart) redraw now, rather
        // than sit on their loading layout until the next page is turned.
        WidgetSync.request(context)
    }

    private fun scanMissing(state: LibraryState): Set<String> =
        state.books.filterNot { fileOf(it).exists() }.mapTo(HashSet()) { it.id }

    fun fileOf(book: Book) = File(booksDir, book.fileName)
    fun coverOf(book: Book): File? = coverPathOf(book)?.takeIf { it.exists() }

    /** Where the cover lives, without touching the disk (safe to call during composition). */
    fun coverPathOf(book: Book): File? = book.coverFile?.let { File(coversDir, it) }

    private fun textPagesOf(id: String) = File(textPagesDir, "$id.epub")

    /**
     * The pages of [book], a text file: made now if they aren't there, were made by an older
     * Jacket, or are older than the file (it came back after a restore). The file itself is never
     * touched. Throws if it can't be read; a file with nothing in it gives a layout of no paragraphs.
     */
    internal suspend fun textPages(book: Book): TextPages = withContext(Dispatchers.IO) {
        textPagesLock.withLock {
            val source = fileOf(book)
            val bytes = source.readBytes()
            val text = TextBook.of(TextDecoding.decode(bytes), TextBook.isMarkdown(book.sourceName))
            val pages = textPagesOf(book.id)
            // As it is now: the title may have been put right since the book was handed over.
            var now = _state.value.find(book.id) ?: book
            if (text.parts.isNotEmpty() && (pages.lastModified() < source.lastModified() || !TextBook.isCurrent(pages))) {
                textPagesDir.mkdirs()
                // Whole or not at all: pages cut short would open as a damaged book.
                val partial = File(textPagesDir, "${pages.name}.part")
                try {
                    val told = runCatching { guessLanguage(text.paragraphs.asSequence().take(200).joinToString("\n")) }.getOrNull()
                    // A file an older Jacket misread (UTF-16, Cyrillic, Japanese...) had its language
                    // told from the wrong letters, and would be read aloud in it. It is told again
                    // from the right ones, once: the first time such a book is given pages.
                    if (told != null && told != now.language && now.locator == null && !pages.exists() && !TextDecoding.isUtf8(bytes)) {
                        setLanguage(now.id, told)
                        now = now.copy(language = told)
                    }
                    val language = now.language ?: told
                    partial.outputStream().buffered().use { text.write(it, TextBook.About(now.id, now.title, now.author, language)) }
                    check(partial.renameTo(pages)) { "Couldn't store the pages of ${book.sourceName}" }
                } finally {
                    partial.delete()
                }
            }
            val layout = text.layout()
            TextPages(pages, layout, layout.saved(now.position, now.positionOffset, now.sectionCount)?.let(text::spotOf))
        }
    }

    private fun fb2PagesOf(id: String) = File(fb2PagesDir, "$id.epub")

    /**
     * The pages of [book], an FB2 book: made now if they aren't there, or were made by an older
     * Jacket or from another file (it came back after a restore). The file itself is never
     * touched. Throws if it can't be read as an FB2 book.
     */
    internal suspend fun fb2Pages(book: Book): File = withContext(Dispatchers.IO) {
        fb2PagesLock.withLock {
            val source = fileOf(book)
            val pages = fb2PagesOf(book.id)
            if (!Fb2Pages.isCurrent(pages, source)) {
                fb2PagesDir.mkdirs()
                // Whole or not at all: pages cut short would open as a damaged book.
                val partial = File(fb2PagesDir, "${pages.name}.part")
                try {
                    // As it is now: the title may have been put right since the book was added.
                    val now = _state.value.find(book.id) ?: book
                    Fb2Pages.make(source, partial.outputStream().buffered()) { read ->
                        // In the language the listener chose, or the file names, or its words are in.
                        val language = now.language ?: read.language ?: runCatching { guessLanguage(read.sample) }.getOrNull()
                        Fb2Book.About(now.id, now.title, now.author, language)
                    }
                    check(partial.renameTo(pages) || run { pages.delete(); partial.renameTo(pages) }) { "Couldn't store the pages of ${book.sourceName}" }
                } finally {
                    partial.delete()
                }
            }
            pages
        }
    }

    private fun pdfPagesOf(id: String) = File(pdfPagesDir, "$id.epub")

    /**
     * The pages [book], a PDF, is read aloud from: made now (its words are read out of every
     * page, which takes a moment for a long one) if they aren't there, or were made by an older
     * Jacket or from another file. The PDF itself is never touched. Throws [PdfWordsUnavailable]
     * on a phone whose Android can't get at a PDF's words, [NoWordsInPdf] for a PDF that has
     * none (a scan), and whatever reading the file throws.
     *
     * A PDF locked with a password is read with the password the reader gave (see [passwords]),
     * and only with it: without one to hand it throws [PdfLocked], whether or not its words were
     * kept from an earlier time, so that nothing of a locked PDF is read aloud until it has been opened.
     */
    internal suspend fun pdfPages(book: Book): PdfPages = withContext(Dispatchers.IO) {
        if (!PdfWords.available) throw PdfWordsUnavailable()
        pdfPagesLock.withLock {
            val source = fileOf(book)
            val pages = pdfPagesOf(book.id)
            val password = passwords.of(book.id)
            if (password == null && (passwords.isLocked(book.id) || PdfSpokenBook.locked(pages))) throw PdfLocked(wrong = false)
            val layout = PdfSpokenBook.kept(pages, source) ?: run {
                pdfPagesDir.mkdirs()
                // Whole or not at all: pages cut short would open as a damaged book.
                val partial = File(pdfPagesDir, "${pages.name}.part")
                try {
                    // As it is now: the title may have been put right since the book was added.
                    val now = _state.value.find(book.id) ?: book
                    val made = try {
                        partial.outputStream().buffered().let { PdfSpokenBook.make(source, it, now.id, now.title, now.author, now.language, password) }
                    } catch (e: PdfLocked) {
                        // Known from now on: a Play passes it over until it has been opened (see [unreadable]).
                        if (e.wrong) passwords.forget(book.id) else passwords.locked(book.id)
                        throw e
                    }
                    check(partial.renameTo(pages) || run { pages.delete(); partial.renameTo(pages) }) { "Couldn't store the pages of ${book.sourceName}" }
                    made
                } finally {
                    partial.delete()
                }
            }
            if (layout.paragraphs == 0) {
                _wordless.update { it + book.id }
                throw NoWordsInPdf(book.sourceName)
            }
            PdfPages(pages, layout)
        }
    }

    /** Serialized, so a picker import and a share arriving together can't both add the same file. */
    suspend fun import(uris: List<Uri>): ImportReport = importLock.withLock { importNow(uris) }

    private suspend fun importNow(uris: List<Uri>): ImportReport = withContext(Dispatchers.IO) {
        val imported = mutableListOf<Book>()
        val failed = mutableListOf<String>()
        val already = mutableListOf<Book>()
        val empty = mutableListOf<String>()
        // Imports come one at a time (see [import]), so any half-copied file here is an old one.
        BookImporter.clearUnfinished(booksDir)
        for (uri in uris) {
            val name = BookImporter.displayName(context, uri) ?: uri.lastPathSegment ?: "file"
            val size = BookImporter.size(context, uri)
            val existing = _state.value.books.firstOrNull { it.sourceName == name && it.sizeBytes == size }
            if (existing != null) {
                // Restored from a backup without its file: adding the same file again brings it back,
                // with its place, bookmarks and highlights.
                val here = fileOf(existing).exists()
                val relinked = here ||
                    runCatching { BookImporter.copyInto(context, uri, fileOf(existing)) }
                        .onFailure { e -> Log.w(TAG, "Re-linking $name failed", e) }
                        .isSuccess
                if (relinked) {
                    if (coverOf(existing) == null) runCatching { BookImporter.restoreCover(existing, this@Library) }
                    imported += existing
                    // Nothing was added or brought back: the reader is told the book is here.
                    if (here) already += existing
                } else {
                    failed += name
                }
                continue
            }
            // Under another name, or from an app that doesn't give its size, the same file is
            // only known once it has been copied in and its bytes can be compared (see bookWith).
            runCatching { BookImporter.import(context, uri, name, this@Library, ::bookWith) }
                .onSuccess { book ->
                    imported += book
                    // A book that was waiting for its file is here already, and has it back now.
                    if (_state.value.find(book.id) == null) {
                        _state.update { s -> s.copy(books = s.books + book, currentId = s.currentId ?: book.id) }
                    }
                }
                .onFailure { e ->
                    if (e is AlreadyInLibrary) {
                        imported += e.book
                        already += e.book
                    } else {
                        Log.w(TAG, "Import failed for $name", e)
                        failed += name
                        if (e is NothingToRead) empty += name
                    }
                }
        }
        if (imported.isNotEmpty()) {
            _missing.value = scanMissing(_state.value)
            changed(saveNow = true)
        }
        ImportReport(imported, failed, already, empty)
    }

    /** The book the library has for a file with this [content], if it has one (see [bookMadeFrom]). */
    private fun bookWith(content: FileContent): Book? =
        bookMadeFrom(_state.value.books, content) { BookImporter.contentOf(fileOf(it)) }

    fun setCurrent(id: String) {
        _state.update { it.copy(currentId = id) }
        changed()
    }

    fun markOpened(id: String) {
        val now = System.currentTimeMillis()
        _state.update { s -> s.copy(currentId = id, books = s.books.map { if (it.id == id) it.copy(lastOpenedAt = now) else it }) }
        changed()
    }

    /**
     * Saves the page a PDF or a comic was scrolled to: [page] of [pages], the top of the screen
     * [offset] of the way down it, [progress] of the way through the book.
     *
     * [back] says the reader went back through the pages themselves to get here. Without it the
     * book's progress is never taken down, as for every other book (see [Book.leftOnPage]).
     */
    fun updatePage(id: String, page: Int, offset: Float, progress: Float, pages: Int, back: Boolean) {
        if (!offset.isFinite() || !progress.isFinite()) return
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.leftOnPage(page, offset, progress, pages, back) else it }) }
        changed()
    }

    /**
     * Saves an EPUB reading location: Readium [locator] JSON, overall [progress] and its position of [positions].
     *
     * A text book is read through pages too, and its place is kept the same way. Beside it, as
     * ever, goes the paragraph it is in: [position] of [positions] paragraphs, and [offset] of the
     * way into it. A Jacket from before text books had pages reads that, and so does this one
     * when the pages a locator names are no longer the book's.
     *
     * [back] says the reader went back through the book themselves to get here. Without it the
     * book's progress is never taken down: a place counted again counts a little differently, and
     * is the same place (see [Book.leftAt]).
     */
    fun updateLocator(id: String, locator: String, progress: Float, position: Int, positions: Int, offset: Float? = null, back: Boolean) {
        if (!progress.isFinite()) return
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.leftAt(locator, progress, position, positions, offset, back) else it }) }
        changed()
    }

    /**
     * A page of [id] was read for [millis] and carried the reader [advanced] of the way through
     * the book: counts towards today's reading and the time spent with the book, and (when it was
     * a page read rather than flipped past) towards the book's pace. [advanced] is 0 for time
     * that says nothing of the pace: a page that was partly listened to.
     */
    fun addReading(id: String, millis: Long, advanced: Float) {
        if (millis <= 0L) return
        val now = java.time.LocalDateTime.now()
        _state.update { s ->
            val timed = s.withTimeSpent(id, millis, listened = false, end = now)
            if (advanced <= 0f || advanced > MOST_ONE_PAGE || !advanced.isFinite()) timed else timed.copy(
                books = timed.books.map {
                    if (it.id != id) it else {
                        val sample = millis / advanced
                        // A running average: it settles after a few pages and keeps following you.
                        it.copy(pace = if (it.pace <= 0f) sample else it.pace * 0.8f + sample * 0.2f)
                    }
                },
            )
        }
        changed()
    }

    /**
     * The voice read [id] aloud for [millis], up to now: listening counts as reading. It goes
     * towards the day's reading time (and so the streak), and the time spent with the book, and
     * is kept as listened in both (see [LibraryState.withTimeSpent]). The book's reading pace is
     * the reader's own, and learns nothing from the voice.
     */
    fun addListening(id: String, millis: Long) {
        if (millis <= 0L) return
        val now = java.time.LocalDateTime.now()
        _state.update { it.withTimeSpent(id, millis, listened = true, end = now) }
        changed()
    }

    /**
     * Looks in the watched folder for books that weren't there before, and adds them. Null when
     * there is no folder, it can't be read any more, or it holds nothing new.
     */
    suspend fun scanWatchedFolder(): ImportReport? = withContext(Dispatchers.IO) {
        val tree = _state.value.settings.watchFolder?.let(Uri::parse) ?: return@withContext null
        // The permission to read the folder is this phone's alone. A library brought over from
        // another phone (Android's own restore of it) names a folder this one was never given:
        // it can't be watched, and Settings shouldn't go on saying it is.
        if (context.contentResolver.persistedUriPermissions.none { it.isReadPermission && it.uri == tree }) {
            Log.w(TAG, "No permission to read the watched folder; it is no longer watched")
            _state.update { s -> s.copy(settings = s.settings.copy(watchFolder = null, watchSeen = emptySet())) }
            changed()
            return@withContext null
        }
        val seen = _state.value.settings.watchSeen
        val found = runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            buildList {
                context.contentResolver.query(children, columns, null, null, null)?.use { rows ->
                    while (rows.moveToNext()) {
                        val name = rows.getString(1) ?: continue
                        if (name.substringAfterLast('.', "").lowercase() !in WATCHED_EXTENSIONS && !Fb2.isNamed(name)) continue
                        add(Triple(DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0)), name, rows.getLong(2)))
                    }
                }
            }
        }.onFailure { e -> Log.w(TAG, "Couldn't read the watched folder", e) }.getOrNull() ?: return@withContext null
        val fresh = found.filter { (_, name, size) -> "$name|$size" !in seen }
        if (fresh.isEmpty()) return@withContext null
        // Remembered whether or not they import: a file that can't be read isn't tried at every launch.
        _state.update { s -> s.copy(settings = s.settings.copy(watchSeen = s.settings.watchSeen + fresh.map { (_, name, size) -> "$name|$size" })) }
        changed()
        // Books added by hand before the folder was watched are already here.
        val missing = fresh.filter { (_, name, size) -> _state.value.books.none { it.sourceName == name && it.sizeBytes == size } }
        if (missing.isEmpty()) return@withContext null
        // So are ones only their bytes give away (added under another name): no news, for a folder
        // nobody is looking at.
        val report = import(missing.map { it.first })
        val news = report.copy(imported = report.imported.filter { book -> report.already.none { it.id == book.id } }, already = emptyList())
        news.takeIf { it.imported.isNotEmpty() || it.failed.isNotEmpty() }
    }

    /** The language [id] is read aloud in: what the book says of itself, or what the listener chose instead. */
    fun setLanguage(id: String, language: String?) {
        if (_state.value.find(id)?.language == language) return
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.copy(language = language) else it }) }
        changed()
    }

    /** Whether [id], a book of pages, reads right to left: the reader's choice, which stands over what the book says of itself. */
    fun setRightToLeft(id: String, rightToLeft: Boolean) {
        if (_state.value.find(id)?.rightToLeft == rightToLeft) return
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.copy(rightToLeft = rightToLeft) else it }) }
        changed()
    }

    /**
     * Forgets the password of [id], a locked PDF: it is asked for the next time the PDF is opened.
     * The words kept of the PDF for reading it aloud go with it.
     */
    fun forgetPassword(id: String) {
        passwords.forget(id)
        scope.launch(disk) { pdfPagesLock.withLock { pdfPagesOf(id).takeIf(PdfSpokenBook::locked)?.delete() } }
        // (The player widget offers to open it now, not to play it.)
        WidgetSync.request(context)
    }

    /** Marks a book finished (or back to reading). */
    fun setFinished(id: String, finished: Boolean) {
        val now = System.currentTimeMillis()
        _state.update { s ->
            s.copy(books = s.books.map { if (it.id == id) it.copy(finishedAt = if (finished) now else 0L) else it })
        }
        changed()
    }

    override fun setPinned(id: String, pinned: Boolean) {
        val now = System.currentTimeMillis()
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.copy(pinnedAt = if (pinned) now else 0L) else it }) }
        changed()
    }

    override fun editDetails(id: String, title: String, author: String) {
        val name = title.trim()
        _state.update { s ->
            s.copy(books = s.books.map { if (it.id == id) it.copy(title = name.ifEmpty { it.title }, author = author.trim()) else it })
        }
        changed()
    }

    override fun setCollections(id: String, collections: Set<String>) {
        _state.update { s ->
            val known = s.collections.mapTo(HashSet()) { it.id }
            s.copy(books = s.books.map { if (it.id == id) it.copy(collections = collections.filterTo(HashSet()) { c -> c in known }) else it })
        }
        changed()
    }

    override fun createCollection(name: String): String {
        val collection = BookCollection(java.util.UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis())
        _state.update { it.copy(collections = it.collections + collection) }
        changed()
        return collection.id
    }

    override fun renameCollection(id: String, name: String) {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return
        _state.update { s -> s.copy(collections = s.collections.map { if (it.id == id) it.copy(name = trimmed) else it }) }
        changed()
    }

    override fun deleteCollection(id: String) {
        onDeleted?.invoke(null, id)
        _state.update { s ->
            s.copy(
                collections = s.collections.filterNot { it.id == id },
                books = s.books.map { if (id in it.collections) it.copy(collections = it.collections - id) else it },
                settings = if (s.settings.collection == id) s.settings.copy(collection = null) else s.settings,
            )
        }
        changed()
    }

    override fun showCollection(id: String?) = updateSettings { it.copy(collection = id) }

    override fun setCover(id: String, cover: android.graphics.Bitmap?, seed: Int?) {
        scope.launch(disk) {
            val book = _state.value.find(id) ?: return@launch
            // A file of its own each time: covers are cached by file, so the new one shows at once.
            val name = cover?.let { "$id-${System.currentTimeMillis()}.jpg" }
            if (cover != null && name != null) Bitmaps.saveJpeg(cover, File(coversDir, name), maxHeight = 1400)
            val tint = cover?.let { seed ?: Bitmaps.seedOf(it) }
            // The custom cover being replaced; the one the book came with stays.
            val replaced = book.coverFile.takeIf { book.coverCustomized }
            _state.update { s ->
                s.copy(books = s.books.map { b ->
                    when {
                        b.id != id -> b
                        name != null -> b.copy(
                            coverFile = name,
                            seedColor = tint,
                            coverCustomized = true,
                            originalCover = if (b.coverCustomized) b.originalCover else b.coverFile,
                            originalSeed = if (b.coverCustomized) b.originalSeed else b.seedColor,
                        )
                        b.coverCustomized -> b.copy(coverFile = b.originalCover, seedColor = b.originalSeed, coverCustomized = false, originalCover = null, originalSeed = null)
                        else -> b
                    }
                })
            }
            replaced?.let { File(coversDir, it).delete() }
            changed()
        }
    }

    fun addAnnotation(annotation: Annotation) {
        _state.update { it.copy(annotations = it.annotations + annotation) }
        changed()
    }

    fun updateAnnotation(id: String, transform: (Annotation) -> Annotation) {
        _state.update { s -> s.copy(annotations = s.annotations.map { if (it.id == id) transform(it) else it }) }
        changed()
    }

    fun deleteAnnotation(id: String) {
        onDeleted?.invoke(id, null)
        _state.update { s -> s.copy(annotations = s.annotations.filterNot { it.id == id }) }
        changed()
    }

    /**
     * Told of each bookmark, highlight or note ([mark]) and each collection the reader deletes,
     * before it goes from the library: so that the reader's other devices can be told it was
     * deleted (see [CatchUp]). The library itself only shows that something is no longer there,
     * which is also true of a book's bookmarks when the book is removed from this device.
     */
    @Volatile internal var onDeleted: ((mark: String?, collection: String?) -> Unit)? = null

    /**
     * Changes the library in one step, from whatever it is at that moment: for folding in what
     * the reader's other devices told (see [CatchUp]). [transform] may be asked more than once
     * (the library changed under it), and returning the library it was given changes nothing.
     */
    internal fun <T> revise(transform: (LibraryState) -> Pair<LibraryState, T>): T {
        while (true) {
            val before = _state.value
            val (after, result) = transform(before)
            if (after === before) return result
            if (_state.compareAndSet(before, after)) {
                changed()
                return result
            }
        }
    }

    /** Puts [id] at the place another device reached in it, because the reader asked to go there. */
    internal fun catchUpTo(id: String, place: SharedPlace) {
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.caughtUpTo(place) else it }) }
        changed(saveNow = true)
    }

    /**
     * "Say it this way": keeps how a word is to be said, in a book or (with no book) in every
     * book. One way of saying a word in each: saving it again, or another with the same word,
     * takes the place of the one that was there.
     */
    fun saveSaying(saying: Saying) {
        val word = SayIt.tidy(saying.word)
        val say = SayIt.tidy(saying.say)
        if (word.isEmpty() || say.isEmpty()) return
        val kept = saying.copy(word = word, say = say)
        _state.update { s ->
            val others = s.sayings.filterNot { it.id == kept.id || (it.bookId == kept.bookId && SayIt.sameWord(it.word, kept.word)) }
            val at = s.sayings.indexOfFirst { it.id == kept.id }.takeIf { it >= 0 }?.coerceAtMost(others.size) ?: others.size
            s.copy(sayings = others.subList(0, at) + kept + others.subList(at, others.size))
        }
        changed()
    }

    fun deleteSaying(id: String) {
        _state.update { s -> s.copy(sayings = s.sayings.filterNot { it.id == id }) }
        changed()
    }

    /** Writes a backup of the library (not the book files) to [uri]. */
    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val text = Backup.write(_state.value)
        // Some apps holding files won't empty one as it is opened: see [WholeFile].
        val written = context.contentResolver.writeWhole(uri, text.toByteArray(Charsets.UTF_8))
        if (written == null) error(context.getString(R.string.settings_backup_cant_write))
    }

    /**
     * Restores a backup from [uri], merged into what's here. Returns how many books in it still
     * need their file added again.
     */
    suspend fun importBackup(uri: Uri): Int = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error(context.getString(R.string.settings_backup_cant_open))
        val backup = Backup.read(input.use { String(it.readBytes(), Charsets.UTF_8) })
        _state.update { Backup.merge(it, backup) }
        val missing = scanMissing(_state.value)
        _missing.value = missing
        changed(saveNow = true)
        missing.size
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        changed()
    }

    fun delete(id: String) {
        val book = _state.value.find(id) ?: return
        _state.update { s ->
            s.copy(
                books = s.books.filterNot { it.id == id },
                currentId = s.currentId.takeIf { it != id },
                annotations = s.annotations.filterNot { it.bookId == id },
                sayings = s.sayings.filterNot { it.bookId == id },
            )
        }
        changed(saveNow = true)
        passwords.forget(id, gone = true)
        scope.launch(disk) {
            fileOf(book).delete()
            textPagesOf(book.id).delete()
            fb2PagesOf(book.id).delete()
            pdfPagesOf(book.id).delete()
            coverOf(book)?.delete()
            book.originalCover?.let { File(coversDir, it).delete() }
        }
    }

    private fun changed(saveNow: Boolean = false) {
        if (saveNow) scope.launch(disk) { writeState(_state.value) } else saves.trySend(Unit)
        WidgetSync.request(context)
    }

    /**
     * Writes whatever hasn't been written yet, before returning. For when Jacket leaves the screen:
     * Android may close it from there without another word (an update does, and so does a swipe
     * out of Recents), and the page just turned to is the reader's place.
     */
    fun flush() = runBlocking(disk) { writeState(_state.value) }

    /**
     * Reads the library. Whatever can't be read is kept, never saved over: an unreadable file is
     * moved aside and the library as it last started is read in its place, and a file with
     * entries that couldn't be read is copied aside before the rest of it is used.
     */
    private fun readState(): LibraryState {
        val file = stateFile.baseFile
        if (!file.exists() && !previousFile.exists()) return LibraryState()
        val read = runCatching { LibraryJson.read(String(stateFile.readFully(), Charsets.UTF_8)) }
            .onFailure { e ->
                val aside = File(context.filesDir, "library.corrupt-${System.currentTimeMillis()}.json")
                if (file.exists()) file.renameTo(aside)
                Log.e(TAG, "Library file unreadable; moved to ${aside.name}", e)
            }
            .getOrNull()
        if (read == null) {
            val previous = runCatching { LibraryJson.read(previousFile.readText()).state }
                .onFailure { e -> Log.e(TAG, "No earlier library to go back to", e) }
                .getOrNull() ?: return LibraryState()
            Log.w(TAG, "Went back to the library as it last started")
            // Not what is on disk: it is written at the first chance.
            saves.trySend(Unit)
            return previous
        }
        if (read.unread > 0) {
            // Named after the file as it is, so starting again before anything is saved copies it once.
            val aside = File(context.filesDir, "library.partly-read-${file.lastModified()}.json")
            runCatching { file.copyTo(aside, overwrite = true) }
            Log.e(TAG, "${read.unread} entries of the library couldn't be read; the file is kept as ${aside.name}")
            // What was read is all the next save will hold.
            saves.trySend(Unit)
        } else {
            written = read.state
            // A file that reads whole is the one to go back to, should the next not.
            scope.launch(disk) { runCatching { file.copyTo(previousFile, overwrite = true) } }
        }
        return read.state
    }

    /**
     * Atomic and fsynced; a failure (e.g. disk full) is logged and retried on the next change.
     * Runs on [disk] only, one write at a time.
     */
    private fun writeState(state: LibraryState) {
        if (state === written) return
        var out: FileOutputStream? = null
        try {
            out = stateFile.startWrite()
            out.write(LibraryJson.encode(state).toByteArray(Charsets.UTF_8))
            stateFile.finishWrite(out)
            written = state
        } catch (e: Exception) {
            out?.let(stateFile::failWrite)
            Log.e(TAG, "Couldn't save the library", e)
        }
    }

    private companion object {
        const val TAG = "JacketLibrary"

        /** How long a change waits to be saved, for the ones that come right behind it. */
        const val SAVE_AFTER_MS = 500L

        /** A step through the book bigger than this wasn't a page read: it was a jump. */
        const val MOST_ONE_PAGE = 0.03f
        val WATCHED_EXTENSIONS = setOf("epub", "pdf", "cbz", "txt")
    }
}
