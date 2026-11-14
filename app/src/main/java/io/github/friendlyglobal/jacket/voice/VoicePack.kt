package io.github.friendlyglobal.jacket.voice

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * A natural voice is a speech model that turns text into sound on the phone itself. Jacket knows
 * the ones in assets/voices/catalog.json; their files are downloaded once, when the listener agrees
 * (see VoiceDownload), and the text being read never leaves the device.
 */

enum class VoiceGender { FEMALE, MALE }

/** One speaker of a pack. */
data class PackVoice(
    val id: String,
    /** The maker's own name for it, shown when Jacket has no better one. */
    val name: String,
    /** The model's speaker number. */
    val speaker: Int,
    val gender: VoiceGender? = null,
    /** "Female voice 2": its place among the pack's voices of the same gender; 0 to show [name]. */
    val number: Int = 0,
)

/**
 * One of a pack's files as it is published: where it is downloaded from, how big it is, and its
 * SHA-256. A file that doesn't match both is thrown away, never used.
 */
data class VoiceFile(
    /** Its name in the pack's folder ("vocoder.int8.onnx"). */
    val name: String,
    val url: String,
    val bytes: Long,
    /** Lower-case hex. */
    val sha256: String,
)

data class PackLicense(
    val name: String,
    val url: String = "",
    /** Who made it, e.g. "Supertonic 3 © Supertone Inc." */
    val attribution: String = "",
    /** Conditions a listener should know about. */
    val notice: String = "",
)

/** A set of voices that share a model: Supertonic 3 and its ten speakers, or the one Brazilian voice. */
data class VoicePack(
    val id: String,
    val name: String,
    /** The model's files' names by role (see [NaturalModel]), in the pack's folder (see [folder]). */
    val files: Map<String, String>,
    /** Languages it speaks, as lower-case primary subtags ("en", "pt"). */
    val languages: List<String>,
    val voices: List<PackVoice>,
    val license: PackLicense,
    /** Where the model comes from. */
    val source: String = "",
    /** A sentence to try a voice with, per language. */
    val samples: Map<String, String> = emptyMap(),
    /** The voice Jacket starts people on: the liveliest of them, not merely the first. */
    val default: String = "",
    /**
     * The accent it speaks a language with, where that language has more than one, as a region
     * by language ("pt" to "PT": its Portuguese is Portugal's). A reader from elsewhere (Brazil)
     * starts on the phone's own voice in that language, which speaks theirs.
     */
    val accents: Map<String, String> = emptyMap(),
    /**
     * The kind of model (see [NaturalModel]): "supertonic" reads text; "piper" reads sounds, which
     * Jacket writes for it, so it speaks only the languages Jacket can write the sounds of.
     */
    val engine: String = ENGINE_SUPERTONIC,
    /** Its files as they are downloaded; empty for a pack that can only come inside the app. */
    val downloads: List<VoiceFile> = emptyList(),
) {
    /** Where its files are in the app's assets, in a build that carries them, and under the folder downloaded voices are kept in. */
    val folder: String get() = "voices/$id"

    /** How much there is to download, in bytes. */
    val bytes: Long get() = downloads.sumOf { it.bytes }

    /** Whether every one of its files can be downloaded. */
    val downloadable: Boolean get() = downloads.isNotEmpty() && files.values.toSet() == downloads.map { it.name }.toSet()

    val defaultVoice: PackVoice get() = voice(default) ?: voices.first()

    fun voice(id: String): PackVoice? = voices.firstOrNull { it.id == id }

    /** Whether it can read [languageTag] ("en-GB", "pt_BR", "in"...). */
    fun speaks(languageTag: String?): Boolean = languageKey(languageTag) in languages

    /** Whether it speaks [languageTag]'s language with the very accent of that tag's region ("pt-BR": Brazil's). */
    fun hasAccentOf(languageTag: String?): Boolean {
        val region = regionOf(languageTag)
        return region.isNotEmpty() && accents[languageKey(languageTag)].equals(region, ignoreCase = true)
    }

    /** Whether its accent in [languageTag]'s language is the one that tag's region speaks (true where it has only one). */
    fun soundsLike(languageTag: String?): Boolean {
        val accent = accents[languageKey(languageTag)] ?: return true
        val region = regionOf(languageTag)
        return region.isEmpty() || region.equals(accent, ignoreCase = true)
    }
}

const val ENGINE_SUPERTONIC = "supertonic"
const val ENGINE_PIPER = "piper"

/**
 * Where most readers of a language with several accents are, for a book and a phone that give no
 * clue: most people who read Portuguese are Brazilian.
 */
private val commonRegion = mapOf("pt" to "BR")

/**
 * The accent to read [language] in: the book's own region if it names one, else the phone's if the
 * phone is in that language, else where most of its readers are. "pt" on a Brazilian phone, or on
 * an English one, is "pt-BR"; on a Portuguese phone it is "pt-PT".
 */
fun accentFor(language: String?, phone: Locale = Locale.getDefault()): String? {
    val heard = withPhoneRegion(language, phone)
    if (heard.isNullOrBlank() || regionOf(heard).isNotEmpty()) return heard
    val key = languageKey(heard)
    return commonRegion[key]?.let { "$key-$it" } ?: heard
}

/** [tag]'s region ("BR" for "pt-BR" or "pt_BR"), or "" for none. */
fun regionOf(tag: String?): String =
    tag?.trim()?.replace('_', '-')?.let { Locale.forLanguageTag(it).country }.orEmpty()

/**
 * [tag] with the phone's region, when it names none of its own and the phone is set to the same
 * language: a book in "pt" on a Brazilian phone is read as "pt-BR". Books mostly say only the
 * language, and the reader's own region is the best guess at the accent they want to hear.
 */
fun withPhoneRegion(tag: String?, phone: Locale = Locale.getDefault()): String? {
    if (tag.isNullOrBlank() || regionOf(tag).isNotEmpty()) return tag
    val key = languageKey(tag)
    return if (key.isNotEmpty() && languageKey(phone.toLanguageTag()) == key && phone.country.isNotEmpty()) "$key-${phone.country}" else tag
}

/** A voice someone can choose: a pack and one of its speakers. Written "pack/voice" in settings. */
data class VoiceRef(val pack: String, val voice: String) {
    override fun toString() = "$pack/$voice"

    companion object {
        /** The settings value for the phone's own voice. */
        const val SYSTEM = "system"

        fun parse(text: String?): VoiceRef? {
            val parts = text?.split('/') ?: return null
            return if (parts.size == 2 && parts.none { it.isBlank() }) VoiceRef(parts[0], parts[1]) else null
        }
    }
}

/**
 * The key voices are chosen by: a language's primary subtag, lower-case, with Android's legacy
 * codes brought up to date ("in" is Indonesian, "iw" Hebrew). Null or blank gives "".
 */
fun languageKey(tag: String?): String {
    val primary = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT).orEmpty()
    return when (primary) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        "und" -> ""
        else -> primary
    }
}

/**
 * Which voice reads [language]. In order: the voice chosen for that language ([byLanguage]); the
 * voice chosen for everything ([chosen]); the natural voice Jacket would pick itself ([preferred],
 * null on a phone too modest for one). A natural voice only counts if it is among [packs] and
 * speaks the language. Null is the phone's own voice: always there, and what "system" asks for.
 *
 * Where a language has several accents and a voice for the reader's own (Brazil's Portuguese for
 * a Brazilian, see [accentFor]), that voice reads it, unless another was chosen for that very
 * language: a voice picked for every book was not picked for its accent in this one.
 */
fun chooseVoice(
    language: String?,
    chosen: String?,
    byLanguage: Map<String, String>,
    packs: List<VoicePack>,
    preferred: VoiceRef?,
    phone: Locale = Locale.getDefault(),
): VoiceRef? {
    val key = languageKey(language)
    fun usable(ref: VoiceRef?): VoiceRef? {
        val pack = packs.firstOrNull { it.id == ref?.pack } ?: return null
        return ref?.takeIf { pack.voice(it.voice) != null && pack.speaks(key) }
    }
    val forLanguage = byLanguage[key]
    if (forLanguage == VoiceRef.SYSTEM) return null
    // Chosen for this very language, it reads it whatever its accent.
    usable(VoiceRef.parse(forLanguage))?.let { return it }
    if (chosen == VoiceRef.SYSTEM) return null
    // The voice that speaks as the reader does, if there is one.
    val heard = accentFor(language, phone)
    val own = packs.firstOrNull { it.speaks(key) && it.hasAccentOf(heard) }?.let { VoiceRef(it.id, it.defaultVoice.id) }
    val other = own ?: packs.firstOrNull { it.speaks(key) }?.let { VoiceRef(it.id, it.defaultVoice.id) }
    if (chosen != null) {
        val ref = VoiceRef.parse(chosen)
        // A chosen voice that is no longer there leaves the reading to the phone's.
        val pack = packs.firstOrNull { it.id == ref?.pack }?.takeIf { ref != null && it.voice(ref.voice) != null } ?: return null
        return when {
            // One that can't read this language (the Brazilian voice and an English book) leaves it to one that can.
            !pack.speaks(key) -> other
            // One with another accent, picked for every book, gives way to the reader's own.
            own != null && pack.accents[key] != null && !pack.hasAccentOf(heard) -> own
            else -> ref
        }
    }
    if (preferred == null) return null
    // Natural beats the phone's voice for most people, even with another accent (Portugal's
    // Portuguese where there is no Brazilian voice): the picker names the accent.
    return own ?: usable(preferred) ?: other
}

/**
 * [packs]' voices that read [language], in the order to offer them: those with the reader's own
 * accent first.
 */
fun voicesFor(language: String?, packs: List<VoicePack>, phone: Locale = Locale.getDefault()): List<Pair<VoicePack, PackVoice>> {
    val key = languageKey(language)
    val heard = accentFor(language, phone)
    return packs.filter { it.speaks(key) }
        .sortedByDescending { it.hasAccentOf(heard) }
        .flatMap { pack -> pack.voices.map { pack to it } }
}

/** Reads the catalog of voices (assets/voices/catalog.json). */
object VoiceCatalog {
    fun parse(text: String): List<VoicePack> {
        val root = JSONObject(text)
        val packs = root.optJSONArray("packs") ?: JSONArray()
        val from = root.optString("download")
        return (0 until packs.length()).mapNotNull { i -> runCatching { pack(packs.getJSONObject(i), from) }.getOrNull() }
    }

    private fun pack(root: JSONObject, from: String): VoicePack {
        val id = root.getString("id")
        // A file is its name alone (a pack that only comes inside the app), or its name with its
        // size and SHA-256, downloaded from the release named at the top (or by the pack itself)
        // as "<pack>.<name>".
        val release = root.optString("download").ifBlank { from }
        val listed = root.getJSONObject("files")
        val files = LinkedHashMap<String, String>()
        val downloads = ArrayList<VoiceFile>()
        for (role in listed.keys()) {
            val file = listed.optJSONObject(role)
            if (file == null) {
                files[role] = listed.getString(role)
                continue
            }
            val name = file.getString("name")
            files[role] = name
            if (downloads.any { it.name == name }) continue
            val sha256 = file.getString("sha256").lowercase(Locale.ROOT)
            val bytes = file.getLong("bytes")
            val url = release + file.optString("asset").ifBlank { "$id.$name" }
            require(SHA256.matches(sha256) && bytes > 0 && url.startsWith("https://")) { "$id has a file it can't check: $name" }
            require('/' !in name && name != "." && name != "..") { "$id has a file outside its folder: $name" }
            downloads += VoiceFile(name, url, bytes, sha256)
        }
        val voices = root.getJSONArray("voices").let { array ->
            (0 until array.length()).map { i ->
                val v = array.getJSONObject(i)
                PackVoice(
                    id = v.getString("id"),
                    name = v.optString("name").ifBlank { v.getString("id") },
                    speaker = v.optInt("speaker", 0),
                    gender = when (v.optString("gender").lowercase(Locale.ROOT)) {
                        "female" -> VoiceGender.FEMALE
                        "male" -> VoiceGender.MALE
                        else -> null
                    },
                    number = v.optInt("number", 0),
                )
            }
        }
        require(voices.isNotEmpty()) { "$id has no voices" }
        val languages = root.getJSONArray("languages").strings().map(::languageKey).filter { it.isNotEmpty() }.distinct()
        require(languages.isNotEmpty()) { "$id has no languages" }
        val license = root.getJSONObject("license")
        val accents = root.optJSONObject("accents")?.let { a ->
            a.keys().asSequence().associate { languageKey(it) to a.optString(it).uppercase(Locale.ROOT) }.filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
        }.orEmpty()
        return VoicePack(
            id = id,
            name = root.optString("name").ifBlank { id },
            files = files,
            languages = languages,
            voices = voices,
            license = PackLicense(
                name = license.getString("name"),
                url = license.optString("url"),
                attribution = license.optString("attribution"),
                notice = license.optString("notice"),
            ),
            source = root.optString("source"),
            samples = root.optJSONObject("samples")?.strings().orEmpty().mapKeys { languageKey(it.key) },
            default = root.optString("default"),
            accents = accents,
            engine = root.optString("engine").ifBlank { ENGINE_SUPERTONIC },
            downloads = downloads,
        )
    }

    private val SHA256 = Regex("[0-9a-f]{64}")

    private fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith { optString(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
}
