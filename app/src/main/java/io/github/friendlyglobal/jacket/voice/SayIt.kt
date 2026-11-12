package io.github.friendlyglobal.jacket.voice

/*
 * "Say it this way": what the voice is given to say is not always what the page has. A name the
 * voice gets wrong is written for it the way it sounds ("Hermione" as "her-MY-oh-nee"), and the
 * small numbers that point to notes are left out. A plain respelling is all it is, so every voice
 * reads it, Jacket's natural ones and the phone's own alike.
 *
 * The ink still has to follow the voice on the page. So the sentence made for the voice keeps
 * track of what was changed where, and each word the voice reports is told back as the place of
 * that word on the page (see [SpokenText.toPage]).
 */

/** A word and how it is to be said: [word] as the page writes it, [say] written the way it sounds. */
data class Respelling(val word: String, val say: String)

/**
 * A sentence as the voice is given it ([spoken]), made from the sentence as the page has it
 * ([page]): with what was changed, so a place in the one can be told in the other.
 */
class SpokenText internal constructor(val page: String, val spoken: String, private val changes: List<Change>) {
    /** Characters [pageStart, pageEnd) of the page became [spokenStart, spokenEnd) of what is said (nothing, for a note's number). */
    internal class Change(val pageStart: Int, val pageEnd: Int, val spokenStart: Int, val spokenEnd: Int) {
        val unspoken: Boolean get() = spokenEnd == spokenStart
    }

    /** The voice is given something other than what the page has. */
    val changed: Boolean get() = changes.isNotEmpty()

    /**
     * Where on the page the characters [range] of what is said are. A word that was written
     * another way for the voice is on the page as a whole, whichever part of its respelling is
     * being said; a note's number that follows a word inks in with it.
     */
    fun toPage(range: IntRange): IntRange {
        if (changes.isEmpty() || range.isEmpty()) return range
        val first = range.first.coerceIn(0, (spoken.length - 1).coerceAtLeast(0))
        val last = range.last.coerceIn(first, (spoken.length - 1).coerceAtLeast(first))
        val start = within(first)?.pageStart ?: (first - shiftBefore(first))
        var end = within(last)?.pageEnd ?: (last - shiftBefore(last) + 1)
        // A note's number set right against the word, left unsaid: it is read with that word.
        for (change in changes) if (change.unspoken && change.pageStart == end) end = change.pageEnd
        val from = start.coerceIn(0, (page.length - 1).coerceAtLeast(0))
        return from until end.coerceIn(from + 1, page.length.coerceAtLeast(from + 1))
    }

    /** The change that characters [at] of what is said came from, if it came from one. */
    private fun within(at: Int): Change? = changes.firstOrNull { at >= it.spokenStart && at < it.spokenEnd }

    /** How much further along what is said is than the page, at character [at] of what is said. */
    private fun shiftBefore(at: Int): Int {
        var shift = 0
        for (change in changes) {
            if (change.spokenEnd > at) break
            shift += (change.spokenEnd - change.spokenStart) - (change.pageEnd - change.pageStart)
        }
        return shift
    }
}

object SayIt {
    /** The longest a word, or the way to say it, may be. */
    const val LONGEST = 80

    /**
     * [text] as the voice is to say it: each word in [respellings] written the way it sounds, and
     * the characters in [unspoken] (the numbers of notes) left out.
     *
     * A word is found whole, not inside a longer one ("Ann" isn't in "Anne"), whatever its
     * capitals; but one saved with a capital letter is a name, and is left alone where the page
     * has it in small letters: "Said", a man, is not every "said" in the book. Where two overlap,
     * the longer wins; of two for the same word, the first in the list does (a book's own come
     * before those for every book: see [io.github.friendlyglobal.jacket.data.LibraryState.respellingsFor]).
     */
    fun speak(text: String, respellings: List<Respelling>, unspoken: List<IntRange> = emptyList()): SpokenText {
        val edits = ArrayList<Edit>()
        for (range in unspoken.sortedBy { it.first }) {
            if (range.isEmpty() || range.first < 0 || range.last >= text.length) continue
            if (edits.isNotEmpty() && range.first < edits.last().end) continue
            // Left out, two words either side of it must not run together.
            val joins = range.first > 0 && !text[range.first - 1].isWhitespace() && text.getOrNull(range.last + 1)?.isLetterOrDigit() == true
            edits += Edit(range.first, range.last + 1, if (joins) " " else "")
        }
        // A number left out is no part of the word it is set against: "Hermione12" is "Hermione".
        val gone = BooleanArray(text.length)
        for (edit in edits) for (i in edit.start until edit.end) gone[i] = true
        val words = ArrayList<Edit>()
        respellings.forEachIndexed { order, respelling ->
            val word = tidy(respelling.word)
            val say = tidy(respelling.say)
            if (word.isEmpty() || say.isEmpty()) return@forEachIndexed
            var at = indexOf(text, word, 0)
            while (at >= 0) {
                val end = at + word.length
                if (whole(text, at, end, gone) && sameCapitals(word, text, at) && (at until end).none { gone[it] }) {
                    words += Edit(at, end, say, order)
                }
                at = indexOf(text, word, at + 1)
            }
        }
        // The earliest first; of those that start together, the longest, then the first listed.
        words.sortWith(compareBy<Edit>({ it.start }, { -(it.end - it.start) }, { it.order }))
        var taken = 0
        for (word in words) {
            if (word.start < taken) continue
            edits += word
            taken = word.end
        }
        if (edits.isEmpty()) return SpokenText(text, text, emptyList())
        edits.sortBy { it.start }
        val spoken = StringBuilder(text.length + 16)
        val changes = ArrayList<SpokenText.Change>(edits.size)
        var from = 0
        for (edit in edits) {
            spoken.append(text, from, edit.start)
            val start = spoken.length
            spoken.append(edit.say)
            changes += SpokenText.Change(edit.start, edit.end, start, spoken.length)
            from = edit.end
        }
        spoken.append(text, from, text.length)
        return SpokenText(text, spoken.toString(), changes)
    }

    /** A word, or the way to say it, as it is kept: one line, single spaces, not longer than [LONGEST]. */
    fun tidy(text: String): String = text.replace(SPACES, " ").trim().take(LONGEST).trim()

    /**
     * The word to offer for words selected on the page: the selection without the quotation marks
     * and the punctuation around it, cut at a word's end if it is very long.
     */
    fun wordFrom(selection: String): String {
        val trimmed = tidy(selection).trim { !it.isLetterOrDigit() }
        if (trimmed.length <= LONGEST_OFFERED) return trimmed
        val cut = trimmed.lastIndexOf(' ', LONGEST_OFFERED)
        return (if (cut > 0) trimmed.take(cut) else trimmed.take(LONGEST_OFFERED)).trimEnd { !it.isLetterOrDigit() }
    }

    /** Whether two words are the same word to a fix: one fix a word, in a book or in all of them. */
    fun sameWord(a: String, b: String): Boolean {
        val one = tidy(a)
        val other = tidy(b)
        return one.length == other.length && one.indices.all { fold(one[it]) == fold(other[it]) }
    }

    private class Edit(val start: Int, val end: Int, val say: String, val order: Int = 0)

    private val SPACES = Regex("\\s+")
    private const val LONGEST_OFFERED = 40

    /** Capitals aside, and the curly apostrophe a book prints for the straight one that is typed. */
    private fun fold(c: Char): Char = when (c) {
        '\u2019', '\u2018', '\u02BC', '\u2032' -> '\''
        '\u00A0', '\u202F' -> ' '
        else -> c.lowercaseChar()
    }

    private fun indexOf(text: String, word: String, from: Int): Int {
        val first = fold(word[0])
        var at = from
        while (at <= text.length - word.length) {
            if (fold(text[at]) == first && (1 until word.length).all { fold(text[at + it]) == fold(word[it]) }) return at
            at++
        }
        return -1
    }

    /** [start, end) of [text] is a word of its own, not part of a longer one. What is [gone] from the text is no part of any word. */
    private fun whole(text: String, start: Int, end: Int, gone: BooleanArray): Boolean {
        val before = text.getOrNull(start - 1)?.takeUnless { gone[start - 1] }
        val after = text.getOrNull(end)?.takeUnless { gone[end] }
        val opens = text[start]
        val closes = text[end - 1]
        // Scripts written without spaces have no edges to go by: a word is wherever it is written.
        val leftEdge = before == null || !inWord(before) || !inWord(opens) || unspaced(opens) || unspaced(before)
        val rightEdge = after == null || !inWord(after) || !inWord(closes) || unspaced(closes) || unspaced(after)
        return leftEdge && rightEdge
    }

    private fun inWord(c: Char): Boolean = c.isLetterOrDigit() || Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun unspaced(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0x0E00..0x0EFF || c.code in 0xF900..0xFAFF

    /**
     * A word saved with a capital is a name: it is said the new way where the page has it just so,
     * or all in capitals (a chapter's heading), and not where the page has it in small letters.
     */
    private fun sameCapitals(word: String, text: String, at: Int): Boolean {
        if (word.none { it.isUpperCase() }) return true
        val found = text.substring(at, at + word.length)
        return found.none { it.isLowerCase() } || word.indices.all { !word[it].isUpperCase() || found[it].isUpperCase() }
    }
}

/**
 * What stands between a book's words and the voice while it is read aloud: the listener's
 * respellings, and the numbers of notes the voice passes over. The reader's sentences are cut
 * through it ([sentences]), and each one is said through it ([spoken]).
 *
 * A note's number is known by where it is in its paragraph, and a sentence reaches the voice as
 * words alone: so the numbers found in a paragraph are remembered by the sentences they are in.
 */
class SpeechEdits {
    /** The words to say another way, a book's own before those for every book. */
    @Volatile
    var respellings: List<Respelling> = emptyList()

    /**
     * Finds the numbers of notes in a paragraph of the book: the characters of it they take up.
     * Null while they are to be read like anything else.
     */
    @Volatile
    var noteMarks: ((paragraph: String) -> List<IntRange>)? = null

    private val marked = object : LinkedHashMap<String, List<IntRange>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<IntRange>>?) = size > REMEMBERED
    }

    /**
     * The sentences of [paragraph], cut by [cut], with its notes' numbers set aside first: a
     * number after a full stop ("…he said.12 Then…") is no longer taken for part of the sentence,
     * and one between two sentences is in neither.
     */
    fun sentences(paragraph: String, cut: (String) -> List<IntRange>): List<IntRange> {
        val marks = noteMarks?.let { find -> runCatching { find(paragraph) }.getOrNull() }.orEmpty()
            .filter { !it.isEmpty() && it.first >= 0 && it.last < paragraph.length }
        if (marks.isEmpty()) return cut(paragraph).also { ranges -> synchronized(marked) { ranges.forEach { marked.remove(paragraph.substring(it)) } } }
        // The same length, a space for each character of a number: the places stay the page's.
        val blanked = StringBuilder(paragraph)
        for (mark in marks) for (i in mark) blanked[i] = ' '
        val ranges = cut(blanked.toString()).mapNotNull { range ->
            // Not starting or ending on what was a number.
            var first = range.first
            var last = range.last
            while (first <= last && blanked[first] == ' ') first++
            while (last >= first && blanked[last] == ' ') last--
            if (first > last) null else first..last
        }
        synchronized(marked) {
            for (range in ranges) {
                val sentence = paragraph.substring(range)
                val inside = marks.filter { it.first >= range.first && it.last <= range.last }.map { it.first - range.first..it.last - range.first }
                if (inside.isEmpty()) marked.remove(sentence) else marked[sentence] = inside
            }
        }
        return ranges
    }

    /** [sentence] (one of those [sentences] gave) as the voice is to say it. */
    fun spoken(sentence: String): SpokenText {
        val marks = if (noteMarks == null) emptyList() else synchronized(marked) { marked[sentence] }.orEmpty()
        return SayIt.speak(sentence, respellings, marks)
    }

    private companion object {
        const val REMEMBERED = 512
    }
}
