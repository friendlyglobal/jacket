package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding what a reader types in a book's words (see TextSearch.kt). */
class TextSearchTest {
    private fun found(text: String, typed: String): List<String> = TextSearch.find(text, TextSearch.query(typed)).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `words are found whatever their case`() {
        assertEquals(listOf("Rabbit", "rabbit", "RABBIT"), found("The Rabbit, a rabbit, THE RABBIT.", "rabbit"))
        assertEquals(listOf("Rabbit", "rabbit", "RABBIT"), found("The Rabbit, a rabbit, THE RABBIT.", "RaBBiT"))
        assertEquals(listOf("Война"), found("«Война и мир»", "война"))
    }

    @Test
    fun `accents of the Latin and Greek alphabets don't matter either way`() {
        assertEquals(listOf("café", "Cafe"), found("Un café. Cafe?", "cafe"))
        assertEquals(listOf("café", "Cafe"), found("Un café. Cafe?", "CAFÉ"))
        assertEquals(listOf("São João"), found("Em São João da Barra", "sao joao"))
        assertEquals(listOf("Żółć"), found("Żółć i gęś", "zółc"))
        assertEquals(listOf("λόγος"), found("ο λόγος", "λογος"))
        // An accent written as a mark of its own after its letter is the same accent.
        assertEquals(listOf("café"), found("Un café noir", "café"))
    }

    @Test
    fun `letters that are letters of their own are left alone`() {
        // In Russian "й" is not "и" with an accent; "ё" is found by "е", as readers type it.
        assertTrue(found("мой дом", "мои").isEmpty())
        assertEquals(listOf("мой"), found("мой дом", "мой"))
        assertEquals(listOf("ёлка", "елка"), found("ёлка и елка", "елка"))
        assertEquals(listOf("Ёлка"), found("Ёлка", "ёлка"))
        // Ukrainian "ї" and "і" are different letters.
        assertTrue(found("їжак", "іжак").isEmpty())
        // A Hindi vowel sign is part of its word.
        assertTrue(found("किताब", "कताब").isEmpty())
        assertEquals(listOf("किताब"), found("एक किताब है", "किताब"))
    }

    @Test
    fun `a line break or a run of spaces is one space`() {
        assertEquals(listOf("very\n  tired"), found("Alice was very\n  tired of sitting", "very tired"))
        assertEquals(listOf("very tired"), found("very tired", "very   tired"))
        assertEquals(listOf("tired"), found("very tired", "  tired "))
    }

    @Test
    fun `what can't be seen takes no part`() {
        // A soft hyphen where a word may be broken; a joiner.
        assertEquals(listOf("con­sid­er­ing"), found("was con­sid­er­ing it", "considering"))
        assertEquals(listOf("zero​width"), found("a zero​width space", "zerowidth"))
        // The vowel points of Arabic.
        assertEquals(listOf("كِتَاب"), found("هذا كِتَاب", "كتاب"))
    }

    @Test
    fun `joined letters, curled quotes and dashes are found by what a keyboard types`() {
        assertEquals(listOf("ﬁrst"), found("the ﬁrst ﬂoor", "first"))
        assertEquals(listOf("ﬂoor"), found("the ﬁrst ﬂoor", "FLOOR"))
        assertEquals(listOf("don’t"), found("I don’t know", "don't"))
        assertEquals(listOf("“Oh dear!”"), found("say, “Oh dear!” twice", "\"oh dear!\""))
        assertEquals(listOf("rabbit—hole"), found("the rabbit—hole", "rabbit-hole"))
    }

    @Test
    fun `what is found says where it is in the text as it was written`() {
        val text = "Un café, deux cafés, trois CAFÉS."
        val ranges = TextSearch.find(text, TextSearch.query("cafes"))
        assertEquals(listOf(14..18, 27..31), ranges)
        // Letters that come apart when they are compared still count as one of the text's.
        assertEquals(listOf(4..5), TextSearch.find("the ﬁrst", TextSearch.query("fir")))
        assertEquals(listOf(4..4), TextSearch.find("the ﬁrst", TextSearch.query("f")))
    }

    @Test
    fun `matches don't overlap, and nothing typed finds nothing`() {
        assertEquals(listOf("aa", "aa"), found("aaaaa", "aa"))
        assertTrue(found("Some words.", "").isEmpty())
        assertTrue(found("Some words.", "   ").isEmpty())
        assertTrue(found("", "words").isEmpty())
        assertTrue(TextSearch.query("  ­ ").blank)
    }
}
