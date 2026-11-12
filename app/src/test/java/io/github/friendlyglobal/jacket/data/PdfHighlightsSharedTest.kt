package io.github.friendlyglobal.jacket.data

import io.github.friendlyglobal.jacket.reader.PdfAnchor
import io.github.friendlyglobal.jacket.reader.PdfPageWords
import io.github.friendlyglobal.jacket.reader.PdfSentence
import io.github.friendlyglobal.jacket.reader.pdfPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PDF's highlights and notes everywhere a highlight goes: in a backup, and between the
 * reader's devices (see [Backup] and [CatchUpRules]). Each keeps its place as a page, its words
 * and a little of what stands around them (see [PdfAnchor]), and must arrive with all of it:
 * the other device finds it again by those, on its own reading of the page.
 */
class PdfHighlightsSharedTest {
    private val page = "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness."

    private fun pdf(id: String, opened: Long = 0) = Book(
        id = id, title = "A Tale", author = "", format = BookFormat.PDF, fileName = "$id.pdf", coverFile = null, seedColor = null,
        sourceName = "tale.pdf", sizeBytes = 9_000, addedAt = 1, sectionCount = 40, lastOpenedAt = opened,
    )

    /** A highlight over "the worst of times", on page 12 (counted from 0) of the book [bookId]. */
    private fun highlight(bookId: String, note: String = "") = Annotation(
        id = "h1", bookId = bookId, kind = AnnotationKind.HIGHLIGHT,
        locator = PdfAnchor(12, before = "the best of times, it was", after = ", it was the age of wisdom").write(),
        section = 12, progress = 0.31f, chapter = "Book the First", text = "the worst of times", note = note, color = HighlightColor.GREEN, createdAt = 7,
    )

    /** Where a device finds [mark] on its own reading of the page: here, the page as one paragraph numbered [paragraph]. */
    private fun foundBy(mark: Annotation, paragraph: Int): List<PdfSentence> =
        PdfPageWords(listOf(paragraph to page)).find(mark.text, PdfAnchor.read(mark.locator)!!)

    private val where = page.indexOf("the worst of times")

    @Test
    fun `a backup keeps a PDF highlight with its place, its note and its colour`() {
        val state = LibraryState(books = listOf(pdf("p1")), annotations = listOf(highlight("p1", note = "Dickens")))
        val back = Backup.read(Backup.write(state))
        assertEquals(state.annotations, back.annotations)
        assertEquals(12, back.annotations.single().pdfPage)
        assertEquals(listOf(PdfSentence(0, where, where + 18)), foundBy(back.annotations.single(), 0))
    }

    @Test
    fun `restored on a phone that has the same file under another id, it is that book's, and is found on its page`() {
        val backup = Backup.read(Backup.write(LibraryState(books = listOf(pdf("old", opened = 9)), annotations = listOf(highlight("old", note = "Dickens")))))
        val here = LibraryState(books = listOf(pdf("new")))
        val merged = Backup.merge(here, backup)
        val mark = merged.annotations.single()
        assertEquals("new", mark.bookId)
        assertEquals(highlight("new", note = "Dickens"), mark)
        // This phone reads the page as its paragraph 31, whatever the other called it.
        assertEquals(listOf(PdfSentence(31, where, where + 18)), foundBy(mark, 31))
        // Restoring the same backup again changes nothing.
        assertEquals(merged.annotations, Backup.merge(merged, backup).annotations)
    }

    @Test
    fun `a highlight changed here since the backup keeps this phone's change`() {
        val backup = LibraryState(books = listOf(pdf("p1")), annotations = listOf(highlight("p1", note = "then")))
        val here = LibraryState(books = listOf(pdf("p1")), annotations = listOf(highlight("p1", note = "now").copy(color = HighlightColor.PINK)))
        assertEquals(here.annotations, Backup.merge(here, backup).annotations)
    }

    // ---- Between devices -------------------------------------------------------------------------

    /** A device as far as catching up goes: its library, what it last told the others, and the file they read. */
    private class Device(val id: String, var library: LibraryState) {
        var mine = DeviceFile(id, id)
        var file: String = DeviceFiles.write(mine)

        fun round(vararg others: Device, deleted: Set<String> = emptySet()) {
            val read = DeviceFiles.newest(others.mapNotNull { DeviceFiles.read(it.file) }, id)
            val round = CatchUpRules.merge(library, mine, read, CatchUpRules.Deleted(marks = deleted))
            library = round.library
            mine = round.mine
            file = DeviceFiles.write(mine)
        }
    }

    @Test
    fun `the file a device keeps for the others says a PDF highlight whole`() {
        val fold = Device("fold", LibraryState(books = listOf(pdf("f-pdf")), annotations = listOf(highlight("f-pdf", note = "Dickens"))))
        fold.round()
        val told = DeviceFiles.read(fold.file)!!.marks.single()
        assertEquals(highlight("f-pdf", note = "Dickens"), told.annotation)
        assertEquals(1, told.version)
    }

    @Test
    fun `a highlight made on one device comes to the other, on its own copy of the PDF`() {
        val fold = Device("fold", LibraryState(books = listOf(pdf("f-pdf")), annotations = listOf(highlight("f-pdf"))))
        val tablet = Device("tablet", LibraryState(books = listOf(pdf("t-pdf"))))
        fold.round(tablet)
        tablet.round(fold)
        val mark = tablet.library.annotations.single()
        assertEquals(highlight("t-pdf"), mark)
        // The tablet reads the page its own way (a later Jacket, another Android): it finds the words all the same.
        assertEquals(listOf(PdfSentence(4, where, where + 18)), foundBy(mark, 4))
        // And hearing of it again changes nothing on either.
        fold.round(tablet)
        tablet.round(fold)
        assertEquals(listOf(highlight("f-pdf")), fold.library.annotations)
        assertEquals(listOf(highlight("t-pdf")), tablet.library.annotations)
    }

    @Test
    fun `a note written on the other device comes back, and the place stays the same`() {
        val fold = Device("fold", LibraryState(books = listOf(pdf("f-pdf")), annotations = listOf(highlight("f-pdf"))))
        val tablet = Device("tablet", LibraryState(books = listOf(pdf("t-pdf"))))
        fold.round(tablet)
        tablet.round(fold)
        tablet.library = tablet.library.copy(annotations = tablet.library.annotations.map { it.copy(note = "From the tablet", color = HighlightColor.BLUE) })
        tablet.round(fold)
        fold.round(tablet)
        val mark = fold.library.annotations.single()
        assertEquals(highlight("f-pdf", note = "From the tablet").copy(color = HighlightColor.BLUE), mark)
        assertEquals(PdfAnchor.read(highlight("x").locator), PdfAnchor.read(mark.locator))
    }

    @Test
    fun `deleted on one device, it is deleted on the other, and does not come back`() {
        val fold = Device("fold", LibraryState(books = listOf(pdf("f-pdf")), annotations = listOf(highlight("f-pdf"))))
        val tablet = Device("tablet", LibraryState(books = listOf(pdf("t-pdf"))))
        fold.round(tablet)
        tablet.round(fold)
        assertEquals(1, tablet.library.annotations.size)
        // The reader deletes it on the tablet: what the tablet tells is that it was deleted.
        tablet.library = tablet.library.copy(annotations = emptyList())
        tablet.round(fold, deleted = setOf("h1"))
        val told = DeviceFiles.read(tablet.file)!!.marks.single()
        assertTrue(told.deleted)
        assertNull(told.annotation)
        fold.round(tablet)
        assertTrue(fold.library.annotations.isEmpty())
        // The fold still says it had it once; the tablet, hearing that again, doesn't take it back.
        tablet.round(fold)
        fold.round(tablet)
        assertTrue(tablet.library.annotations.isEmpty())
        assertTrue(fold.library.annotations.isEmpty())
        assertFalse(DeviceFiles.read(fold.file)!!.marks.any { !it.deleted })
    }

    @Test
    fun `a device that hasn't the PDF takes none of its highlights, and they wait for it`() {
        val fold = Device("fold", LibraryState(books = listOf(pdf("f-pdf")), annotations = listOf(highlight("f-pdf"))))
        val phone = Device("phone", LibraryState())
        fold.round(phone)
        phone.round(fold)
        assertTrue(phone.library.annotations.isEmpty())
        // The PDF is added on the phone: its highlight arrives with the next round.
        phone.library = phone.library.copy(books = listOf(pdf("ph-pdf")))
        phone.round(fold)
        assertEquals(listOf(highlight("ph-pdf")), phone.library.annotations)
    }
}
