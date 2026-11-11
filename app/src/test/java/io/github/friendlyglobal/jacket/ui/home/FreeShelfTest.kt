package io.github.friendlyglobal.jacket.ui.home

import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.LibraryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FreeShelfTest {
    private val shelves = listOf("en", "pt-BR", "zh-CN", "zh-TW", "id")

    @Test
    fun theReadersLanguageFindsItsShelf() {
        assertEquals("pt-BR", shelfLanguage(Locale.forLanguageTag("pt-BR"), shelves))
        assertEquals("en", shelfLanguage(Locale.forLanguageTag("en-GB"), shelves))
        assertEquals("zh-TW", shelfLanguage(Locale.forLanguageTag("zh-Hant-TW"), shelves))
        assertEquals("zh-CN", shelfLanguage(Locale.forLanguageTag("zh-Hans-CN"), shelves))
        assertEquals("id", shelfLanguage(Locale.forLanguageTag("in-ID"), shelves))
        // A language with no shelf of its own: all of them are shown.
        assertNull(shelfLanguage(Locale.forLanguageTag("fi-FI"), shelves))
    }

    @Test
    fun theCatalogReadsAndSkipsWhatItCant() {
        val shelf = FreeShelf.parse(
            """{"release":"https://github.com/friendlyglobal/jacket/releases/tag/books","books":[
              {"language":"pt-BR","title":"Dom Casmurro","author":"Machado de Assis","year":1899,"file":"pt-BR-machado-de-assis-dom-casmurro.epub","bytes":400000,"source":"Project Gutenberg","licence":"Public domain","cover":"pt-BR-machado-de-assis-dom-casmurro.webp"},
              {"language":"en","title":"Alice","author":"Lewis Carroll","year":1865,"file":"en-alice.epub","bytes":1,"source":"Standard Ebooks","licence":"CC0","cover":null},
              {"title":"no language or file"}
            ]}""",
        )
        assertEquals(2, shelf.books.size)
        assertEquals("pt-BR-machado-de-assis-dom-casmurro.webp", shelf.books[0].cover)
        assertNull(shelf.books[1].cover)
        assertEquals(1899, shelf.books[0].year)
    }

    @Test
    fun aDownloadedFreeBookIsRecognisedInTheLibrary() {
        val free = FreeBook("pt-BR", "Dom Casmurro", "Machado de Assis", 1899, "pt-BR-machado-de-assis-dom-casmurro.epub", 1, "", "", null)
        fun book(source: String, title: String = "Something else") = Book(
            id = source, title = title, author = "Machado de Assis", format = BookFormat.EPUB, fileName = "x.epub",
            coverFile = null, seedColor = null, sourceName = source, sizeBytes = 1, addedAt = 1,
        )
        assertNotNull(inLibrary(free, LibraryState(books = listOf(book("pt-BR-machado-de-assis-dom-casmurro.epub")))))
        // Downloaded twice, the browser numbers the second.
        assertNotNull(inLibrary(free, LibraryState(books = listOf(book("pt-BR-machado-de-assis-dom-casmurro (1).epub")))))
        // Got from somewhere else: the same title and author.
        assertNotNull(inLibrary(free, LibraryState(books = listOf(book("dom.epub", title = "Dom Casmurro")))))
        assertNull(inLibrary(free, LibraryState(books = listOf(book("other.epub")))))
    }
}
