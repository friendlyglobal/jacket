package io.github.friendlyglobal.jacket.ui

import androidx.compose.ui.graphics.toArgb
import io.github.friendlyglobal.jacket.data.PageColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `contrast runs from one to twenty-one, whichever way round`() {
        assertEquals(21.0, contrast(black, white), 0.001)
        assertEquals(21.0, contrast(white, black), 0.001)
        assertEquals(1.0, contrast(white, white), 0.001)
        // The grey the guidelines give as the lightest that body text can be on white.
        assertEquals(4.54, contrast(0xFF767676.toInt(), white), 0.01)
    }

    @Test
    fun `every ink on the paper, dark and black pages is readable on its page`() {
        for (choice in listOf(PageColor.PAPER, PageColor.DARK, PageColor.BLACK)) {
            val page = fixedPageColors(choice)!!
            val paper = page.background.toArgb()
            for ((name, ink) in listOf("text" to page.text, "the quiet line under the page" to page.secondary, "links" to page.link)) {
                val ratio = contrast(ink.toArgb(), paper)
                assertTrue("$choice: $name is $ratio to 1", ratio >= READABLE)
            }
            // "Back to the voice" is written in the page's colour on the link colour.
            assertTrue("$choice: words on the link colour", contrast(paper, page.link.toArgb()) >= READABLE)
        }
    }

    @Test
    fun `the words still to come stay readable under their veil on every page`() {
        for (choice in listOf(PageColor.PAPER, PageColor.DARK, PageColor.BLACK)) {
            val page = fixedPageColors(choice)!!
            val ink = page.text.toArgb()
            val paper = page.background.toArgb()
            val veil = readableVeil(ink, paper)
            assertTrue("$choice: veiled words", contrast(veiled(ink, paper, veil), paper) >= READABLE)
            // As strong as it can be: a little stronger and the words would not be readable.
            assertTrue("$choice: the veil is as strong as allowed", contrast(veiled(ink, paper, veil + 0.02f), paper) < READABLE)
            // And still a veil that is seen: the words wait visibly paler than the rest.
            assertTrue("$choice: the veil is $veil", veil in 0.25f..0.6f)
        }
    }

    @Test
    fun `ink that is barely readable as it is gets no veil, and strong ink gets all of it`() {
        // Mid grey on white is at the limit already.
        assertEquals(0f, readableVeil(0xFF767676.toInt(), white), 0.01f)
        // Nothing is readable through a veil that hides it: the most is never passed.
        assertEquals(0.2f, readableVeil(black, white, most = 0.2f), 0.001f)
    }

    @Test
    fun `a veil mixes the paper into the ink`() {
        assertEquals(black, veiled(black, white, 0f))
        assertEquals(white, veiled(black, white, 1f))
        assertEquals(0xFF7F7F7F.toInt(), veiled(black, white, 0.5f))
    }
}
