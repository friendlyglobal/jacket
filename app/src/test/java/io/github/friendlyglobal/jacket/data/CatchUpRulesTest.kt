package io.github.friendlyglobal.jacket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catching up between two devices (see [CatchUpRules]): a foldable and a tablet with the same
 * books on them, each keeping a file the other reads. Every file here goes the way a real one
 * does: written as text, and read back from it.
 */
class CatchUpRulesTest {
    /** A device as far as catching up goes: its library, and what it last told the others. */
    private class Device(val id: String, val name: String, var library: LibraryState) {
        var mine = DeviceFile(id, name)
        var answered: Map<String, Map<String, Long>> = emptyMap()

        /** What another device finds in the shared place: this device's file, as text. */
        var file: String = DeviceFiles.write(mine)
            private set

        /** Writes the file, by this device's own clock (which is only ever shown). */
        fun tell(clock: Long = 0L) {
            file = DeviceFiles.write(mine.copy(writtenAt = clock))
        }

        fun read(vararg others: Device): List<DeviceFile> = DeviceFiles.newest(others.mapNotNull { DeviceFiles.read(it.file) }, id)

        /** A round: read the others, merge, and write what this device tells now. */
        fun round(vararg others: Device, deleted: CatchUpRules.Deleted = CatchUpRules.Deleted(), chapters: Map<String, String> = emptyMap()) {
            val round = CatchUpRules.merge(library, mine, read(*others), deleted, chapters)
            library = round.library
            mine = round.mine
            tell()
        }

        fun offers(vararg others: Device) = CatchUpRules.offers(library, read(*others), answered)

        fun answer(bookId: String, vararg others: Device) {
            answered = CatchUpRules.answered(answered, library, read(*others), bookId)
        }

        fun book(id: String) = library.find(id)!!
        fun marks() = library.annotations.associateBy { it.id }

        fun change(transform: (LibraryState) -> LibraryState) {
            library = transform(library)
        }

        /** The reader deletes a bookmark here: it goes from the library, and the next round is told why. */
        fun delete(markId: String, vararg others: Device) {
            change { it.copy(annotations = it.annotations.filterNot { a -> a.id == markId }) }
            round(*others, deleted = CatchUpRules.Deleted(marks = setOf(markId)))
        }
    }

    private fun book(id: String, name: String, size: Long, format: BookFormat = BookFormat.EPUB, sections: Int = 400, progress: Float = 0f, sha256: String? = null) = Book(
        id = id, title = name.substringBeforeLast('.'), author = "", format = format, fileName = "$id.${format.extension}", coverFile = null,
        seedColor = null, sourceName = name, sizeBytes = size, addedAt = 1, sectionCount = sections, sha256 = sha256,
        position = (progress * sections).toInt(), progress = progress, lastOpenedAt = if (progress > 0f) 5 else 0,
        locator = if (progress > 0f && format != BookFormat.PDF) """{"href":"c.xhtml","locations":{"totalProgression":$progress}}""" else null,
    )

    /** The same two files on both devices. Each device gave them ids of its own when they were added. */
    private fun fold(dune: Float = 0f, pdf: Float = 0f) = Device(
        FOLD, "Fold",
        LibraryState(books = listOf(book("f-dune", "Dune.epub", 5_000, progress = dune), book("f-pdf", "Manual.pdf", 9_000, BookFormat.PDF, 400, pdf))),
    )

    private fun tablet(dune: Float = 0f, pdf: Float = 0f) = Device(
        TABLET, "Tablet",
        LibraryState(books = listOf(book("t-pdf", "Manual.pdf", 9_000, BookFormat.PDF, 400, pdf), book("t-dune", "Dune.epub", 5_000, progress = dune))),
    )

    private fun bookmark(id: String, bookId: String, note: String = "", progress: Float = 0.3f) = Annotation(
        id = id, bookId = bookId, kind = AnnotationKind.HIGHLIGHT, locator = """{"href":"c.xhtml"}""", progress = progress,
        chapter = "Chapter 9", text = "Fear is the mind-killer.", note = note, color = HighlightColor.GREEN, createdAt = 7,
    )

    private fun Device.add(annotation: Annotation) = change { it.copy(annotations = it.annotations + annotation) }

    // ---- Places: offered, never taken ----------------------------------------------------------

    @Test
    fun `a place another device read further to is offered, and this device's own is left alone`() {
        val fold = fold(dune = 0.30f)
        val tablet = tablet(dune = 0.53f)
        tablet.round(fold, chapters = mapOf("t-dune" to "Chapter 9"))
        val before = fold.book("f-dune")
        fold.round(tablet)

        // Nothing of the fold's own place moved, though the tablet's is newer and further.
        assertEquals(before, fold.book("f-dune"))
        val offer = fold.offers(tablet).getValue("f-dune")
        assertEquals("Tablet", offer.deviceName)
        assertEquals(TABLET, offer.device)
        assertEquals(0.53f, offer.place.progress, 0f)
        assertEquals(fold.read(tablet).single().books.first { it.id == "t-dune" }.place, offer.place)
        assertEquals(400, offer.place.sections)
        assertEquals("Chapter 9", offer.place.chapter)
        // And the tablet, which is the one ahead, is offered nothing.
        tablet.round(fold)
        assertTrue(tablet.offers(fold).isEmpty())
    }

    @Test
    fun `less than about a page further is the same place, counted on another screen`() {
        // 400 of Readium's positions: a position and a half is 0.375% of the book.
        val fold = fold(dune = 0.5300f)
        val closer = tablet(dune = 0.5330f).also { it.round(fold) }
        assertTrue(fold.offers(closer).isEmpty())
        val further = tablet(dune = 0.5340f).also { it.round(fold) }
        assertEquals(setOf("f-dune"), fold.offers(further).keys)
    }

    @Test
    fun `a page of a PDF is a page, and of a short book never more than a fiftieth of it`() {
        // Page 212 and page 213 of 400: three quarters of a page apart is worth saying.
        val fold = fold(pdf = 211.0f / 400)
        assertTrue(fold.offers(tablet(pdf = 211.7f / 400).also { it.round() }).isEmpty())
        assertEquals(setOf("f-pdf"), fold.offers(tablet(pdf = 211.8f / 400).also { it.round() }).keys)

        // A text of four paragraphs: a paragraph and a half would be 37% of it.
        val short = book("f-txt", "note.txt", 300, BookFormat.TXT, sections = 4, progress = 0.25f)
        val place = SharedPlace(position = 1, sections = 4, progress = 0.28f, rev = 1)
        assertTrue(CatchUpRules.isFurther(place, short))
        assertFalse(CatchUpRules.isFurther(place.copy(progress = 0.26f), short))
    }

    @Test
    fun `a book never opened here is offered the place it has elsewhere`() {
        val fold = fold()
        val tablet = tablet(dune = 0.12f).also { it.round() }
        assertEquals(0.12f, fold.offers(tablet).getValue("f-dune").place.progress, 0f)
        // And a book the other device never opened tells no place at all.
        assertNull(DeviceFiles.read(tablet.file)!!.books.first { it.id == "t-pdf" }.place)
    }

    @Test
    fun `of several devices the furthest is offered`() {
        val fold = fold(dune = 0.10f)
        val tablet = tablet(dune = 0.40f).also { it.round() }
        val phone = Device(PHONE, "Phone", LibraryState(books = listOf(book("p-dune", "Dune.epub", 5_000, progress = 0.60f)))).also { it.round() }
        assertEquals("Phone", fold.offers(tablet, phone).getValue("f-dune").deviceName)
        assertEquals("Phone", fold.offers(phone, tablet).getValue("f-dune").deviceName)
    }

    @Test
    fun `an offer answered isn't made again until that device reads on`() {
        val fold = fold(dune = 0.30f)
        val tablet = tablet(dune = 0.53f).also { it.round() }
        assertEquals(1, fold.offers(tablet).size)
        // Waved away.
        fold.answer("f-dune", tablet)
        assertTrue(fold.offers(tablet).isEmpty())
        // The tablet writes its file again without having gone anywhere (a bookmark was added):
        // the same place, still answered.
        tablet.add(bookmark("m1", "t-dune"))
        tablet.round()
        assertTrue(fold.offers(tablet).isEmpty())
        // Read on there, it is a new place and offered again.
        tablet.change { s -> s.copy(books = s.books.map { if (it.id == "t-dune") it.copy(position = 220, progress = 0.55f) else it }) }
        tablet.round()
        assertEquals(0.55f, fold.offers(tablet).getValue("f-dune").place.progress, 0f)
    }

    @Test
    fun `waving away the furthest doesn't bring up the next furthest`() {
        val fold = fold(dune = 0.10f)
        val tablet = tablet(dune = 0.40f).also { it.round() }
        val phone = Device(PHONE, "Phone", LibraryState(books = listOf(book("p-dune", "Dune.epub", 5_000, progress = 0.60f)))).also { it.round() }
        fold.answer("f-dune", tablet, phone)
        assertTrue(fold.offers(tablet, phone).isEmpty())
    }

    @Test
    fun `once this device has read past it, there is nothing to offer`() {
        val fold = fold(dune = 0.30f)
        val tablet = tablet(dune = 0.53f).also { it.round() }
        fold.change { s -> s.copy(books = s.books.map { if (it.id == "f-dune") it.copy(position = 240, progress = 0.60f) else it }) }
        assertTrue(fold.offers(tablet).isEmpty())
        // The tablet is the one behind now.
        fold.round(tablet)
        assertEquals("Fold", tablet.offers(fold).getValue("t-dune").deviceName)
    }

    @Test
    fun `gone there, neither device is sent to the page it is already on`() {
        val fold = fold(dune = 0.30f)
        val tablet = tablet(dune = 0.53f).also { it.round() }
        fold.round(tablet)
        // The fold goes to the tablet's place, and counts the page it lands on a little differently.
        fold.answer("f-dune", tablet)
        fold.change { s -> s.copy(books = s.books.map { if (it.id == "f-dune") it.copy(position = 212, progress = 0.5312f) else it }) }
        fold.round(tablet)
        tablet.round(fold)
        assertTrue(fold.offers(tablet).isEmpty())
        assertTrue(tablet.offers(fold).isEmpty())
    }

    @Test
    fun `a finished book and a book whose file isn't here are offered nothing`() {
        val tablet = tablet(dune = 0.53f, pdf = 0.5f).also { it.round() }
        val fold = fold(dune = 0.30f)
        fold.change { s -> s.copy(books = s.books.map { if (it.id == "f-dune") it.copy(finishedAt = 9) else it }) }
        val others = fold.read(tablet)
        assertEquals(setOf("f-pdf"), CatchUpRules.offers(fold.library, others).keys)
        assertTrue(CatchUpRules.offers(fold.library, others, unreadable = setOf("f-pdf")).isEmpty())
    }

    @Test
    fun `the same book is known by its file, whatever each device calls its entry`() {
        val here = book("a", "Dune.epub", 5_000, sha256 = "abc")
        // The same name and size.
        assertTrue(CatchUpRules.sameBook(here, SharedBook("z", "Dune", "Dune.epub", 5_000)))
        // Another name, the same bytes ("Dune (1).epub").
        assertTrue(CatchUpRules.sameBook(here, SharedBook("z", "Dune", "Dune (1).epub", 5_000, sha256 = "abc")))
        // The same entry of a backup both were set up from.
        assertTrue(CatchUpRules.sameBook(here, SharedBook("a", "Dune", "", 0)))
        // Another edition, or another book altogether.
        assertFalse(CatchUpRules.sameBook(here, SharedBook("z", "Dune", "Dune.epub", 5_001)))
        assertFalse(CatchUpRules.sameBook(here, SharedBook("z", "Dune", "Dune (1).epub", 5_000, sha256 = "def")))
        assertFalse(CatchUpRules.sameBook(here.copy(sha256 = null), SharedBook("z", "Emma", "Emma.epub", 5_000)))
        assertFalse(CatchUpRules.sameBook(here.copy(sourceName = "", sizeBytes = 0, sha256 = null), SharedBook("z", "", "", 0)))
    }

    @Test
    fun `a place is told as new only when it moved`() {
        val tablet = tablet(dune = 0.53f)
        tablet.round(chapters = mapOf("t-dune" to "Chapter 9"))
        val first = tablet.mine.books.first { it.id == "t-dune" }.place!!
        assertEquals(1L, first.rev)
        // Another round, and a bookmark: the file is written again, the place is the one it was.
        tablet.add(bookmark("m1", "t-dune"))
        tablet.round()
        assertEquals(2L, tablet.mine.rev)
        assertEquals(first, tablet.mine.books.first { it.id == "t-dune" }.place)
        // The same page saved over with another sentence of it hasn't moved either.
        tablet.change { s -> s.copy(books = s.books.map { if (it.id == "t-dune") it.copy(locator = """{"href":"c.xhtml","text":{"highlight":"x"}}""") else it }) }
        tablet.round()
        assertEquals(1L, tablet.mine.books.first { it.id == "t-dune" }.place!!.rev)
        assertEquals("Chapter 9", tablet.mine.books.first { it.id == "t-dune" }.place!!.chapter)
        // Read on with no reader saying which chapter: a new place, and not the old chapter's name.
        tablet.change { s -> s.copy(books = s.books.map { if (it.id == "t-dune") it.copy(position = 260, progress = 0.65f) else it }) }
        tablet.round()
        val moved = tablet.mine.books.first { it.id == "t-dune" }.place!!
        assertEquals(tablet.mine.rev, moved.rev)
        assertEquals("", moved.chapter)
    }

    @Test
    fun `a round with nothing new to tell changes nothing`() {
        val fold = fold(dune = 0.30f)
        val tablet = tablet(dune = 0.53f)
        fold.add(bookmark("m1", "f-dune"))
        repeat(3) {
            fold.round(tablet)
            tablet.round(fold)
        }
        val told = fold.mine to tablet.mine
        val libraries = fold.library to tablet.library
        fold.round(tablet)
        tablet.round(fold)
        // (The same objects: nothing to write, nothing to save.)
        assertTrue(told.first === fold.mine && told.second === tablet.mine)
        assertEquals(libraries, fold.library to tablet.library)
    }

    // ---- Bookmarks, highlights and notes -----------------------------------------------------

    @Test
    fun `a highlight made on one device appears in the same book on the other`() {
        val fold = fold()
        val tablet = tablet()
        tablet.add(bookmark("m1", "t-dune", note = "Litany"))
        tablet.round(fold)
        fold.round(tablet)
        // Under the fold's own id for the book, and otherwise as it was made.
        assertEquals(bookmark("m1", "f-dune", note = "Litany"), fold.marks().getValue("m1"))
        // The tablet hears it back and has nothing to change.
        val before = tablet.library
        tablet.round(fold)
        assertEquals(before, tablet.library)
    }

    @Test
    fun `a note changed on one device is changed on the other, whichever made it`() {
        val fold = fold()
        val tablet = tablet()
        tablet.add(bookmark("m1", "t-dune", note = "first"))
        tablet.round(fold)
        fold.round(tablet)
        // Edited on the fold, which only received it.
        fold.change { s -> s.copy(annotations = s.annotations.map { it.copy(note = "second", color = HighlightColor.PINK) }) }
        fold.round(tablet)
        tablet.round(fold)
        assertEquals("second", tablet.marks().getValue("m1").note)
        assertEquals(HighlightColor.PINK, tablet.marks().getValue("m1").color)
        assertEquals(2, tablet.mine.marks.single().version)
        // And back again.
        tablet.change { s -> s.copy(annotations = s.annotations.map { it.copy(note = "third") }) }
        tablet.round(fold)
        fold.round(tablet)
        assertEquals("third", fold.marks().getValue("m1").note)
    }

    @Test
    fun `a note changed on both devices at once ends up the same on both`() {
        val fold = fold()
        val tablet = tablet()
        tablet.add(bookmark("m1", "t-dune", note = "first"))
        tablet.round(fold)
        fold.round(tablet)
        // Neither has heard of the other's change when it makes its own.
        fold.change { s -> s.copy(annotations = s.annotations.map { it.copy(note = "from the fold") }) }
        tablet.change { s -> s.copy(annotations = s.annotations.map { it.copy(note = "from the tablet") }) }
        fold.round()
        tablet.round()
        fold.round(tablet)
        tablet.round(fold)
        fold.round(tablet)
        assertEquals(fold.marks().getValue("m1").note, tablet.marks().getValue("m1").note)
        // Whichever round came first, it is the same one that is kept.
        val other = fold().also { it.add(bookmark("m1", "f-dune", note = "from the fold")) }
        val again = tablet().also { it.add(bookmark("m1", "t-dune", note = "from the tablet")) }
        again.round()
        other.round()
        again.round(other)
        other.round(again)
        assertEquals(fold.marks().getValue("m1").note, other.marks().getValue("m1").note)
        assertEquals(fold.marks().getValue("m1").note, again.marks().getValue("m1").note)
    }

    @Test
    fun `a deleted highlight is deleted on the other device, and doesn't come back`() {
        val fold = fold()
        val tablet = tablet()
        val phone = Device(PHONE, "Phone", LibraryState(books = listOf(book("p-dune", "Dune.epub", 5_000))))
        tablet.add(bookmark("m1", "t-dune"))
        tablet.add(bookmark("m2", "t-dune", note = "kept"))
        tablet.round(fold, phone)
        fold.round(tablet, phone)
        phone.round(tablet, fold)
        assertEquals(setOf("m1", "m2"), phone.marks().keys)

        // Deleted on the fold. The phone has been off all the while and still has it.
        fold.delete("m1", tablet, phone)
        tablet.round(fold, phone)
        assertEquals(setOf("m2"), tablet.marks().keys)
        // The phone's old file says the highlight is there: it stays deleted on the other two...
        fold.round(tablet, phone)
        tablet.round(fold, phone)
        assertEquals(setOf("m2"), fold.marks().keys)
        assertEquals(setOf("m2"), tablet.marks().keys)
        // ...and goes from the phone when it catches up, even reading only the tablet's file.
        phone.round(tablet)
        assertEquals(setOf("m2"), phone.marks().keys)
        // What is kept of it is its id and that it was deleted.
        val tombstone = DeviceFiles.read(phone.file)!!.marks.first { it.id == "m1" }
        assertTrue(tombstone.deleted)
        assertEquals(2, tombstone.version)
        assertEquals(FOLD, tombstone.by)
    }

    @Test
    fun `only what the reader deleted is deleted elsewhere`() {
        val fold = fold()
        val tablet = tablet()
        tablet.add(bookmark("m1", "t-dune"))
        tablet.add(bookmark("m2", "t-pdf"))
        tablet.round(fold)
        fold.round(tablet)

        // The book is removed from the fold to make room: its highlight goes with it there...
        fold.change { s -> s.copy(books = s.books.filterNot { it.id == "f-dune" }, annotations = s.annotations.filterNot { it.bookId == "f-dune" }) }
        fold.round(tablet)
        assertEquals(setOf("m2"), fold.marks().keys)
        assertEquals(listOf("m2"), fold.mine.marks.map { it.id })
        // ...and stays on the tablet, which still has the book.
        tablet.round(fold)
        assertEquals(setOf("m1", "m2"), tablet.marks().keys)

        // The fold's library goes back to an older copy of itself that hadn't the PDF's
        // highlight yet. Nobody deleted it: it is put back, and never deleted on the tablet.
        fold.change { it.copy(annotations = emptyList()) }
        fold.round(tablet)
        assertEquals(setOf("m2"), fold.marks().keys)
        tablet.round(fold)
        assertEquals(setOf("m1", "m2"), tablet.marks().keys)
    }

    @Test
    fun `highlights of a book that isn't here wait until it is`() {
        val tablet = tablet()
        tablet.add(bookmark("m1", "t-dune"))
        tablet.round()
        val fold = Device(FOLD, "Fold", LibraryState(books = listOf(book("f-pdf", "Manual.pdf", 9_000, BookFormat.PDF))))
        fold.round(tablet)
        assertTrue(fold.library.annotations.isEmpty())
        assertTrue(fold.mine.marks.isEmpty())
        // The book is added on the fold, under whatever id: the highlight is there the next round.
        fold.change { it.copy(books = it.books + book("new-id", "Dune.epub", 5_000)) }
        fold.round(tablet)
        assertEquals("new-id", fold.marks().getValue("m1").bookId)
    }

    @Test
    fun `a highlight deleted and then brought back from a backup is back everywhere`() {
        val fold = fold()
        val tablet = tablet()
        tablet.add(bookmark("m1", "t-dune"))
        tablet.round(fold)
        fold.round(tablet)
        tablet.delete("m1", fold)
        fold.round(tablet)
        assertTrue(fold.library.annotations.isEmpty())
        // Restoring a backup merges its bookmarks in by id: the reader wanted it back.
        tablet.add(bookmark("m1", "t-dune"))
        tablet.round(fold)
        fold.round(tablet)
        assertEquals(setOf("m1"), fold.marks().keys)
        assertEquals(3, fold.mine.marks.single().version)
    }

    @Test
    fun `two devices set up from the same backup don't trade their bookmarks back and forth`() {
        val shared = bookmark("m1", "same-id")
        val one = Device(FOLD, "Fold", LibraryState(books = listOf(book("same-id", "Dune.epub", 5_000)), annotations = listOf(shared)))
        val two = Device(TABLET, "Tablet", LibraryState(books = listOf(book("same-id", "Dune.epub", 5_000)), annotations = listOf(shared)))
        one.round(two)
        two.round(one)
        one.round(two)
        val settled = one.mine.rev to two.mine.rev
        repeat(3) {
            one.round(two)
            two.round(one)
        }
        assertEquals(settled, one.mine.rev to two.mine.rev)
        assertEquals(listOf(shared), one.library.annotations)
        assertEquals(listOf(shared), two.library.annotations)
    }

    // ---- Collections ---------------------------------------------------------------------------

    private fun Device.shelve(bookId: String, vararg collections: String) =
        change { s -> s.copy(books = s.books.map { if (it.id == bookId) it.copy(collections = collections.toSet()) else it }) }

    private fun Device.collect(id: String, name: String) = change { it.copy(collections = it.collections + BookCollection(id, name, 3)) }

    @Test
    fun `a collection and the books in it carry over`() {
        val fold = fold()
        val tablet = tablet()
        tablet.collect("c1", "Book club")
        tablet.shelve("t-dune", "c1")
        tablet.round(fold)
        fold.round(tablet)
        assertEquals(listOf(BookCollection("c1", "Book club", 3)), fold.library.collections)
        assertEquals(setOf("c1"), fold.book("f-dune").collections)
        assertTrue(fold.book("f-pdf").collections.isEmpty())

        // Renamed on the fold, and the PDF put in it there.
        fold.change { s -> s.copy(collections = s.collections.map { it.copy(name = "Club") }) }
        fold.shelve("f-pdf", "c1")
        fold.round(tablet)
        tablet.round(fold)
        assertEquals("Club", tablet.library.collections.single().name)
        assertEquals(setOf("c1"), tablet.book("t-pdf").collections)
        assertEquals(setOf("c1"), tablet.book("t-dune").collections)
    }

    @Test
    fun `a book taken out of a collection is taken out on the other device`() {
        val fold = fold()
        val tablet = tablet()
        tablet.collect("c1", "Book club")
        tablet.collect("c2", "Holiday")
        tablet.shelve("t-dune", "c1", "c2")
        tablet.round(fold)
        fold.round(tablet)
        fold.shelve("f-dune", "c2")
        fold.round(tablet)
        tablet.round(fold)
        assertEquals(setOf("c2"), tablet.book("t-dune").collections)
        // Both collections are still there.
        assertEquals(2, tablet.library.collections.size)
    }

    @Test
    fun `a book put in different collections on both devices at once is in all of them`() {
        val fold = fold()
        val tablet = tablet()
        tablet.collect("c1", "Book club")
        tablet.collect("c2", "Holiday")
        tablet.round(fold)
        fold.round(tablet)
        fold.shelve("f-dune", "c1")
        tablet.shelve("t-dune", "c2")
        fold.round()
        tablet.round()
        fold.round(tablet)
        tablet.round(fold)
        assertEquals(setOf("c1", "c2"), fold.book("f-dune").collections)
        assertEquals(setOf("c1", "c2"), tablet.book("t-dune").collections)
    }

    @Test
    fun `a deleted collection is deleted on the other device, and its books stay`() {
        val fold = fold()
        val tablet = tablet()
        tablet.collect("c1", "Book club")
        tablet.collect("c2", "Holiday")
        tablet.shelve("t-dune", "c1", "c2")
        tablet.round(fold)
        fold.round(tablet)
        // The fold is showing that collection when it goes.
        fold.change { it.copy(settings = it.settings.copy(collection = "c1")) }

        tablet.change { s ->
            s.copy(collections = s.collections.filterNot { it.id == "c1" }, books = s.books.map { it.copy(collections = it.collections - "c1") })
        }
        tablet.round(fold, deleted = CatchUpRules.Deleted(collections = setOf("c1")))
        fold.round(tablet)
        assertEquals(listOf("c2"), fold.library.collections.map { it.id })
        assertEquals(setOf("c2"), fold.book("f-dune").collections)
        assertNull(fold.library.settings.collection)
        assertEquals(2, fold.library.books.size)
        // It doesn't come back from the fold's file, old or new.
        tablet.round(fold)
        fold.round(tablet)
        assertEquals(listOf("c2"), tablet.library.collections.map { it.id })
        assertEquals(listOf("c2"), fold.library.collections.map { it.id })
    }

    @Test
    fun `a collection made on both devices under one name is one collection`() {
        val fold = fold()
        val tablet = tablet()
        fold.collect("c-b", "Poetry")
        fold.shelve("f-dune", "c-b")
        fold.change { it.copy(settings = it.settings.copy(collection = "c-b")) }
        tablet.collect("c-a", "poetry ")
        tablet.shelve("t-pdf", "c-a")
        repeat(2) {
            fold.round(tablet)
            tablet.round(fold)
        }
        // Under the id that comes first, on both; each device's books are in it.
        assertEquals(listOf("c-a"), fold.library.collections.map { it.id })
        assertEquals(listOf("c-a"), tablet.library.collections.map { it.id })
        assertEquals(setOf("c-a"), fold.book("f-dune").collections)
        assertEquals(setOf("c-a"), fold.book("f-pdf").collections)
        assertEquals(setOf("c-a"), tablet.book("t-dune").collections)
        assertEquals(setOf("c-a"), tablet.book("t-pdf").collections)
        // The fold goes on showing it.
        assertEquals("c-a", fold.library.settings.collection)
        // Nothing was deleted to get there: no tombstone, and it stays settled.
        assertTrue(fold.mine.collections.none { it.deleted } && tablet.mine.collections.none { it.deleted })
        val settled = fold.mine.rev to tablet.mine.rev
        fold.round(tablet)
        tablet.round(fold)
        assertEquals(settled, fold.mine.rev to tablet.mine.rev)
    }

    // ---- Clocks, old copies and three devices ------------------------------------------------

    @Test
    fun `a device whose clock is wrong merges the same`() {
        fun play(foldClock: Long, tabletClock: Long): Pair<LibraryState, LibraryState> {
            val fold = fold(dune = 0.30f)
            val tablet = tablet(dune = 0.53f)
            tablet.add(bookmark("m1", "t-dune", note = "first").copy(createdAt = tabletClock))
            tablet.round(fold)
            tablet.tell(tabletClock)
            fold.round(tablet)
            fold.tell(foldClock)
            // The later change is the fold's, whatever its clock says.
            fold.change { s -> s.copy(annotations = s.annotations.map { it.copy(note = "second") }) }
            fold.round(tablet)
            fold.tell(foldClock + 1)
            tablet.round(fold)
            tablet.tell(tabletClock + 1)
            assertEquals(setOf("f-dune"), fold.offers(tablet).keys)
            return fold.library to tablet.library
        }
        val right = play(foldClock = NOW, tabletClock = NOW + 60_000)
        // The fold thinks it is 1970; then it thinks it is some time next century.
        for ((behind, ahead) in listOf(0L to NOW, NOW + 3_000_000_000_000L to NOW)) {
            val (fold, tablet) = play(behind, ahead)
            assertEquals("second", fold.annotations.single().note)
            assertEquals("second", tablet.annotations.single().note)
            assertEquals(right.first.books, fold.books)
            assertEquals(right.second.books, tablet.books)
        }
    }

    @Test
    fun `an old copy of a device's file doesn't take anything back`() {
        val fold = fold(dune = 0.30f)
        val tablet = tablet(dune = 0.53f)
        tablet.add(bookmark("m1", "t-dune", note = "first"))
        tablet.round(fold)
        val old = tablet.file
        fold.round(tablet)
        tablet.change { s -> s.copy(annotations = s.annotations.map { it.copy(note = "second") }) }
        tablet.round(fold)
        fold.round(tablet)
        assertEquals("second", fold.marks().getValue("m1").note)

        // The sync app brings the old file round again (or keeps it beside the new one as a copy).
        val stale = DeviceFiles.read(old)!!
        val round = CatchUpRules.merge(fold.library, fold.mine, listOf(stale))
        assertEquals("second", round.library.annotations.single().note)
        assertTrue(round.mine === fold.mine)
    }

    @Test
    fun `three devices that each read the others end up with the same bookmarks and collections`() {
        val fold = fold()
        val tablet = tablet()
        val phone = Device(PHONE, "Phone", LibraryState(books = listOf(book("p-dune", "Dune.epub", 5_000))))
        fold.add(bookmark("m1", "f-dune", note = "fold"))
        tablet.add(bookmark("m2", "t-dune", note = "tablet"))
        phone.add(bookmark("m3", "p-dune", note = "phone"))
        tablet.collect("c1", "Book club")
        tablet.shelve("t-dune", "c1")
        repeat(3) {
            fold.round(tablet, phone)
            tablet.round(fold, phone)
            phone.round(fold, tablet)
        }
        for (device in listOf(fold, tablet, phone)) {
            assertEquals(setOf("m1", "m2", "m3"), device.marks().keys)
            assertEquals(listOf("Book club"), device.library.collections.map { it.name })
        }
        assertEquals(setOf("c1"), phone.book("p-dune").collections)
        // Each kept the others' notes as they were written.
        assertEquals(listOf("fold", "tablet", "phone"), listOf("m1", "m2", "m3").map { phone.marks().getValue(it).note })
        assertNotEquals(fold.mine.device, phone.mine.device)
    }

    private companion object {
        const val FOLD = "3f0a6f0e-0000-4000-8000-00000000f01d"
        const val TABLET = "9b1d2c3e-0000-4000-8000-0000007ab1e7"
        const val PHONE = "c4d5e6f7-0000-4000-8000-0000000f0e11"
        const val NOW = 1_794_000_000_000L
    }
}
