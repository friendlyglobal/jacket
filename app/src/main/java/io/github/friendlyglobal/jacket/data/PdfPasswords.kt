package io.github.friendlyglobal.jacket.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * The passwords of PDFs that are locked with one.
 *
 * A password the reader types opens the PDF for as long as Jacket runs: it is held in memory, and
 * is gone when Jacket is closed. Only if they ask for it to be remembered is it kept, and then on
 * this phone alone:
 *
 *  - in a file of its own under the app's "no backup" storage, which neither Android's backup nor
 *    a phone-to-phone transfer copies;
 *  - never in the library, so never in a backup file or in what other devices are told (see
 *    Backup.kt and CatchUp.kt: they write the library, and this is no part of it);
 *  - sealed with a key that Android's keystore holds and never gives out, so the file is of no
 *    use anywhere but on this phone.
 *
 * It is forgotten when the reader asks, when the book is removed, and when it no longer opens the
 * file. Beside the passwords, the file notes which books are locked at all: a locked PDF isn't
 * read aloud until its password has been given (see Library.pdfPages).
 */

/** Seals a password so it can be kept, and opens what it sealed. Either gives null when it can't (and nothing is kept, or remembered). */
internal interface PasswordSeal {
    fun seal(password: String): String?
    fun open(sealed: String): String?
}

internal class PdfPasswords(private val file: File, private val seal: PasswordSeal) {
    /** Given since Jacket started, by book. */
    private val given = HashMap<String, String>()

    /** Remembered on this phone, sealed, by book; and the books known to be locked. Read from [file] when first needed. */
    private var kept: MutableMap<String, String>? = null
    private val lockedBooks = HashSet<String>()

    private val _remembered = MutableStateFlow<Set<String>>(emptySet())

    /** The books whose password is remembered on this phone: those are the ones it can be forgotten for. */
    val remembered: StateFlow<Set<String>> = _remembered.asStateFlow()

    private val _waiting = MutableStateFlow<Set<String>>(emptySet())

    /** The locked books Jacket has no password for just now: they can't be read aloud until they are opened. */
    val waiting: StateFlow<Set<String>> = _waiting.asStateFlow()

    /** Reads what is kept, so that [remembered] and [waiting] say it. (Otherwise it is read when first asked for.) */
    @Synchronized
    fun load() {
        kept()
    }

    /** The password that opens [bookId], if Jacket has one: given since it started, or remembered. */
    @Synchronized
    fun of(bookId: String): String? = given[bookId] ?: kept()[bookId]?.let(seal::open)

    /** Whether [bookId] is known to be locked with a password. */
    @Synchronized
    fun isLocked(bookId: String): Boolean {
        kept()
        return bookId in lockedBooks
    }

    /** [bookId] turned out to be locked. */
    @Synchronized
    fun locked(bookId: String) {
        kept()
        if (lockedBooks.add(bookId)) write()
        publish()
    }

    /**
     * [password] opened [bookId]. It is held for as long as Jacket runs; with [remember], it is
     * kept on this phone as well, and without, whatever was kept for the book is forgotten.
     */
    @Synchronized
    fun opened(bookId: String, password: String, remember: Boolean) {
        val kept = kept()
        given[bookId] = password
        lockedBooks += bookId
        val sealed = if (remember) seal.seal(password) else null
        if (sealed != null) kept[bookId] = sealed else kept.remove(bookId)
        write()
        publish()
    }

    /** Forgets [bookId]'s password, here and on this phone. With [gone], the book has left the library: everything about it is forgotten. */
    @Synchronized
    fun forget(bookId: String, gone: Boolean = false) {
        val kept = kept()
        val had = given.remove(bookId) != null
        val wasKept = kept.remove(bookId) != null
        val wasLocked = gone && lockedBooks.remove(bookId)
        if (wasKept || wasLocked) write()
        if (had || wasKept || wasLocked) publish()
    }

    private fun publish() {
        val kept = kept ?: return
        _remembered.value = kept.keys.toSet()
        _waiting.value = lockedBooks.filterTo(HashSet()) { it !in given && it !in kept }
    }

    private fun kept(): MutableMap<String, String> = kept ?: run {
        val read = HashMap<String, String>()
        runCatching {
            val root = JSONObject(file.readText())
            root.optJSONObject("passwords")?.let { all -> all.keys().forEach { id -> all.optString(id).takeIf { it.isNotEmpty() }?.let { read[id] = it } } }
            root.optJSONArray("locked")?.let { all -> (0 until all.length()).forEach { i -> all.optString(i).takeIf { it.isNotEmpty() }?.let(lockedBooks::add) } }
        }
        lockedBooks += read.keys
        read.also {
            kept = it
            publish()
        }
    }

    private fun write() {
        val kept = kept ?: return
        runCatching {
            if (kept.isEmpty() && lockedBooks.isEmpty()) {
                file.delete()
                return
            }
            val text = JSONObject().apply {
                put("version", 1)
                put("locked", JSONArray(lockedBooks.sorted()))
                put("passwords", JSONObject().apply { kept.toSortedMap().forEach { (id, sealed) -> put(id, sealed) } })
            }.toString()
            file.parentFile?.mkdirs()
            // Whole or not at all.
            val partial = File(file.parentFile, file.name + ".part")
            partial.writeText(text)
            if (!partial.renameTo(file)) {
                file.delete()
                partial.renameTo(file)
            }
        }
    }
}

/**
 * Seals with a key made in Android's keystore the first time one is needed. The key can't be read
 * out of the phone, so what it sealed can't be opened anywhere else. If the keystore fails (some
 * phones' do, after a restore), nothing is sealed and nothing opened: the password is asked for again.
 */
internal object KeystoreSeal : PasswordSeal {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "jacket.pdf-passwords"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val NONCE = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    override fun seal(password: String): String? = runCatching {
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }.getOrNull()

    override fun open(sealed: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE)) }
        String(cipher.doFinal(bytes, NONCE, bytes.size - NONCE), Charsets.UTF_8)
    }.getOrNull()
}
