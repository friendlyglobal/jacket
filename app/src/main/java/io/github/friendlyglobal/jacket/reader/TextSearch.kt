package io.github.friendlyglobal.jacket.reader

import java.text.Normalizer
import java.util.Locale

/*
 * Finding words in a book's text the way a reader types them into a search box.
 *
 * What is typed is found whatever its case, with or without the accents of the Latin and Greek
 * alphabets ("cafe" finds "café"), with a straight quote for a curled one and a plain hyphen for
 * a dash, and across a line break or a run of spaces. The joined letters some PDFs are set with
 * ("ﬁ") are the letters they are made of, and marks that aren't letters at all (a soft hyphen,
 * the vowel points of Arabic and Hebrew) are passed over. Letters that are letters of their own
 * are left alone: "й" is not "и", and a Hindi vowel sign is part of its word. "ё" is found by
 * "е", as Russian readers expect.
 *
 * This is how a PDF's words are searched (see PdfSearch.kt). An EPUB is searched by Readium.
 */
internal object TextSearch {
    /** Text made ready to be compared: [folded], and for each of its letters the letter of the text it came from. */
    class Folded(val folded: String, val from: IntArray)

    /** What a reader typed, made ready to be looked for; [blank] when there is nothing in it to look for. */
    class Query(val folded: String) {
        val blank: Boolean get() = folded.isEmpty()
    }

    fun query(typed: String): Query = Query(fold(typed).folded.trim())

    /**
     * Where [query] is in [text], in order: each a range of [text]'s own letters, for marking
     * them and quoting what stands around them. Matches don't overlap.
     */
    fun find(text: String, query: Query): List<IntRange> {
        if (query.blank || text.isEmpty()) return emptyList()
        val ready = fold(text)
        var at = ready.folded.indexOf(query.folded)
        if (at < 0) return emptyList()
        val found = ArrayList<IntRange>()
        while (at >= 0) {
            val end = at + query.folded.length
            // An accent written as a mark of its own after the last letter found belongs to that letter.
            var last = ready.from[end - 1]
            while (last + 1 < text.length && mark(text[last + 1])) last++
            found += ready.from[at]..last
            at = ready.folded.indexOf(query.folded, end)
        }
        return found
    }

    /** [text] as it is compared (see the top of this file). */
    fun fold(text: String): Folded {
        val out = StringBuilder(text.length)
        var from = IntArray(text.length + 8)
        fun put(c: Char, source: Int) {
            if (out.length == from.size) from = from.copyOf(from.size * 2)
            from[out.length] = source
            out.append(c)
        }
        var spaced = false
        for (index in text.indices) {
            val c = text[index]
            when {
                c.isWhitespace() || Character.isSpaceChar(c) -> {
                    if (!spaced) put(' ', index)
                    spaced = true
                    continue
                }
                unseen(c) -> continue
                c.code < 0x80 -> put(c.lowercaseChar(), index)
                else -> {
                    val plain = PUNCTUATION[c]
                    if (plain != null) {
                        put(plain, index)
                    } else {
                        for (letter in letters(c)) put(letter, index)
                    }
                }
            }
            spaced = false
        }
        return Folded(out.toString(), from.copyOf(out.length))
    }

    /** A letter from beyond plain ASCII as it is compared: in small type, and, for the alphabets that take accents, without them. */
    private fun letters(c: Char): String {
        if (c.isSurrogate()) return c.toString()
        val small = c.toString().lowercase(Locale.ROOT)
        if (small == "ё") return "е"
        // Joined letters ("ﬁ") come apart; nothing else is taken to pieces that way.
        val apart = if (c.code in 0xFB00..0xFB06) Normalizer.normalize(small, Normalizer.Form.NFKD) else Normalizer.normalize(small, Normalizer.Form.NFD)
        if (apart.length == small.length) return small
        val base = apart[0]
        val accented = Character.UnicodeScript.of(base.code).let { it == Character.UnicodeScript.LATIN || it == Character.UnicodeScript.GREEK }
        return if (accented) apart.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() } else small
    }

    /** Marks that take no part in a search: what can't be seen, accents written by themselves, and the vowel points of Arabic and Hebrew. */
    private fun unseen(c: Char): Boolean = Character.getType(c) == Character.FORMAT.toInt() || c.code == 0x0640 || mark(c)

    /** An accent or a vowel point written as a mark of its own, after the letter it sits on. */
    private fun mark(c: Char): Boolean {
        val code = c.code
        return code in 0x0300..0x036F || code in 0x0591..0x05C7 || code in 0x064B..0x0652 || code == 0x0670
    }

    private val PUNCTUATION: Map<Char, Char> = buildMap {
        "‘’‚‛ʼ′".forEach { put(it, '\'') }
        "“”„‟″".forEach { put(it, '"') }
        "‐‑‒–—―−".forEach { put(it, '-') }
    }
}
