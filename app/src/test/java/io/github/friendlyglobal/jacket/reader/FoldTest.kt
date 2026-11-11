package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldTest {
    private val width = 1000f
    private val height = 2000f

    /** The fold of a leaf whose edge point [edgeY] down the side is held at ([x], [y]). */
    private fun held(x: Float, y: Float, edgeY: Float) = Fold().apply { set(x, y, edgeY, width, height) }

    @Test
    fun `a leaf held where it rests lies flat`() {
        val fold = held(width, 300f, edgeY = 300f)
        assertEquals(0f, fold.reach, 0f)
        assertEquals(0f, fold.turned, 0f)
        // No corner of the page is past the fold.
        assertTrue(fold.beyond(width, 0f) <= 0f && fold.beyond(width, height) <= 0f)
    }

    @Test
    fun `pulled straight across, it folds upright halfway to the finger`() {
        val fold = held(400f, 600f, edgeY = 600f)
        assertEquals(700f, fold.midX, 0.01f)
        assertEquals(1f, fold.acrossX, 0.001f)
        assertEquals(0f, fold.acrossY, 0.001f)
        // What is past the fold lies back over the page as far as the finger: the edge is under it.
        assertEquals(300f, fold.reach, 0.01f)
        assertEquals(0.3f, fold.turned, 0.001f)
    }

    @Test
    fun `held above where it rests, the bottom corner comes up before the top`() {
        val fold = held(800f, 1500f, edgeY = 1900f)
        assertTrue("the bottom corner is turned over", fold.beyond(width, height) > 0f)
        assertTrue("the top corner still lies flat", fold.beyond(width, 0f) < 0f)
    }

    @Test
    fun `held below where it rests, the top corner comes up before the bottom`() {
        val fold = held(800f, 500f, edgeY = 100f)
        assertTrue("the top corner is turned over", fold.beyond(width, 0f) > 0f)
        assertTrue("the bottom corner still lies flat", fold.beyond(width, height) < 0f)
    }

    @Test
    fun `turned right over, the fold is the binding`() {
        val fold = held(-width, 900f, edgeY = 900f)
        assertEquals(0f, fold.midX, 0.01f)
        assertEquals(width, fold.reach, 0.01f)
        assertEquals(1f, fold.turned, 0.001f)
    }

    @Test
    fun `wherever the finger pulls, the binding stays down`() {
        var worst = 0f
        for (edge in 0..4) {
            val edgeY = height * edge / 4f
            for (i in -6..6) {
                for (j in -4..8) {
                    val fold = held(width * i / 4f, height * j / 4f, edgeY)
                    worst = maxOf(worst, fold.beyond(0f, 0f), fold.beyond(0f, height))
                }
            }
        }
        // Neither end of the binding is ever past the fold.
        assertTrue("the binding lifts by $worst", worst <= 0.5f)
    }

    @Test
    fun `a finger past the far edge pulls no further than the paper reaches`() {
        // Off beyond the left edge, level: turned right over and no more.
        assertEquals(1f, held(-3 * width, 700f, edgeY = 700f).turned, 0.001f)
        // Off beyond the right edge: lying flat.
        assertEquals(0f, held(2 * width, 700f, edgeY = 700f).reach, 0.5f)
    }
}
