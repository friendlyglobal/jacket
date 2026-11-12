package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val russian = "Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему.\n\n" +
        "Всё смешалось в доме Облонских. Жена узнала, что муж был в связи с бывшею в их доме француженкою-гувернанткой."
    private val ukrainian = "Садок вишневий коло хати, хрущі над вишнями гудуть. Плугатарі з плугами йдуть, співають ідучи дівчата, а матері вечерять ждуть."
    private val japanese = "吾輩は猫である。名前はまだ無い。\n\nどこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"
    private val simplified = "我们在这里，他们也来了。这是一个很大的国家，人们都说中国有很多地方可以去看一看。\n\n你要是有时间，就到我家来。"
    private val traditional = "我們在這裡，他們也來了。這是一個很大的國家，人們都說中國有很多地方可以去看一看。\n\n你要是有時間，就到我家來。"
    private val korean = "나는 고양이로소이다. 이름은 아직 없다.\n\n어디서 태어났는지 도무지 짐작이 가지 않는다. 어둡고 축축한 곳에서 울고 있었던 것만은 기억한다."

    @Test
    fun `a novel saved the way its language used to be is read in that`() {
        val saved = listOf(
            russian to "windows-1251", russian to "KOI8-R", ukrainian to "windows-1251",
            japanese to "Shift_JIS", japanese to "EUC-JP", simplified to "GBK", traditional to "Big5", korean to "EUC-KR",
        )
        for ((text, name) in saved) assertEquals(name, text, TextDecoding.decode(text.toByteArray(Charset.forName(name))))
    }

    @Test
    fun `a Western file is not taken for any of them`() {
        val western = listOf(
            words,
            "Où est la bibliothèque ? Elle est là-bas, près de l'église, à côté du café où l'on a déjeuné hier.",
            "Die Straße führt über die Brücke. Später hören wir Musik, während draußen der Regen fällt und die Bäume rauschen.",
            "El niño comió piña y después durmió. Mañana irá al colegio con su compañero más pequeño.",
            "ÀÉÎÕÜ ÇÑ ÆØÅ — ALL CAPITALS, AND ACCENTS: ÉTÉ, ÀGE, NOËL, FAÇADE, DÉJÀ VU.",
        )
        for (text in western) {
            val bytes = text.toByteArray(Charset.forName("windows-1252"))
            assertNull(text, TextDecoding.older(bytes))
            assertEquals(text, TextDecoding.decode(bytes))
        }
        // Nothing above ASCII, nothing to tell.
        assertNull(TextDecoding.older("plain".toByteArray()))
    }

    @Test
    fun `only the first pages of a long file are looked at, and no character is cut in two`() {
        val long = (1..3_000).joinToString("\n") { japanese }
        assertEquals(long, TextDecoding.decode(long.toByteArray(Charset.forName("Shift_JIS"))))
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
