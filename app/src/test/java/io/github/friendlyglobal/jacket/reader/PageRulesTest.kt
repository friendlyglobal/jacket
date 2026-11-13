package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRulesTest {
    private fun ruled(html: String) = String(PageRules.withRule(html.toByteArray()), Charsets.UTF_8)

    @Test
    fun thePageCarriesTheRuleBeforeAnythingOfItsOwn() {
        val page = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><HEAD lang="pt"><script src="a.js"></script></HEAD><body>Olá, mundo</body></html>"""
        val out = ruled(page)
        val rule = """<meta http-equiv="Content-Security-Policy" content="connect-src 'self' https: data: blob:"/>"""
        assertEquals(page.replace("""<HEAD lang="pt">""", """<HEAD lang="pt">$rule"""), out)
        // The words, accented ones too, are as they were.
        assertEquals(true, out.endsWith("<body>Olá, mundo</body></html>"))
    }

    @Test
    fun aPageItCantReadIsLeftAsItIs() {
        // No head (a fragment), and UTF-16.
        assertEquals("<p>hi</p>", ruled("<p>hi</p>"))
        val utf16 = "<html><head></head></html>".toByteArray(Charsets.UTF_16)
        assertArrayEquals(utf16, PageRules.withRule(utf16))
        // <header> isn't <head>.
        assertEquals("<html><header>x</header></html>", ruled("<html><header>x</header></html>"))
    }
}
