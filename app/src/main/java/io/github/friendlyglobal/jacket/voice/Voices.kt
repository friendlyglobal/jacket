package io.github.friendlyglobal.jacket.voice

import android.app.ActivityManager
import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * The natural voices: which there are, which are on this phone, and which one reads a given
 * language. They run on the phone, and the text they speak goes nowhere. Their files are
 * downloaded once, when the listener agrees (see [VoiceDownloads]); a build can also carry them
 * inside the app (see app/build.gradle.kts).
 */
class Voices(private val context: Context) {
    private val host = ModelHost(context)
    private val _health = MutableStateFlow(VoiceHealth())
    private val store = VoiceStore(File(context.noBackupFilesDir, "voices"))

    /** The phone's own word on its temperature (PowerManager.THERMAL_STATUS_*), as it last said. */
    @Volatile
    private var thermal = 0

    /**
     * The voices Jacket knows (the catalog), whether or not they are on this phone: those it can
     * download, and any this build carries inside the app.
     */
    val packs: List<VoicePack> = runCatching {
        VoiceCatalog.parse(context.assets.open("voices/catalog.json").use { it.readBytes().toString(Charsets.UTF_8) })
            .filter { pack -> pack.downloadable || bundled(pack) }
    }.onFailure { Log.e(TAG, "Voice catalog unreadable", it) }.getOrDefault(emptyList())

    /** The packs whose files came inside the app. */
    private val inApp: Set<String> = packs.filter(::bundled).mapTo(HashSet()) { it.id }

    private val _ready = MutableStateFlow(readyNow())

    /** The packs on this phone, ready to speak, by id: those that came inside the app and those downloaded and checked. */
    val ready: StateFlow<Set<String>> = _ready.asStateFlow()

    /** Downloading the voices, and offering them. */
    val downloads: VoiceDownloads by lazy { VoiceDownloads(context, this, store) }

    /**
     * The natural voice used until the listener chooses: the first one there is. None on a phone
     * Android itself calls low on memory, where the model would crowd everything else out; such a
     * phone starts on its own voice, and its owner can still pick a natural one.
     */
    val preferred: VoiceRef? = packs.firstOrNull()
        ?.takeIf { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true }
        ?.let { VoiceRef(it.id, it.defaultVoice.id) }

    /** How the voice that is speaking now is keeping up (see [VoiceHealth]). */
    val health: StateFlow<VoiceHealth> = _health.asStateFlow()

    init {
        // A phone that is warming up gets the same voice with less strain (see NaturalSpeech).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val power = context.getSystemService(PowerManager::class.java) ?: return@runCatching
                thermal = power.currentThermalStatus
                power.addThermalStatusListener { status ->
                    thermal = status
                    if (!hot && _health.value.hot) _health.update { it.copy(hot = false) }
                }
            }
        }
    }

    /** Warming up: Android has started holding the processor back. The voice works further ahead, on fewer cores. */
    private val warm: Boolean get() = thermal >= PowerManager.THERMAL_STATUS_MODERATE

    /** Running hot: Android is holding the processor back hard. */
    private val hot: Boolean get() = thermal >= PowerManager.THERMAL_STATUS_SEVERE

    fun pack(id: String): VoicePack? = packs.firstOrNull { it.id == id }

    /** Whether [pack] is on this phone, ready to speak. */
    fun isReady(pack: VoicePack): Boolean = pack.id in _ready.value

    /** Whether [pack]'s files came inside the app: there is nothing to download or remove. */
    internal fun inApp(pack: VoicePack): Boolean = pack.id in inApp

    /** Which natural voice reads [language] now, of those on this phone; null for the phone's own voice. */
    fun choose(language: String?, settings: Settings = context.library.state.value.settings): VoiceRef? =
        chooseVoice(language, settings.voice, settings.voices, packs.filter { it.id in _ready.value }, preferred)

    /**
     * Which natural voice would read [language] if every voice were on the phone: the one to offer
     * when it isn't. Null where the phone's own voice would read it anyway.
     */
    fun wanted(language: String?, settings: Settings = context.library.state.value.settings): VoiceRef? =
        chooseVoice(language, settings.voice, settings.voices, packs, preferred)

    /** Looks again at which packs are on the phone: one has just been downloaded, or removed. */
    internal fun refresh() {
        _ready.value = readyNow()
    }

    private fun readyNow(): Set<String> = packs.filter { it.id in inApp || store.isReady(it) }.mapTo(HashSet()) { it.id }

    /** Whether this build carries [pack]'s files inside the app. */
    private fun bundled(pack: VoicePack): Boolean {
        val present = runCatching { context.assets.list(pack.folder) }.getOrNull().orEmpty().toSet()
        return pack.files.values.all { it in present }
    }

    /**
     * Where [pack]'s files are: inside the app (read through its assets), or downloaded (read by
     * their paths). Null if it isn't on the phone.
     */
    internal fun placeOf(pack: VoicePack): PackPlace? = when {
        pack.id in inApp -> PackPlace(context.assets, pack.folder)
        store.isReady(pack) -> PackPlace(null, store.folder(pack).absolutePath)
        else -> null
    }

    /** Opens [ref] for speaking; null if its files can't be read. */
    internal suspend fun open(ref: VoiceRef): NaturalSpeech? {
        val pack = pack(ref.pack)?.takeIf(::isReady) ?: return null
        val voice = pack.voice(ref.voice) ?: return null
        val model = runCatching { host.acquire(pack, warm) }
            .onFailure { Log.e(TAG, "Couldn't load ${ref.pack}", it) }
            .getOrNull() ?: return null
        val power = context.getSystemService(PowerManager::class.java)
        return NaturalSpeech(host, model, voice.speaker, _health, warm = { warm }, hot = { hot }, thermal = { thermal }, screenOn = { power?.isInteractive != false })
    }

    /**
     * Loads [ref]'s model ahead of its first sentence: it is kept a while after (see [ModelHost]),
     * so a voice about to take over starts promptly.
     */
    internal suspend fun preload(ref: VoiceRef) {
        val pack = pack(ref.pack)?.takeIf(::isReady) ?: return
        runCatching { host.acquire(pack, warm) }.getOrNull()?.let(host::release)
    }

    /**
     * The language code [pack] reads [language] in. Asked for one it doesn't speak (or for none: a
     * text file whose language couldn't be told), it reads in the phone's language, or English.
     */
    internal fun languageFor(pack: VoicePack, language: String?): String =
        languageKey(language).takeIf { it in pack.languages }
            ?: languageKey(java.util.Locale.getDefault().toLanguageTag()).takeIf { it in pack.languages }
            ?: "en".takeIf { it in pack.languages }
            ?: pack.languages.first()

    private companion object {
        const val TAG = "JacketVoice"
    }
}

/**
 * Where a pack's files are, for the engine: inside the app, read through its [assets], or (with
 * no [assets]) downloaded, read by their paths in [folder].
 */
internal class PackPlace(val assets: AssetManager?, private val folder: String) {
    fun path(name: String): String = "$folder/$name"
}
