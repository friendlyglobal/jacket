package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.importer.PdfLocked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening a PDF that may be locked: which password it is tried with, and what is kept of how that went (see PdfLock.kt). */
class PdfOpeningTest {
    /** What Jacket knows of one PDF's password, and what it was told. */
    private class Keys(var known: String? = null) : PdfKeys {
        var kept: PdfAttempt? = null
        var dropped = false
        var locked = false
        override fun known() = known
        override fun opened(attempt: PdfAttempt) {
            kept = attempt
        }
        override fun stale() {
            dropped = true
        }
        override fun locked() {
            locked = true
        }
    }

    /** A PDF as Android opens it: [password] null for one that isn't locked. Each try is noted. */
    private class Pdf(private val password: String?) {
        val tried = ArrayList<String?>()
        fun open(given: String?): String {
            tried += given
            if (password != null && given != password) throw PdfLocked(wrong = given != null)
            return "pages"
        }
    }

    private fun locked(typed: PdfAttempt?, keys: Keys, pdf: Pdf): PdfLocked? = try {
        PdfOpening.open(typed, keys, pdf::open)
        null
    } catch (e: PdfLocked) {
        e
    }

    @Test
    fun `a PDF that isn't locked opens as it always did`() {
        val keys = Keys()
        val pdf = Pdf(password = null)
        assertEquals("pages", PdfOpening.open(null, keys, pdf::open))
        assertEquals(listOf<String?>(null), pdf.tried)
        assertNull(keys.kept)
        assertFalse(keys.locked)
    }

    @Test
    fun `a locked PDF asks for its password, without calling anything wrong`() {
        val keys = Keys()
        val refused = locked(null, keys, Pdf("s3cret"))!!
        assertFalse(refused.wrong)
        assertTrue(keys.locked)
        assertNull(keys.kept)
    }

    @Test
    fun `a wrong password is said to be wrong, and nothing is kept of it`() {
        val keys = Keys()
        val refused = locked(PdfAttempt("guess", remember = true), keys, Pdf("s3cret"))!!
        assertTrue(refused.wrong)
        assertNull(keys.kept)
        assertFalse(keys.dropped)
    }

    @Test
    fun `the right password opens it, and is kept the way the reader asked`() {
        for (remember in listOf(true, false)) {
            val keys = Keys()
            assertEquals("pages", PdfOpening.open(PdfAttempt("s3cret", remember), keys, Pdf("s3cret")::open))
            assertEquals("s3cret", keys.kept!!.password)
            assertEquals(remember, keys.kept!!.remember)
        }
    }

    @Test
    fun `a PDF whose password Jacket has opens without asking`() {
        val keys = Keys(known = "s3cret")
        val pdf = Pdf("s3cret")
        assertEquals("pages", PdfOpening.open(null, keys, pdf::open))
        assertEquals(listOf<String?>("s3cret"), pdf.tried)
        // Nothing new to keep: it is kept already, the way it was asked to be.
        assertNull(keys.kept)
    }

    @Test
    fun `a password Jacket had that no longer opens the file is dropped, and asked for anew`() {
        val keys = Keys(known = "old")
        val refused = locked(null, keys, Pdf("new"))!!
        // The reader typed nothing just now: they are asked, not told they were wrong.
        assertFalse(refused.wrong)
        assertTrue(keys.dropped)
    }

    @Test
    fun `what the reader types is what is tried, whatever Jacket had`() {
        val keys = Keys(known = "old")
        val pdf = Pdf("new")
        assertEquals("pages", PdfOpening.open(PdfAttempt("new", remember = false), keys, pdf::open))
        assertEquals(listOf<String?>("new"), pdf.tried)
        assertFalse(keys.dropped)
    }
}
