package io.github.friendlyglobal.jacket.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The natural voices: which there are, and which one reads a given language. They ship inside the
 * app and run on the phone: nothing is downloaded, and the text they speak goes nowhere.
 */
class Voices(private val context: Context) {
    private val host = ModelHost(context)
    private val _health = MutableStateFlow(VoiceHealth())
    private var hot = false

    /** The voices in this build: those in the catalog whose files really are in the app. */
    val packs: List<VoicePack> = runCatching {
        VoiceCatalog.parse(context.assets.open("voices/catalog.json").use { it.readBytes().toString(Charsets.UTF_8) })
            .filter { pack ->
                val present = context.assets.list(pack.folder).orEmpty().toSet()
                pack.files.values.all { it in present }
            }
    }.onFailure { Log.e(TAG, "Voice catalog unreadable", it) }.getOrDefault(emptyList())

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
        // A phone that is running hot gets a lighter voice (see NaturalSpeech).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.getSystemService(PowerManager::class.java)?.addThermalStatusListener { status ->
                    hot = status >= PowerManager.THERMAL_STATUS_SEVERE
                    if (!hot && _health.value.hot) _health.update { it.copy(hot = false) }
                }
            }
        }
    }

    fun pack(id: String): VoicePack? = packs.firstOrNull { it.id == id }

    /** Which natural voice reads [language]; null for the phone's own voice. */
    fun choose(language: String?, settings: Settings = context.library.state.value.settings): VoiceRef? =
        chooseVoice(language, settings.voice, settings.voices, packs, preferred)

    /** Opens [ref] for speaking; null if its files can't be read. */
    internal suspend fun open(ref: VoiceRef): NaturalSpeech? {
        val pack = pack(ref.pack) ?: return null
        val voice = pack.voice(ref.voice) ?: return null
        val model = runCatching { host.acquire(pack) }
            .onFailure { Log.e(TAG, "Couldn't load ${ref.pack}", it) }
            .getOrNull() ?: return null
        return NaturalSpeech(host, model, voice.speaker, _health) { hot }
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
