package io.github.friendlyglobal.jacket.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file a device keeps for the others (see [DeviceFile]), as a sync app may hand it over:
 * whole, cut short, twice under two names, or as it was a week ago.
 */
class DeviceFileTest {
    private val highlight = Annotation(
        id = "m1", bookId = "t-dune", kind = AnnotationKind.HIGHLIGHT, locator = """{"href":"c.xhtml","locations":{"progression":0.25}}""",
        section = 3, progress = 0.3f, chapter = "Chapter 9", text = "Fear is the mind-killer.", note = "Litany \"against\" fear\n", color = HighlightColor.BLUE,
        createdAt = 1_794_000_000_000L,
    )

    private val tablet = DeviceFile(
        device = TABLET, name = "Tablet", rev = 12, writtenAt = 1_794_000_000_000L,
        books = listOf(
            SharedBook(
                id = "t-dune", title = "Dune", file = "Dune.epub", size = 5_000, sha256 = "abc",
                place = SharedPlace("""{"href":"c.xhtml"}""", position = 212, offset = 0.4f, sections = 400, progress = 0.53f, chapter = "Chapter 9", rev = 11),
                collections = setOf("c1", "c2"), shelved = 3,
            ),
            SharedBook(id = "t-pdf", title = "Manual", file = "Manual.pdf", size = 9_000),
        ),
        marks = listOf(SharedMark("m1", 2, FOLD, highlight), SharedMark("m0", 4, TABLET, null)),
        collections = listOf(SharedCollection("c1", 1, TABLET, BookCollection("c1", "Book club", 3)), SharedCollection("c9", 2, FOLD, null)),
    )

    @Test
    fun `a file is read back as it was written`() {
        assertEquals(tablet, DeviceFiles.read(DeviceFiles.write(tablet)))
    }

    @Test
    fun `a file cut short is not read at all`() {
        val whole = DeviceFiles.write(tablet)
        // However much of it the sync app has brought so far.
        for (length in listOf(0, 1, 40, whole.length / 3, whole.length / 2, whole.length - 20, whole.length - 1)) {
            assertNull("the first $length characters", DeviceFiles.read(whole.take(length)))
        }
        assertNotNull(DeviceFiles.read(whole))
    }

    @Test
    fun `what isn't a device's file is not read`() {
        assertNull(DeviceFiles.read(""))
        assertNull(DeviceFiles.read("   \n"))
        assertNull(DeviceFiles.read("not json at all"))
        assertNull(DeviceFiles.read("[1, 2, 3]"))
        assertNull(DeviceFiles.read("{}"))
        // Another JSON file in the same folder, and Jacket's own backup.
        assertNull(DeviceFiles.read("""{"kind":"something-else","device":"x"}"""))
        assertNull(DeviceFiles.read(Backup.write(LibraryState())))
        // One with no device to its name.
        assertNull(DeviceFiles.read("""{"kind":"jacket-catch-up","device":""}"""))
    }

    @Test
    fun `a file written over a longer one and padded with spaces reads the same`() {
        // What WholeFile leaves when the app holding the file won't cut it short.
        assertEquals(tablet, DeviceFiles.read(DeviceFiles.write(tablet) + " ".repeat(4_000)))
    }

    @Test
    fun `an entry that can't be read is left out, and the rest is used`() {
        val root = JSONObject(DeviceFiles.write(tablet))
        root.getJSONArray("marks").put(JSONObject().put("id", "m7").put("kind", "SCRIBBLE").put("bookId", "t-dune")).put("nonsense").put(JSONObject())
        root.getJSONArray("books").put(JSONObject().put("title", "No id")).put(7)
        root.getJSONArray("collections").put(JSONObject().put("name", "No id"))
        // And something a later Jacket might add.
        root.put("version", 2).put("shelves", JSONObject().put("a", 1))
        assertEquals(tablet, DeviceFiles.read(root.toString()))
    }

    @Test
    fun `a place that can't be right is kept within bounds`() {
        val root = JSONObject(DeviceFiles.write(tablet))
        root.getJSONArray("books").getJSONObject(0).getJSONObject("place").put("progress", 7.5).put("offset", -2).put("position", -4).put("sections", -1)
        val place = DeviceFiles.read(root.toString())!!.books[0].place!!
        assertEquals(1f, place.progress, 0f)
        assertEquals(0f, place.offset, 0f)
        assertEquals(0, place.position)
        assertEquals(0, place.sections)
    }

    @Test
    fun `of two copies of a device's file the one it wrote last is used`() {
        // "Jacket – Tablet.json" and the "Jacket – Tablet (1).json" a sync app made of it when
        // it couldn't choose: the same device, and its own count says which is the newer.
        val newer = tablet.copy(rev = 13, writtenAt = 5)
        val older = tablet.copy(rev = 12, writtenAt = 9_999_999_999_999L, name = "Tablet (old)")
        val fold = DeviceFile(FOLD, "Fold", rev = 40)
        for (found in listOf(listOf(older, newer, fold), listOf(fold, newer, older))) {
            assertEquals(listOf(newer), DeviceFiles.newest(found, me = FOLD))
        }
        // This device's own file, and copies of it, are never read as another's.
        assertTrue(DeviceFiles.newest(listOf(fold, fold.copy(rev = 41)), me = FOLD).isEmpty())
    }

    @Test
    fun `a copy older than one already seen is the past, and isn't gone back to`() {
        val seen = mapOf(TABLET to 12L)
        assertTrue(DeviceFiles.newest(listOf(tablet.copy(rev = 11)), me = FOLD, seen = seen).isEmpty())
        // The one seen, read again, and anything newer are.
        assertEquals(listOf(tablet), DeviceFiles.newest(listOf(tablet), me = FOLD, seen = seen))
        assertEquals(13L, DeviceFiles.newest(listOf(tablet.copy(rev = 13)), me = FOLD, seen = seen).single().rev)
        // A device never seen before is taken as it comes.
        assertEquals(listOf(tablet.copy(rev = 0)), DeviceFiles.newest(listOf(tablet.copy(rev = 0)), me = FOLD))
    }

    @Test
    fun `any JSON file in the folder may be a device's, whatever a sync app called its copy`() {
        assertTrue(DeviceFiles.mayBeOne("Jacket \u2013 Tablet.json"))
        assertTrue(DeviceFiles.mayBeOne("Jacket \u2013 Tablet (1).json"))
        assertTrue(DeviceFiles.mayBeOne("Jacket \u2013 Tablet.sync-conflict-20261112-101500-ABCDEFG.JSON"))
        assertFalse(DeviceFiles.mayBeOne("Dune.epub"))
        assertFalse(DeviceFiles.mayBeOne("Jacket \u2013 Tablet.json.tmp"))
    }

    @Test
    fun `a device's file is named after it, without what a file's name can't hold`() {
        assertEquals("Jacket \u2013 Tablet.json", DeviceFiles.nameFor("Tablet"))
        assertEquals("Jacket \u2013 Ana's Pixel 9 Pro Fold.json", DeviceFiles.nameFor("  Ana's  Pixel 9 Pro Fold "))
        assertEquals("Jacket \u2013 A B C.json", DeviceFiles.nameFor("A/B\\C:*?\"<>|"))
        assertEquals("Jacket \u2013 device.json", DeviceFiles.nameFor(" / "))
        assertEquals(60, DeviceFiles.tidyName("x".repeat(200)).length)
    }

    @Test
    fun `when it was written says nothing of what it says`() {
        assertTrue(tablet.saysTheSameAs(tablet.copy(rev = 99, writtenAt = 0)))
        assertFalse(tablet.saysTheSameAs(tablet.copy(name = "Slate")))
        assertFalse(tablet.saysTheSameAs(tablet.copy(marks = tablet.marks.dropLast(1))))
    }

    private companion object {
        const val FOLD = "3f0a6f0e-0000-4000-8000-00000000f01d"
        const val TABLET = "9b1d2c3e-0000-4000-8000-0000007ab1e7"
    }
}
