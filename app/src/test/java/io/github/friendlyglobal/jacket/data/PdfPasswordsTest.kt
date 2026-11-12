package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The passwords of locked PDFs: held while Jacket runs, kept on the phone only when asked, and forgotten when asked (see PdfPasswords.kt). */
class PdfPasswordsTest {
    /** Stands in for Android's keystore: what it seals can't be read without it. */
    private class Seal(var works: Boolean = true) : PasswordSeal {
        override fun seal(password: String): String? = if (works) "sealed:" + password.reversed() else null
        override fun open(sealed: String): String? = if (works) sealed.removePrefix("sealed:").reversed() else null
    }

    private val file: File = File(Files.createTempDirectory("pdf-passwords").toFile().apply { deleteOnExit() }, "pdf-passwords.json")
    private val seal = Seal()

    /** Jacket, started again: what it knows is what the file holds. */
    private fun started(): PdfPasswords = PdfPasswords(file, seal)

    @Test
    fun `a password that isn't to be remembered opens the PDF only while Jacket runs`() {
        val passwords = started()
        assertNull(passwords.of("book"))
        passwords.opened("book", "s3cret", remember = false)
        assertEquals("s3cret", passwords.of("book"))
        assertTrue(passwords.remembered.value.isEmpty())
        // Nothing of it is written anywhere.
        assertFalse(file.readText().contains("s3cret"))
        assertFalse(file.readText().contains("terc3s"))
        // Started again, Jacket has no password for it: only the knowledge that it is locked.
        val again = started()
        assertNull(again.of("book"))
        assertTrue(again.isLocked("book"))
        assertEquals(setOf("book"), again.waiting.value)
    }

    @Test
    fun `a remembered password is kept sealed, and opens the PDF the next time`() {
        started().opened("book", "s3cret", remember = true)
        val written = file.readText()
        assertFalse(written.contains("s3cret"))
        assertTrue(written.contains("sealed:terc3s"))
        val again = started()
        again.load()
        assertEquals(setOf("book"), again.remembered.value)
        assertTrue(again.waiting.value.isEmpty())
        assertEquals("s3cret", again.of("book"))
        assertNull(again.of("another book"))
    }

    @Test
    fun `a forgotten password is gone from the phone, and the PDF is locked again`() {
        val passwords = started()
        passwords.opened("book", "s3cret", remember = true)
        passwords.forget("book")
        assertNull(passwords.of("book"))
        assertTrue(passwords.remembered.value.isEmpty())
        assertEquals(setOf("book"), passwords.waiting.value)
        assertFalse(file.readText().contains("terc3s"))
        assertNull(started().of("book"))
        assertTrue(started().isLocked("book"))
    }

    @Test
    fun `opening it again without remembering forgets what was kept`() {
        val passwords = started()
        passwords.opened("book", "s3cret", remember = true)
        passwords.opened("book", "s3cret", remember = false)
        assertEquals("s3cret", passwords.of("book"))
        assertTrue(passwords.remembered.value.isEmpty())
        assertNull(started().of("book"))
    }

    @Test
    fun `a book that leaves the library takes everything about its password with it`() {
        val passwords = started()
        passwords.opened("book", "s3cret", remember = true)
        passwords.opened("other", "0ther", remember = true)
        passwords.forget("book", gone = true)
        assertFalse(passwords.isLocked("book"))
        assertTrue(passwords.waiting.value.isEmpty())
        assertFalse(file.readText().contains("book\""))
        assertEquals("0ther", started().of("other"))
        // With the last of them gone, so is the file.
        passwords.forget("other", gone = true)
        assertFalse(file.exists())
    }

    @Test
    fun `a PDF found to be locked waits for its password, and stops waiting once it is given`() {
        val passwords = started()
        passwords.locked("book")
        assertEquals(setOf("book"), passwords.waiting.value)
        assertTrue(started().isLocked("book"))
        passwords.opened("book", "s3cret", remember = false)
        assertTrue(passwords.waiting.value.isEmpty())
        // Forgetting a password nobody kept changes nothing on the phone.
        passwords.forget("nobody")
        assertTrue(passwords.isLocked("book"))
    }

    @Test
    fun `a phone that can't seal remembers nothing, and still opens the PDF while Jacket runs`() {
        seal.works = false
        val passwords = started()
        passwords.opened("book", "s3cret", remember = true)
        assertEquals("s3cret", passwords.of("book"))
        assertTrue(passwords.remembered.value.isEmpty())
        assertFalse(file.readText().contains("s3cret"))
        assertNull(started().of("book"))
    }

    @Test
    fun `a password the phone can no longer unseal is asked for again`() {
        started().opened("book", "s3cret", remember = true)
        // (The keystore's key was lost: a restore onto another phone, say.)
        seal.works = false
        assertNull(started().of("book"))
    }

    @Test
    fun `a damaged file is an empty one`() {
        file.writeText("{ not json")
        val passwords = started()
        assertNull(passwords.of("book"))
        passwords.opened("book", "s3cret", remember = true)
        assertEquals("s3cret", started().of("book"))
    }

    @Test
    fun `no password is part of the library, so none is in a backup`() {
        started().opened("book", "s3cret", remember = true)
        val state = LibraryState(books = listOf(Book("book", "Locked", "", BookFormat.PDF, "book.pdf", null, null, "locked.pdf", 1, 1)))
        for (written in listOf(LibraryJson.encode(state), Backup.write(state))) {
            assertFalse(written.contains("s3cret"))
            assertFalse(written.contains("terc3s"))
            assertFalse(written.contains("password", ignoreCase = true))
        }
    }
}
