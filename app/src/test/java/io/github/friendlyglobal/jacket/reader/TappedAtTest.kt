package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TappedAtTest {
    private val blocks = listOf(
        "XLIII",
        "Você tem medo?",
        "—Medo?",
        "—Sim, pergunto se você tem medo.",
        "Capitú fez um gesto de impaciencia. Os olhos de ressaca não se mexiam e pareciam crescer.",
        "—Medo?",
    )

    /** What the page would give for a tap at the start of [word] in block [block]: the text either side of it. */
    private fun tapOn(block: Int, word: String): Pair<String, String> {
        val text = blocks[block]
        val at = text.indexOf(word)
        val before = (blocks.take(block) + text.substring(0, at)).joinToString("\n")
        val after = (listOf(text.substring(at)) + blocks.drop(block + 1)).joinToString("\n")
        return before to after
    }

    @Test
    fun `a tap in the middle of a paragraph is found in it, letters in`() {
        val (before, after) = tapOn(4, "Os olhos")
        val spot = tappedAt(blocks, before, after)
        assertEquals(TextSpot(4, bare("Capitú fez um gesto de impaciencia. ").length), spot)
    }

    @Test
    fun `a short paragraph said twice is told apart by the words around it`() {
        val (before, after) = tapOn(5, "—Medo?")
        assertEquals(TextSpot(5, 0), tappedAt(blocks, before, after))
    }

    @Test
    fun `the page's spacing doesn't matter`() {
        val spot = tappedAt(blocks, "Você tem   medo?\n\n—Medo?\n\n—Sim,", " pergunto se\nvocê tem medo.")
        assertEquals(TextSpot(3, bare("—Sim,").length), spot)
    }

    @Test
    fun `words the voice's reading leaves out are got round with less of them`() {
        // A footnote mark the page shows and Readium's reading of the file doesn't.
        val spot = tappedAt(blocks, "—Sim, pergunto se você tem medo.¹ Capitú fez um gesto de ", "impaciencia. Os olhos de ressaca não se mexiam")
        assertEquals(TextSpot(4, bare("Capitú fez um gesto de ").length), spot)
    }

    @Test
    fun `words that are nowhere, or not enough to go by, find nothing`() {
        assertNull(tappedAt(blocks, "Nada disto", " está no livro"))
        assertNull(tappedAt(blocks, "", "—Medo?"))
    }
}
