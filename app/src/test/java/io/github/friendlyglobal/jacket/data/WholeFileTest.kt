package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * "Save a backup" over a file held by an app that won't empty it first. The file here behaves
 * like such an app's: bytes written land over the start of what it holds.
 */
class WholeFileTest {
    /** A file some other app holds: whether it takes "wt", and whether opening it "w" empties it. */
    private class Held(var bytes: ByteArray, val takesWt: Boolean, val wEmpties: Boolean) {
        val opened = ArrayList<String>()

        fun open(mode: String): OutputStream? {
            opened += mode
            when (mode) {
                "wt" -> if (takesWt) bytes = ByteArray(0) else throw IllegalArgumentException("Invalid mode: wt")
                "w" -> if (wEmpties) bytes = ByteArray(0)
                else -> return null
            }
            val old = bytes
            return object : ByteArrayOutputStream() {
                override fun close() {
                    val written = toByteArray()
                    // Over the start of what was there; the rest of it stays.
                    bytes = written + old.drop(written.size)
                }
            }
        }

        val text get() = String(bytes, Charsets.UTF_8)
    }

    private fun book(id: String) = Book(
        id = id, title = "T$id", author = "A", format = BookFormat.EPUB, fileName = "$id.epub",
        coverFile = null, seedColor = null, sourceName = "$id.epub", sizeBytes = 100, addedAt = 1,
    )

    private val small = LibraryState(books = listOf(book("b1")))
    private val large = LibraryState(books = List(40) { book("book-$it") })

    private fun write(file: Held, state: LibraryState, length: Long = file.bytes.size.toLong()): Long? =
        WholeFile.write(Backup.write(state, exportedAt = 1).toByteArray(Charsets.UTF_8), { length }, file::open)

    @Test
    fun `a file that can be emptied holds the backup and nothing else`() {
        val file = Held(Backup.write(large, exportedAt = 1).toByteArray(), takesWt = true, wEmpties = false)
        val length = write(file, small)
        assertEquals(Backup.write(small, exportedAt = 1), file.text)
        assertEquals(file.bytes.size.toLong(), length)
        assertEquals(listOf("wt"), file.opened)
    }

    @Test
    fun `a shorter backup over a longer one that won't be emptied leaves nothing of the old one`() {
        val file = Held(Backup.write(large, exportedAt = 1).toByteArray(), takesWt = false, wEmpties = false)
        val before = file.bytes.size
        val length = write(file, small)
        // Written over the start alone, it would end with the tail of the old backup.
        assertEquals(Backup.write(small, exportedAt = 1), file.text.trimEnd(' '))
        assertEquals(before, file.bytes.size)
        assertEquals(before.toLong(), length)
        // And it reads back as the backup it is.
        assertEquals(small, Backup.read(file.text))
        assertEquals(listOf("wt", "w"), file.opened)
    }

    @Test
    fun `a longer backup over a shorter one is written as it is`() {
        val file = Held(Backup.write(small, exportedAt = 1).toByteArray(), takesWt = false, wEmpties = false)
        write(file, large)
        assertEquals(Backup.write(large, exportedAt = 1), file.text)
    }

    @Test
    fun `an app that empties the file by itself gets the backup, with spaces after it at most`() {
        val file = Held(Backup.write(large, exportedAt = 1).toByteArray(), takesWt = false, wEmpties = true)
        write(file, small)
        assertEquals(Backup.write(small, exportedAt = 1), file.text.trimEnd(' '))
        assertEquals(small, Backup.read(file.text))
    }

    @Test
    fun `a new, empty file gets the backup exactly`() {
        val file = Held(ByteArray(0), takesWt = false, wEmpties = false)
        write(file, small)
        assertEquals(Backup.write(small, exportedAt = 1), file.text)
    }

    @Test
    fun `a file that won't be emptied and can't be measured is left alone`() {
        val old = Backup.write(large, exportedAt = 1).toByteArray()
        val file = Held(old, takesWt = false, wEmpties = false)
        assertNull(write(file, small, length = -1))
        assertArrayEquals(old, file.bytes)
        // It was never opened for writing in the mode that would have written over it.
        assertEquals(listOf("wt"), file.opened)
    }

    @Test
    fun `a file that can't be opened at all is reported, not thrown`() {
        assertNull(WholeFile.write("{}".toByteArray(), { 0 }) { null })
        assertNull(WholeFile.write("{}".toByteArray(), { 0 }) { throw SecurityException("no permission") })
    }

    @Test
    fun `a very long file is covered to its end`() {
        // The reader chose to save over some other, much longer file.
        val file = Held(ByteArray(100_000) { 'x'.code.toByte() }, takesWt = false, wEmpties = false)
        write(file, small)
        assertEquals(100_000, file.bytes.size)
        assertEquals(small, Backup.read(file.text))
        assertEquals(Backup.write(small, exportedAt = 1), file.text.trimEnd(' '))
    }
}
