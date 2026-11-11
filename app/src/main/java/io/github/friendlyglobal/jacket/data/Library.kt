package io.github.friendlyglobal.jacket.data

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.importer.BookImporter
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

data class ImportReport(val imported: List<Book>, val failed: List<String>) {
    /** What happened, for a snackbar; empty when nothing was imported or failed. */
    fun message(resources: Resources): String {
        val added = when (imported.size) {
            0 -> ""
            1 -> resources.getString(R.string.import_added_one, imported[0].title)
            else -> resources.getQuantityString(R.plurals.import_added_books, imported.size, imported.size)
        }
        if (failed.isEmpty()) return added
        val couldNotRead = resources.getString(R.string.import_failed, failed.joinToString())
        return if (added.isEmpty()) couldNotRead else resources.getString(R.string.import_added_and_failed, added, couldNotRead)
    }
}

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

    /** Serialized, so a picker import and a share arriving together can't both add the same file. */
    suspend fun import(uris: List<Uri>): ImportReport = importLock.withLock { importNow(uris) }

    private suspend fun importNow(uris: List<Uri>): ImportReport = withContext(Dispatchers.IO) {
        val imported = mutableListOf<Book>()
        val failed = mutableListOf<String>()
        for (uri in uris) {
            val name = BookImporter.displayName(context, uri) ?: uri.lastPathSegment ?: "file"
            val size = BookImporter.size(context, uri)
            val existing = _state.value.books.firstOrNull { it.sourceName == name && it.sizeBytes == size }
            if (existing != null) {
                // Restored from a backup without its file: adding the same file again brings it back,
                // with its place, bookmarks and highlights.
                val relinked = fileOf(existing).exists() ||
                    runCatching { BookImporter.copyInto(context, uri, fileOf(existing)) }
                        .onFailure { e -> Log.w(TAG, "Re-linking $name failed", e) }
                        .isSuccess
                if (relinked) {
                    if (coverOf(existing) == null) runCatching { BookImporter.restoreCover(existing, this@Library) }
                    imported += existing
                } else {
                    failed += name
                }
                continue
            }
            runCatching { BookImporter.import(context, uri, name, this@Library) }
                .onSuccess { book ->
                    imported += book
                    _state.update { s -> s.copy(books = s.books + book, currentId = s.currentId ?: book.id) }
                }
                .onFailure { e ->
                    Log.w(TAG, "Import failed for $name", e)
                    failed += name
                }
        }
        if (imported.isNotEmpty()) {
            _missing.value = scanMissing(_state.value)
            changed(saveNow = true)
        }
        ImportReport(imported, failed)
    }

    fun setCurrent(id: String) {
        _state.update { it.copy(currentId = id) }
        changed()
    }

    fun markOpened(id: String) {
        val now = System.currentTimeMillis()
        _state.update { s -> s.copy(currentId = id, books = s.books.map { if (it.id == id) it.copy(lastOpenedAt = now) else it }) }
        changed()
    }

    fun updatePosition(id: String, position: Int, offset: Float, progress: Float, sectionCount: Int) {
        if (!offset.isFinite() || !progress.isFinite()) return
        _state.update { s ->
            s.copy(books = s.books.map {
                if (it.id == id) it.copy(
                    position = position,
                    positionOffset = offset.coerceIn(0f, 1f),
                    progress = progress.coerceIn(0f, 1f),
                    sectionCount = sectionCount,
                ) else it
            })
        }
        changed()
    }

    /** Saves an EPUB reading location: Readium [locator] JSON, overall [progress] and its position of [positions]. */
    fun updateLocator(id: String, locator: String, progress: Float, position: Int, positions: Int) {
        if (!progress.isFinite()) return
        _state.update { s ->
            s.copy(books = s.books.map {
                if (it.id == id) it.copy(
                    locator = locator,
                    progress = progress.coerceIn(0f, 1f),
                    position = position.coerceAtLeast(0),
                    sectionCount = positions.coerceAtLeast(0),
                ) else it
            })
        }
        changed()
    }

    /**
     * A page of [id] was on screen for [millis] and carried the reader [advanced] of the way
     * through the book: counts towards today's reading, and (when it was a page read rather than
     * flipped past) towards the book's pace.
     */
    fun addReading(id: String, millis: Long, advanced: Float) {
        if (millis <= 0L) return
        val today = java.time.LocalDate.now().toString()
        _state.update { s ->
            val days = (s.reading + (today to (s.reading[today] ?: 0L) + millis)).let { all ->
                if (all.size <= KEPT_DAYS) all else all.toSortedMap().entries.toList().takeLast(KEPT_DAYS).associate { it.key to it.value }
            }
            val books = if (advanced <= 0f || advanced > MOST_ONE_PAGE || !advanced.isFinite()) s.books else s.books.map {
                if (it.id != id) it else {
                    val sample = millis / advanced
                    // A running average: it settles after a few pages and keeps following you.
                    it.copy(pace = if (it.pace <= 0f) sample else it.pace * 0.8f + sample * 0.2f)
                }
            }
            s.copy(books = books, reading = days)
        }
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
                        if (name.substringAfterLast('.', "").lowercase() !in WATCHED_EXTENSIONS) continue
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
        if (missing.isEmpty()) null else import(missing.map { it.first })
    }

    /** The language [id] is read aloud in: what the book says of itself, or what the listener chose instead. */
    fun setLanguage(id: String, language: String?) {
        if (_state.value.find(id)?.language == language) return
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) it.copy(language = language) else it }) }
        changed()
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
        _state.update { s -> s.copy(annotations = s.annotations.filterNot { it.id == id }) }
        changed()
    }

    /** Writes a backup of the library (not the book files) to [uri]. */
    suspend fun exportBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val text = Backup.write(_state.value)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.settings_backup_cant_write))
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
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
            )
        }
        changed(saveNow = true)
        scope.launch(disk) {
            fileOf(book).delete()
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

        /** Reading time is kept per day for a little over a year. */
        const val KEPT_DAYS = 400

        /** A step through the book bigger than this wasn't a page read: it was a jump. */
        const val MOST_ONE_PAGE = 0.03f
        val WATCHED_EXTENSIONS = setOf("epub", "pdf", "cbz", "txt")
    }
}
