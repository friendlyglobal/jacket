package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockShowingTest {
    private val blocks = listOf(
        "XL",
        "Uma egua.",
        "Ficando só, reflecti algum tempo, e tive uma fantasia. Já conheceis as minhas fantasias.",
        "XLI",
        "A audiencia secreta.",
        "O resto fez-me ficar mais algum tempo, no corredor, pensando.",
        "—Não, senhora, ella foi só.",
        "Toda assustada, quiz saber o que é que me doia, se a cabeça, se o peito.",
    )

    @Test
    fun `a page that opens on a chapter's heading starts at the heading`() {
        // What the page shows runs on from the heading into the title and the first paragraph.
        assertEquals(0, blockShowing(blocks, "XL Uma egua. Ficando só, reflecti algum tempo, e tive uma fantasia."))
        assertEquals(3, blockShowing(blocks, "XLI A audiencia secreta. O resto fez-me ficar mais algum tempo"))
    }

    @Test
    fun `a page that opens in the middle of a paragraph starts at that paragraph`() {
        assertEquals(2, blockShowing(blocks, "algum tempo, e tive uma fantasia. Já conheceis as minhas fantasias. XLI A audiencia"))
        assertEquals(5, blockShowing(blocks, "no corredor, pensando. —Não, senhora, ella foi só."))
    }

    @Test
    fun `spaces, line breaks and unseen characters don't matter`() {
        assertEquals(2, blockShowing(blocks, "Ficando  só,\n reflecti algum tem­po, e tive uma fan⁠tasia."))
        // The page puts a space between every piece of text; Readium's reading of the file doesn't.
        assertEquals(1, blockShowing(listOf("One", "Two AD. For that, I should explain"), "Two AD . For that, I should explain"))
    }

    @Test
    fun `too little to tell by, not there, or there twice, is left to the page's first element`() {
        assertNull(blockShowing(blocks, null))
        assertNull(blockShowing(blocks, "XL"))
        assertNull(blockShowing(blocks, "Nothing of the kind is written anywhere in this file."))
        val twice = listOf("—Sim, é isto, pensei.", "Outra cousa.", "—Sim, é isto, pensei.")
        assertNull(blockShowing(twice, "—Sim, é isto, pensei."))
    }
}
