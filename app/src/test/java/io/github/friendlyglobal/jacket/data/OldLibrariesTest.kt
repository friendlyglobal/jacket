package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An update must find the library the last version left: every book, its place, its notes, and
 * the settings. These are library files as older versions wrote them, read by this one.
 */
class OldLibrariesTest {
    /** A Readium locator as it is saved: JSON inside the JSON, quotes and slashes escaped. */
    private val place = """{\"href\":\"epub\\/text\\/chapter-4.xhtml\",\"type\":\"application\\/xhtml+xml\",\"title\":\"IV\",\"locations\":{\"progression\":0.4167,\"position\":28,\"totalProgression\":0.28125}}"""

    /** 1.0.0-rc.3: before natural voices, collections, pins and covers of your own. */
    private val rc3 = """
        {"version":1,"currentId":"b1",
         "settings":{"coverStyle":"SHELF","showProgress":false,"ambientDim":true,"autoOuterScreen":false,"textScale":1.2,
           "bookColors":true,"font":"HYPERLEGIBLE","pageColor":"DARK","layout":"SCROLL","sort":"TITLE","listenRate":1.25,
           "lineSpacing":"LOOSE","margins":"WIDE","alignment":"JUSTIFY","volumeKeys":true,"showFinished":false,
           "pageTurn":"CURL","coverWhenClosed":true,"onboarded":true,"timeLeft":false,"edgeSwipes":false,
           "brightness":0.4,"warmth":0.3,"nightPages":true,"tabletop":false,"readingStats":true,"ambientQuote":true,
           "watchFolder":"content://tree/books","watchSeen":["a.epub|10"]},
         "reading":{"2026-11-09":120000,"2026-11-10":190231},
         "books":[{"id":"b1","title":"The Time Machine","author":"H. G. Wells","format":"EPUB","fileName":"b1.epub",
           "coverFile":"b1.jpg","seedColor":-5732312,"sourceName":"The Time Machine.epub","sizeBytes":535571,
           "addedAt":1794327745046,"position":27,"positionOffset":0,"sectionCount":96,"progress":0.283,
           "lastOpenedAt":1794348721840,"locator":"$place","finishedAt":0,"pace":1424230.25}],
         "annotations":[{"id":"h1","bookId":"b1","kind":"HIGHLIGHT","locator":"$place","section":27,"progress":0.28,
           "chapter":"IV","text":"a queer thing","note":"mine","color":"BLUE","createdAt":5},
          {"id":"m1","bookId":"b1","kind":"BOOKMARK","locator":null,"section":3,"progress":0.1,"chapter":"I","text":"The Time Traveller","note":"","color":"YELLOW","createdAt":6}]}
    """.trimIndent()

    @Test
    fun aLibraryFromBeforeTheVoicesKeepsItsPlaceAndEverythingElse() {
        val read = LibraryJson.read(rc3)
        assertEquals(0, read.unread)
        val state = read.state
        val book = state.books.single()
        assertEquals("b1", state.currentId)
        assertEquals(27, book.position)
        assertEquals(96, book.sectionCount)
        assertEquals(0.283f, book.progress)
        assertTrue(book.locator!!.contains("chapter-4.xhtml"))
        assertEquals(1424230.25f, book.pace)
        assertEquals(1794348721840L, book.lastOpenedAt)
        // Notes and bookmarks, with their words and colours.
        assertEquals(listOf("h1", "m1"), state.annotations.map { it.id })
        assertEquals("mine", state.annotations[0].note)
        assertEquals(HighlightColor.BLUE, state.annotations[0].color)
        assertEquals(AnnotationKind.BOOKMARK, state.annotations[1].kind)
        // Reading time and the days read.
        assertEquals(mapOf("2026-11-09" to 120000L, "2026-11-10" to 190231L), state.reading)
        // Settings as they were set, none back to its default.
        val s = state.settings
        assertEquals(CoverStyle.SHELF, s.coverStyle)
        assertEquals(ReadingFont.HYPERLEGIBLE, s.font)
        assertEquals(PageColor.DARK, s.pageColor)
        assertEquals(PageLayout.SCROLL, s.layout)
        assertEquals(PageTurn.CURL, s.pageTurn)
        assertEquals(LibrarySort.TITLE, s.sort)
        assertEquals(1.25f, s.listenRate)
        assertEquals(1.2f, s.textScale)
        assertTrue(s.volumeKeys && s.coverWhenClosed && s.nightPages && s.ambientQuote)
        assertFalse(s.showProgress || s.autoOuterScreen || s.timeLeft || s.edgeSwipes || s.tabletop)
        assertEquals("content://tree/books", s.watchFolder)
        assertEquals(setOf("a.epub|10"), s.watchSeen)
        // The welcome isn't shown again.
        assertTrue(s.onboarded)
        // What that version knew nothing of starts as it would for anyone.
        assertNull(s.voice)
        assertTrue(s.voices.isEmpty() && state.collections.isEmpty())
        assertFalse(book.isPinned || book.coverCustomized)
    }

    @Test
    fun aLibraryFromBeforeTheWelcomeIsNotWelcomedAgain() {
        // 0.3: no "onboarded", no reading time, a book with a Readium place.
        val old = """{"version":1,"currentId":"b","settings":{"font":"SERIF"},
            "books":[{"id":"b","title":"T","author":"A","format":"EPUB","fileName":"b.epub","position":4,"sectionCount":90,"progress":0.05,"lastOpenedAt":7,"locator":"$place"}],
            "annotations":[]}"""
        val state = LibraryJson.read(old).state
        assertTrue(state.settings.onboarded)
        assertEquals(ReadingFont.SERIF, state.settings.font)
        assertTrue(state.books.single().locator!!.contains("chapter-4"))
    }

    @Test
    fun aLibraryFromJacketZeroTwoKeepsItsChapterAndOffset() {
        // 0.2 had no Readium: the place was a chapter number and how far down it.
        val old = """{"books":[{"id":"b","title":"T","format":"EPUB","fileName":"b.epub","position":6,"positionOffset":0.42,"sectionCount":20,"progress":0.33,"lastOpenedAt":9}]}"""
        val read = LibraryJson.read(old)
        val book = read.state.books.single()
        assertEquals(0, read.unread)
        assertNull(book.locator)
        assertEquals(6, book.position)
        assertEquals(0.42f, book.positionOffset)
        assertEquals(0.33f, book.progress)
    }

    @Test
    fun todaysLibraryReadsBackWhole() {
        val state = LibraryState(
            books = listOf(
                Book(
                    id = "b1", title = "Dom Casmurro", author = "Machado de Assis", format = BookFormat.EPUB, fileName = "b1.epub",
                    coverFile = "b1-1.jpg", seedColor = -4147056, sourceName = "dom-casmurro.epub", sizeBytes = 222381, addedAt = 1,
                    position = 46, sectionCount = 169, progress = 0.271f, lastOpenedAt = 2, locator = "{\"href\":\"a.xhtml\"}",
                    pace = 1951504.8f, language = "pt", pinnedAt = 3, collections = setOf("c1"),
                    coverCustomized = true, originalCover = "b1.jpg", originalSeed = -1,
                ),
                Book(
                    id = "b2", title = "Notes", author = "", format = BookFormat.TXT, fileName = "b2.txt", coverFile = null, seedColor = null,
                    sourceName = "notes.txt", sizeBytes = 184, addedAt = 4, position = 12, positionOffset = 0.5f, sectionCount = 40, progress = 0.31f,
                    lastOpenedAt = 5, finishedAt = 6,
                ),
            ),
            currentId = "b1",
            settings = Settings(
                onboarded = true, voice = "supertonic3/F2", voices = mapOf("pt" to "cadu/cadu", "zh" to "system"), listenRate = 1.5f,
                layout = PageLayout.SCROLL, pageTurn = PageTurn.CARDS, collection = "c1", showFinished = true,
            ),
            annotations = listOf(Annotation("h", "b1", AnnotationKind.HIGHLIGHT, locator = "{}", progress = 0.2f, text = "t", note = "n", color = HighlightColor.PINK, createdAt = 7)),
            reading = mapOf("2026-11-10" to 327377L),
            collections = listOf(BookCollection("c1", "Brazil", 8)),
        )
        val read = LibraryJson.read(LibraryJson.encode(state))
        assertEquals(0, read.unread)
        assertEquals(state, read.state)
    }

    @Test
    fun anEntryThatCannotBeReadIsCountedAndTheRestKept() {
        // A book in a format this version doesn't know (a newer Jacket's), and a note of a kind it doesn't.
        val newer = """{"books":[
              {"id":"b1","title":"Known","format":"EPUB","fileName":"b1.epub","progress":0.5,"locator":"$place"},
              {"id":"b2","title":"Unknown","format":"DJVU","fileName":"b2.djvu","progress":0.9}],
            "annotations":[
              {"id":"h1","bookId":"b1","kind":"HIGHLIGHT","text":"kept"},
              {"id":"x1","bookId":"b1","kind":"DRAWING"}],
            "collections":[{"id":"c1","name":"Kept"},{"name":"No id"}]}"""
        val read = LibraryJson.read(newer)
        // Three things left out: whoever saves over the file is told, and keeps a copy of it first.
        assertEquals(3, read.unread)
        assertEquals(listOf("b1"), read.state.books.map { it.id })
        assertEquals(0.5f, read.state.books.single().progress)
        assertEquals(listOf("h1"), read.state.annotations.map { it.id })
        assertEquals(listOf("c1"), read.state.collections.map { it.id })
    }

    @Test
    fun anFb2BookIsKeptAsTheFileItCameAs() {
        // The .fb2 (or the zip it came in) is the book: its name, its size and its bytes' SHA-256 are what the
        // library and a backup keep, as for any other file. The pages made of it are no part of either.
        val fb2 = Book(
            "b1", "Война и мир", "Лев Толстой", BookFormat.FB2, "b1.fb2", "b1.jpg", null, "war.fb2.zip", 1_014, 5,
            position = 12, sectionCount = 80, progress = 0.15f, locator = place, language = "ru", sha256 = "ab12",
        )
        val state = LibraryState(books = listOf(fb2), currentId = "b1")
        val read = LibraryJson.read(LibraryJson.encode(state))
        assertEquals(0, read.unread)
        assertEquals(fb2, read.state.books.single())
        val restored = Backup.merge(LibraryState(), Backup.read(Backup.write(state))).books.single()
        assertEquals(BookFormat.FB2, restored.format)
        assertEquals("war.fb2.zip" to 1_014L, restored.sourceName to restored.sizeBytes)
        assertEquals("ab12", restored.sha256)
        assertEquals(place, restored.locator)
        assertTrue(BookFormat.FB2.isWords)
        assertTrue(BookFormat.FB2.isEpubLike)
    }

    @Test
    fun aListThatIsNotAListCountsAsUnread() {
        // An empty library must not be taken for a file whose books simply couldn't be read.
        assertEquals(1, LibraryJson.read("""{"books":"gone"}""").unread)
        assertEquals(0, LibraryJson.read("""{"books":[]}""").unread)
        assertEquals(0, LibraryJson.read("""{}""").unread)
    }

    @Test
    fun whatIsNotALibraryIsRefusedNotReadAsAnEmptyOne() {
        assertThrows(Exception::class.java) { LibraryJson.read("") }
        assertThrows(Exception::class.java) { LibraryJson.read("{\"books\":[{\"id\":") }
        assertThrows(Exception::class.java) { LibraryJson.read("not a library") }
    }

    @Test
    fun anUnknownSettingValueFallsBackWithoutLosingTheOthers() {
        val state = LibraryJson.read("""{"settings":{"font":"COMIC_SANS","pageTurn":"CURL","voice":"supertonic3/M1","listenRate":9}}""").state
        assertEquals(ReadingFont.LITERATA, state.settings.font)
        assertEquals(PageTurn.CURL, state.settings.pageTurn)
        assertEquals("supertonic3/M1", state.settings.voice)
        assertEquals(1f, state.settings.listenRate)
    }

    @Test
    fun aLibraryFromBeforeListeningCountedHasNoListeningAndNoTimeWithEachBook() {
        val state = LibraryJson.read(rc3).state
        assertTrue(state.listened.isEmpty())
        assertEquals(0L, state.books.single().spentMs)
        assertEquals(0L, state.books.single().listenedMs)
        // Its reading time is as it was.
        assertEquals(mapOf("2026-11-09" to 120000L, "2026-11-10" to 190231L), state.reading)
    }

    @Test
    fun listeningIsAddedBesideTheReadingTimeSoAnOlderJacketReadsTheSameTotals() {
        val state = LibraryJson.read(rc3).state.let { old ->
            old.copy(
                books = old.books.map { it.copy(spentMs = 900_000L, listenedMs = 600_000L) },
                reading = old.reading + ("2026-11-11" to 900_000L),
                listened = mapOf("2026-11-11" to 600_000L),
            )
        }
        val written = org.json.JSONObject(LibraryJson.encode(state))
        // "reading" keeps its shape, every minute in it (listening too) where an older version looks.
        assertEquals(900_000L, written.getJSONObject("reading").getLong("2026-11-11"))
        // What an older version doesn't know, it passes over: without the new keys, the rest is the same.
        written.remove("listened")
        written.getJSONArray("books").getJSONObject(0).apply {
            remove("spentMs")
            remove("listenedMs")
        }
        val older = LibraryJson.read(written.toString())
        assertEquals(0, older.unread)
        assertEquals(state.reading, older.state.reading)
        assertEquals(state.books.map { it.copy(spentMs = 0L, listenedMs = 0L) }, older.state.books)
    }

    @Test
    fun aDamagedFileNeverHasMoreListenedThanSpent() {
        val damaged = rc3.replace("\"pace\":1424230.25", "\"pace\":1424230.25,\"spentMs\":1000,\"listenedMs\":5000")
        val book = LibraryJson.read(damaged).state.books.single()
        assertEquals(5000L, book.spentMs)
        assertEquals(5000L, book.listenedMs)
    }
}
