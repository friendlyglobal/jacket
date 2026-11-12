package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.Charset

/** A text file is read as what it was saved as, not as whatever makes its accents into rubbish. */
class TextDecodingTest {
    private val words = "Não há “pressa”: a manhã é só nossa — disse."

    @Test
    fun `UTF-8 is read as UTF-8, with or without the mark at its start`() {
        assertEquals(words, TextDecoding.decode(words.toByteArray(Charsets.UTF_8)))
        val marked = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + words.toByteArray(Charsets.UTF_8)
        assertEquals(words, TextDecoding.decode(marked))
        assertEquals("plain", TextDecoding.decode("plain".toByteArray(Charsets.US_ASCII)))
        assertEquals("", TextDecoding.decode(ByteArray(0)))
    }

    @Test
    fun `nobody is asked about a file that reads as UTF-8`() {
        TextDecoding.decode("日本語のテキスト".toByteArray(Charsets.UTF_8)) { error("not to be asked") }
    }

    @Test
    fun `UTF-16 is known by its mark`() {
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + words.toByteArray(Charsets.UTF_16LE)
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + words.toByteArray(Charsets.UTF_16BE)
        assertEquals(words, TextDecoding.decode(little))
        assertEquals(words, TextDecoding.decode(big))
    }

    @Test
    fun `and without one, by the nought in every other byte`() {
        val text = "Call me Ishmael. Some years ago, never mind how long precisely.\n\nSecond paragraph."
        assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `UTF-32 is known by its mark`() {
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + words.toByteArray(Charset.forName("UTF-32LE"))
        assertEquals(words, TextDecoding.decode(little))
    }

    @Test
    fun `a file from an older Windows program keeps its accents and its curly quotes`() {
        val bytes = words.toByteArray(Charset.forName("windows-1252"))
        val read = TextDecoding.decode(bytes)
        assertEquals(words, read)
        // Read as Latin-1, as Jacket 1.1 did, the quotes and the dash were control characters.
        assertFalse(String(bytes, Charsets.ISO_8859_1) == words)
    }

    @Test
    fun `another alphabet's own encoding is read by the name the phone gives it`() {
        val russian = "Все счастливые семьи похожи друг на друга."
        val cyrillic = Charset.forName("windows-1251")
        assertEquals(russian, TextDecoding.decode(russian.toByteArray(cyrillic)) { cyrillic })
        val japanese = "吾輩は猫である。名前はまだ無い。"
        val shiftJis = Charset.forName("Shift_JIS")
        assertEquals(japanese, TextDecoding.decode(japanese.toByteArray(shiftJis)) { shiftJis })
    }

    @Test
    fun `a guess that fails leaves the file readable`() {
        val bytes = "café".toByteArray(Charsets.ISO_8859_1)
        assertEquals("café", TextDecoding.decode(bytes) { error("no idea") })
        assertEquals("café", TextDecoding.decode(bytes) { null })
    }

    @Test
    fun `however a file is read, its line breaks are where they were`() {
        // What keeps a paragraph's number the same from one version to the next: an encoding of
        // one byte to a letter, read as another, still has its paragraphs where they were.
        val text = "Primeiro parágrafo,\nem duas linhas.\n\nSegundo — “entre aspas”.\n\n\nTerceiro."
        val bytes = text.toByteArray(Charset.forName("windows-1252"))
        val asBefore = paragraphsOf(String(bytes, Charsets.ISO_8859_1))
        val asNow = paragraphsOf(TextDecoding.decode(bytes))
        assertEquals(3, asNow.size)
        assertEquals(asBefore.size, asNow.size)
        assertEquals("Segundo — “entre aspas”.", asNow[1])
    }
}
