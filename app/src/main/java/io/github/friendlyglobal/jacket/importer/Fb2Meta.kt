package io.github.friendlyglobal.jacket.importer

import io.github.friendlyglobal.jacket.voice.guessLanguage
import java.io.File
import java.util.Base64

/** What an FB2 file says of its book, for the library: read when the book is added, and again for its cover after a restore. */
internal object Fb2Meta {
    /**
     * The title, authors, language and cover of the FB2 book in [file] (zipped or not). The
     * language is the one the file names, or failing that the one its first words are in. Throws
     * [Fb2Unreadable] for a file that can't be read as an FB2 book.
     */
    fun read(file: File): BookMeta {
        var cover: ByteArray? = null
        val document = Fb2.open(file) { input ->
            // Only the cover is taken of the file's pictures, and none of its chapters are kept.
            Fb2.read(input, bodies = false, wants = { _, isCover -> isCover }) { binary ->
                cover = runCatching { Base64.getMimeDecoder().decode(binary.base64) }.getOrNull()
            }
        }
        // (Only a picture of a kind the pages would show: see Fb2Pages.pictureType.)
        val picture = cover?.takeIf { Fb2Pages.pictureType(it) != null }?.let { bytes -> runCatching { Bitmaps.decodeSampled(bytes, 1400) }.getOrNull() }
        val language = document.language ?: runCatching { guessLanguage(document.sample) }.getOrNull()
        return BookMeta(document.title, document.authors.joinToString(", "), picture, sections = 0, language = language, hasWords = document.letters > 0)
    }
}
