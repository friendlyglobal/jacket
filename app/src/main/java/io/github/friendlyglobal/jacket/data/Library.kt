package io.github.friendlyglobal.jacket.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.widget.WidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ImportReport(val imported: List<Book>, val failed: List<String>) {
    val message: String
        get() = buildString {
            when (imported.size) {
                0 -> {}
                1 -> append("Added “${imported[0].title}”")
                else -> append("Added ${imported.size} books")
            }
            if (failed.isNotEmpty()) {
                if (isNotEmpty()) append(". ")
                append("Couldn't read ${failed.joinToString()}")
            }
        }
}

/**
 * The on-device library: imported files live in app storage, metadata in one JSON file.
 * Nothing leaves the device.
 */
class Library(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private val stateFile = AtomicFile(File(context.filesDir, "library.json"))
    private val importLock = Mutex()
    private var saveJob: Job? = null

    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        // Jacket 0.2 unpacked EPUBs to disk; Readium reads the files directly, so free that space.
        scope.launch(disk) { File(context.filesDir, "unpacked").deleteRecursively() }
    }

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
                imported += existing
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
        if (imported.isNotEmpty()) changed(saveNow = true)
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

    fun updateSettings(transform: (Settings) -> Settings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        changed()
    }

    fun delete(id: String) {
        val book = _state.value.find(id) ?: return
        _state.update { s ->
            s.copy(books = s.books.filterNot { it.id == id }, currentId = s.currentId.takeIf { it != id })
        }
        changed(saveNow = true)
        scope.launch(disk) {
            fileOf(book).delete()
            coverOf(book)?.delete()
        }
    }

    private fun changed(saveNow: Boolean = false) {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (!saveNow) delay(500)
            withContext(disk) { writeState(_state.value) }
        }
        WidgetSync.request(context)
    }

    private fun readState(): LibraryState {
        if (!stateFile.baseFile.exists()) return LibraryState()
        return try {
            LibraryJson.decode(String(stateFile.readFully(), Charsets.UTF_8))
        } catch (e: Exception) {
            // Keep the unreadable file for recovery instead of saving an empty library over it.
            val aside = File(context.filesDir, "library.corrupt-${System.currentTimeMillis()}.json")
            stateFile.baseFile.renameTo(aside)
            Log.e(TAG, "Library file unreadable; moved to ${aside.name}", e)
            LibraryState()
        }
    }

    /** Atomic and fsynced; a failure (e.g. disk full) is logged and retried on the next change. */
    private fun writeState(state: LibraryState) {
        var out: FileOutputStream? = null
        try {
            out = stateFile.startWrite()
            out.write(LibraryJson.encode(state).toByteArray(Charsets.UTF_8))
            stateFile.finishWrite(out)
        } catch (e: Exception) {
            out?.let(stateFile::failWrite)
            Log.e(TAG, "Couldn't save the library", e)
        }
    }

    private companion object {
        const val TAG = "JacketLibrary"
    }
}
