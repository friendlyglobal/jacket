package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The voice choice and a book's language are saved with the library, and old libraries load without them. */
class VoiceSettingsTest {
    private val book = Book(
        id = "b1", title = "T", author = "A", format = BookFormat.EPUB, fileName = "b1.epub",
        coverFile = null, seedColor = null, sourceName = "t.epub", sizeBytes = 100, addedAt = 1, language = "pt-BR",
    )

    @Test
    fun theVoiceAndTheBooksLanguageRoundTrip() {
        val state = LibraryState(
            books = listOf(book),
            settings = Settings(voice = "supertonic3/F2", voices = mapOf("pt" to "supertonic3/M1", "en" to "system")),
        )
        val back = LibraryJson.decode(LibraryJson.encode(state))
        assertEquals(state.settings.voice, back.settings.voice)
        assertEquals(state.settings.voices, back.settings.voices)
        assertEquals("pt-BR", back.books.single().language)
        // And through a backup file.
        assertEquals(state.settings.voices, Backup.read(Backup.write(state)).settings.voices)
    }

    @Test
    fun aLibrarySavedBeforeThereWereVoicesLoadsUnchanged() {
        val old = """
            {"version":1,"currentId":"b1","settings":{"listenRate":1.5,"font":"LITERATA"},
             "books":[{"id":"b1","title":"T","author":"A","format":"EPUB","fileName":"b1.epub","sourceName":"t.epub",
                       "sizeBytes":100,"addedAt":1,"progress":0.25}],"annotations":[]}
        """.trimIndent()
        val state = LibraryJson.decode(old)
        assertNull(state.settings.voice)
        assertEquals(emptyMap<String, String>(), state.settings.voices)
        assertEquals(1.5f, state.settings.listenRate)
        assertNull(state.books.single().language)
        assertEquals(0.25f, state.books.single().progress)
    }
}
