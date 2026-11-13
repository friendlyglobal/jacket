package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.publication.Locator

class SelectionSpotTest {
    private val blocks = listOf(
        "CHAPTER II.",
        "The Emperor of Lilliput, attended by several of the nobility, comes to see the author in his confinement.",
        "When I found myself on my feet, I looked about me, and must confess I never beheld a more entertaining prospect.",
        "The country around appeared like a continued garden.",
    )

    @Test
    fun `words selected in the middle of a paragraph are found where they begin`() {
        // As Readium gives a selection: the words, and a little either side.
        val words = Locator.Text(before = "When I found myself on my feet, ", highlight = "I looked about me", after = ", and must confess I never")
        assertEquals(TextSpot(2, bare("When I found myself on my feet, ").length), selectionSpot(blocks, words))
    }

    @Test
    fun `a selection at the very start of a paragraph is found at its start`() {
        val words = Locator.Text(before = "comes to see the author in his confinement.\n", highlight = "The country around", after = " appeared like a continued garden.")
        assertEquals(TextSpot(3, 0), selectionSpot(blocks, words))
    }

    @Test
    fun `words that aren't in the chapter aren't found`() {
        val words = Locator.Text(before = "Nothing like this ", highlight = "is in the book", after = " at all, anywhere.")
        assertNull(selectionSpot(blocks, words))
    }
}
