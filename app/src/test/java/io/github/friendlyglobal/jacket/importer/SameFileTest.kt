package io.github.friendlyglobal.jacket.importer

import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.bookMadeFrom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * The same file added twice is one book. Caught in the test library, which had "Dom Casmurro"
 * twice: a file is known by its bytes, not by its name ("book (1).epub" is "book.epub" downloaded
 * again) or by the size the app handing it over gives, which some don't.
 */
class SameFileTest {
    private fun book(id: String, name: String, size: Long, sha256: String? = null) = Book(
        id = id, title = "T$id", author = "A", format = BookFormat.EPUB, fileName = "$id.epub",
        coverFile = null, seedColor = null, sourceName = name, sizeBytes = size, addedAt = 1, sha256 = sha256,
    )

    private fun contentOf(text: String) = BookImporter.read(text.byteInputStream())

    @Test
    fun `a file is told by its length and the SHA-256 of its bytes`() {
        val content = contentOf("abc")
        assertEquals(3L, content.size)
        // The well-known SHA-256 of "abc".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", content.sha256)
        assertEquals(content, contentOf("abc"))
        // The same length, one letter different.
        assertNotEquals(content, contentOf("abd"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", contentOf("").sha256)
    }

    @Test
    fun `copying a file in says what was copied`() {
        // Longer than one buffer of the copy, so it is read in several goes.
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val content = BookImporter.read(bytes.inputStream(), out)
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(bytes.size.toLong(), content.size)
        assertEquals(BookImporter.read(bytes.inputStream()), content)
    }

    @Test
    fun `a book's file on the phone is read for what is in it`() {
        val folder = Files.createTempDirectory("jacket-same-file").toFile()
        try {
            val file = File(folder, "3ecca0b8.epub").apply { writeText("a whole book") }
            assertEquals(contentOf("a whole book"), BookImporter.contentOf(file))
            // A book restored from a backup has no file yet.
            assertNull(BookImporter.contentOf(File(folder, "missing.epub")))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the same file under another name is the book already here`() {
        val content = contentOf("Dom Casmurro, the whole of it")
        val here = book("b1", "dom-casmurro.epub", content.size, content.sha256)
        // "dom-casmurro (1).epub", or a name an app made up: the name isn't asked.
        assertEquals(here, bookMadeFrom(listOf(book("b0", "other.epub", 999, "ffff"), here), content) { null })
    }

    @Test
    fun `a book added before its SHA-256 was kept has its file read`() {
        val content = contentOf("Dom Casmurro, the whole of it")
        val old = book("b1", "dom-casmurro.epub", content.size)
        assertEquals(old, bookMadeFrom(listOf(old), content) { content })
        // Its file isn't on this phone (restored from a backup): there is nothing to tell it by.
        assertNull(bookMadeFrom(listOf(old), content) { null })
    }

    @Test
    fun `only a file of the same length is read`() {
        val content = contentOf("Dom Casmurro, the whole of it")
        val read = ArrayList<String>()
        val books = listOf(book("big", "big.pdf", 90_000_000), book("same", "same.epub", content.size), book("small", "small.txt", 12))
        assertNull(bookMadeFrom(books, content) { read += it.id; contentOf("Some other book of the length") })
        assertEquals(listOf("same"), read)
    }

    @Test
    fun `another book of the same length is another book`() {
        val content = contentOf("Dom Casmurro, the whole of it")
        val other = contentOf("Some other book of the length")
        assertEquals(content.size, other.size)
        assertNull(bookMadeFrom(listOf(book("b1", "one.epub", other.size, other.sha256)), content) { null })
    }

    @Test
    fun `books that are in the library twice already are left as they are`() {
        // The test library's two "Dom Casmurro" entries: the file added a third time is the first.
        val content = contentOf("Dom Casmurro, the whole of it")
        val twice = listOf(book("first", "dom-casmurro.epub", content.size), book("second", "dom-casmurro (1).epub", content.size))
        assertEquals("first", bookMadeFrom(twice, content) { content }?.id)
    }
}
