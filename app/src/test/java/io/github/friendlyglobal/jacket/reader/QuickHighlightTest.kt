package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Highlighting without selecting anything, for a screen reader and a keyboard: with nothing being
 * read aloud it marks "the first paragraph on this page". Which paragraph that is, is told from
 * what the page shows from its first word on, found in Readium's reading of the chapter file.
 */
class QuickHighlightTest {
    private val chapter = listOf(
        "V",
        "In the Golden Age",
        "As I stood there in the gathering dark I thought that in this simple explanation I had mastered the problem of the world.",
        "Possibly the checks they had devised for the increase of population had succeeded too well, and their numbers had rather diminished than kept stationary.",
        "That would account for the abandoned ruins. Very simple was my explanation, and plausible enough, as most wrong theories are!",
    )
    private val places = TextPlaces(chapter)
    private val starts = chapter.indices.map(places::of)
    private val headings = setOf(0, 1)

    private fun first(top: String?, around: Double? = null, texts: List<String> = chapter) =
        firstParagraphOn(texts, texts.indices.map(TextPlaces(texts)::of), top, around) { texts === chapter && it in headings }

    @Test
    fun `a page that opens in the middle of a paragraph marks that paragraph`() {
        // The page begins part of the way through the third paragraph, and runs on into the fourth.
        val top = "had succeeded too well, and their numbers had rather diminished than kept stationary. That would account for the abandoned ruins."
        assertEquals(3, first(top))
    }

    @Test
    fun `a page that opens with a paragraph marks it`() {
        assertEquals(4, first("That would account for the abandoned ruins. Very simple was my explanation, and plausible enough"))
    }

    @Test
    fun `a chapter's number and title are no paragraph, the first one under them is`() {
        // The page a chapter opens on: "V", its title, and then the words.
        assertEquals(2, first("V In the Golden Age As I stood there in the gathering dark I thought that in this simple explanation"))
    }

    @Test
    fun `the page's line breaks and the file's spaces don't matter`() {
        assertEquals(2, first("As I stood there\nin the gathering dark I thought that in this simple explanation I had mastered"))
    }

    @Test
    fun `a page with no words has nothing to mark`() {
        // A picture, a blank page.
        assertNull(first(null))
        assertNull(first("   "))
        assertNull(firstParagraphOn(emptyList(), emptyList(), "Some words on a page of a file that has none", 0.5))
    }

    @Test
    fun `nothing is marked when only headings are left in the file`() {
        val ending = listOf("The story's last paragraph, which fills the page before this one to its very last line.", "THE END")
        assertNull(firstParagraphOn(ending, ending.indices.map(TextPlaces(ending)::of), "THE END", 0.99) { it == 1 })
    }

    @Test
    fun `words that come more than once are told apart by where the page is`() {
        // A refrain: the page's first words are found in three places, and can't say which.
        val song = listOf(
            "And it's no, nay, never, no nay never no more, will I play the wild rover, no never no more.",
            "I went to an alehouse I used to frequent, and I told the landlady my money was spent.",
            "And it's no, nay, never, no nay never no more, will I play the wild rover, no never no more.",
            "I took from my pocket ten sovereigns bright, and the landlady's eyes opened wide with delight.",
            "And it's no, nay, never, no nay never no more, will I play the wild rover, no never no more.",
        )
        val refrain = "And it's no, nay, never, no nay never no more, will I play the wild rover, no never no more."
        val where = song.indices.map(TextPlaces(song)::of)
        assertEquals(2, firstParagraphOn(song, where, refrain, where[2]))
        assertEquals(4, firstParagraphOn(song, where, refrain, where[4] + 0.01))
        assertEquals(0, firstParagraphOn(song, where, refrain, 0.0))
    }

    @Test
    fun `the page says where it is when its words aren't found at all`() {
        // The page and Readium's reading of the file disagree about these words: the paragraph that far down the file is taken.
        assertEquals(3, first("Possibly the cheques they had devized for the increase", around = starts[3] + 0.001))
    }

    @Test
    fun `a highlight's words are kept on one line`() {
        assertEquals(
            "As I stood there in the gathering dark I thought",
            highlightWords("  As I stood there\n   in the gathering dark\tI thought \n"),
        )
        assertEquals("", highlightWords(" \n "))
    }
}
