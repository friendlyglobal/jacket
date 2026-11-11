package io.github.friendlyglobal.jacket.voice.ptbr

import java.text.Normalizer

/**
 * Written Portuguese made ready to be said: numbers, dates, money, the time of day and common
 * abbreviations become the words a Brazilian would say ("R$ 20,50" is "vinte reais e cinquenta
 * centavos", "Sr." is "senhor", "século XIX" is "século dezenove"), and the text is cut into words
 * and pauses.
 */
internal object PtBrText {
    sealed interface Token {
        /** A word, which may hold hyphens or an apostrophe ("disse-lhe", "d'água"). */
        data class Word(val text: String) : Token

        /** A pause, by the punctuation that makes it: one of , ; : . ! ? */
        data class Pause(val mark: Char) : Token
    }

    fun tokens(text: String, oldSpelling: Boolean = false): List<Token> {
        val plain = expand(Normalizer.normalize(text, Normalizer.Form.NFC))
        val out = ArrayList<Token>()
        var i = 0
        while (i < plain.length) {
            val c = plain[i]
            when {
                c.isLetter() -> {
                    var end = i + 1
                    while (end < plain.length) {
                        val d = plain[end]
                        val joins = (d == '-' || d == '\'' || d == '’') && end + 1 < plain.length && plain[end + 1].isLetter()
                        if (d.isLetter() || joins) end++ else break
                    }
                    words(plain.substring(i, end), oldSpelling, out)
                    i = end
                }
                c == '.' || c == '…' -> { out += Token.Pause('.'); i++ }
                c == '!' || c == '?' || c == ',' || c == ';' || c == ':' -> { out += Token.Pause(c); i++ }
                // A dash or a bracket is a short breath.
                c == '—' || c == '–' || c == '(' || c == ')' || c == '[' || c == ']' -> { out += Token.Pause(','); i++ }
                c == '-' && (i == 0 || plain[i - 1].isWhitespace()) -> { out += Token.Pause(','); i++ }
                else -> i++
            }
        }
        return out
    }

    /** A word as written, made one or more words to say: capitals spelt out, old spelling brought up to date. */
    private fun words(written: String, oldSpelling: Boolean, out: MutableList<Token>) {
        val letters = written.filter { it.isLetter() }
        // "IBGE", "CPF": capitals with no vowel to hang a word on are said letter by letter.
        if (letters.length in 2..5 && letters.all { it.isUpperCase() } && letters.none { it in "AEIOUÁÉÍÓÚÂÊÔÃÕ" }) {
            letters.forEach { out += Token.Word(letterName(it)) }
            return
        }
        // A letter on its own that isn't a word ("o", "a", "e" are) is said by its name.
        if (letters.length == 1 && letters[0].lowercaseChar() in "bcdfghjklmnpqrstuvwxyz") {
            out += Token.Word(letterName(letters[0]))
            return
        }
        out += Token.Word(if (oldSpelling) PtBrOldSpelling.modern(written.lowercase()) else written.lowercase())
    }

    private val letterNames = mapOf(
        'a' to "ah", 'b' to "bê", 'c' to "cê", 'd' to "dê", 'e' to "éh", 'f' to "éfe", 'g' to "gê", 'h' to "agá", 'i' to "íh",
        'j' to "jóta", 'k' to "cá", 'l' to "éle", 'm' to "ême", 'n' to "êne", 'o' to "óh", 'p' to "pê", 'q' to "quê",
        'r' to "érre", 's' to "ésse", 't' to "tê", 'u' to "úh", 'v' to "vê", 'w' to "dáblio", 'x' to "xis", 'y' to "ípsilon",
        'z' to "zê",
    )

    fun letterName(letter: Char): String = letterNames[letter.lowercaseChar()] ?: letter.lowercase()

    // ---- reading out what isn't written in words ----

    private val months = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    private const val MONTHS = "janeiro|fevereiro|março|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro"

    /** Titles and the like, always followed by more of the sentence. */
    private val titles = listOf(
        "Srta" to "senhorita", "Sras" to "senhoras", "Srs" to "senhores", "Snra" to "senhora", "Snr" to "senhor", "Sra" to "senhora",
        "Sr" to "senhor", "Dras" to "doutoras", "Drs" to "doutores", "Dra" to "doutora", "Dr" to "doutor", "Profa" to "professora",
        "Prof" to "professor", "Exmo" to "excelentíssimo", "Exma" to "excelentíssima", "Ilmo" to "ilustríssimo",
        "Ilma" to "ilustríssima", "Revmo" to "reverendíssimo", "Pe" to "padre", "Fr" to "frei", "Sto" to "santo", "Sta" to "santa",
        "Mme" to "madame", "Mlle" to "mademoiselle", "Mrs" to "mrs", "Mr" to "mr", "Cel" to "coronel", "Gen" to "general",
        "Cap" to "capitão", "Ten" to "tenente", "Sarg" to "sargento", "Maj" to "major", "Cons" to "conselheiro",
        "Com" to "comendador", "Dep" to "deputado", "Sen" to "senador", "Gov" to "governador", "Pres" to "presidente",
        "Eng" to "engenheiro", "Av" to "avenida",
    )

    /** Abbreviations that may end a sentence: their full stop can be the sentence's too. */
    private val shortened = listOf(
        "etc" to "et cétera", "Ltda" to "limitada", "Cia" to "companhia", "Jr" to "júnior", "págs" to "páginas", "pág" to "página",
        "cap" to "capítulo", "vol" to "volume", "obs" to "observação", "tel" to "telefone", "séc" to "século", "art" to "artigo",
        "fls" to "folhas", "fl" to "folha",
    )

    private val feminineNames = setOf(
        "inês", "ignez", "isabel", "beatriz", "leonor", "carmen", "raquel", "rachel", "ester", "esther", "judite", "alice",
        "irene", "marie", "luíse", "matilde", "mathilde", "clotilde", "adelaide", "berenice", "eunice", "dolores", "mercedes",
        "glória", "gloria", "lourdes", "iracy", "nair", "ruth", "rute", "liz", "mariquinhas", "sancha", "capitu",
    )

    private fun feminineName(name: String): Boolean {
        val n = name.lowercase()
        return n in feminineNames || (n.endsWith("a") && n !in setOf("luca", "josua", "garcia", "costa", "silva", "sousa", "souza"))
    }

    /** Things counted in the feminine, so "2 horas" is "duas horas". */
    private val feminineNouns = setOf(
        "hora", "horas", "vez", "vezes", "página", "páginas", "semana", "semanas", "pessoa", "pessoas", "mulher", "mulheres",
        "casa", "casas", "libra", "libras", "légua", "léguas", "coisa", "coisas", "cousa", "cousas", "moça", "moças", "menina",
        "meninas", "filha", "filhas", "irmã", "irmãs", "noite", "noites", "tarde", "tardes", "manhã", "manhãs", "palavra",
        "palavras", "carta", "cartas", "linha", "linhas", "milha", "milhas", "arroba", "arrobas", "onça", "onças", "senhora",
        "senhoras", "criança", "crianças", "creança", "creanças", "mão", "mãos", "lágrima", "lágrimas", "viagem", "viagens",
        "patacas", "moedas", "garrafas", "braças", "varas", "polegadas", "toneladas", "gramas", "dúzias", "mil",
    )

    private fun number(digits: String, feminine: Boolean = false): String {
        val plain = digits.replace(".", "")
        // A year or a count is said whole; a long string of digits one by one.
        return if (plain.length > 15 || (plain.length > 1 && plain.startsWith("0"))) PtBrNumbers.digits(plain) else PtBrNumbers.cardinal(plain.toLong(), feminine)
    }

    private fun money(whole: String, cents: String?, one: String, many: String, cent: String = "centavo"): String {
        val n = whole.replace(".", "").toLongOrNull() ?: return whole
        val c = cents?.padEnd(2, '0')?.take(2)?.toIntOrNull() ?: 0
        val out = ArrayList<String>()
        if (n > 0 || c == 0) {
            val millions = n >= 1_000_000 && n % 1_000_000 == 0L
            out += PtBrNumbers.cardinal(n) + (if (millions) " de " else " ") + (if (n == 1L) one else many)
        }
        if (c > 0) out += PtBrNumbers.cardinal(c.toLong()) + " " + cent + (if (c == 1) "" else "s")
        return out.joinToString(" e ")
    }

    private fun romanSaid(numeral: String, feminine: Boolean): String? {
        val n = PtBrNumbers.roman(numeral) ?: return null
        // Kings, popes, centuries and chapters: "primeiro" to "décimo", then plain numbers.
        return if (n <= 10) PtBrNumbers.ordinal(n, feminine) else PtBrNumbers.cardinal(n.toLong())
    }

    private const val L = "\\p{L}"
    private const val NUM = "\\d{1,3}(?:\\.\\d{3})+|\\d+"

    private val rules: List<Pair<Regex, (MatchResult) -> String>> = buildList {
        fun rule(pattern: String, option: RegexOption? = null, replace: (MatchResult) -> String) {
            add((if (option != null) Regex(pattern, option) else Regex(pattern)) to replace)
        }
        // Spaces of every kind are one space; quotes of every kind are dropped by the tokenizer.
        rule("[\\u00A0\\u2000-\\u200B\\u202F\\u2060\\uFEFF]") { " " }
        rule("\\u00AD") { "" }

        // "V. Exa.", "S. M.": forms of address.
        rule("(?<![$L])V\\. ?Ex(?:a|\\.ª|c)?\\.?") { "vossa excelência" }
        rule("(?<![$L])S\\. ?Ex(?:a|\\.ª|c)?\\.?") { "sua excelência" }
        rule("(?<![$L])V\\. ?S(?:a|\\.ª)?\\.") { "vossa senhoria" }
        rule("(?<![$L])V\\. ?M\\.") { "vossa majestade" }
        rule("(?<![$L])S\\. ?M\\.") { "sua majestade" }
        rule("(?<![$L])V\\. ?A\\.") { "vossa alteza" }
        rule("(?<![$L])p\\. ?ex\\.") { "por exemplo" }
        rule("(?<![$L])a\\. ?C\\.") { "antes de Cristo" }
        rule("(?<![$L])d\\. ?C\\.") { "depois de Cristo" }
        rule("(?<![$L])[Nn]\\.? ?[º°o](?= ?\\d)") { "número " }
        // "D. Pedro" is dom; "D. Maria" is dona. "S. Paulo" is são; "S. Antônio" is santo.
        rule("(?<![$L])D\\. ?(?=([A-ZÀ-Ý][$L]+))") { m -> if (feminineName(m.groupValues[1])) "dona " else "dom " }
        rule("(?<![$L])S\\. ?(?=([A-ZÀ-Ý][$L]+))") { m ->
            val name = m.groupValues[1]
            when {
                feminineName(name) -> "santa "
                name.first().lowercaseChar() in "aeiouáéíóúâêôh" -> "santo "
                else -> "são "
            }
        }
        for ((short, long) in titles) rule("(?<![$L])$short\\.(?![$L])") { long }
        // "B. L. Garnier": an initial is said as its letter, and its full stop ends nothing.
        rule("(?<![$L])([A-Z])\\.(?=\\s*[A-ZÀ-Ý])") { m -> letterName(m.groupValues[1][0]) }
        for ((short, long) in shortened) rule("(?<![$L])$short\\.(?![$L])(?=(\\s*(?:[A-ZÀ-Ý]|$))?)", RegexOption.IGNORE_CASE) { m ->
            // Kept as the sentence's full stop if a new sentence follows.
            if (m.groups[1] != null) "$long." else long
        }

        // Money. Old Brazilian books count in réis: "200$000" is two hundred thousand of them, "5:000$000" five contos.
        rule("($NUM):(\\d{3})\\$(\\d{3})") { m ->
            val contos = m.groupValues[1].replace(".", "").toLong()
            val thousands = m.groupValues[2].toInt()
            val units = m.groupValues[3].toInt()
            val out = ArrayList<String>()
            out += PtBrNumbers.cardinal(contos) + (if (contos == 1L) " conto" else " contos")
            if (thousands > 0 || units > 0) out += PtBrNumbers.cardinal(thousands * 1000L + units) + " réis" else out[0] = out[0] + " de réis"
            out.joinToString(" e ")
        }
        rule("(\\d{1,3})\\$(\\d{3})") { m ->
            val reis = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toInt()
            PtBrNumbers.cardinal(reis) + " réis"
        }
        rule("R\\$ ?($NUM)(?:,(\\d{1,2}))?") { m -> money(m.groupValues[1], m.groups[2]?.value, "real", "reais") }
        rule("(?:US|U)\\$ ?($NUM)(?:[,.](\\d{2}))?") { m -> money(m.groupValues[1], m.groups[2]?.value, "dólar", "dólares") }
        rule("€ ?($NUM)(?:,(\\d{1,2}))?") { m -> money(m.groupValues[1], m.groups[2]?.value, "euro", "euros", "cêntimo") }
        rule("($NUM)(?:,(\\d{1,2}))? ?€") { m -> money(m.groupValues[1], m.groups[2]?.value, "euro", "euros", "cêntimo") }
        rule("£ ?($NUM)") { m -> money(m.groupValues[1], null, "libra", "libras") }
        rule("\\$ ?($NUM)(?:[,.](\\d{2}))?") { m -> money(m.groupValues[1], m.groups[2]?.value, "dólar", "dólares") }

        // Dates: "12/03/1999", "1º de março".
        rule("(?<!\\d)(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})(?!\\d)") { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            if (month !in 1..12 || day !in 1..31) return@rule m.value
            val year = m.groupValues[3]
            (if (day == 1) "primeiro" else PtBrNumbers.cardinal(day.toLong())) + " de " + months[month - 1] + " de " + number(year)
        }
        rule("(?<!\\d)1(?:\\.?[º°])? de (?=$MONTHS)", RegexOption.IGNORE_CASE) { "primeiro de " }

        // The time of day: "14h30", "8h", "14:30".
        rule("(?<!\\d)(\\d{1,2}) ?h ?(\\d{2})(?:min)?(?![$L\\d])") { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            hours(h) + (if (min > 0) " e " + PtBrNumbers.cardinal(min.toLong()) else "")
        }
        rule("(?<!\\d)(\\d{1,2}) ?hs?(?![$L\\d])") { m -> hours(m.groupValues[1].toInt()) }
        rule("(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])") { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            hours(h) + (if (min > 0) " e " + PtBrNumbers.cardinal(min.toLong()) else "")
        }

        // "século XIX", "capítulo XII", "Pedro II".
        rule("(?<![$L])(capítulo|capitulo|século|seculo|tomo|livro|volume|ato|acto|canto|título|titulo|artigo|ano)( +)([IVXLCDM]+)(?![$L])", RegexOption.IGNORE_CASE) { m ->
            romanSaid(m.groupValues[3], false)?.let { m.groupValues[1] + " " + it } ?: m.value
        }
        rule("(?<![$L])(parte|cena|scena|seção|secção|lição|carta|estância|estancia)( +)([IVXLCDM]+)(?![$L])", RegexOption.IGNORE_CASE) { m ->
            romanSaid(m.groupValues[3], true)?.let { m.groupValues[1] + " " + it } ?: m.value
        }
        rule("(?<![$L])([A-ZÀ-Ý][$L]+) (I{2,3}|IV|VI{0,3}|IX|XI{0,3}|XI?V|XVI{0,3}|XI?X|XXI{0,3})(?![$L])") { m ->
            romanSaid(m.groupValues[2], feminineName(m.groupValues[1]))?.let { m.groupValues[1] + " " + it } ?: m.value
        }
        rule("(?<![$L])(Pedro|João|Luís|Luiz|Carlos|Henrique|Afonso|Manuel|José|Leão|Pio|Bento|Paulo|Napoleão|Alexandre) I(?![$L])") { m ->
            m.groupValues[1] + " primeiro"
        }
        rule("(?<![$L])(Maria|Isabel|Vitória|Catarina|Elizabeth|Joana|Ana) I(?![$L])") { m -> m.groupValues[1] + " primeira" }
        // A line that is nothing but a Roman numeral: a chapter's number.
        rule("^\\s*([IVXLCDM]+)\\s*\\.?\\s*$") { m -> PtBrNumbers.roman(m.groupValues[1])?.let { PtBrNumbers.cardinal(it.toLong()) + "." } ?: m.value }

        // "1º", "2ª", "3.º".
        rule("(\\d+)\\.?ª") { m -> PtBrNumbers.ordinal(m.groupValues[1].toInt(), true) }
        rule("(\\d+)\\.?[º°](?! ?[CF](?![$L]))") { m -> PtBrNumbers.ordinal(m.groupValues[1].toInt(), false) }

        // Measures after a number.
        rule("(-?\\d+(?:,\\d+)?) ?°C(?![$L])") { m -> m.groupValues[1] + " graus celsius" }
        rule("(\\d+(?:,\\d+)?) ?%") { m -> m.groupValues[1] + " por cento" }
        for ((unit, one, many) in listOf(
            Triple("km", "quilômetro", "quilômetros"), Triple("kg", "quilo", "quilos"), Triple("cm", "centímetro", "centímetros"),
            Triple("mm", "milímetro", "milímetros"), Triple("ml", "mililitro", "mililitros"), Triple("m", "metro", "metros"),
            Triple("g", "grama", "gramas"), Triple("l", "litro", "litros"),
        )) rule("(?<![\\d,.])(\\d+(?:,\\d+)?) ?$unit(?![$L\\d])") { m -> m.groupValues[1] + " " + (if (m.groupValues[1] == "1") one else many) }

        // A telephone number is said digit by digit.
        rule("(?<![\\d$L])(\\d{4,5})-(\\d{4})(?![\\d$L])") { m -> PtBrNumbers.digits(m.groupValues[1]) + ", " + PtBrNumbers.digits(m.groupValues[2]) }

        // Plain numbers: "1.234", "3,5", "1899".
        rule("(?<![\\d$L])($NUM),(\\d+)(?![\\d$L])") { m ->
            val decimals = m.groupValues[2]
            number(m.groupValues[1]) + " vírgula " + (if (decimals.startsWith("0") && decimals.length > 1) PtBrNumbers.digits(decimals) else number(decimals))
        }
        rule("(?<![\\d$L])($NUM)(?![\\d])( +[$L]+)?") { m ->
            val noun = m.groups[2]?.value.orEmpty()
            number(m.groupValues[1], noun.trim().lowercase() in feminineNouns) + noun
        }
        rule("\\d+") { m -> " " + PtBrNumbers.digits(m.value) + " " }

        // Signs.
        rule(" ?& ?") { " e " }
        rule(" ?\\+ ?") { " mais " }
        rule(" ?= ?") { " igual a " }
        rule("§ ?") { "parágrafo " }
        rule("@") { " arroba " }
        rule("%") { " por cento" }
        rule("°") { " graus" }
        rule("(?<=[$L])/(?=[$L])") { " ou " }
    }

    private fun hours(h: Int): String = when (h) {
        0 -> "zero hora"
        1 -> "uma hora"
        else -> PtBrNumbers.cardinal(h.toLong(), feminine = true) + " horas"
    }

    /** [text] with everything that isn't a word written out as one. */
    fun expand(text: String): String {
        var out = text
        for ((pattern, replace) in rules) {
            if (pattern.containsMatchIn(out)) out = pattern.replace(out, replace)
        }
        return out
    }
}
