package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNamesTest {
    private fun shown(title: String) = ChapterNames.shown(title) { "Chapter $it" }

    @Test
    fun `a chapter called only by its number is shown as a chapter, not as a button`() {
        // Gulliver's Travels: "II" between the two chapter buttons read as a pause button.
        assertEquals("Chapter II", shown("II"))
        assertEquals("Chapter 12", shown("12"))
        assertEquals("Chapter XIV", shown("XIV"))
        assertEquals("Chapter MCMXCIX", shown("MCMXCIX"))
    }

    @Test
    fun `the full stop or brackets around the number are left off`() {
        assertEquals("Chapter 2", shown("2."))
        assertEquals("Chapter IV", shown("(IV)"))
        assertEquals("Chapter 3", shown("[3]"))
        assertEquals("Chapter VII", shown("VII."))
        assertEquals("Chapter 7", shown("— 7 —"))
        assertEquals("Chapter 12", shown("  12:  "))
    }

    @Test
    fun `figures of other scripts and the roman numeral signs are numbers too`() {
        assertEquals("Chapter ٣", shown("٣"))
        assertEquals("Chapter २", shown("२."))
        assertEquals("Chapter Ⅱ", shown("Ⅱ"))
    }

    @Test
    fun `a letter by itself stands for a chapter as a number does`() {
        assertEquals("Chapter A", shown("A"))
        assertEquals("Chapter 一", shown("一"))
    }

    @Test
    fun `a title with words in it is shown as it is`() {
        assertEquals("Chapter II", shown("Chapter II"))
        assertEquals("II. A Voyage to Lilliput", shown("II. A Voyage to Lilliput"))
        assertEquals("Prologue", shown("Prologue"))
        assertEquals("第二章", shown("第二章"))
        assertEquals("Page 3 of 40", shown("Page 3 of 40"))
    }

    @Test
    fun `small letters that only look like roman numerals are words`() {
        assertEquals("mix", shown("mix"))
        assertEquals("Liv", shown("Liv"))
        assertNull(ChapterNames.numberOf("ii"))
        // Nor is a jumble of capitals a numeral.
        assertEquals("IIII V", shown("IIII V"))
        assertNull(ChapterNames.numberOf("IIX"))
    }

    @Test
    fun `a title of marks alone names nothing and is not shown`() {
        assertEquals("", shown("* * *"))
        assertEquals("", shown("§"))
        assertEquals("", shown("—"))
        assertEquals("", shown("   "))
        assertEquals("", shown(""))
    }

    @Test
    fun `a very long number is no chapter's`() {
        assertEquals("9780140430226", shown("9780140430226"))
    }
}
