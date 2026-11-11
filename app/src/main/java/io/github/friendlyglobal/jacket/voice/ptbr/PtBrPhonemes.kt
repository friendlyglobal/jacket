package io.github.friendlyglobal.jacket.voice.ptbr

import java.text.Normalizer

/**
 * Brazilian Portuguese spelling turned into sounds, for the Brazilian voices.
 *
 * Those voices don't read letters: they were taught on text already turned into sound symbols, and
 * want the same symbols back. This writes them, by the rules of Brazilian speech: where the stress
 * falls, open and closed é/ê and ó/ô, nasal vowels, "ti" and "di" said "tchi" and "dji", a final
 * "e" said "i" and a final "o" said "u", the r of "rato" and of "carro", an l that ends a syllable
 * said "u", and how neighbouring words run together. It is written from the grammar of the
 * language; the symbols themselves are simply the ones the voices know (some are not what a
 * phonetician would pick: a final "a" is "æ", a final "e" is "y").
 *
 * No word is ever dropped: one the lists below don't know is read by the rules.
 */
internal object PtBrPhonemes {
    /** [word] (letters only) as the voice's symbols, stress marks included. */
    fun word(word: String): String {
        val key = word.lowercase()
        PtBrWords.fixed[key]?.let { return it }
        val said = Spelling(PtBrWords.respelled[key] ?: key).say()
        return if (key in PtBrWords.weak) weaken(said) else said
    }

    /** A word that leans on its neighbours keeps only a light stress where its strong one was. */
    private fun weaken(said: String): String = said.replace("ˌ", "").replace('ˈ', 'ˌ')

    /**
     * [text] as the symbols for each of its sentences, pauses included, ready for the voice.
     * Numbers, dates, money and abbreviations are read out in words first (see [PtBrText]).
     */
    fun sentences(text: String, oldSpelling: Boolean = false): List<String> {
        val out = ArrayList<String>()
        val now = StringBuilder()
        var pending: String? = null // the last word, held back until we know what follows it
        var joined = false // nothing but a space since the last word
        fun flushWord() {
            pending?.let { now.append(it) }
            pending = null
        }
        fun pause(mark: Char) {
            // A word that leaned on the next one has nothing to lean on before a pause.
            pending = pending?.let { if ('ˈ' !in it && 'ˌ' in it) it.replaceRange(it.lastIndexOf('ˌ'), it.lastIndexOf('ˌ') + 1, "ˈ") else it }
            flushWord()
            if (now.isEmpty() || now.last() in ".,;:!?") {
                // Two pauses in a row are one: the stronger wins.
                if (now.isNotEmpty() && mark in ".!?" && now.last() in ",;:") now.setLength(now.length - 1) else return
            }
            now.append(mark)
        }
        fun endSentence() {
            flushWord()
            val said = now.toString().trim()
            if (said.any { it !in ".,;:!? " }) out += said
            now.setLength(0)
            joined = false
        }
        for (token in PtBrText.tokens(text, oldSpelling)) {
            when (token) {
                is PtBrText.Token.Word -> {
                    val said = spoken(token.text)
                    if (said.isEmpty()) continue
                    val before = pending
                    if (before != null && joined) {
                        now.append(link(before, said)).append(' ')
                        pending = said
                    } else {
                        flushWord()
                        if (now.isNotEmpty()) now.append(' ')
                        pending = said
                    }
                    joined = true
                }
                is PtBrText.Token.Pause -> {
                    pause(token.mark)
                    joined = false
                    if (token.mark in ".!?") endSentence()
                }
            }
        }
        endSentence()
        return out
    }

    /** [sentences] as the numbers the engine takes: one list of Unicode code points per sentence. */
    fun codepoints(text: String, oldSpelling: Boolean = false): Array<IntArray> =
        sentences(text, oldSpelling).map { sentence ->
            // The voices know "ã" as "a" followed by a tilde, never as one character.
            Normalizer.normalize(sentence, Normalizer.Form.NFD).codePoints().toArray()
        }.toTypedArray()

    /** A written word, which may be several joined by hyphens or an apostrophe ("disse-lhe", "d'água"). */
    private fun spoken(written: String): String {
        val parts = written.lowercase().split('-').filter { it.isNotEmpty() }
        val out = StringBuilder()
        parts.forEachIndexed { i, part ->
            val apostrophe = part.indexOfFirst { it == '\'' || it == '’' }
            val said = when {
                // "d'água": the stump of a word, said with the next one.
                apostrophe in 1..2 && apostrophe < part.lastIndex -> {
                    val rest = word(part.substring(apostrophe + 1).filter { it.isLetter() })
                    val head = when (part.substring(0, apostrophe)) {
                        "d" -> if (rest.trimStart('ˈ', 'ˌ').startsWith("i")) "dʒ" else "d"
                        "qu", "c" -> "k"
                        else -> part.substring(0, 1)
                    }
                    head + rest
                }
                // A pronoun hung on its verb is said as its tail, unstressed.
                i > 0 && part in PtBrWords.clitics -> PtBrWords.clitics.getValue(part)
                else -> word(part.filter { it.isLetter() })
            }
            out.append(said)
        }
        return out.toString()
    }

    private const val VOWELS = "aeiouæɛɔɐʊyɪũõ"
    private const val VOICED = "bdɡvzʒmnlxʎɲrɾwj"

    /** Little words whose final "e" stays, with a glide after it, before a vowel. */
    private val GLIDING = setOf("ky", "ˌely", "ˌestʃy")

    private fun firstSound(said: String): Char = said.firstOrNull { it != 'ˈ' && it != 'ˌ' } ?: ' '

    /** How [word] ends when [next] follows it with no pause between: Brazilians run words together. */
    private fun link(word: String, next: String): String {
        val first = firstSound(next)
        val vowel = first in VOWELS
        return when {
            // "os olhos" is "uz olhos"; "os dois" too: an s before a voiced sound hums.
            word.endsWith("s") && (vowel || first in VOICED) -> word.dropLast(1) + "z"
            // "falar alto": an r before a vowel is the light one.
            word.endsWith("r") && vowel -> word.dropLast(1) + "ɾ"
            !vowel -> word
            word == "ʊ" -> "u"
            word == "foɪ" || word == "fuɪ" -> word
            word.endsWith("i") || word.endsWith("ɪ") -> word + "ʲ"
            word.endsWith("ˈy") || word.endsWith("ˌy") || word in GLIDING -> word + "ʲ"
            // A final "e" before a vowel glides into it.
            word.endsWith("y") && word.length > 1 -> when (word[word.length - 2]) {
                'r', 'ɾ', 'l' -> word.dropLast(1) + "iʲ"
                else -> word.dropLast(1) + "j"
            }
            // So does a final "o", in a word that has a stress of its own.
            word.endsWith("ʊ") && 'ˈ' in word && word[word.length - 2] !in VOWELS -> word.dropLast(1) + "w"
            else -> word
        }
    }

}

private const val NONE = 0
private const val ACUTE = 1
private const val CIRCUMFLEX = 2
private const val TILDE = 3

private enum class Role { CONSONANT, NUCLEUS, OFFGLIDE, ONGLIDE, MUTE, NASAL }

/** A letter or pair of letters that stands for one sound. */
private class Grapheme(val text: String, val base: Char = ' ', val mark: Int = NONE) {
    val vowel: Boolean get() = base != ' '
    var role: Role = if (base != ' ') Role.NUCLEUS else Role.CONSONANT

    /** Followed by an m or n that only colours it ("canto", "bem"). */
    var nasal = false

    /** é rather than ê, ó rather than ô. */
    var open = false

    /** 2 for the stressed syllable, 1 for a lighter beat. */
    var stress = 0

    /** A u that is said where it usually isn't ("aguentar", "tranquilo"). */
    var sounded = false

    /** An i or u kept apart from the vowel before it ("saïr"). */
    var apart = false
}

/** One word on its way from letters to sounds. */
private class Spelling(private val word: String) {
    private val gs = ArrayList<Grapheme>()
    private var nuclei: List<Int> = emptyList()
    private var primary = -1

    fun say(): String {
        read()
        if (gs.none { it.vowel }) return gs.joinToString("") { consonantAlone(it.text) }
        mutes()
        nasals()
        glides()
        nuclei = gs.indices.filter { gs[it].role == Role.NUCLEUS }
        if (nuclei.isEmpty()) {
            // Only a glide for a vowel ("qu"): let it carry the word.
            gs.first { it.vowel }.role = Role.NUCLEUS
            nuclei = gs.indices.filter { gs[it].role == Role.NUCLEUS }
        }
        primary = stressed()
        gs[primary].stress = 2
        quality(gs[primary], primary)
        beats()
        return sounds()
    }

    // ---- letters ----

    private fun read() {
        var i = 0
        val w = word
        while (i < w.length) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            val after = w.getOrNull(i + 2)
            when {
                (c == 'c' || c == 'l' || c == 'n') && next == 'h' -> { gs += Grapheme("$c" + "h"); i += 2 }
                c == 's' && next == 'h' -> { gs += Grapheme("ch"); i += 2 }
                c == 'p' && next == 'h' -> { gs += Grapheme("f"); i += 2 }
                (c == 't' || c == 'r') && next == 'h' -> { gs += Grapheme("$c"); i += 2 }
                c == 'r' && next == 'r' -> { gs += Grapheme("rr"); i += 2 }
                c == 's' && next == 's' -> { gs += Grapheme("ss"); i += 2 }
                c == 's' && next == 'ç' -> { gs += Grapheme("ss"); i += 2 }
                (c == 's' || c == 'x') && next == 'c' && after != null && after in SOFTENING -> { gs += Grapheme("ss"); i += 2 }
                c == 'h' -> { gs += Grapheme("h").also { it.role = Role.MUTE }; i++ }
                c in VOWEL_LETTERS -> { gs += vowel(c); i++ }
                c.isLetter() -> {
                    // A doubled consonant is one sound ("attento", "Anna").
                    if (next == c && c !in "rs") i++
                    gs += Grapheme(c.toString())
                    i++
                }
                else -> i++
            }
        }
    }

    private fun vowel(c: Char): Grapheme = when (c) {
        'a', 'à' -> Grapheme("a", 'a')
        'á' -> Grapheme("a", 'a', ACUTE)
        'â' -> Grapheme("a", 'a', CIRCUMFLEX)
        'ã' -> Grapheme("a", 'a', TILDE)
        'e', 'è', 'ë' -> Grapheme("e", 'e')
        'é' -> Grapheme("e", 'e', ACUTE)
        'ê' -> Grapheme("e", 'e', CIRCUMFLEX)
        'i', 'y', 'ì' -> Grapheme("i", 'i')
        'í', 'ý' -> Grapheme("i", 'i', ACUTE)
        'î' -> Grapheme("i", 'i', CIRCUMFLEX)
        'ï' -> Grapheme("i", 'i').also { it.apart = true }
        'o', 'ò', 'ö' -> Grapheme("o", 'o')
        'ó' -> Grapheme("o", 'o', ACUTE)
        'ô' -> Grapheme("o", 'o', CIRCUMFLEX)
        'õ' -> Grapheme("o", 'o', TILDE)
        'ú' -> Grapheme("u", 'u', ACUTE)
        'û' -> Grapheme("u", 'u', CIRCUMFLEX)
        'ü' -> Grapheme("u", 'u').also { it.sounded = true }
        else -> Grapheme("u", 'u')
    }

    /** The next grapheme that is heard or matters, past a silent h. */
    private fun after(i: Int): Int {
        var k = i + 1
        while (k < gs.size && gs[k].text == "h") k++
        return k
    }

    private fun before(i: Int): Int {
        var k = i - 1
        while (k >= 0 && gs[k].text == "h") k--
        return k
    }

    private fun at(i: Int): Grapheme? = gs.getOrNull(i)

    // ---- which letters are vowels of their own ----

    /** The u of "que", "gui": silent, or a glide ("quando", "água", "aguentar"). */
    private fun mutes() {
        for (i in 1 until gs.size) {
            val g = gs[i]
            if (g.base != 'u' || g.mark != NONE) continue
            val lead = gs[i - 1].text
            val next = at(i + 1)
            if ((lead != "q" && lead != "g") || next == null || !next.vowel) continue
            val front = next.base == 'e' || next.base == 'i'
            g.role = when {
                !front -> Role.ONGLIDE
                g.sounded || PtBrWords.soundedU(word) -> Role.ONGLIDE
                else -> Role.MUTE
            }
        }
    }

    /** An m or n that closes a syllable isn't said for itself: it makes the vowel before it nasal. */
    private fun nasals() {
        for (i in 1 until gs.size) {
            val g = gs[i]
            if (g.role != Role.CONSONANT || (g.text != "m" && g.text != "n")) continue
            val prev = gs[i - 1]
            if (!prev.vowel || prev.role == Role.MUTE) continue
            val next = at(after(i))
            val closes = next == null || (!next.vowel && !(g.text == "m" && next.text == "n"))
            if (closes) {
                g.role = Role.NASAL
                prev.nasal = true
            }
        }
    }

    /** Two vowels in a row: one syllable ("pai", "meu") or two ("país", "saúde", "ruim")? */
    private fun glides() {
        for (i in 1 until gs.size) {
            val b = gs[i]
            val a = gs[i - 1]
            if (!b.vowel || !a.vowel || b.role != Role.NUCLEUS || a.role != Role.NUCLEUS) continue
            // Nasal pairs: mãe, pão, põe, cãibra.
            if (a.mark == TILDE) {
                if (b.mark == NONE && ((a.base == 'a' && b.base in "oei") || (a.base == 'o' && b.base == 'e'))) b.role = Role.OFFGLIDE
                continue
            }
            // caos, aos: a final "ao" is one syllable.
            if (a.base == 'a' && a.mark == NONE && b.base == 'o' && b.mark == NONE && !b.nasal && gs.drop(i + 1).all { it.text == "s" }) {
                b.role = Role.OFFGLIDE
                continue
            }
            if (b.mark != NONE || b.sounded || b.apart || (b.base != 'i' && b.base != 'u') || a.base == b.base) continue
            // reunir: "re" and "unir".
            if (word.startsWith("reun") && i == 2) continue
            val pair = a.base in "aeo" || (a.base == 'u' && b.base == 'i') || (a.base == 'i' && b.base == 'u')
            if (!pair || b.nasal) continue
            val next = at(i + 1)
            val beyond = at(i + 2)
            val apart = when {
                next == null -> false
                next.text == "nh" -> true // rainha, moinho
                // An l, r or z that closes the syllable takes the i or u for itself: cair, juiz, Raul.
                next.text in CLOSERS && (beyond == null || !beyond.vowel && beyond.text != "h") -> true
                // saiu, caiu: the i belongs with the u.
                next.vowel && b.base == 'i' && next.base == 'u' && next.mark == NONE && (beyond == null || beyond.text == "s") -> true
                else -> false
            }
            if (!apart) b.role = Role.OFFGLIDE
        }
    }

    // ---- stress ----

    private fun stressed(): Int {
        nuclei.lastOrNull { gs[it].mark == ACUTE || gs[it].mark == CIRCUMFLEX }?.let { return it }
        val last = nuclei.last()
        if (nuclei.size == 1) return last
        val g = gs[last]
        val tail = gs.subList(last + 1, gs.size).filter { it.text != "h" }
        val glide = tail.firstOrNull()?.takeIf { it.role == Role.OFFGLIDE }
        val closing = tail.filter { it.role != Role.OFFGLIDE }.joinToString("") { it.text }
        val final = when {
            // irmã, coração, põe.
            g.mark == TILDE -> true
            // falei, comeu, partiu, falou, depois.
            glide != null && closing in OPEN_ENDS -> true
            // aqui, caju, jardim, algum, tupis.
            g.base in "iu" && closing in NASAL_ENDS -> true
            // bombom, marrom.
            g.base == 'o' && g.nasal && closing in NASAL_ENDS -> true
            // falar, papel, feliz, tórax without its accent.
            closing in OXYTONE_ENDS -> true
            else -> false
        }
        return if (final) last else nuclei[nuclei.size - 2]
    }

    /** Whether the stressed e or o is open (é, ó) or closed (ê, ô), where no accent says. */
    private fun quality(g: Grapheme, at: Int) {
        if (g.base != 'e' && g.base != 'o') return
        g.open = when {
            g.mark == ACUTE -> true
            g.mark == CIRCUMFLEX || g.mark == TILDE || g.nasal -> false
            else -> {
                val next = at(after(at))
                val nasalNext = next != null && (next.text == "nh" || ((next.text == "m" || next.text == "n") && next.role == Role.CONSONANT))
                // The stressed vowel's letter index in the word, to cut the rime from.
                val rime = rime(at)
                PtBrWords.open(word, g.base, rime, nasalNext, next?.role == Role.OFFGLIDE)
            }
        }
    }

    /** The word from its stressed vowel on, in plain letters: "janela" gives "ela". */
    private fun rime(at: Int): String = gs.subList(at, gs.size).joinToString("") { g ->
        when {
            !g.vowel -> g.text
            g.mark == TILDE -> if (g.base == 'a') "ã" else "õ"
            else -> g.base.toString()
        }
    }

    /** The lighter beats before the stress: on the first syllable of a long word, then every other one. */
    private fun beats() {
        var count = nuclei.size
        val p = nuclei.indexOf(primary)
        // "navio": the "io" is said as one.
        if (endsInIo()) count--
        if (count < 3 || p < 1) return
        gs[nuclei[0]].stress = 1
        var k = 2
        while (k <= p - 2) {
            gs[nuclei[k]].stress = 1
            k += 2
        }
    }

    private fun endsInIo(): Boolean {
        val p = nuclei.indexOf(primary)
        if (gs[primary].base != 'i' || p != nuclei.size - 2) return false
        val o = nuclei.last()
        return o == primary + 1 && finalVowel(o) && gs[o].base == 'o'
    }

    /** A last, unstressed vowel with nothing after it but a plural s. */
    private fun finalVowel(i: Int): Boolean {
        val g = gs[i]
        if (g.role != Role.NUCLEUS || g.stress == 2 || g.nasal || g.mark != NONE || i != nuclei.last()) return false
        val tail = gs.subList(i + 1, gs.size).filter { it.text != "h" }
        return tail.isEmpty() || (tail.size == 1 && tail[0].text == "s")
    }

    // ---- sounds ----

    private val out = StringBuilder()

    /** The vowel sound just written, for the l that may follow it. */
    private var lastVowel = ' '

    private fun sounds(): String {
        for (i in gs.indices) {
            val g = gs[i]
            when (g.role) {
                Role.MUTE -> Unit
                Role.NUCLEUS -> nucleus(i, g)
                Role.OFFGLIDE -> offglide(i, g)
                Role.ONGLIDE -> out.append('w')
                Role.NASAL -> nasalCoda(i)
                Role.CONSONANT -> consonant(i, g)
            }
        }
        return out.toString()
    }

    private fun mark(g: Grapheme) {
        if (g.stress == 2) out.append('ˈ') else if (g.stress == 1) out.append('ˌ')
    }

    private fun nasalOnset(i: Int): Boolean {
        val next = at(after(i)) ?: return false
        return next.role == Role.CONSONANT && (next.text == "m" || next.text == "n")
    }

    private fun nucleus(i: Int, g: Grapheme) {
        val stressed = g.stress == 2
        val post = i > primary
        val next = at(after(i))
        val sound: String = when (g.base) {
            'a' -> when {
                g.mark == TILDE -> "ɐ̃"
                // falam, eram: the unstressed ending of "they".
                g.nasal && !stressed && i == nuclei.last() && at(i + 1)?.text == "m" && i + 1 == gs.lastIndex -> "ɐ̃ʊ̃"
                g.nasal -> "ɐ̃"
                next?.text == "nh" -> "ɐ̃"
                stressed -> if (g.mark != ACUTE && nasalOnset(i)) "ɐ̃" else "a"
                post -> "æ"
                nasalOnset(i) -> "æ"
                else -> "a"
            }
            'e' -> when {
                g.nasal -> "eɪ"
                stressed -> if (g.open) "ɛ" else "e"
                finalVowel(i) -> "y"
                else -> "e"
            }
            'i' -> when {
                g.nasal -> "i"
                next != null && next.role == Role.NUCLEUS -> when {
                    post -> "j"
                    // paciência, ciúme: before a stressed ê or u the i is only a glide.
                    next.stress == 2 && (next.base == 'u' || (next.base == 'e' && !next.open)) -> "j"
                    finalVowel(after(i)) && next.base == 'o' -> "i"
                    else -> "iʲ"
                }
                else -> "i"
            }
            'o' -> when {
                g.mark == TILDE -> "õ"
                g.nasal -> "o"
                stressed -> if (g.open) "ɔ" else "o"
                finalVowel(i) -> "ʊ"
                else -> "o"
            }
            else -> when {
                g.nasal -> "ũ"
                next?.text == "nh" -> "ũ"
                else -> "u"
            }
        }
        // A glide carries no beat of its own.
        if (sound != "j") mark(g)
        out.append(sound)
        lastVowel = sound.last { it != '̃' && it != 'ʲ' }
    }

    private fun offglide(i: Int, g: Grapheme) {
        val lead = gs[i - 1]
        val vowelNext = at(after(i))?.role == Role.NUCLEUS
        val sound = when {
            lead.mark == TILDE && lead.base == 'a' -> if (g.base == 'o') "ʊ̃" else "y"
            lead.mark == TILDE -> "j"
            g.base == 'i' -> if (vowelNext && !(lead.base == 'e' && lead.open)) "ɪʲ" else "ɪ"
            g.base == 'o' -> "ʊ"
            lead.base == 'o' -> "w"
            else -> "ʊ"
        }
        out.append(sound)
    }

    private fun nasalCoda(i: Int) {
        if (out.endsWith("ɐ̃ʊ̃")) return
        val next = at(after(i))
        // "põem", "têm": after a glide the m is simply said.
        if (gs[i - 1].role == Role.OFFGLIDE) {
            out.append(gs[i].text)
            return
        }
        out.append(if (next != null && (next.text == "p" || next.text == "b")) 'm' else 'ŋ')
    }

    private fun vowelLike(g: Grapheme?): Boolean = g != null && g.vowel && g.role != Role.MUTE

    private fun consonant(i: Int, g: Grapheme) {
        val prev = at(before(i))
        val next = at(after(i))
        val nextVowel = vowelLike(next) || (next != null && next.role == Role.MUTE && next.vowel)
        val front = next != null && next.vowel && (next.base == 'e' || next.base == 'i')
        val sound: String = when (g.text) {
            "ch" -> "ʃ"
            "lh" -> "lj"
            "nh" -> "ɲ"
            "rr" -> "x"
            "ss", "ç" -> "s"
            "c" -> if (front) "s" else "k"
            "g" -> if (front) "ʒ" else "ɡ"
            "q", "k" -> "k"
            "j" -> "ʒ"
            "t", "d" -> {
                // "ti" and "di" are "tchi" and "dji"; so is a final, unstressed "te" or "de".
                val palatal = next != null && next.vowel && next.role != Role.MUTE &&
                    (next.base == 'i' || (next.base == 'e' && finalVowel(after(i))))
                if (g.text == "t") (if (palatal) "tʃ" else "t") else (if (palatal) "dʒ" else "d")
            }
            "l" -> when {
                nextVowel || !vowelLike(prev) -> "l"
                // An l that closes a syllable is a "u" in Brazil: "Brasil", "alto", "sol".
                lastVowel == 'ɔ' -> "l"
                lastVowel == 'o' || lastVowel == 'u' || lastVowel == 'ũ' -> "w"
                else -> "ʊ"
            }
            "r" -> when {
                i == 0 || prev == null -> "x"
                !vowelLike(prev) -> if (prev.role == Role.NASAL || prev.text == "l" || prev.text == "s" || prev.text == "z") "x" else "r"
                nextVowel -> "ɾ"
                next == null -> "r"
                else -> "ɾə"
            }
            "s" -> when {
                next == null -> "s"
                nextVowel -> if (vowelLike(prev) || (word.startsWith("trans") && i == 4)) "z" else "s"
                else -> if (next.text in VOICED_LETTERS) "z" else "s"
            }
            "z" -> when {
                next == null -> "s"
                nextVowel -> "z"
                else -> if (next.text in VOICED_LETTERS) "z" else "s"
            }
            "x" -> x(i, prev, next, nextVowel)
            "w" -> if (nextVowel) "w" else "ʊ"
            "ñ" -> "ɲ"
            "h" -> ""
            else -> g.text
        }
        out.append(sound)
    }

    /** The letter of four sounds: "xícara", "exame", "texto", "táxi". */
    private fun x(i: Int, prev: Grapheme?, next: Grapheme?, nextVowel: Boolean): String = when {
        next == null -> "ks"
        !nextVowel -> "s"
        PtBrWords.xAsKs(word) -> "ks"
        PtBrWords.xAsS(word) -> "s"
        i == 0 -> "ʃ"
        prev != null && (prev.role == Role.NASAL || prev.role == Role.OFFGLIDE) -> "ʃ"
        // exame, exemplo, inexato.
        prev != null && prev.base == 'e' && (i == 1 || word.startsWith("inex") || word.startsWith("reex") || word.startsWith("coex")) && i <= 3 -> "z"
        else -> "ʃ"
    }

    /** A word with no vowel to carry it ("pst", "hm"): its letters as they come. */
    private fun consonantAlone(text: String): String = when (text) {
        "ch" -> "ʃ"
        "ss", "ç", "c" -> "s"
        "rr", "r" -> "x"
        "h" -> ""
        "g" -> "ɡ"
        "j" -> "ʒ"
        "q" -> "k"
        "x" -> "ʃ"
        "lh" -> "lj"
        "nh" -> "ɲ"
        else -> text
    }

    private companion object {
        const val VOWEL_LETTERS = "aeiouyàáâãèéêëìíîïòóôõöùúûüý"
        const val SOFTENING = "eiéêíy"
        val CLOSERS = setOf("l", "r", "z")
        val OPEN_ENDS = setOf("", "s")
        val NASAL_ENDS = setOf("", "s", "m", "n", "ns", "ms")
        val OXYTONE_ENDS = setOf("r", "l", "z", "x")
        val VOICED_LETTERS = setOf("b", "d", "g", "v", "z", "j", "m", "n", "l", "r", "rr", "lh", "nh")
    }
}
