package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice's place, told the way a page's is. The file here is laid out like Project Gutenberg's
 * "Die Verwandlung", where the bug was caught: a few dozen one-line blocks (the title, the notice
 * at the top), then a story in paragraphs that run to pages, then a licence in short ones.
 */
class SpokenPlaceTest {
    private val line = "Title: Die Verwandlung"
    private val paragraph = "Als Gregor Samsa eines Morgens aus unruhigen Träumen erwachte, fand er sich in seinem Bett zu einem ungeheueren Ungeziefer verwandelt. ".repeat(40)
    private val notice = List(30) { "$line $it" }
    private val story = List(60) { "$it. $paragraph" }
    private val licence = List(60) { "1.E.$it. You may copy it, give it away or re-use it." }
    private val file = notice + story + licence
    private val places = TextPlaces(file)

    /** Where Readium's read-aloud says a block is: its number among the file's blocks. */
    private fun counted(block: Int) = block / file.size.toDouble()

    // ---- How far down the file a sentence is -----------------------------------------------------

    @Test
    fun `a block's place is where its words are, not its number among the blocks`() {
        // The story's first paragraph is a fifth of the blocks down, and still on the first pages.
        assertEquals(0.2, counted(30), 0.001)
        assertTrue(places.of(30) < 0.01)
        // Half way through the story is a third of the blocks down and half way through the pages.
        assertEquals(0.4, counted(60), 0.001)
        assertEquals(0.5, places.of(60), 0.01)
        // The last paragraph of the story: three fifths by the count, all but the end of the file.
        assertEquals(0.6, counted(90), 0.001)
        assertTrue(places.of(90) > 0.98)
    }

    @Test
    fun `the place runs from the top of the file to the bottom, never backwards`() {
        assertEquals(0.0, places.of(0), 0.0)
        for (block in 1 until file.size) assertTrue("block $block", places.of(block) > places.of(block - 1))
        assertTrue(places.of(file.lastIndex) < 1.0)
    }

    @Test
    fun `a sentence is placed where it starts in its block`() {
        val long = "First sentence of a paragraph that runs to pages. " + "Filling. ".repeat(500) + "The sentence the voice stopped on, near the end."
        val one = TextPlaces(listOf(long, "The next paragraph."))
        // By its block alone it would be at the top of the file: pages back from where the voice is.
        assertEquals(0.0, one.of(0), 0.0)
        assertTrue(one.of(0, "The sentence the voice stopped on, near the end.") > 0.95)
        // Voices cut and space sentences their own way: it is known by its opening.
        assertEquals(one.of(0, "The sentence the voice stopped on, near the end."), one.of(0, "The sentence the\nvoice  stopped on, near the end. And more."), 0.0)
        // Words that aren't in the block leave it at the block's start.
        assertEquals(0.0, one.of(0, "Words from some other book."), 0.0)
        assertEquals(one.of(1), one.of(1, null), 0.0)
    }

    @Test
    fun `a file without words, or a block it doesn't have, is the top of the file`() {
        assertEquals(0.0, TextPlaces(emptyList()).of(0), 0.0)
        assertEquals(0.0, TextPlaces(listOf("", " \n")).of(1, "anything"), 0.0)
        assertEquals(0.0, places.of(-1), 0.0)
        assertEquals(0.0, places.of(file.size, "anything"), 0.0)
    }

    // ---- Which block a sentence is in ------------------------------------------------------------

    @Test
    fun `a sentence straight from the voice is in the block read-aloud names`() {
        assertEquals(31, places.blockOf("Als Gregor Samsa eines Morgens aus unruhigen Träumen erwachte", exact = 31, named = emptyList(), around = counted(31)))
        // One with no words to check is taken at its name.
        assertEquals(31, places.blockOf(null, exact = 31, named = emptyList(), around = null))
    }

    @Test
    fun `a saved sentence is found again by its words`() {
        // Saved with its place in the file, it no longer carries read-aloud's number for its block.
        // The same words open every paragraph of the story: the one nearest where it was.
        val all = story.indices.map { it + notice.size }
        assertEquals(45, places.blockOf("15. Als Gregor Samsa eines Morgens", exact = null, named = all, around = places.of(45)))
        assertEquals(75, places.blockOf("Als Gregor Samsa eines Morgens aus unruhigen", exact = null, named = all, around = places.of(75)))
        // Found again, it comes out where it was: placing a sentence twice doesn't move it.
        assertEquals(places.of(75, "Als Gregor Samsa eines Morgens aus unruhigen"), places.of(places.blockOf("Als Gregor Samsa eines Morgens aus unruhigen", null, all, places.of(75))!!, "Als Gregor Samsa eines Morgens aus unruhigen"), 0.0)
    }

    @Test
    fun `without a name to go by it is the one block that holds its words`() {
        assertEquals(3, places.blockOf("$line 3", exact = null, named = emptyList(), around = null))
        // Held by many blocks, or by none: not known.
        assertNull(places.blockOf("Als Gregor Samsa eines Morgens aus unruhigen", exact = null, named = emptyList(), around = 0.5))
        assertNull(places.blockOf("Words from some other book entirely.", exact = null, named = listOf(3, 4), around = 0.5))
        assertNull(places.blockOf(null, exact = null, named = listOf(3), around = 0.5))
        // A name that is out of date (another edition of the file) falls back on the words.
        assertEquals(3, places.blockOf("$line 3", exact = 200, named = emptyList(), around = null))
        assertEquals(3, places.blockOf("$line 3", exact = 40, named = emptyList(), around = null))
    }

    // ---- The page a sentence is on ---------------------------------------------------------------

    @Test
    fun `a page holds the words it starts at or a little before`() {
        // A sentence 0.3084 of the way down a file of 115 pages is on the page that starts at 0.3043.
        assertTrue(Pages.holdsWords(reported = 35.0 / 115, kept = 0.3084, pages = 115))
        // A page's first words, saved folded and found unfolded in the middle of a spread.
        assertTrue(Pages.holdsWords(reported = 0.4875, kept = 0.5, pages = 40))
        // In a chapter of four pages a sentence at the foot of the second is a fifth of the file
        // from where that page starts, and still on it.
        assertTrue(Pages.holdsWords(reported = 0.25, kept = 0.49, pages = 4))
        // Scrolling there are no pages to count: near is near.
        assertTrue(Pages.holdsWords(reported = 0.30, kept = 0.33, pages = null))
    }

    @Test
    fun `a page far from the words is some other page`() {
        // What 1.0.0 took for the sentence's place: its block's number among the blocks.
        // The page 0.1544 of the way down the file holds it; the one at 0.3167 is chapters on.
        assertTrue(Pages.holdsWords(reported = 0.1540, kept = 0.1544, pages = 62))
        assertFalse(Pages.holdsWords(reported = 0.3167, kept = 0.1544, pages = 62))
        assertFalse(Pages.holdsWords(reported = 0.1540, kept = 0.3167, pages = 62))
        // The page the phone was shut on, long before the voice got to the sentence.
        assertFalse(Pages.holdsWords(reported = 0.20, kept = 0.42, pages = 300))
        assertFalse(Pages.holdsWords(reported = 0.30, kept = 0.40, pages = null))
    }

    // ---- Words a page can find ---------------------------------------------------------------------

    @Test
    fun `a sentence of a length the page's search loses is asked for a letter short`() {
        // 192 characters: Readium's page finds where this sentence ends and nothing of it, so it
        // couldn't say whether the sentence was on the page, and Listen began pages back.
        val lost = "Der Prokurist war ihm für einen Augenblick ganz aus dem Sinn gekommen; dagegen konnte er sich nicht versagen, " +
            "im Anblick des fließenden Kaffees mehrmals mit den Kiefern ins Leere zu schnappen."
        assertEquals(192, lost.length)
        assertEquals(191, findableLength(lost))
        for (length in listOf(64, 96, 128, 160, 224, 256, 320)) assertEquals(length - 1, findableLength("x".repeat(length)))
    }

    @Test
    fun `any other sentence is asked for whole`() {
        for (length in listOf(0, 1, 31, 32, 33, 63, 65, 127, 129, 190, 191, 193)) assertEquals(length, findableLength("x".repeat(length)))
        // One asked for a letter short is not shortened again.
        assertEquals(191, findableLength("x".repeat(findableLength("x".repeat(192)))))
    }

    // ---- How far through the book the voice is ---------------------------------------------------

    @Test
    fun `while its page is in view the voice counts as the page does`() {
        // The page starts 0.4200 of the way down; the sentence on it is a little further on. Reading
        // this page counted 0.4200, and so does listening on it: pressing Listen moves no number.
        assertEquals(0.42, spokenWithin(told = 0.4236, page = 0.42, lead = null, pages = 100), 0.0)
        assertEquals(0.42, spokenWithin(told = 0.4236, page = 0.42, lead = -0.01, pages = null), 0.0)
    }

    @Test
    fun `out of sight the count carries on from where the page left it, a page at a time`() {
        // The voice came onto page 42 of 100 on a sentence whose own place was 0.4236: pictures
        // earlier in the file take room that words don't.
        val lead = 0.42 - 0.4236
        // The screen goes dark and the voice reads on down the same page: the number holds still,
        // as it would with the page in view (not a step back or forth with every sentence).
        assertEquals(0.42, spokenWithin(told = 0.4236, page = null, lead = lead, pages = 100), 1e-9)
        assertEquals(0.42, spokenWithin(told = 0.4290, page = null, lead = lead, pages = 100), 1e-9)
        assertEquals(0.42, spokenWithin(told = 0.4330, page = null, lead = lead, pages = 100), 1e-9)
        // Onto what would be the next page, and thirty pages on.
        assertEquals(0.43, spokenWithin(told = 0.4340, page = null, lead = lead, pages = 100), 1e-9)
        assertEquals(0.72, spokenWithin(told = 0.7290, page = null, lead = lead, pages = 100), 1e-9)
        // Unfolded meanwhile, the same file is 60 spreads: counted from the spread that would hold it.
        assertEquals(25.0 / 60, spokenWithin(told = 0.4236, page = null, lead = lead, pages = 60), 1e-9)
    }

    @Test
    fun `with no page to go by it is the sentence's own place`() {
        // A file the voice was never seen in (it read on into the next chapter in a pocket).
        assertEquals(0.07, spokenWithin(told = 0.07, page = null, lead = null, pages = null), 0.0)
        // Scrolling, there are no pages to count from: carried on from where it was last seen.
        assertEquals(0.4504, spokenWithin(told = 0.4540, page = null, lead = 0.42 - 0.4236, pages = null), 1e-9)
        // Never off either end of the file.
        assertEquals(1.0, spokenWithin(told = 0.99, page = null, lead = 0.03, pages = null), 0.0)
        assertEquals(0.0, spokenWithin(told = 0.01, page = null, lead = -0.03, pages = null), 0.0)
        assertEquals(0.99, spokenWithin(told = 0.999, page = null, lead = 0.03, pages = 100), 1e-9)
    }

    @Test
    fun `a page says of a sentence on it only that it comes after the page's start`() {
        // First seen half way down the page that starts at 0.0625: the lead is at least this.
        assertEquals(0.0181, pageLead(page = 0.0625, told = 0.0444, known = null, moved = false), 1e-9)
        // A sentence further down the same page says less: the lead stays.
        assertEquals(0.0181, pageLead(page = 0.0625, told = 0.0462, known = 0.0181, moved = false), 1e-9)
        // One tapped higher up the same page says more.
        assertEquals(0.0205, pageLead(page = 0.0625, told = 0.0420, known = 0.0181, moved = false), 1e-9)
        // Folded, the page holding the same sentence starts earlier (0.0556 of 72 pages): no reason to think the sentence did.
        assertEquals(0.0181, pageLead(page = 4.0 / 72, told = 0.0450, known = 0.0181, moved = false), 1e-9)
        // The voice turns the page: the sentence it turned it for is at the top of the new one.
        assertEquals(0.0170, pageLead(page = 0.0833, told = 0.0663, known = 0.0181, moved = true), 1e-9)
    }

    @Test
    fun `folding and unfolding in the middle of a page doesn't send the count back a page`() {
        // Caught on the emulator: listening on the spread that starts at 0.0625 (of 48), the phone
        // was folded (the page there starts at 0.0556 of 72), unfolded, and its screen turned off.
        var lead = pageLead(page = 3.0 / 48, told = 0.0444, known = null, moved = false)
        lead = pageLead(page = 4.0 / 72, told = 0.0450, known = lead, moved = false)
        // Out of sight on the unfolded pages, the sentence is still counted from the spread it is on.
        assertEquals(3.0 / 48, spokenWithin(told = 0.0462, page = null, lead = lead, pages = 48), 1e-9)
        // Going by the folded page alone it was counted from the spread before.
        assertEquals(2.0 / 48, spokenWithin(told = 0.0462, page = null, lead = 4.0 / 72 - 0.0450, pages = 48), 1e-9)
    }

    // ---- The place kept through a fold -----------------------------------------------------------

    @Test
    fun `the page is the place to keep while it is in front of the reader`() {
        assertEquals("page 20", placeKept(spoken = null, unseen = false) { "page 20" })
        // The voice is reading and the page has followed it: the page shows the sentence.
        assertEquals("page 42", placeKept(spoken = "sentence on page 42", unseen = false) { "page 42" })
        assertNull(placeKept<String>(spoken = null, unseen = false) { null })
    }

    @Test
    fun `once the voice has read on out of sight the place to keep is its sentence`() {
        // The phone was shut on page 20 and the voice read on to page 42 in a pocket. Opened again,
        // the pages are put back at the sentence, not at the page the phone was shut on.
        assertEquals("sentence on page 42", placeKept(spoken = "sentence on page 42", unseen = true) { "page 20" })
        // Nothing heard out of sight: the page.
        assertEquals("page 20", placeKept(spoken = null, unseen = true) { "page 20" })
    }

    // ---- The end of the book --------------------------------------------------------------------

    @Test
    fun `on the last page the voice counts all of the book, as the page does`() {
        // Caught on the emulator, in a book of three one-page chapters: the last page read by eye
        // is 100%, and with the voice on it the book stayed at 67%, where that page starts.
        assertTrue(spokenIsAllOfTheBook(ended = false, onPage = true, lastPage = true))
        // Any other page in front of the listener is counted from where it starts.
        assertFalse(spokenIsAllOfTheBook(ended = false, onPage = true, lastPage = false))
    }

    @Test
    fun `the last page says nothing of a voice that isn't on it`() {
        // The listener has gone to look at the last page while the voice reads chapter one, or the
        // phone was shut on the last page and the voice started again from somewhere else.
        assertFalse(spokenIsAllOfTheBook(ended = false, onPage = false, lastPage = true))
        assertFalse(spokenIsAllOfTheBook(ended = false, onPage = false, lastPage = false))
    }

    @Test
    fun `a voice that has read the book to its end has read all of it, seen or not`() {
        // In a pocket there is no page to ask.
        assertTrue(spokenIsAllOfTheBook(ended = true, onPage = false, lastPage = false))
        // Nor does it matter where the last words fall: a picture may have the last page to itself.
        assertTrue(spokenIsAllOfTheBook(ended = true, onPage = true, lastPage = false))
        assertTrue(spokenIsAllOfTheBook(ended = true, onPage = true, lastPage = true))
    }
}
