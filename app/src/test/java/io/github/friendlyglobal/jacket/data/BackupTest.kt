package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
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
}
