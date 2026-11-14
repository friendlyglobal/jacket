package io.github.friendlyglobal.jacket.voice

import android.content.Context
import androidx.core.content.edit
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Why a voice's download stopped, in the words the listener is told. */
enum class DownloadProblem {
    /** The phone isn't connected to the internet. */
    OFFLINE,

    /** Connected, but GitHub couldn't be reached, or didn't send the file. */
    UNREACHABLE,

    /** What arrived wasn't the file (its size or checksum was wrong): it was deleted. */
    DAMAGED,

    /** Not enough free space on the phone. */
    NO_ROOM,

    /** The phone moved onto mobile data, which the listener hadn't agreed to use. */
    MOBILE_DATA,

    /** The phone wouldn't let Jacket keep the file. */
    STORAGE,
}

/** Why a download stopped, said plainly; [needs] the free space it needs, for [DownloadProblem.NO_ROOM]. */
fun DownloadProblem.message(context: Context, needs: Long = 0L): String = when (this) {
    DownloadProblem.OFFLINE -> context.getString(R.string.voice_problem_offline)
    DownloadProblem.UNREACHABLE -> context.getString(R.string.voice_problem_unreachable)
    DownloadProblem.DAMAGED -> context.getString(R.string.voice_problem_damaged)
    DownloadProblem.NO_ROOM -> context.getString(R.string.voice_problem_space, Formatter.formatShortFileSize(context, needs))
    DownloadProblem.MOBILE_DATA -> context.getString(R.string.voice_problem_mobile)
    DownloadProblem.STORAGE -> context.getString(R.string.voice_problem_storage)
}

/** Where a pack's download is, for the screens to show. */
data class PackState(
    /** All its files are here and checked: it can speak. */
    val ready: Boolean = false,
    /** It came inside the app (a build that carries the voices): nothing to download, and it can't be removed. */
    val inApp: Boolean = false,
    val downloading: Boolean = false,
    /** Bytes here, checked or still arriving. */
    val done: Long = 0L,
    val total: Long = 0L,
    val problem: DownloadProblem? = null,
    /** With [DownloadProblem.NO_ROOM]: the free space the download needs, in bytes. */
    val needs: Long = 0L,
) {
    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    /** Bytes still to download. */
    val left: Long get() = (total - done).coerceAtLeast(0L)

    /** Some of it is here but not all: space taken that Remove gives back. */
    val partly: Boolean get() = !ready && done > 0L
}

/**
 * The natural voices' downloads: started when the listener says yes, one pack at a time, the one
 * their book's language needs. The bytes themselves come through [VoiceDownload], the only code
 * in Jacket that uses the internet; they are kept and checked by [VoiceStore].
 *
 * A download runs as long as Jacket does: listening keeps Jacket running (it is a media
 * service), and the phone's own voice reads meanwhile. Stopped (Android closing Jacket, a lost
 * connection, Cancel), it carries on from where it was the next time it is started, and on its
 * own the next time Jacket is opened, on Wi-Fi, if the listener had asked for it.
 */
class VoiceDownloads internal constructor(private val context: Context, private val voices: Voices, private val store: VoiceStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("voice_downloads", Context.MODE_PRIVATE)
    private val jobs = HashMap<String, Job>()
    private val _states = MutableStateFlow(voices.packs.associate { it.id to stateOf(it) })

    /** Each pack's state, by id. */
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    /** Packs offered on a Listen this time Jacket has been running: one put aside isn't offered again until the next. */
    private val offered = HashSet<String>()

    init {
        // Jacket 1.5 and before carried the voices inside the app. Someone updating from one of
        // those finds them gone, and is told why the first time it matters (see [offerFor]). The
        // phone says whether Jacket was installed as an update; asked once, on the first start
        // of a Jacket that downloads its voices.
        if (!prefs.contains(CAME_WITH_VOICES)) {
            val updated = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.lastUpdateTime > info.firstInstallTime
            }.getOrDefault(false)
            prefs.edit { putBoolean(CAME_WITH_VOICES, updated) }
        }
    }

    fun state(pack: VoicePack): PackState = _states.value[pack.id] ?: stateOf(pack)

    private fun stateOf(pack: VoicePack): PackState = when {
        voices.inApp(pack) -> PackState(ready = true, inApp = true, done = pack.bytes, total = pack.bytes)
        else -> PackState(ready = store.isReady(pack), done = store.bytesHere(pack), total = pack.bytes)
    }

    private fun set(pack: VoicePack, state: PackState) = _states.update { it + (pack.id to state) }

    // ---- The network, as the phone tells it (ACCESS_NETWORK_STATE) -------------------------------

    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    /** The phone is connected to the internet. */
    fun connected(): Boolean = runCatching {
        val network = connectivity?.activeNetwork ?: return false
        connectivity?.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** The phone is on a connection that may cost money (mobile data, a phone's hotspot): the listener is asked first. */
    fun onMobileData(): Boolean = runCatching { connected() && connectivity?.isActiveNetworkMetered == true }.getOrDefault(false)

    // ---- Downloading ----------------------------------------------------------------------------

    /**
     * Downloads [pack], carrying on from whatever of it is here. [mobileData]: the listener has
     * agreed to use mobile data for it (asked first, with its size).
     */
    fun download(pack: VoicePack, mobileData: Boolean = false) {
        if (!pack.downloadable || jobs[pack.id]?.isActive == true || state(pack).ready) return
        prefs.edit { putBoolean(WANTED + pack.id, true); putBoolean(MOBILE_OK + pack.id, mobileData) }
        // One just cancelled may still be letting go of its file: this one waits for it.
        val stopping = jobs[pack.id]
        set(pack, state(pack).copy(downloading = true, problem = null, needs = 0L))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            stopping?.join()
            val me = coroutineContext[Job]
            var needs = 0L
            val problem = try {
                fetch(pack, mobileData)
                null
            } catch (e: Exception) {
                // Cancelled: the connection was closed under it, which is no problem of the download's.
                coroutineContext.ensureActive()
                when (e) {
                    is OnMobileData -> DownloadProblem.MOBILE_DATA
                    is Damaged -> DownloadProblem.DAMAGED
                    is NoRoom -> DownloadProblem.NO_ROOM.also { needs = e.needs }
                    is IOException -> {
                        Log.w(TAG, "Couldn't download ${pack.id}", e)
                        if (connected()) DownloadProblem.UNREACHABLE else DownloadProblem.OFFLINE
                    }
                    else -> {
                        Log.w(TAG, "Couldn't keep ${pack.id}", e)
                        DownloadProblem.STORAGE
                    }
                }
            } finally {
                if (jobs[pack.id] === me) jobs.remove(pack.id)
            }
            val now = withContext(Dispatchers.IO) { stateOf(pack) }
            set(pack, now.copy(problem = problem, needs = needs))
            if (problem == null) {
                prefs.edit { remove(WANTED + pack.id); remove(MOBILE_OK + pack.id) }
                voices.refresh()
                Toast.makeText(context, R.string.voice_download_ready, Toast.LENGTH_SHORT).show()
            } else {
                // Said wherever the listener is (the player, most likely); Settings › Voice says it too.
                Toast.makeText(context, problem.message(context, needs), Toast.LENGTH_LONG).show()
            }
        }
        jobs[pack.id] = job
        job.start()
    }

    private suspend fun fetch(pack: VoicePack, mobileData: Boolean) {
        withContext(Dispatchers.IO) { store.prepare(pack) }
        // Room for what is still to come, and a little over: a phone filled to the brim works badly.
        val toGo = withContext(Dispatchers.IO) { store.bytesToGo(pack) }
        val free = withContext(Dispatchers.IO) { store.freeBytes() }
        if (free < toGo + SPARE_BYTES) throw NoRoom(toGo + SPARE_BYTES)
        for (file in pack.downloads) {
            if (withContext(Dispatchers.IO) { store.isChecked(pack, file) }) continue
            val before = withContext(Dispatchers.IO) { store.bytesHere(pack) - store.partOf(pack, file).length().coerceAtMost(file.bytes) }
            var shownAt = 0L
            var askedAt = 0L
            Log.i(TAG, "Downloading ${pack.id}/${file.name} from byte ${store.partOf(pack, file).length()} of ${file.bytes}")
            VoiceDownload.fetch(file.url, store.partOf(pack, file), file.bytes) { inFile ->
                val now = SystemClock.uptimeMillis()
                // Every few megabytes: has the phone moved onto mobile data meanwhile?
                if (!mobileData && inFile - askedAt >= CHECK_NETWORK_BYTES) {
                    askedAt = inFile
                    if (onMobileData()) throw OnMobileData()
                }
                if (now - shownAt >= SHOW_EVERY_MS) {
                    shownAt = now
                    _states.update { it + (pack.id to (it[pack.id] ?: PackState()).copy(done = before + inFile, total = pack.bytes)) }
                }
            }
            val accepted = withContext(Dispatchers.IO) { store.accept(pack, file) }
            Log.i(TAG, "${pack.id}/${file.name}: " + if (accepted) "size and SHA-256 match, kept" else "didn't match the catalog, deleted")
            if (!accepted) throw Damaged()
        }
        if (!withContext(Dispatchers.IO) { store.isReady(pack) }) throw Damaged()
    }

    /** Stops [pack]'s download. What has arrived is kept: Download carries on from it, Remove frees it. */
    fun cancel(pack: VoicePack) {
        jobs[pack.id]?.cancel()
        prefs.edit { remove(WANTED + pack.id); remove(MOBILE_OK + pack.id) }
        set(pack, stateOf(pack))
    }

    /** Deletes [pack] from the phone, or what had arrived of it. */
    fun remove(pack: VoicePack) {
        if (voices.inApp(pack)) return
        val job = jobs[pack.id]
        job?.cancel()
        prefs.edit { remove(WANTED + pack.id); remove(MOBILE_OK + pack.id) }
        scope.launch {
            // (A download being stopped lets go of its file first.)
            job?.join()
            withContext(Dispatchers.IO) { store.remove(pack) }
            voices.refresh()
            set(pack, stateOf(pack))
        }
    }

    /**
     * Jacket has come to the front: a download that was stopped before it was done (Android
     * closed Jacket) carries on, if the listener had asked for it and the phone isn't on mobile
     * data (unless that had been agreed to). Otherwise it waits, and the screens offer to carry on.
     */
    fun resume() {
        for (pack in voices.packs) {
            if (!prefs.getBoolean(WANTED + pack.id, false) || jobs[pack.id]?.isActive == true) continue
            val mobileOk = prefs.getBoolean(MOBILE_OK + pack.id, false)
            if (state(pack).ready || !connected() || (onMobileData() && !mobileOk)) continue
            download(pack, mobileData = mobileOk)
        }
    }

    // ---- Offering a voice on Listen -------------------------------------------------------------

    /**
     * What the sheet shown on Listen offers: [pack]; whether to say the voices used to come with the
     * app; and whether another natural voice (one on the phone already) is [reading] meanwhile,
     * rather than the phone's own.
     */
    data class Offer(val pack: VoicePack, val updated: Boolean, val reading: Boolean = false)

    /**
     * Listen was pressed, on a screen, for a book in [language]: the voice to offer, if the one
     * that would read it isn't on the phone. None if the listener chose the phone's voice for this
     * (from the sheet, once), if it is downloading already, or if it was put aside since Jacket
     * started. Never asked from away from the screen (a headset, the notification, a car): there,
     * the phone's voice reads.
     */
    fun offerFor(language: String?, settings: Settings): Offer? {
        if (prefs.getBoolean(PHONE_VOICE, false)) return null
        val pack = voices.wanted(language, settings)?.let { voices.pack(it.pack) } ?: return null
        val state = state(pack)
        if (state.ready || state.downloading || !pack.downloadable || pack.id in offered) return null
        offered += pack.id
        val updated = prefs.getBoolean(CAME_WITH_VOICES, false) && !prefs.getBoolean(EXPLAINED, false)
        if (updated) prefs.edit { putBoolean(EXPLAINED, true) }
        return Offer(pack, updated, reading = voices.choose(language, settings) != null)
    }

    /**
     * "Use the phone's voice" (or "Keep the voice reading now"), from the sheet: no voice is offered
     * on Listen again. Settings › Voice still offers every one.
     */
    fun usePhoneVoice() {
        prefs.edit { putBoolean(PHONE_VOICE, true) }
    }

    private class OnMobileData : Exception()
    private class Damaged : Exception()
    private class NoRoom(val needs: Long) : Exception()

    private companion object {
        const val TAG = "JacketVoice"

        /** Free space kept over what a voice needs. */
        const val SPARE_BYTES = 50L * 1024 * 1024

        const val CHECK_NETWORK_BYTES = 4L * 1024 * 1024
        const val SHOW_EVERY_MS = 200L

        // What is kept (in a file of its own, outside every backup).
        const val CAME_WITH_VOICES = "came_with_voices"
        const val EXPLAINED = "explained"
        const val PHONE_VOICE = "phone_voice"
        const val WANTED = "wanted_"
        const val MOBILE_OK = "mobile_ok_"
    }
}
