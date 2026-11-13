package io.github.friendlyglobal.jacket.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import io.github.friendlyglobal.jacket.R

/*
 * A chapter's name as the chapter control shows it.
 *
 * Many books number their chapters and call them nothing more: "II", "12", "XIV.". Between the
 * previous and next chapter buttons such a title looks like one more button: in Gulliver's
 * Travels the pill read "⏮ II ⏭", right above the player's real pause button. A title that is
 * only a number (or a letter by itself) is shown as what it is, the name of a chapter: "Chapter
 * II". A title that is nothing but marks ("* * *", "§") names nothing the eye can read, and isn't
 * shown at all: the two buttons stand by themselves, as in a book whose chapters have no names.
 */
internal object ChapterNames {
    /** [title] as the control shows it: [numbered] ("Chapter %s") around a number, nothing for marks alone, else as it is. */
    fun shown(title: String, numbered: (String) -> String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return ""
        numberOf(trimmed)?.let { return numbered(it) }
        // A letter by itself ("A", "一") stands for a chapter as a number does, and is as short.
        if (trimmed.codePointCount(0, trimmed.length) == 1 && Character.isLetter(trimmed.codePointAt(0))) return numbered(trimmed)
        return if (trimmed.any(Char::isLetterOrDigit)) trimmed else ""
    }

    /**
     * The number [title] is, without the full stop or brackets around it ("2." is "2", "(IV)" is
     * "IV"), in figures of any script or in Roman numerals; null for a title that is more than a
     * number. Roman numerals are taken in capitals only: in small letters too many words are
     * written with them alone ("mix", "civil", "liv").
     */
    fun numberOf(title: String): String? {
        val bare = title.trim().trim { it in AROUND || it.isWhitespace() }
        if (bare.isEmpty() || bare.length > LONGEST) return null
        return bare.takeIf { it.all(Char::isDigit) || ROMAN.matches(it) || it.all { c -> c in ROMAN_SIGNS } }
    }

    /** What may stand around a chapter's number: "2.", "(IV)", "[3]", "12:", "— 7 —". */
    private const val AROUND = ".:()[]-–—"

    /** A number longer than this is a year, or a code: not a chapter's. */
    private const val LONGEST = 9

    /** I to MMMMCMXCIX in capitals, nothing else. */
    private val ROMAN = Regex("(?=[MDCLXVI])M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})")

    /** The Roman numerals Unicode has as signs of their own ("Ⅱ", "Ⅻ"). */
    private val ROMAN_SIGNS = '\u2160'..'\u2188'
}

/** [title] as the chapter control shows it (see [ChapterNames]): "Chapter II" for "II", in the phone's language. */
@Composable
internal fun chapterName(title: String): String {
    val resources = LocalResources.current
    return remember(title, resources) { ChapterNames.shown(title) { resources.getString(R.string.reader_chapter_numbered, it) } }
}
