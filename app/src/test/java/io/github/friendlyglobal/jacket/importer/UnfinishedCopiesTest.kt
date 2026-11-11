package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnfinishedCopiesTest {
    @Test
    fun halfCopiedFilesAreClearedAndBooksAreLeftAlone() {
        val books = Files.createTempDirectory("jacket-books").toFile()
        try {
            // A book, a new book cut short as it was copied in, and a restored book's file cut short.
            File(books, "3ecca0b8.epub").writeText("a whole book")
            File(books, "7743d9b4.part").writeText("half a b")
            File(books, "c6736c1a.pdf.part").writeText("half a p")
            // A folder is never a copy, whatever it is called.
            File(books, "odd.part").delete()
            File(books, "folder.part").mkdir()

            BookImporter.clearUnfinished(books)

            assertEquals(listOf("3ecca0b8.epub", "folder.part"), books.list()!!.sorted())
            assertEquals("a whole book", File(books, "3ecca0b8.epub").readText())
        } finally {
            books.deleteRecursively()
        }
    }

    @Test
    fun aFolderThatIsNotThereIsNoTrouble() {
        BookImporter.clearUnfinished(File("/nowhere/at/all"))
    }
}
