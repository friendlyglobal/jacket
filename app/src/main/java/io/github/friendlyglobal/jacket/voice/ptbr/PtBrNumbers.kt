package io.github.friendlyglobal.jacket.voice.ptbr

/** Numbers said in Portuguese: "1899" is "mil oitocentos e noventa e nove", "2ª" is "segunda". */
internal object PtBrNumbers {
    private val units = listOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze", "treze",
        "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
    )
    private val tens = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val hundreds = listOf(
        "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos",
    )
    private val scales = listOf("" to "", "mil" to "mil", "milhão" to "milhões", "bilhão" to "bilhões", "trilhão" to "trilhões")

    private val ordinalUnits = listOf("", "primeiro", "segundo", "terceiro", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono")
    private val ordinalTens = listOf(
        "", "décimo", "vigésimo", "trigésimo", "quadragésimo", "quinquagésimo", "sexagésimo", "septuagésimo", "octogésimo", "nonagésimo",
    )
    private val ordinalHundreds = listOf(
        "", "centésimo", "ducentésimo", "trecentésimo", "quadringentésimo", "quingentésimo", "sexcentésimo", "septingentésimo",
        "octingentésimo", "noningentésimo",
    )

    /** Up to 999, with "cem" for a round hundred. */
    private fun below1000(n: Int, feminine: Boolean): String {
        if (n == 100) return "cem"
        val parts = ArrayList<String>()
        val h = n / 100
        val rest = n % 100
        if (h > 0) parts += if (feminine && h > 1) hundreds[h].dropLast(2) + "as" else hundreds[h]
        if (rest in 1..19) {
            parts += when {
                feminine && rest == 1 -> "uma"
                feminine && rest == 2 -> "duas"
                else -> units[rest]
            }
        } else if (rest >= 20) {
            parts += tens[rest / 10]
            val u = rest % 10
            if (u > 0) parts += if (feminine && u == 1) "uma" else if (feminine && u == 2) "duas" else units[u]
        }
        return parts.joinToString(" e ")
    }

    /** [n] in words; [feminine] for "uma", "duas", "duzentas". */
    fun cardinal(n: Long, feminine: Boolean = false): String {
        if (n < 0) return "menos " + cardinal(-n, feminine)
        if (n == 0L) return "zero"
        if (n >= 1_000_000_000_000_000L) return digits(n.toString())
        val groups = ArrayList<Int>()
        var left = n
        while (left > 0) {
            groups += (left % 1000).toInt()
            left /= 1000
        }
        val said = ArrayList<Pair<Int, String>>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            val words = when {
                i == 0 -> below1000(g, feminine)
                i == 1 -> if (g == 1) "mil" else below1000(g, feminine) + " mil"
                else -> below1000(g, false) + " " + (if (g == 1) scales[i].first else scales[i].second)
            }
            said += g to words
        }
        val out = StringBuilder()
        said.forEachIndexed { i, (group, words) ->
            if (i > 0) {
                // "mil e duzentos", "mil e um", but "mil duzentos e cinquenta".
                out.append(if (i == said.lastIndex && (group <= 100 || group % 100 == 0)) " e " else " ")
            }
            out.append(words)
        }
        return out.toString()
    }

    /** "primeiro", "vigésima segunda"; past 999 it falls back to the plain number. */
    fun ordinal(n: Int, feminine: Boolean = false): String {
        if (n <= 0 || n > 1000) return cardinal(n.toLong(), feminine)
        if (n == 1000) return if (feminine) "milésima" else "milésimo"
        val parts = listOf(ordinalHundreds[n / 100], ordinalTens[n / 10 % 10], ordinalUnits[n % 10]).filter { it.isNotEmpty() }
        return parts.joinToString(" ") { if (feminine) it.dropLast(1) + "a" else it }
    }

    /** Each digit on its own: for telephone numbers and the like. */
    fun digits(text: String): String = text.filter { it.isDigit() }.map { units[it - '0'] }.joinToString(" ")

    private val roman = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

    /** "XIV" is 14; null for what isn't a Roman numeral. */
    fun roman(text: String): Int? {
        if (text.isEmpty() || !roman.matches(text)) return null
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in text.indices) {
            val v = values.getValue(text[i])
            val next = text.getOrNull(i + 1)?.let(values::getValue) ?: 0
            total += if (v < next) -v else v
        }
        return total
    }
}
