package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A paragraph too long to lay out whole is cut into pieces (see [TextBook.LONGEST_BLOCK]). */
class TextPiecesTest {
    @Test
    fun shortTextIsOnePiece() {
        assertEquals(listOf(0 to "Hello there."), pieces("Hello there.", 100))
    }

    @Test
    fun piecesCoverTheTextExactlyAndRespectTheLimit() {
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val result = pieces(text, 500)
        assertTrue(result.size > 1)
        assertTrue(result.all { it.second.length <= 500 })
        // Offsets line up and nothing is lost or duplicated.
        var expected = 0
        for ((offset, piece) in result) {
            assertEquals(expected, offset)
            expected += piece.length
        }
        assertEquals(text, result.joinToString("") { it.second })
    }

    @Test
    fun cutsAtSentenceBoundariesWhenPossible() {
        val text = "First sentence is here. " + "x".repeat(60) + ". Tail words follow on and on."
        val result = pieces(text, 100)
        assertTrue(result.first().second.endsWith(". "))
    }

    @Test
    fun textWithoutSpacesStillSplits() {
        val text = "y".repeat(1050)
        val result = pieces(text, 400)
        assertEquals(listOf(400, 400, 250), result.map { it.second.length })
    }

    @Test
    fun aCharacterOfTwoHalvesIsNeverCutInTwo() {
        // Each of these is two UTF-16 units: a cut at an odd count would leave half of one.
        val text = "\uD83D\uDCD6".repeat(300)
        val result = pieces(text, 101)
        assertEquals(text, result.joinToString("") { it.second })
        assertTrue(result.all { (_, piece) -> !piece.last().isHighSurrogate() && !piece.first().isLowSurrogate() })
    }
}
