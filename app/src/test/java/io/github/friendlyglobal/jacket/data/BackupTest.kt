package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private fun book(id: String, name: String, opened: Long = 0, progress: Float = 0f) = Book(
        id = id, title = "T$id", author = "A", format = BookFormat.EPUB, fileName = "$id.epub",
        coverFile = null, seedColor = null, sourceName = name, sizeBytes = 100, addedAt = 1,
        progress = progress, lastOpenedAt = opened,
    )

    private val highlight = Annotation(
        id = "h1", bookId = "b1", kind = AnnotationKind.HIGHLIGHT, locator = "{\"href\":\"x\"}",
        progress = 0.4f, chapter = "II", text = "a quote", note = "nice", color = HighlightColor.BLUE, createdAt = 5,
    )

    @Test
    fun roundTripKeepsEverything() {
        val state = LibraryState(
            books = listOf(book("b1", "one.epub", opened = 10, progress = 0.5f).copy(finishedAt = 7, locator = "{\"href\":\"c\"}")),
            settings = Settings(volumeKeys = true, lineSpacing = LineSpacing.LOOSE, margins = PageMargins.WIDE, alignment = TextAlignment.JUSTIFY),
            annotations = listOf(highlight),
        )
        assertEquals(state.copy(currentId = null), Backup.read(Backup.write(state)))
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backup.read("{\"books\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { Backup.read("not json") }
    }

    @Test
    fun mergeMatchesBooksByFileAndKeepsTheFurthestPlace() {
        // Same file, imported again on a new phone under a new id.
        val here = LibraryState(books = listOf(book("new", "one.epub", opened = 5, progress = 0.1f)))
        val saved = LibraryState(
            books = listOf(book("b1", "one.epub", opened = 50, progress = 0.6f), book("b2", "two.epub")),
            annotations = listOf(highlight),
        )
        val merged = Backup.merge(here, saved)
        assertEquals(listOf("new", "b2"), merged.books.map { it.id })
        assertEquals(0.6f, merged.books[0].progress)
        // The highlight follows its book to the new id.
        assertEquals("new", merged.annotations.single().bookId)
    }

    @Test
    fun mergeKnowsTheSameFileUnderAnotherName() {
        // Downloaded again on the new phone as "one (1).epub": the same bytes, so the same book.
        val here = LibraryState(books = listOf(book("new", "one (1).epub", opened = 5, progress = 0.1f).copy(sha256 = "abc123")))
        val saved = LibraryState(
            books = listOf(
                book("b1", "one.epub", opened = 50, progress = 0.6f).copy(sha256 = "abc123"),
                // Another book that happens to be the same size isn't.
                book("b2", "two.epub").copy(sha256 = "def456"),
            ),
            annotations = listOf(highlight),
        )
        val merged = Backup.merge(here, saved)
        assertEquals(listOf("new", "b2"), merged.books.map { it.id })
        assertEquals(0.6f, merged.books[0].progress)
        assertEquals("new", merged.annotations.single().bookId)
    }

    @Test
    fun aBooksSha256IsKeptInBackupsAndOlderOnesHaveNone() {
        val state = LibraryState(books = listOf(book("b1", "one.epub").copy(sha256 = "abc123"), book("b2", "two.epub")))
        val text = Backup.write(state)
        assertEquals(listOf("abc123", null), Backup.read(text).books.map { it.sha256 })
        // A backup from before it was kept has no such field at all.
        val older = org.json.JSONObject(text).apply {
            val books = getJSONArray("books")
            for (i in 0 until books.length()) books.getJSONObject(i).remove("sha256")
        }.toString()
        assertEquals(listOf(null, null), Backup.read(older).books.map { it.sha256 })
    }

    @Test
    fun anEmptyLibraryNeverReplacesAnAutomaticBackupThatHasBooks() {
        val good = Backup.write(LibraryState(books = listOf(book("b1", "one.epub", opened = 10, progress = 0.5f)), annotations = listOf(highlight)))
        // The library couldn't be read when Jacket started (or this is a new phone): no books.
        assertFalse(Backup.mayReplace(LibraryState()) { good })
        assertFalse(Backup.mayReplace(LibraryState(settings = Settings(volumeKeys = true), reading = mapOf("2026-11-11" to 60_000L))) { good })
        // The same backup with spaces after it, as an app that won't empty files leaves it.
        assertFalse(Backup.mayReplace(LibraryState()) { "$good     " })
    }

    @Test
    fun aLibraryWithBooksReplacesAnyAutomaticBackupWithoutReadingIt() {
        val state = LibraryState(books = listOf(book("b2", "two.epub")))
        assertTrue(Backup.mayReplace(state) { throw AssertionError("the file was read") })
    }

    @Test
    fun anEmptyLibraryMayBeWrittenWhereThereAreNoBooksToLose() {
        val empty = LibraryState()
        // The file just made for the backup.
        assertTrue(Backup.mayReplace(empty) { "" })
        assertTrue(Backup.mayReplace(empty) { null })
        // A backup of an empty library, some other file, and a backup cut short.
        assertTrue(Backup.mayReplace(empty) { Backup.write(LibraryState(settings = Settings(volumeKeys = true))) })
        assertTrue(Backup.mayReplace(empty) { "{\"books\":[{\"id\":\"x\"}]}" })
        assertTrue(Backup.mayReplace(empty) { "not json" })
        val good = Backup.write(LibraryState(books = listOf(book("b1", "one.epub"))))
        assertTrue(Backup.mayReplace(empty) { good.take(good.length / 2) })
    }

    @Test
    fun backupFileNamesCannotLeaveTheBooksFolder() {
        val evil = book("x", "evil.epub").copy(fileName = "../library.json", coverFile = "../../shared_prefs/a.xml")
        val merged = Backup.merge(LibraryState(), LibraryState(books = listOf(evil)))
        assertEquals("library.json", merged.books.single().fileName)
        assertEquals("a.xml", merged.books.single().coverFile)
    }

    @Test
    fun oldLibrariesKeepBooksReadToTheEndAsFinished() {
        val old = """{"books":[{"id":"b","title":"T","format":"EPUB","fileName":"b.epub","progress":1.0,"lastOpenedAt":42}]}"""
        assertEquals(42L, LibraryJson.decode(old).books.single().finishedAt)
    }

    @Test
    fun restoringOnANewPhoneBringsBackTheReadingTimeAndTheStreak() {
        val days = mapOf("2026-11-08" to 1_800_000L, "2026-11-09" to 600_000L, "2026-11-10" to 2_400_000L)
        val restored = Backup.merge(LibraryState(), Backup.read(Backup.write(LibraryState(reading = days))))
        assertEquals(days, restored.reading)
        assertEquals(3, readingStreak(restored.reading, java.time.LocalDate.parse("2026-11-10")))
    }

    @Test
    fun aDayInBothCountsOnceAtTheLongerOfTheTwo() {
        val here = LibraryState(reading = mapOf("2026-11-10" to 2_400_000L, "2026-11-11" to 300_000L))
        // This phone's own backup from yesterday evening, and a day only the backup knows.
        val saved = LibraryState(reading = mapOf("2026-11-09" to 600_000L, "2026-11-10" to 1_200_000L))
        val merged = Backup.merge(here, saved).reading
        assertEquals(mapOf("2026-11-09" to 600_000L, "2026-11-10" to 2_400_000L, "2026-11-11" to 300_000L), merged)
        // Restoring the same backup twice changes nothing more.
        assertEquals(merged, Backup.merge(here.copy(reading = merged), saved).reading)
    }

    @Test
    fun theWatchedFolderIsThisPhonesNotTheBackups() {
        val backup = LibraryState(settings = Settings(font = ReadingFont.SANS, watchFolder = "content://tree/old-phone", watchSeen = setOf("a.epub|10")))
        // A new phone watches nothing until it is given a folder of its own.
        val fresh = Backup.merge(LibraryState(), backup).settings
        assertEquals(null, fresh.watchFolder)
        assertTrue(fresh.watchSeen.isEmpty())
        // A phone that watches a folder goes on watching it, and remembers what it took from it.
        val here = LibraryState(settings = Settings(watchFolder = "content://tree/here", watchSeen = setOf("b.epub|20")))
        val kept = Backup.merge(here, backup).settings
        assertEquals("content://tree/here", kept.watchFolder)
        assertEquals(setOf("b.epub|20"), kept.watchSeen)
        // The rest of the settings still come from the backup.
        assertEquals(ReadingFont.SANS, kept.font)
    }

    @Test
    fun mergeDoesNotDuplicateAnnotations() {
        val state = LibraryState(books = listOf(book("b1", "one.epub")), annotations = listOf(highlight))
        val merged = Backup.merge(state, state)
        assertEquals(1, merged.annotations.size)
        assertTrue(merged.books.size == 1)
    }

    // --- "Say it this way": the words the voice says the listener's way ---

    @Test
    fun roundTripKeepsTheWordsSaidYourWay() {
        val state = LibraryState(
            books = listOf(book("b1", "one.epub")),
            settings = Settings(skipNoteNumbers = false),
            sayings = listOf(
                Saying(id = "s1", word = "Hermione", say = "her-MY-oh-nee", bookId = "b1", createdAt = 3),
                Saying(id = "s2", word = "Siobhan", say = "shi-VAWN", bookId = null, createdAt = 4),
            ),
        )
        val restored = Backup.read(Backup.write(state))
        assertEquals(state.sayings, restored.sayings)
        assertFalse(restored.settings.skipNoteNumbers)
        assertEquals(state.copy(currentId = null), restored)
    }

    @Test
    fun anOlderBackupRestoresWithoutThem() {
        // As 1.1 wrote it: no "sayings", no "skipNoteNumbers".
        val old = org.json.JSONObject(Backup.write(LibraryState(books = listOf(book("b1", "one.epub")), annotations = listOf(highlight))))
        old.remove("sayings")
        old.getJSONObject("settings").remove("skipNoteNumbers")
        val restored = Backup.read(old.toString())
        assertEquals(emptyList<Saying>(), restored.sayings)
        assertTrue(restored.settings.skipNoteNumbers)
        assertEquals(listOf(highlight), restored.annotations)
        // And merged into a library that has some, theirs stay.
        val here = LibraryState(books = listOf(book("b1", "one.epub")), sayings = listOf(Saying("s1", "Hermione", "her-MY-oh-nee", "b1")))
        assertEquals(here.sayings, Backup.merge(here, restored).sayings)
    }

    @Test
    fun mergeBringsWordsBackToTheBookTheyWereFor() {
        // The same file, added again on a new phone under a new id.
        val here = LibraryState(
            books = listOf(book("new", "one.epub")),
            sayings = listOf(Saying("mine", "Smaug", "smowg", bookId = "new")),
        )
        val backup = LibraryState(
            books = listOf(book("old", "one.epub"), book("gone", "two.epub")),
            sayings = listOf(
                Saying("s1", "Hermione", "her-MY-oh-nee", bookId = "old"),
                // Already said another way here: this phone's stays.
                Saying("s2", "smaug", "smog", bookId = "old"),
                Saying("s3", "Siobhan", "shi-VAWN", bookId = null),
                Saying("s4", "Aragorn", "A-ra-gorn", bookId = "gone"),
                // For a book in neither library: nothing to be said in.
                Saying("s5", "Nobody", "no-body", bookId = "never"),
            ),
        )
        val merged = Backup.merge(here, backup)
        assertEquals(listOf("mine", "s1", "s3", "s4"), merged.sayings.map { it.id })
        assertEquals("new", merged.sayings.first { it.id == "s1" }.bookId)
        assertEquals("smowg", merged.sayings.first { it.id == "mine" }.say)
        assertEquals("gone", merged.sayings.first { it.id == "s4" }.bookId)
        // Restoring the same backup again adds nothing.
        assertEquals(merged.sayings, Backup.merge(merged, backup).sayings)
    }

    @Test
    fun aBooksOwnWayOfSayingAWordComesFirst() {
        val state = LibraryState(
            sayings = listOf(
                Saying("s1", "Smaug", "smog", bookId = null),
                Saying("s2", "Smaug", "smowg", bookId = "b1"),
                Saying("s3", "Bilbo", "BILL-bo", bookId = "b2"),
            ),
        )
        assertEquals(listOf("smowg", "smog"), state.respellingsFor("b1").map { it.say })
        assertEquals(listOf("BILL-bo", "smog"), state.respellingsFor("b2").map { it.say })
    }

    @Test
    fun aDamagedWordIsLeftOutAndCounted() {
        val text = org.json.JSONObject(LibraryJson.encode(LibraryState(sayings = listOf(Saying("s1", "Hermione", "her-MY-oh-nee")))))
        text.getJSONArray("sayings").put(org.json.JSONObject().put("id", "s2").put("word", "Ron"))
        val read = LibraryJson.read(text.toString())
        assertEquals(listOf("s1"), read.state.sayings.map { it.id })
        assertEquals(1, read.unread)
    }
}
