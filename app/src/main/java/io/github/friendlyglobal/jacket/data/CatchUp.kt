package io.github.friendlyglobal.jacket.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reading on two devices: each picks up where the reader left off on the other.
 *
 * The reader chooses, once on each device, a place the devices share: a folder that an app of
 * theirs (Google Drive, Dropbox, Syncthing) keeps the same on all of them, or a USB stick. Jacket
 * keeps one small file there for this device ("Jacket – Tablet.json", see [DeviceFile]), which it
 * alone writes, and reads the files the other devices keep there. It only ever reads and writes
 * files through Android's file picker: the app that holds the folder does any sending, so Jacket
 * still never goes online.
 *
 * Where a folder can't be chosen (some apps only offer single files, and Google Drive only
 * offers folders on Android 11 and later), the reader chooses the files one by one instead: this
 * device's own, and then each other device's.
 *
 * Bookmarks, highlights, notes and collections are merged as they are found (see
 * [CatchUpRules]). A place is not: when another device is further on in a book, that is
 * [offers]ed, and the reader's place here stays until they take it up.
 *
 * Which folder or files, and what this device has told the others, stay on this device (out of
 * the library, backups and Android's own copying to a new phone): a new phone is a new device.
 */
internal class CatchUp(private val context: Context, private val library: Library) {
    /** Another of the reader's devices, as Settings names it, and when it last wrote its file by its own clock. */
    data class Other(val name: String, val writtenAt: Long)

    /** What Settings shows. */
    data class Status(
        val on: Boolean = false,
        /** A folder was chosen; otherwise the files were, one by one. */
        val folder: Boolean = false,
        /** The folder's name, or the name of this device's file. */
        val where: String? = null,
        /** The app that holds it ("Drive"). */
        val place: String? = null,
        /** What this device is called on the others. */
        val device: String = "",
        val writtenAt: Long = 0L,
        /** This device's file couldn't be written: the folder may have gone, or the permission with it. */
        val failed: Boolean = false,
        val others: List<Other> = emptyList(),
    )

    private class Config(val tree: Uri?, val own: Uri?, val others: List<Uri>) {
        /** Whether [now] is the same folder, or the same file of this device's, as this was. */
        fun isStill(now: Config?): Boolean = now != null && now.tree == tree && (tree != null || now.own == own)
    }

    /** A file as it was last read: unchanged since (the same date and size), it isn't fetched again. */
    private class Read(val modified: Long, val size: Long, val file: DeviceFile?)

    private class Found(val files: List<DeviceFile>, val read: Map<String, Read>, val own: Uri?)

    private val prefs = context.getSharedPreferences("catch_up", Context.MODE_PRIVATE)
    private val stateFile = AtomicFile(File(context.noBackupFilesDir, "catch-up.json"))

    /** One thing at a time: everything this device knows of the others is only touched here. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Files are read apart from that, so that a folder slow to answer (no signal) holds nothing else up. */
    private val readers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status(on = isOn, folder = prefs.contains(TREE), failed = prefs.getBoolean(FAILED, false)))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _offers = MutableStateFlow<Map<String, CatchUpRules.Offer>>(emptyMap())

    /** By book id: the place another device has read further to, which the reader hasn't answered yet. */
    val offers: StateFlow<Map<String, CatchUpRules.Offer>> = _offers.asStateFlow()

    // What this device has told the others, and what it knows of them. Only touched on [scope].
    private var mine = DeviceFile(UUID.randomUUID().toString(), "")
    private var answered: Map<String, Map<String, Long>> = emptyMap()
    private var seen: Map<String, Long> = emptyMap()
    private var writtenRev = -1L
    private var others: List<DeviceFile> = emptyList()
    private var lastRead: Map<String, Read> = emptyMap()
    private var folded: LibraryState? = null
    private var telling: Job? = null
    private var lookedAt = 0L
    private var wroteAt = 0L

    // What the reader deleted since this device last told the others, and the chapter each book is in.
    private val deletedMarks = ConcurrentHashMap.newKeySet<String>()
    private val deletedCollections = ConcurrentHashMap.newKeySet<String>()
    private val chapters = ConcurrentHashMap<String, String>()

    /** The places the library has said once already ("device|book" to the place's rev): after that they are offered over the book's own pages only. */
    private val said = ConcurrentHashMap<String, Long>()

    /** What the reader last called this device. */
    @Volatile private var called = ""

    private val isOn: Boolean get() = prefs.contains(TREE) || prefs.contains(OWN)

    init {
        scope.launch {
            load()
            showStatus()
            // Something was still to be written when Jacket was last closed.
            if (isOn && mine.rev > writtenRev) owe()
        }
        // Before the bookmark or the collection goes from the library: by the time the library is
        // seen without it, why is already known (see the round).
        library.onDeleted = { mark, collection ->
            if (isOn) {
                mark?.let(deletedMarks::add)
                collection?.let(deletedCollections::add)
                scope.launch { save() }
            }
        }
        // The chapter a book is in is only known while a reader has it open.
        scope.launch {
            library.moment.collect { moment -> if (moment != null && moment.chapter.isNotBlank()) chapters[moment.bookId] = moment.chapter.trim() }
        }
        // A change here is told to the others once things have settled, as a backup is; and an
        // offer goes when the reader has read past it.
        scope.launch {
            library.state.drop(1).collectLatest { state ->
                if (!isOn || state === folded) return@collectLatest
                showOffers(state)
                owe()
            }
        }
    }

    /**
     * What this device is called until the reader says otherwise: what they called it the last
     * time, else the name they gave it in Android's settings, else its model.
     */
    fun suggestedName(): String {
        called.takeIf { it.isNotEmpty() }?.let { return it }
        val given = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return DeviceFiles.tidyName(given?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()).ifEmpty { "Android" }
    }

    /** Starts catching up through [tree], a folder the reader just chose, as the device called [name]. */
    fun enableFolder(tree: Uri, name: String) = enable(tree, name, folder = true)

    /** Starts catching up with [file] as this device's own (the reader just made it), as the device called [name]. */
    fun enableFile(file: Uri, name: String) = enable(file, name, folder = false)

    private fun enable(uri: Uri, name: String, folder: Boolean) {
        release()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, READ_WRITE) }.onFailure { Log.w(TAG, "No lasting permission for $uri", it) }
        prefs.edit {
            clear()
            putString(if (folder) TREE else OWN, uri.toString())
        }
        val called = DeviceFiles.tidyName(name).ifEmpty { suggestedName() }
        _status.value = Status(on = true, folder = folder, device = called)
        scope.launch {
            // What this device knew of the others before is not gone by: it starts again from
            // the library as it is, as the same device (its count of its writes goes on).
            forget(called)
            round(read = true)
            config()?.let { write(it) }
        }
    }

    /** Another device's file, chosen by the reader where a folder can't be: read from now on. */
    fun addFiles(files: List<Uri>) {
        if (!isOn || files.isEmpty()) return
        val known = prefs.getStringSet(OTHERS, emptySet()).orEmpty()
        for (file in files) {
            runCatching { context.contentResolver.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.w(TAG, "No lasting permission for $file", it) }
        }
        prefs.edit { putStringSet(OTHERS, known + files.map { it.toString() }) }
        look(force = true)
    }

    /** Stops catching up. This device's file stays where it is, as it was last written. */
    fun disable() {
        release()
        prefs.edit { clear() }
        _status.value = Status()
        _offers.value = emptyMap()
        scope.launch {
            telling?.cancel()
            forget(mine.name)
        }
    }

    /**
     * Jacket opened or came back to the front, or a book opened: reads what the other devices
     * wrote, merges it, and offers their places. Asked again within a few seconds, it has just looked.
     */
    fun look(force: Boolean = false) {
        if (!isOn) return
        scope.launch {
            if (!force && lookedAt != 0L && SystemClock.uptimeMillis() - lookedAt < LOOK_AGAIN_MS) return@launch
            round(read = true)
            if (mine.rev > writtenRev) owe()
        }
    }

    /**
     * Jacket has left the screen: where the reader got to is written for the other devices now,
     * and waited for briefly (Android may close Jacket from here without another word).
     */
    fun leave() {
        if (!isOn) return
        val job = scope.launch {
            telling?.cancel()
            round(read = false)
            if (mine.rev > writtenRev) config()?.let { write(it) }
        }
        runBlocking { withTimeoutOrNull(LEAVE_WAIT_MS) { job.join() } }
    }

    /** The reader answered the offer for [bookId] (went there, or waved it away): it isn't made again until that device reads on. */
    fun answer(bookId: String) {
        _offers.value = _offers.value - bookId
        scope.launch {
            answered = CatchUpRules.answered(answered, library.state.value, others, bookId)
            save()
            showOffers(library.state.value)
        }
    }

    /** Whether the library has already said [offer]. It says each place once: left alone there, it is made again when the book is opened. */
    fun saidInLibrary(offer: CatchUpRules.Offer): Boolean = (said[offer.device + "|" + offer.bookId] ?: Long.MIN_VALUE) >= offer.place.rev

    fun sayInLibrary(offer: CatchUpRules.Offer) {
        said[offer.device + "|" + offer.bookId] = offer.place.rev
        scope.launch { save() }
    }

    /** Puts the book at the place it was offered, and has the offer answered. For a book that isn't open: an open one is taken there by its reader. */
    fun goThere(bookId: String) {
        val offer = _offers.value[bookId] ?: return
        library.catchUpTo(bookId, offer.place)
        answer(bookId)
    }

    // ---- A round -------------------------------------------------------------------------------

    /**
     * Folds what the other devices tell into the library, and notes what this device tells them
     * now. With [read], their files are read first; otherwise what they last said is gone by.
     */
    private suspend fun round(read: Boolean) {
        val config = config() ?: return
        var learnt = false
        if (read) {
            lookedAt = SystemClock.uptimeMillis()
            val before = lastRead
            val me = mine.device
            val reading = readers.async {
                runCatching { find(config, before, me) }.onFailure { Log.w(TAG, "Couldn't read the other devices' files", it) }.getOrNull()
            }
            val found = withTimeoutOrNull(READ_PATIENCE_MS) { reading.await() }
            // Turned off, or pointed somewhere else, while the files were being read.
            if (!config.isStill(config())) return
            if (found != null) {
                lastRead = found.read
                if (config.tree != null && config.own == null && found.own != null) prefs.edit { putString(OWN, found.own.toString()) }
                val fresh = DeviceFiles.newest(found.files, mine.device, seen)
                val news = fresh.filter { it.rev > (seen[it.device] ?: Long.MIN_VALUE) }
                if (news.isNotEmpty()) {
                    seen = seen + news.associate { it.device to it.rev }
                    learnt = true
                }
                // A device whose file couldn't be read this time (only part of it was there) is
                // still the device it was a moment ago.
                others = (others.filter { old -> fresh.none { it.device == old.device } } + fresh).sortedBy { it.device }
            }
        }
        var used = CatchUpRules.Deleted()
        var produced: LibraryState? = null
        val told = library.revise { state ->
            // A library with no books in it may be one that couldn't be read: it tells the
            // others nothing, and nothing is made of what it lacks.
            if (state.books.isEmpty()) return@revise state to null
            val deleted = CatchUpRules.Deleted(HashSet(deletedMarks), HashSet(deletedCollections))
            val round = CatchUpRules.merge(state, mine, others, deleted, HashMap(chapters))
            used = deleted
            produced = round.library
            round.library to round.mine
        }
        deletedMarks.removeAll(used.marks)
        deletedCollections.removeAll(used.collections)
        folded = produced ?: folded
        val changed = told != null && told !== mine
        if (told != null) mine = told
        if (changed || learnt || used.marks.isNotEmpty() || used.collections.isNotEmpty()) save()
        showOffers(library.state.value)
        showStatus()
    }

    /** A change is told once nothing else has changed for a while; and, read through without a pause, every few minutes all the same. */
    private fun owe() {
        telling?.cancel()
        telling = scope.launch {
            val overdue = wroteAt != 0L && SystemClock.uptimeMillis() - wroteAt >= LONGEST_MS
            delay(if (overdue) SOON_MS else SETTLE_MS)
            // From here on it isn't put off again: a change this very round makes is its own.
            telling = null
            round(read = false)
            if (mine.rev > writtenRev) config()?.let { write(it) }
        }
    }

    private fun showOffers(state: LibraryState) {
        _offers.value = if (isOn) CatchUpRules.offers(state, others, answered, library.missing.value) else emptyMap()
    }

    // ---- Files ---------------------------------------------------------------------------------

    private fun config(): Config? {
        val tree = prefs.getString(TREE, null)?.toUri()
        val own = prefs.getString(OWN, null)?.toUri()
        if (tree == null && own == null) return null
        return Config(tree, own, prefs.getStringSet(OTHERS, emptySet()).orEmpty().map { it.toUri() })
    }

    /**
     * Reads every file in the shared place that may be a device's. In a folder that is any JSON
     * file, whatever it is called: a sync app's copy of a device's file ("… (1).json") is read
     * like the file itself, and which of the two to go by is told from what they say (see
     * [DeviceFiles.newest]). A file that is the same as when it was last read isn't read again.
     */
    private fun find(config: Config, before: Map<String, Read>, me: String): Found {
        val resolver = context.contentResolver
        val listed: List<Triple<Uri, Long, Long>> = if (config.tree != null) children(config.tree) else config.others.map { Triple(it, 0L, -1L) }
        val files = ArrayList<DeviceFile>()
        val now = HashMap<String, Read>()
        var own: Uri? = null
        var ownRev = Long.MIN_VALUE
        var budget = MOST_BYTES
        for ((uri, modified, size) in listed) {
            val key = uri.toString()
            val last = before[key]
            val entry = if (last != null && modified > 0L && last.modified == modified && last.size == size) {
                last
            } else {
                // (A folder full of other apps' big files: Jacket's own come first, and the rest wait.)
                if (budget <= 0L) continue
                val text = if (size > DeviceFiles.MAX_BYTES) null else runCatching { resolver.openInputStream(uri)?.use { it.textUpTo(DeviceFiles.MAX_BYTES) } }.getOrNull()
                budget -= text?.length?.toLong() ?: 0L
                Read(modified, size, text?.let(DeviceFiles::read))
            }
            now[key] = entry
            val file = entry.file ?: continue
            files += file
            // This device's own file, from before catching up was turned off and on again.
            if (file.device == me && file.rev > ownRev) {
                own = uri
                ownRev = file.rev
            }
        }
        return Found(files, now, own)
    }

    /** The files in the folder that may be a device's: where each is, when it was last changed and how big it is. */
    private fun children(tree: Uri): List<Triple<Uri, Long, Long>> {
        val folder = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val found = ArrayList<Pair<String, Triple<Uri, Long, Long>>>()
        context.contentResolver.query(folder, columns, null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(1) ?: continue
                if (rows.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR || !DeviceFiles.mayBeOne(name)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0))
                found += name to Triple(uri, if (rows.isNull(2)) 0L else rows.getLong(2), if (rows.isNull(3)) -1L else rows.getLong(3))
            }
        }
        // Jacket's own first, should the folder hold a great many other files.
        return found.sortedBy { (name, _) -> !name.startsWith("Jacket", ignoreCase = true) }.take(MOST_FILES).map { it.second }
    }

    /** Writes this device's file, making it first where there is a folder and it isn't there (yet, or any more). */
    private fun write(config: Config) {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val bytes = DeviceFiles.write(mine.copy(writtenAt = now)).toByteArray(Charsets.UTF_8)
        val length = runCatching {
            val known = config.own ?: prefs.getString(OWN, null)?.toUri()
            // Some apps holding files won't empty one as it is opened: see [WholeFile].
            known?.let { resolver.writeWhole(it, bytes, atLeast = prefs.getInt(LENGTH, 0).toLong()) }
                ?: config.tree?.let { tree ->
                    val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val made = DocumentsContract.createDocument(resolver, folder, MIME, DeviceFiles.nameFor(mine.name)) ?: error("Can't make a file in $tree")
                    prefs.edit { putString(OWN, made.toString()) }
                    resolver.writeWhole(made, bytes)
                }
                ?: error("Can't write $known")
        }
        wroteAt = SystemClock.uptimeMillis()
        length.onSuccess {
            writtenRev = mine.rev
            prefs.edit { putLong(WRITTEN_AT, now); putInt(LENGTH, it.toInt()); putBoolean(FAILED, false) }
            save()
        }.onFailure {
            Log.w(TAG, "Couldn't write this device's file", it)
            prefs.edit { putBoolean(FAILED, true) }
        }
        showStatus()
    }

    private fun showStatus() {
        val config = config()
        if (config == null) {
            _status.value = Status()
            return
        }
        val resolver = context.contentResolver
        val shown = config.tree?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) } ?: config.own
        val where = shown?.let { uri ->
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }
        val place = runCatching {
            val pm = context.packageManager
            (config.tree ?: config.own)?.authority?.let { pm.resolveContentProvider(it, 0)?.loadLabel(pm)?.toString() }
        }.getOrNull()
        // The right to write there is this device's, and can be taken away (the folder removed, the app holding it reinstalled).
        val held = (config.tree ?: config.own).let { uri -> resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }
        _status.value = Status(
            on = true, folder = config.tree != null, where = where?.takeIf { it.isNotBlank() }, place = place, device = mine.name,
            writtenAt = prefs.getLong(WRITTEN_AT, 0L), failed = prefs.getBoolean(FAILED, false) || !held,
            others = others.map { Other(it.name, it.writtenAt) }.sortedBy { it.name.lowercase() },
        )
    }

    private fun release() {
        val config = config() ?: return
        val resolver = context.contentResolver
        for (uri in listOfNotNull(config.tree, config.own.takeIf { config.tree == null }) + config.others) {
            runCatching { resolver.releasePersistableUriPermission(uri, if (uri in config.others) Intent.FLAG_GRANT_READ_URI_PERMISSION else READ_WRITE) }
        }
    }

    // ---- What is kept on this device -----------------------------------------------------------

    /** Starts over as the same device, called [name]: nothing told, nothing known of the others. */
    private fun forget(name: String) {
        mine = DeviceFile(mine.device, name, rev = mine.rev)
        called = name
        answered = emptyMap()
        others = emptyList()
        lastRead = emptyMap()
        writtenRev = -1L
        folded = null
        deletedMarks.clear()
        deletedCollections.clear()
        said.clear()
        save()
    }

    private fun load() {
        val root = runCatching { JSONObject(String(stateFile.readFully(), Charsets.UTF_8)) }.getOrNull() ?: return
        mine = root.optJSONObject("mine")?.let { DeviceFiles.read(it.toString()) } ?: return
        called = mine.name
        writtenRev = root.optLong("writtenRev", -1L)
        fun revs(of: JSONObject?): Map<String, Long> = of?.let { o -> o.keys().asSequence().associateWith { o.optLong(it) } }.orEmpty()
        seen = revs(root.optJSONObject("seen"))
        answered = root.optJSONObject("answered")?.let { o -> o.keys().asSequence().associateWith { revs(o.optJSONObject(it)) } }.orEmpty()
        said += revs(root.optJSONObject("said"))
        root.optJSONObject("deleted")?.let { deleted ->
            fun ids(name: String) = deleted.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
            deletedMarks += ids("marks")
            deletedCollections += ids("collections")
        }
    }

    private fun save() {
        val text = JSONObject().apply {
            put("mine", JSONObject(DeviceFiles.write(mine)))
            put("writtenRev", writtenRev)
            put("seen", JSONObject().apply { seen.forEach { (device, rev) -> put(device, rev) } })
            put("answered", JSONObject().apply {
                answered.forEach { (device, books) -> put(device, JSONObject().apply { books.forEach { (book, rev) -> put(book, rev) } }) }
            })
            put("said", JSONObject().apply { said.forEach { (place, rev) -> put(place, rev) } })
            put("deleted", JSONObject().apply {
                put("marks", JSONArray(deletedMarks.toList()))
                put("collections", JSONArray(deletedCollections.toList()))
            })
        }.toString()
        var out: FileOutputStream? = null
        try {
            out = stateFile.startWrite()
            out.write(text.toByteArray(Charsets.UTF_8))
            stateFile.finishWrite(out)
        } catch (e: Exception) {
            out?.let(stateFile::failWrite)
            Log.e(TAG, "Couldn't keep what this device has told the others", e)
        }
    }

    private companion object {
        const val TAG = "JacketCatchUp"
        const val MIME = "application/json"
        const val TREE = "tree"
        const val OWN = "own"
        const val OTHERS = "others"
        const val WRITTEN_AT = "writtenAt"
        const val LENGTH = "length"
        const val FAILED = "failed"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        /** A change is told once nothing else has changed for this long... */
        const val SETTLE_MS = 60_000L

        /** ...or, when it has been this long since the file was written (a long read, a book read aloud), almost at once. */
        const val LONGEST_MS = 10 * 60_000L
        const val SOON_MS = 5_000L

        /** Having just looked, the other devices' files aren't read again for this long. */
        const val LOOK_AGAIN_MS = 15_000L

        /** How long the files are waited for before going on with what is known. */
        const val READ_PATIENCE_MS = 30_000L

        /** How long leaving the screen waits for this device's file to be written. */
        const val LEAVE_WAIT_MS = 800L

        /** No more files of a folder than this are read, and no more of them at one go than this much. */
        const val MOST_FILES = 200
        const val MOST_BYTES = 32L * 1024 * 1024
    }
}

/** The text of a file, or null if it is longer than [limit] bytes. */
private fun InputStream.textUpTo(limit: Long): String? {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (bytes.size() + n > limit) return null
        bytes.write(buffer, 0, n)
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}
