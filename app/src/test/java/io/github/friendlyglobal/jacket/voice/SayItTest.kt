package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Say it this way": what the voice is given to say, and where each of its words is on the page. */
class SayItTest {
    private val hermione = Respelling("Hermione", "her-MY-oh-nee")

    /** The words of what is said, each told back as the words of the page it stands for. */
    private fun SpokenText.wordsOnPage(): List<String> =
        SpeechTiming.words(spoken).map { page.substring(toPage(it)) }

    @Test
    fun aWordIsSaidTheWayItIsWritten() {
        val said = SayIt.speak("Then Hermione laughed.", listOf(hermione))
        assertEquals("Then her-MY-oh-nee laughed.", said.spoken)
        assertTrue(said.changed)
    }

    @Test
    fun nothingToChangeLeavesTheSentenceAsItIs() {
        val said = SayIt.speak("Then Harry laughed.", listOf(hermione))
        assertEquals("Then Harry laughed.", said.spoken)
        assertFalse(said.changed)
        assertEquals(5..9, said.toPage(5..9))
    }

    @Test
    fun everyWordSpokenIsToldAsItsWordOnThePage() {
        val said = SayIt.speak("Then Hermione laughed, and Hermione's cat ran.", listOf(hermione))
        assertEquals("Then her-MY-oh-nee laughed, and her-MY-oh-nee's cat ran.", said.spoken)
        assertEquals(listOf("Then", "Hermione", "laughed,", "and", "Hermione's", "cat", "ran."), said.wordsOnPage())
    }

    @Test
    fun aRespellingOfSeveralWordsInksItsOneWordForEachOfThem() {
        val said = SayIt.speak("Ask Siobhan now.", listOf(Respelling("Siobhan", "shi vawn")))
        assertEquals("Ask shi vawn now.", said.spoken)
        // "shi" and "vawn" are both "Siobhan" on the page; what follows is where it was.
        assertEquals(listOf("Ask", "Siobhan", "Siobhan", "now."), said.wordsOnPage())
    }

    @Test
    fun aShorterRespellingKeepsTheWordsAfterItInStep() {
        val said = SayIt.speak("The Worcestershire sauce, please.", listOf(Respelling("Worcestershire", "WUSS-ter")))
        assertEquals("The WUSS-ter sauce, please.", said.spoken)
        assertEquals(listOf("The", "Worcestershire", "sauce,", "please."), said.wordsOnPage())
    }

    @Test
    fun aRangeInsideARespellingIsTheWholeWordOnThePage() {
        val said = SayIt.speak("Then Hermione laughed.", listOf(hermione))
        // The phone's voice may report "her", "MY"... as it says them: each is "Hermione".
        assertEquals("Hermione", said.page.substring(said.toPage(5..7)))
        assertEquals("Hermione", said.page.substring(said.toPage(9..10)))
        // From inside it to the word after: both words.
        assertEquals("Hermione laughed.", said.page.substring(said.toPage(12..26)))
    }

    @Test
    fun aWordIsFoundWholeNotInsideALongerOne() {
        val said = SayIt.speak("Ann met Anne and Joanna; Ann's hat, Ann-Marie.", listOf(Respelling("Ann", "AN")))
        assertEquals("AN met Anne and Joanna; AN's hat, AN-Marie.", said.spoken)
    }

    @Test
    fun aNameIsLeftAloneWhereThePageHasItInSmallLetters() {
        val name = listOf(Respelling("Said", "sah-EED"))
        assertEquals("sah-EED said he had said so. sah-EED!", SayIt.speak("Said said he had said so. SAID!", name).spoken)
        // A word saved in small letters is said the new way however the page writes it.
        val word = listOf(Respelling("lead", "led"))
        assertEquals("led pipes. A led pipe. led!", SayIt.speak("Lead pipes. A lead pipe. LEAD!", word).spoken)
    }

    @Test
    fun theLongerOfTwoThatOverlapWins() {
        val both = listOf(Respelling("Minas", "MEE-nas"), Respelling("Minas Tirith", "MEE-nas TEE-rith"))
        assertEquals("To MEE-nas TEE-rith, then MEE-nas Morgul.", SayIt.speak("To Minas Tirith, then Minas Morgul.", both).spoken)
    }

    @Test
    fun theBooksOwnWayComesBeforeTheOneForEveryBook() {
        val both = listOf(Respelling("Smaug", "smowg"), Respelling("smaug", "smog"))
        assertEquals("smowg woke.", SayIt.speak("Smaug woke.", both).spoken)
    }

    @Test
    fun aTypedApostropheFindsTheBooksCurlyOne() {
        val said = SayIt.speak("Then O’Brien and D’Artagnan left.", listOf(Respelling("O'Brien", "oh-BRY-un"), Respelling("d'artagnan", "dar-tan-YAN")))
        assertEquals("Then oh-BRY-un and dar-tan-YAN left.", said.spoken)
    }

    @Test
    fun wordsWithoutSpacesBetweenThemAreFoundWhereverTheyAre() {
        val said = SayIt.speak("他在重庆长大。", listOf(Respelling("重庆", "虫庆")))
        assertEquals("他在虫庆长大。", said.spoken)
    }

    @Test
    fun emptyOnesChangeNothing() {
        val said = SayIt.speak("Then Hermione laughed.", listOf(Respelling("  ", "x"), Respelling("Hermione", " ")))
        assertEquals("Then Hermione laughed.", said.spoken)
    }

    @Test
    fun aNotesNumberIsLeftOut() {
        //            0123456789012345678901234
        val text = "It was late.12 Nobody came."
        val said = SayIt.speak(text, emptyList(), listOf(12..13))
        assertEquals("It was late. Nobody came.", said.spoken)
        // The number inks in with the word it follows; the words after it are where they were.
        assertEquals(listOf("It", "was", "late.12", "Nobody", "came."), said.wordsOnPage())
    }

    @Test
    fun aNumberLeftOutDoesNotRunTwoWordsTogether() {
        val said = SayIt.speak("the end.3Then more", emptyList(), listOf(8..8))
        assertEquals("the end. Then more", said.spoken)
        assertEquals(listOf("the", "end.", "Then", "more"), said.wordsOnPage().map { it.trimEnd('3') })
    }

    @Test
    fun respellingsAndNotesNumbersTogether() {
        //            0         1         2         3
        //            0123456789012345678901234567890123456789
        val text = "Hermione1 saw Hermione, said Ron.2"
        val said = SayIt.speak(text, listOf(hermione), listOf(8..8, 33..33))
        assertEquals("her-MY-oh-nee saw her-MY-oh-nee, said Ron.", said.spoken)
        assertEquals(listOf("Hermione1", "saw", "Hermione,", "said", "Ron.2"), said.wordsOnPage())
    }

    @Test
    fun aNumberInsideAWordToRespellIsNotRespelled() {
        // The mark sits on what would otherwise be found: the number goes, the rest is read as it is.
        val said = SayIt.speak("Room 12 is shut.", listOf(Respelling("12", "twelve")), listOf(5..6))
        assertEquals("Room  is shut.", said.spoken)
    }

    @Test
    fun theWordOfferedForASelectionIsTheWordItself() {
        assertEquals("Hermione", SayIt.wordFrom("  “Hermione,”\n"))
        assertEquals("Minas Tirith", SayIt.wordFrom("Minas   Tirith."))
        assertEquals("", SayIt.wordFrom(" … "))
        val long = SayIt.wordFrom("It was the best of times, it was the worst of times, it was the age of wisdom")
        assertTrue(long.length <= 40)
        assertEquals("It was the best of times, it was the", long)
    }

    @Test
    fun theSameWordWhateverItsCapitalsAndSpaces() {
        assertTrue(SayIt.sameWord("Hermione", " hermione "))
        assertTrue(SayIt.sameWord("O'Brien", "O’brien"))
        assertFalse(SayIt.sameWord("Ann", "Anne"))
    }

    // --- Cut into sentences, and said, through SpeechEdits ---

    private fun cut(text: String) = sentencesOf(text)

    @Test
    fun aNotesNumberAfterAFullStopNoLongerJoinsTwoSentences() {
        val paragraph = "It was late.12 Nobody came. He left3 at once."
        val edits = SpeechEdits()
        // Left to itself the first two are one sentence: "late.12 Nobody" has no break in it.
        assertEquals(listOf("It was late.12 Nobody came.", "He left3 at once."), edits.sentences(paragraph, ::cut).map { paragraph.substring(it) })
        edits.noteMarks = { listOf(12..13, 35..35) }
        val sentences = edits.sentences(paragraph, ::cut).map { paragraph.substring(it) }
        // Between two sentences the number is in neither; inside one it stays on the page.
        assertEquals(listOf("It was late.", "Nobody came.", "He left3 at once."), sentences)
        assertEquals("It was late.", edits.spoken(sentences[0]).spoken)
        val last = edits.spoken(sentences[2])
        assertEquals("He left at once.", last.spoken)
        assertEquals(listOf("He", "left3", "at", "once."), SpeechTiming.words(last.spoken).map { last.page.substring(last.toPage(it)) })
    }

    @Test
    fun theSameWordsWithoutANoteAreReadInFull() {
        val edits = SpeechEdits()
        edits.noteMarks = { text -> if (text.startsWith("See")) listOf(text.length - 2..text.length - 2) else emptyList() }
        val noted = "See page 12."
        assertEquals("See page 1.", edits.spoken(noted.substring(edits.sentences(noted, ::cut).single())).spoken)
        // The same words again, in a paragraph where the 2 is no note's number.
        edits.noteMarks = { emptyList() }
        assertEquals("See page 12.", edits.spoken(noted.substring(edits.sentences(noted, ::cut).single())).spoken)
    }

    @Test
    fun switchedOffNotesNumbersAreReadAgain() {
        val edits = SpeechEdits()
        edits.noteMarks = { listOf(7..7) }
        val paragraph = "He left3 at once."
        val sentence = paragraph.substring(edits.sentences(paragraph, ::cut).single())
        assertEquals("He left at once.", edits.spoken(sentence).spoken)
        edits.noteMarks = null
        assertEquals("He left3 at once.", edits.spoken(sentence).spoken)
    }

    @Test
    fun respellingsAreSaidInEverySentence() {
        val edits = SpeechEdits()
        edits.respellings = listOf(hermione)
        val paragraph = "Hermione sat. Then Hermione stood."
        val said = edits.sentences(paragraph, ::cut).map { edits.spoken(paragraph.substring(it)).spoken }
        assertEquals(listOf("her-MY-oh-nee sat.", "Then her-MY-oh-nee stood."), said)
    }

    @Test
    fun theVoicesTimingStaysOnThePagesWords() {
        // As the natural voices do it: the words of what was said, timed, then told by the page.
        val said = SayIt.speak("Hermione1 went to Worcestershire.", listOf(hermione, Respelling("Worcestershire", "WUSS-ter-sheer")), listOf(8..8))
        val pcm = ShortArray(16_000) { if (it % 2 == 0) 8_000 else -8_000 }
        val timed = SpeechTiming.time(said.spoken, SpeechTiming.words(said.spoken), pcm, 16_000)
        val inked = timed.map { said.page.substring(said.toPage(it.start until it.end)) }
        assertEquals(listOf("Hermione1", "went", "to", "Worcestershire."), inked)
        // One after the other, never back up the page.
        val starts = timed.map { said.toPage(it.start until it.end).first }
        assertEquals(starts.sorted(), starts)
    }
}
