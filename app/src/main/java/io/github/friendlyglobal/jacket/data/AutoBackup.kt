package io.github.friendlyglobal.jacket.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a backup file up to date by itself: one the reader chose once, in Google Drive say. Jacket
 * only writes the file; the app that holds it (Drive) does the uploading, so Jacket still never
 * goes online. Which file it is stays on this phone (the permission to write it is this phone's),
 * not in the library or in backups.
 */
class AutoBackup(private val context: Context, private val library: Library) {
    /** What Settings shows: the file, where it is, when it was last written, and whether that failed. */
    data class Status(val uri: Uri? = null, val name: String? = null, val place: String? = null, val writtenAt: Long = 0L, val failed: Boolean = false) {
        val on: Boolean get() = uri != null
    }

    private val prefs = context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(load())
    val status: StateFlow<Status> = _status.asStateFlow()
    private var pending: Job? = null
    private var lastWriteUptime = 0L

    init {
        // After a change, once things have settled: not at every page turned while reading.
        scope.launch {
            library.state.drop(1).collect { if (_status.value.on) schedule() }
        }
        // Opening Jacket brings a backup that missed a change (the app was closed first) up to date.
        if (_status.value.on) schedule(SETTLE_MS)
    }

    /** Starts keeping [uri] up to date (a file the reader just chose), writing it straight away. */
    fun enable(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "No lasting permission for $uri", it) }
        prefs.edit { putString(URI, uri.toString()); putLong(WRITTEN_AT, 0L); putInt(LENGTH, 0); putBoolean(FAILED, false) }
        _status.value = load()
        pending?.cancel()
        pending = scope.launch { write() }
    }

    /** Stops keeping the file up to date; the file itself stays where it is. */
    fun disable() {
        pending?.cancel()
        _status.value.uri?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs.edit { clear() }
        _status.value = Status()
    }

    private fun schedule(wait: Long = SETTLE_MS) {
        pending?.cancel()
        pending = scope.launch {
            // Settle, and never more often than every few minutes: Drive uploads each write.
            val sinceLast = SystemClock.uptimeMillis() - lastWriteUptime
            delay(maxOf(wait, if (lastWriteUptime == 0L) 0L else MIN_INTERVAL_MS - sinceLast))
            write()
        }
    }

    private suspend fun write() = withContext(Dispatchers.IO) {
        val uri = _status.value.uri ?: return@withContext
        val state = library.state.value
        val bytes = Backup.write(state).toByteArray(Charsets.UTF_8)
        val previous = prefs.getInt(LENGTH, 0)
        val written = runCatching {
            val resolver = context.contentResolver
            // A library with no books in it never takes the place of a backup that has some. It
            // may be no library at all: one that couldn't be read when Jacket started, or a new
            // phone's, pointed at the old phone's backup. Saving a backup by hand still does.
            val kept = !Backup.mayReplace(state) { resolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }
            if (kept) {
                Log.w(TAG, "The library has no books and the backup has: the backup is left as it is")
                lastWriteUptime = SystemClock.uptimeMillis()
                return@withContext
            }
            // "wt" empties the file first. Some apps holding files only take "w", which writes over
            // the start and leaves the rest: then the end is padded with spaces, which JSON ignores.
            resolver.writeWhole(uri, bytes, atLeast = previous.toLong())?.toInt() ?: error("Can't open $uri")
        }
        lastWriteUptime = SystemClock.uptimeMillis()
        written.onSuccess { length ->
            prefs.edit { putLong(WRITTEN_AT, System.currentTimeMillis()); putInt(LENGTH, length); putBoolean(FAILED, false) }
        }.onFailure {
            Log.w(TAG, "Couldn't update the backup", it)
            prefs.edit { putBoolean(FAILED, true) }
        }
        _status.value = load()
    }

    private fun load(): Status {
        val uri = prefs.getString(URI, null)?.toUri() ?: return Status()
        return Status(uri, nameOf(uri), placeOf(uri), prefs.getLong(WRITTEN_AT, 0L), prefs.getBoolean(FAILED, false))
    }

    /** The file's name, as the app holding it tells it. */
    private fun nameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** The app that holds the file ("Drive"). */
    private fun placeOf(uri: Uri): String? = runCatching {
        val pm = context.packageManager
        pm.resolveContentProvider(uri.authority ?: return null, 0)?.loadLabel(pm)?.toString()
    }.getOrNull()

    private companion object {
        const val TAG = "JacketAutoBackup"
        const val URI = "uri"
        const val WRITTEN_AT = "writtenAt"
        const val LENGTH = "length"
        const val FAILED = "failed"
        /** A change is written once nothing else has changed for this long... */
        const val SETTLE_MS = 60_000L
        /** ...and never sooner than this after the last write. */
        const val MIN_INTERVAL_MS = 10 * 60_000L
    }
}
