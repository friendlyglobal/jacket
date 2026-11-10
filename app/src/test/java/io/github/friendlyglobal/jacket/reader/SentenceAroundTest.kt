package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceAroundTest {
    private val text = "It was late. \"Who goes there?\" he asked… Nobody answered"

    private fun at(index: Int) = sentenceAround(text, index).let { text.substring(it.first, it.last + 1) }

    @Test
    fun findsTheSentenceHoldingTheWord() {
        assertEquals("It was late.", at(0))
        assertEquals("It was late.", at(11))
        assertEquals("\"Who goes there?\" he asked…", at(text.indexOf("goes")))
        assertEquals("\"Who goes there?\" he asked…", at(text.indexOf("asked")))
    }

    @Test
    fun theLastSentenceNeedsNoFullStop() {
        assertEquals("Nobody answered", at(text.indexOf("Nobody")))
        assertEquals("Nobody answered", at(text.length + 5))
    }

    @Test
    fun aDecimalPointIsNotAnEnd() {
        val price = "It cost 3.50 then. Now more."
        assertEquals("It cost 3.50 then.", sentenceAround(price, 9).let { price.substring(it.first, it.last + 1) })
    }
}
