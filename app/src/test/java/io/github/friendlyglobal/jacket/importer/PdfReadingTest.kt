package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A PDF's lines made into the paragraphs a voice reads (see PdfText.kt). */
class PdfReadingTest {
    private val first = "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do."
    private val second = "So she was considering in her own mind whether the pleasure of making a daisy chain would be worth the trouble."
    private val third = "There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit speak."
    private val fourth = "In another moment down went Alice after it, never once considering how in the world she was to get out again."

    /** How each page of a test book begins: no two alike, as in a book. */
    private val openers = listOf("Down she went.", "Then came the hall.", "A little door stood there.", "Beyond it lay a garden.", "She could not get through.", "So she sat and cried.")

    private fun texts(vararg pages: PdfPageText): List<String> = PdfReading.paragraphs(pages.toList()).map { it.text }

    // ---- Paragraphs ---------------------------------------------------------------------------

    @Test
    fun linesOfAParagraphAreJoinedAndParagraphsKeptApart() {
        val page = page { column(listOf(first, second, third), left = 40f, right = 380f, top = 60f) }
        assertEquals(listOf(first, second, third), texts(page))
    }

    @Test
    fun paragraphsSetApartByWhiteAloneAreKeptApart() {
        // No first line set in: only more room between paragraphs than between lines.
        val page = page { column(listOf(first, second), left = 40f, right = 380f, top = 60f, indent = 0f, gap = 10f) }
        assertEquals(listOf(first, second), texts(page))
    }

    @Test
    fun aHeadingIsAParagraphOfItsOwn() {
        val page = page {
            line("Chapter One", 40f, 60f, size = 16f)
            column(listOf(first), left = 40f, right = 380f, top = 100f)
        }
        assertEquals(listOf("Chapter One", first), texts(page))
    }

    @Test
    fun aPageWithNoTextGivesNothing() {
        val scan = PdfPageText(PAGE_WIDTH, PAGE_HEIGHT, emptyList())
        assertEquals(emptyList<String>(), texts(scan, scan))
        // And a scan among pages of text is passed over.
        val page = page { column(listOf(first), left = 40f, right = 380f, top = 60f) }
        assertEquals(listOf(first), texts(scan, page, scan))
    }

    // ---- Words broken at the end of a line ---------------------------------------------------------

    @Test
    fun aWordBrokenAtTheEndOfALineIsOneWord() {
        val page = page {
            line("She had never before seen a rabbit with a waist-", 40f, 60f)
            line("coat pocket, and burning with curi-", 40f, 74f)
            line("osity she ran across the field.", 40f, 88f)
        }
        assertEquals(listOf("She had never before seen a rabbit with a waistcoat pocket, and burning with curiosity she ran across the field."), texts(page))
    }

    @Test
    fun aHyphenBeforeACapitalOrANumberIsTheWordsOwn() {
        val page = page {
            line("They met Jean-", 40f, 60f)
            line("Paul in the winter of 1914-", 40f, 74f)
            line("18, and he spoke of a well-", 40f, 88f)
            line("known and much loved song.", 40f, 102f)
        }
        // (A compound broken at its own hyphen can't be told from a broken word: it loses the hyphen.)
        assertEquals(listOf("They met Jean-Paul in the winter of 1914-18, and he spoke of a wellknown and much loved song."), texts(page))
    }

    @Test
    fun aDashAtTheEndOfALineIsNotAHyphen() {
        val page = page {
            line("He stopped - and then, quite suddenly -", 40f, 60f)
            line("he went on as if nothing had happened.", 40f, 74f)
        }
        assertEquals(listOf("He stopped - and then, quite suddenly - he went on as if nothing had happened."), texts(page))
    }

    @Test
    fun aSoftHyphenAlwaysJoins() {
        val page = page {
            line("An extra­ordinary thing hap­", 40f, 60f)
            line("pened that morning in the garden.", 40f, 74f)
        }
        assertEquals("An extra­ordinary thing happened that morning in the garden.", texts(page).single())
    }

    @Test
    fun linesOfChineseAreJoinedWithoutASpace() {
        val page = page {
            line("我在年青时候也曾经做过许多梦", 40f, 60f)
            line("后来大半忘却了但自己也并不以为可惜。", 40f, 74f)
        }
        assertEquals(listOf("我在年青时候也曾经做过许多梦后来大半忘却了但自己也并不以为可惜。"), texts(page))
    }

    @Test
    fun spacesAreSingleAndWhatCannotBeShownIsLeftOut() {
        val page = page { line("  Two   spaces,\ta tab and a\u0002 mark. ", 40f, 60f) }
        assertEquals(listOf("Two spaces, a tab and a mark."), texts(page))
    }

    // ---- Running titles and page numbers -------------------------------------------------------

    private fun bookPage(number: Int, paragraphs: List<String>, title: String = "ALICE'S ADVENTURES IN WONDERLAND", carriedOn: Boolean = false) = page {
        centred(title, 30f, size = 8f)
        column(paragraphs, left = 40f, right = 380f, top = 60f, carriedOn = carriedOn)
        centred(number.toString(), 560f, size = 8f)
    }

    @Test
    fun aRunningTitleAndThePageNumbersAreLeftOut() {
        val pages = arrayOf(bookPage(1, listOf(first)), bookPage(2, listOf(second)), bookPage(3, listOf(third)))
        assertEquals(listOf(first, second, third), texts(*pages))
    }

    @Test
    fun titlesThatTakeTurnsOnLeftAndRightPagesAreLeftOut() {
        val pages = (1..6).map { n -> bookPage(n, listOf("${openers[n - 1]} $first"), title = if (n % 2 == 0) "LEWIS CARROLL" else "DOWN THE RABBIT-HOLE") }
        assertEquals((1..6).map { "${openers[it - 1]} $first" }, PdfReading.paragraphs(pages).map { it.text })
    }

    @Test
    fun aFooterThatCountsIsLeftOut() {
        val pages = (1..3).map { n ->
            page {
                column(listOf("${openers[n - 1]} $first"), left = 40f, right = 380f, top = 60f)
                centred("Page $n of 3", 560f, size = 8f)
            }
        }
        assertEquals((1..3).map { "${openers[it - 1]} $first" }, PdfReading.paragraphs(pages).map { it.text })
    }

    @Test
    fun aLonePageNumberIsLeftOutEvenWhereNothingRepeats() {
        val page = page {
            column(listOf(first), left = 40f, right = 380f, top = 60f)
            centred("- 7 -", 565f, size = 8f)
        }
        assertEquals(listOf(first), texts(page))
    }

    @Test
    fun aLineOfThePageItselfIsKeptThoughItIsNearTheTop() {
        // Every page of a book opens with words of its own up there: only the same words at the same place are a running title.
        val pages = arrayOf(
            page { column(listOf(first), left = 40f, right = 380f, top = 40f) },
            page { column(listOf(second), left = 40f, right = 380f, top = 40f) },
        )
        assertEquals(listOf(first, second), texts(*pages))
    }

    @Test
    fun onlyAFewLinesAtTheEdgeCanBeRunningTitles() {
        // Slides that all open with the same six lines: past the first few, they are what the slide says.
        val same = (1..6).map { "Point number $it of the plan" }
        val pages = (1..3).map { n ->
            page {
                same.forEachIndexed { i, text -> line(text, 40f, 20f + i * PITCH) }
                column(listOf("Slide $n says something of its own here."), left = 40f, right = 380f, top = 200f)
            }
        }
        val read = PdfReading.paragraphs(pages).map { it.text }
        assertTrue(read.toString(), read.count { it.contains("Point number 6") } == 3)
        assertEquals(3, read.count { it.startsWith("Slide") })
    }

    @Test
    fun aNumberInTheMiddleOfThePageIsKept() {
        val page = page {
            column(listOf(first), left = 40f, right = 380f, top = 60f)
            line("1865", 40f, 300f)
            column(listOf(second), left = 40f, right = 380f, top = 340f)
        }
        assertEquals(listOf(first, "1865", second), texts(page))
    }

    // ---- Paragraphs that run on -----------------------------------------------------------------

    @Test
    fun aSentenceThatRunsOverThePageIsNotCut() {
        val whole = "$first $second $third"
        val cut = whole.indexOf("pleasure")
        // The paragraph is set as one, and the page turns in the middle of a sentence.
        val set = PageBuilder().apply { column(listOf(whole), left = 40f, right = 380f, top = 60f) }.build()
        val turn = set.lines.indexOfFirst { it.text.contains("pleasure") }
        assertTrue(turn > 0 && cut > 0)
        val one = page {
            centred("ALICE", 30f, size = 8f)
            set.lines.take(turn).forEach { line(it.text, it.left, it.top) }
            centred("1", 560f, size = 8f)
        }
        val two = page {
            centred("ALICE", 30f, size = 8f)
            set.lines.drop(turn).forEachIndexed { i, it -> line(it.text, it.left, 60f + i * PITCH) }
            centred("2", 560f, size = 8f)
        }
        val read = PdfReading.paragraphs(listOf(one, two))
        assertEquals(listOf(whole), read.map { it.text })
        // And it is known which of its words are on which page.
        assertEquals(listOf(0, 1), read.single().runs.map { it.page }.distinct())
    }

    @Test
    fun aParagraphThatEndsWithThePageIsNotJoinedToTheNext() {
        assertEquals(listOf(first, second), texts(bookPage(1, listOf(first)), bookPage(2, listOf(second))))
    }

    @Test
    fun aTitlePageIsNotRunIntoTheBook() {
        val title = page {
            centred("Down the Rabbit-Hole", 200f, size = 20f)
            centred("Lewis Carroll", 260f)
        }
        val text = page { column(listOf(first, second, third, fourth), left = 40f, right = 380f, top = 60f) }
        assertEquals(listOf("Down the Rabbit-Hole", "Lewis Carroll", first, second, third, fourth), texts(title, text))
    }

    @Test
    fun aWordBrokenOverThePageIsOneWord() {
        val one = page { line("It was a most extraordinary and quite unexpected cir-", 40f, 500f) }
        val two = page { line("cumstance, as everybody agreed.", 40f, 60f) }
        assertEquals(listOf("It was a most extraordinary and quite unexpected circumstance, as everybody agreed."), texts(one, two))
    }

    // ---- Columns -------------------------------------------------------------------------------------

    @Test
    fun columnsAreReadOneAfterTheOtherWhateverOrderTheFileDrawsThemIn() {
        val left = listOf(first, second)
        val right = listOf(third, fourth)
        val inOrder = page {
            column(left, left = 30f, right = 200f, top = 60f)
            column(right, left = 220f, right = 390f, top = 60f)
        }
        assertEquals(left + right, texts(inOrder))
        // The right-hand column drawn first, as a page made of two text boxes can be.
        val swapped = page {
            column(right, left = 220f, right = 390f, top = 60f)
            column(left, left = 30f, right = 200f, top = 60f)
        }
        assertEquals(left + right, texts(swapped))
    }

    @Test
    fun aParagraphRunsOnFromTheFootOfOneColumnToTheTopOfTheNext() {
        val whole = "$first $second $third $fourth"
        val set = PageBuilder().apply { column(listOf(whole), left = 30f, right = 200f, top = 60f) }.build()
        val half = set.lines.size / 2
        val page = page {
            set.lines.take(half).forEach { line(it.text, it.left, it.top) }
            set.lines.drop(half).forEachIndexed { i, it -> line(it.text, 220f, 60f + i * PITCH) }
        }
        assertEquals(listOf(whole), texts(page))
    }

    @Test
    fun aTitleAcrossTheColumnsIsReadBeforeThem() {
        val page = page {
            // Drawn last, as a title added after the text often is.
            column(listOf(first, second), left = 30f, right = 200f, top = 100f)
            column(listOf(third, fourth), left = 220f, right = 390f, top = 100f)
            centred("On Reading Aloud by Machine and by Hand, with Some Notes on Columns", 50f)
        }
        assertEquals(listOf("On Reading Aloud by Machine and by Hand, with Some Notes on Columns", first, second, third, fourth), texts(page))
    }

    @Test
    fun threeColumnsAreReadLeftToRight() {
        val a = "$first $second"
        val b = "$third $fourth"
        val c = "$second $first"
        val page = page {
            column(listOf(c), left = 280f, right = 400f, top = 60f, indent = 0f)
            column(listOf(a), left = 20f, right = 140f, top = 60f, indent = 0f)
            column(listOf(b), left = 150f, right = 270f, top = 60f, indent = 0f)
        }
        assertEquals(listOf(a, b, c), texts(page))
    }

    @Test
    fun aPageOfOneColumnIsLeftInTheFilesOrder() {
        // Short lines down the left and a few words to the right of them are not two columns.
        val page = page {
            column(listOf(first, second, third), left = 40f, right = 380f, top = 60f)
        }
        assertEquals(1, PdfReading.readingOrder(page, page.lines.indices.toList()).size)
    }

    // ---- Captions ------------------------------------------------------------------------------------

    @Test
    fun aCaptionInTheMiddleOfASentenceIsReadAfterTheParagraph() {
        val whole = "$first $second $third"
        val set = PageBuilder().apply { column(listOf(whole), left = 40f, right = 380f, top = 60f) }.build()
        val cut = set.lines.size / 2
        val page = page {
            set.lines.take(cut).forEach { line(it.text, it.left, it.top) }
            // The picture, and under it its caption.
            val under = 60f + cut * PITCH + 120f
            centred("Figure 1. A White Rabbit with pink eyes.", under, size = 8f)
            set.lines.drop(cut).forEachIndexed { i, it -> line(it.text, it.left, under + 40f + i * PITCH) }
        }
        val read = PdfReading.paragraphs(listOf(page))
        assertEquals(listOf(whole, "Figure 1. A White Rabbit with pink eyes."), read.map { it.text })
        assertEquals(listOf(false, true), read.map { it.caption })
    }

    @Test
    fun aCaptionAtTheTopOfTheNextPageWaitsForTheSentenceToo() {
        val whole = "$first $second"
        val set = PageBuilder().apply { column(listOf(whole), left = 40f, right = 380f, top = 60f) }.build()
        val one = page { set.lines.take(2).forEach { line(it.text, it.left, it.top) } }
        val two = page {
            centred("Table 2: Daisies picked, by the hour", 60f, size = 8f)
            set.lines.drop(2).forEachIndexed { i, it -> line(it.text, it.left, 120f + i * PITCH) }
        }
        assertEquals(listOf(whole, "Table 2: Daisies picked, by the hour"), texts(one, two))
    }

    @Test
    fun aCaptionBetweenTwoParagraphsStaysWhereItIs() {
        val page = page {
            val y = column(listOf(first), left = 40f, right = 380f, top = 60f)
            centred("Fig. 3 The rabbit-hole", y + 100f, size = 8f)
            column(listOf(second), left = 40f, right = 380f, top = y + 140f)
        }
        assertEquals(listOf(first, "Fig. 3 The rabbit-hole", second), texts(page))
    }

    @Test
    fun captionsAreKnownByHowTheyOpen() {
        for (caption in listOf("Figure 1. A page.", "Fig. 12: Results", "FIGURE 3", "Table 2 Growth by year", "Abbildung 4: Aufbau", "Рис. 5. Схема", "图 3 结构", "Tabela 1 – Resultados", "Figure 2.1: Detail")) {
            assertTrue(caption, PdfReading.isCaption(caption))
        }
        for (other in listOf("Figure 2 shows that the rabbit was late.", "Figures of speech are common.", "Table manners matter.", "The figure 8 is a number.", "Tablets were found.")) {
            assertFalse(other, PdfReading.isCaption(other))
        }
    }

    // ---- Sentences -----------------------------------------------------------------------------------

    @Test
    fun aSentenceEndsAtAFullStopWhateverClosesAfterIt() {
        for (ended in listOf("She ran.", "Was she late?", "“Oh dear!”", "(Which was very likely true.)", "It was this:", "彼は走った。", "…and so on…")) {
            assertTrue(ended, PdfReading.endsSentence(ended))
        }
        for (open in listOf("She ran across the", "the pleasure of making a daisy-", "Alice, who was tired,", "and then (as she thought", "")) {
            assertFalse(open, PdfReading.endsSentence(open))
        }
    }

    // ---- Where the words came from ----------------------------------------------------------------

    @Test
    fun everyLetterOfAParagraphIsKnownByItsLine() {
        val page = page {
            line("She had never before seen a rabbit with a waist-", 40f, 60f)
            line("coat  pocket,   and burning with curi-", 40f, 74f)
            line("osity she ran across the field.", 40f, 88f)
        }
        val paragraph = PdfReading.paragraphs(listOf(page)).single()
        for (run in paragraph.runs) {
            val line = page.lines[run.line].text
            assertEquals(paragraph.text.substring(run.at, run.at + run.length), line.substring(run.from, run.from + run.length))
        }
        // All of it but the spaces put in between lines is accounted for.
        val covered = paragraph.runs.sumOf { it.length }
        assertTrue("$covered of ${paragraph.text.length}", paragraph.text.length - covered <= 2)
        assertEquals(paragraph.runs.sortedBy { it.at }.map { it.at }, paragraph.runs.map { it.at })
    }
}
