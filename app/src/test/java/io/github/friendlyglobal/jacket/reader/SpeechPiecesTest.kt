package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPiecesTest {
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
}
