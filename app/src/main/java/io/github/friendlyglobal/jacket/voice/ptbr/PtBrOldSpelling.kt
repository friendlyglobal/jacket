package io.github.friendlyglobal.jacket.voice.ptbr

/**
 * Portuguese as it was spelt before the reforms of the twentieth century ("elle", "theatro",
 * "pharmacia", "hontem", "titulo" with no accent), brought up to today's spelling so it is said
 * right. Free editions of Machado de Assis, Alencar and their time are mostly in the old spelling:
 * its doubled and silent letters matter little to the ear, but its missing accents move the
 * stress ("titulo" read by today's rules is "tiTUlo").
 *
 * Three things are done, in order: the letters are brought up to date; a word known to carry an
 * accent today gets it back (see [PtBrAccents]); and failing that, endings that all but always
 * carry one get it ("-ario" is "-ário", "-avel" is "-ável", "-ico" is stressed two syllables back).
 */
internal object PtBrOldSpelling {
    private val word = Regex("[\\p{L}]+(?:['’][\\p{L}]+)?")

    /** Words only the old spelling has. */
    private val telling = setOf(
        "elle", "ella", "elles", "ellas", "aquelle", "aquella", "aquelles", "aquellas", "delle", "della", "nelle", "nella",
        "á", "ás", "hontem", "ahi", "dahi", "alli", "dalli", "pae", "mãi", "céo", "quasi", "vae", "sahir", "sahiu", "cahir",
        "commigo", "comsigo", "emquanto", "emfim", "tambem", "ninguem", "d'elle", "d'ella", "n'um", "n'uma",
    )
    private val oldLetters = Regex("ph|th|ll|tt|pp|ff|mm|nn|\\Bct|[a-zà-ÿ]y[a-zà-ÿ]")

    /**
     * Whether [text] is in the old spelling: a word only it has, or two with its letters. Names
     * are passed over: a Thiago or a Raphaella is spelt so to this day.
     */
    fun looksOld(text: String): Boolean {
        var letters = 0
        for (match in word.findAll(text)) {
            val w = match.value
            if (w.lowercase() in telling) return true
            // English words in a modern book have such letters too ("shopping", "happy"): not those.
            val foreign = w.endsWith("ing") || w.endsWith("y") || 'k' in w || 'w' in w
            if (w.first().isLowerCase() && !foreign && oldLetters.containsMatchIn(w) && ++letters >= 2) return true
        }
        return false
    }

    /** Whole words that changed. */
    private val words = mapOf(
        "hum" to "um", "huma" to "uma", "hums" to "uns", "humas" to "umas", "quasi" to "quase", "veiu" to "veio", "pateo" to "pátio",
        "heroe" to "herói", "poz" to "pôs", "pôz" to "pôs", "tres" to "três", "tambem" to "também", "ninguem" to "ninguém",
        "alguem" to "alguém", "porem" to "porém", "alem" to "além", "aquem" to "aquém", "armazem" to "armazém", "vintem" to "vintém",
        "vintens" to "vinténs", "refem" to "refém", "desdem" to "desdém", "parabens" to "parabéns", "harem" to "harém",
        "recem" to "recém", "mantem" to "mantém", "obtem" to "obtém", "detem" to "detém", "retem" to "retém", "convem" to "convém",
        "provem" to "provém", "intervem" to "intervém", "contem" to "contém", "hontem" to "ontem", "egual" to "igual",
        "eguaes" to "iguais", "egualmente" to "igualmente", "creança" to "criança", "creanças" to "crianças", "edade" to "idade",
        "edades" to "idades", "egreja" to "igreja", "egrejas" to "igrejas", "logar" to "lugar", "logares" to "lugares",
        "sinão" to "senão", "similhante" to "semelhante", "similhantes" to "semelhantes", "ceu" to "céu", "ceus" to "céus",
        "chapeu" to "chapéu", "chapeus" to "chapéus", "reu" to "réu", "voce" to "você", "voces" to "vocês", "mãi" to "mãe",
        "mãis" to "mães", "meo" to "meu", "teo" to "teu", "seo" to "seu", "meos" to "meus", "teos" to "teus", "seos" to "seus",
        "deo" to "deu", "eo" to "eu", "judeo" to "judeu", "europeo" to "europeu", "museo" to "museu", "atheo" to "ateu",
        "plebeo" to "plebeu", "hebreo" to "hebreu", "idea" to "ideia", "ideas" to "ideias", "aldea" to "aldeia", "aldeas" to "aldeias",
        "assemblea" to "assembleia", "platea" to "plateia", "europea" to "europeia", "cadea" to "cadeia", "chea" to "cheia",
        "peor" to "pior", "deante" to "diante", "socego" to "sossego", "geito" to "jeito", "agoa" to "água", "agoas" to "águas",
        "lingoa" to "língua", "joven" to "jovem", "hade" to "há de", "cincoenta" to "cinquenta", "extrangeiro" to "estrangeiro",
        "caracter" to "caráter", "snr" to "senhor", "snra" to "senhora", "papae" to "papai", "mamãi" to "mamãe", "fóra" to "fora",
        "dôr" to "dor", "flôr" to "flor", "amôr" to "amor", "côr" to "cor", "fôr" to "for", "sôbre" to "sobre", "devéras" to "deveras",
        "anecdota" to "anedota", "anecdotas" to "anedotas", "tinhamos" to "tínhamos", "vinhamos" to "vínhamos", "eramos" to "éramos", "iamos" to "íamos", "juizo" to "juízo",
    )

    /** Greek and Latin words whose "ch" was a k: "christão", "orchestra", "machina". */
    private val hardCh = Regex(
        "chr|chl|^arch|monarch|triarch|anarch|ierarch|oligarch|orchest|machin|chimic|chimer|cherub|eschol|eschem|psych|techn|" +
            "mechan|anchol|^choler|chaos|epocha|stomach|^echo|bacch|synchr|charact|aristarch|plutarch|zachar|melchior",
    )

    /** Words that keep their "ct" to this day. */
    private val keepCt = listOf(
        "pact", "aspect", "convict", "invict", "nectar", "néctar", "bacter", "caract", "lact", "detect", "infect", "espectr",
        "espectad", "perspect", "conect", "autoct", "octo", "octa", "fict", "veredict", "intelect", "respectiv", "expect",
    )

    /** Words whose "pt" or "pç" lost its p. */
    private val dropP = listOf(
        "script", "scripç", "baptis", "baptiz", "captiv", "egypt", "egipt", "septim", "sept", "excepto", "excepç", "adopç",
        "adopt", "optim", "óptim", "assumpç", "esculpt",
    )
    private val keepCc = listOf("ficç", "convicç", "fricç", "sucç", "dicç", "facç", "infecç", "confecç")

    private val vowels = "aeiouáàâãéêíóôõúïü"

    /** [written] ("tiral-os", "d'elle", "theatro") in today's spelling ("tirá-los", "d'ele", "teatro"). */
    fun modern(written: String): String {
        // "tiral-os" is "tirá-los": the verb's r became an l and leaned on the pronoun.
        enclitic.matchEntire(written)?.let { m ->
            val (stem, vowel, pronoun) = m.destructured
            val accented = when (vowel) { "a" -> "á"; "e" -> "ê"; "o" -> "ô"; else -> vowel }
            return letters(stem) + accented + "-l" + pronoun
        }
        return written.split('-').joinToString("-") { part ->
            val cut = part.indexOfFirst { it == '\'' || it == '’' }
            if (cut in 0 until part.lastIndex) part.substring(0, cut + 1) + single(part.substring(cut + 1)) else single(part)
        }
    }

    private val enclitic = Regex("(.*[^aeiou])([aeiou])l-([oa]s?)")

    private fun single(word: String): String {
        words[word]?.let { return it }
        val spelt = letters(word)
        words[spelt]?.let { return it }
        // An accent of the old spelling on a word that "-mente" or "-zinho" was added to: gone today.
        if (spelt.length >= 7 && (spelt.endsWith("mente") || spelt.endsWith("zinho") || spelt.endsWith("zinha"))) {
            val head = spelt.dropLast(5)
            if (head.any { it in "áéíóúàèòâêô" }) return PtBrAccents.bare(head.replace('à', 'a').replace('è', 'e').replace('ò', 'o')) + spelt.takeLast(5)
        }
        if (spelt.any { it in "áéíóúâêô" }) return spelt
        PtBrAccents.restore[spelt]?.let { return it }
        return byEnding(spelt) ?: spelt
    }

    /** The old letters, one rule at a time. */
    private fun letters(word: String): String {
        var w = word
        if (hardCh.containsMatchIn(w)) w = w.replace(Regex("ch(?=[ei])"), "qu").replace("ch", "c")
        // "áquelle" is today's "àquele": its accent was never a stress.
        if (w.startsWith("áquel") || w.startsWith("áquil")) w = "a" + w.drop(1)
        w = w.replace("ph", "f").replace("th", "t").replace("rh", "r").replace('y', 'i')
        // An h between vowels kept them apart: "sahir", "ahi", "bahú".
        w = Regex("(?<=[$vowels])h([$vowels])").replace(w) { m ->
            when (val vowel = m.groupValues[1]) {
                "i" -> "ï"
                "u" -> "ü"
                else -> vowel
            }
        }
        if (keepCc.none { it in w }) w = w.replace("cç", "ç")
        w = Regex("([bcdfglmnpt])\\1").replace(w) { it.groupValues[1] }
        w = w.replace(Regex("^sc(?=[eiéêí])"), "c")
        if ("amnes" !in w && "amnés" !in w && "mnem" !in w) w = w.replace("mn", "n")
        w = w.replace("augment", "aument").replace("signal", "sinal").replace("signae", "sinae").replace("assign", "assin").replace("ignaci", "inaci")
        w = w.replace("nct", "nt").replace("mpt", "nt").replace("mpç", "nç")
        if (keepCt.none { it in w }) w = w.replace(Regex("(?<=[$vowels])ct(?=[$vowels])"), "t")
        if (dropP.any { it in w }) w = w.replace("pt", "t").replace("pç", "ç")
        // "emfim", "comtudo", "circumstancia": an m before any consonant but p and b is an n today.
        w = w.replace(Regex("m(?=[cdfgjklqrstvxz])"), "n")
        w = w.replace(Regex("(?<![ãõ])aes$"), "ais").replace(Regex("(?<![ãõ])ae$"), "ai")
        w = w.replace(Regex("(?<![ãõ])oes$"), "óis").replace(Regex("éo(s?)$"), "éu$1")
        w = w.replace(Regex("éa(s?)$"), "éia$1").replace(Regex("êa(s?)$"), "eia$1")
        return w
    }

    // ---- endings that all but always carry an accent ----

    /** The accent a stressed [vowel] takes: closed (â, ê, ô) before an m or n, as Brazilians say it. */
    private fun accented(vowel: Char, next: Char?): Char = when (vowel) {
        'a' -> if (next == 'm' || next == 'n') 'â' else 'á'
        'e' -> if (next == 'm' || next == 'n') 'ê' else 'é'
        'o' -> if (next == 'm' || next == 'n') 'ô' else 'ó'
        'i', 'ï' -> 'í'
        'u', 'ü' -> 'ú'
        else -> vowel
    }

    /** [word] with an accent on the vowel at [index]. */
    private fun accent(word: String, index: Int): String =
        word.substring(0, index) + accented(word[index], word.getOrNull(index + 1)) + word.substring(index + 1)

    /** Endings stressed on their first vowel, with the words that aren't. */
    private val endings: List<Pair<String, Set<String>>> = listOf(
        "ario" to setOf("desvario", "casario"),
        "orio" to setOf("senhorio"),
        "icio" to emptySet(), "icia" to emptySet(),
        "acio" to emptySet(),
        "acia" to setOf("bacia", "diplomacia", "supremacia", "advocacia", "primacia"),
        "ocio" to emptySet(), "ucia" to emptySet(),
        "encia" to setOf("convencia", "pertencia", "vencia"), "ancia" to emptySet(), "uncia" to emptySet(), "incia" to emptySet(),
        "encio" to emptySet(), "ancio" to emptySet(), "uncio" to emptySet(), "oncio" to emptySet(),
        "cipio" to emptySet(),
        "ilio" to emptySet(), "ilia" to setOf("homilia", "filia"),
        "elio" to emptySet(), "elia" to emptySet(), "ulio" to emptySet(), "ulia" to emptySet(), "olio" to emptySet(),
        "onio" to emptySet(), "monia" to setOf("harmonia", "pneumonia", "hegemonia"),
        "enio" to emptySet(), "enia" to emptySet(), "odio" to setOf("explodio"), "onimo" to emptySet(), "animo" to emptySet(),
        "ografo" to emptySet(), "ologo" to emptySet(), "alogo" to emptySet(), "ilogo" to emptySet(), "ometro" to emptySet(),
        "ametro" to emptySet(), "imetro" to emptySet(), "onomo" to emptySet(), "osofo" to emptySet(), "ofilo" to emptySet(),
        "agrafo" to emptySet(), "egrafo" to emptySet(), "ifero" to emptySet(), "ifera" to emptySet(), "ivoro" to emptySet(),
        "aneo" to emptySet(), "anea" to emptySet(), "oreo" to emptySet(), "ereo" to emptySet(), "erea" to emptySet(),
        "avel" to emptySet(), "ivel" to emptySet(), "ovel" to setOf("novel"), "uvel" to emptySet(),
        "aveis" to emptySet(), "iveis" to emptySet(), "oveis" to emptySet(), "uveis" to emptySet(),
        "issimo" to emptySet(), "issima" to emptySet(), "esimo" to emptySet(), "esima" to emptySet(),
        "errimo" to emptySet(), "errima" to emptySet(),
        "avamos" to emptySet(), "iamos" to emptySet(), "assemos" to emptySet(), "essemos" to emptySet(), "issemos" to emptySet(),
    )

    /** "caido", "construido": the i is a syllable of its own, and the stressed one. */
    private val participles = Regex("(?:(?<=a)|(?<=[^gq]u))id[oa]s?$")
    private val notParticiples = setOf("cuido", "cuida", "descuido", "fluido")

    /** Verbs in "-ica" and "-ula", stressed on the i and the u: "explica", "calcula". The rest are nouns and adjectives. */
    private val verbs = (
        "indica indico dedica dedico prejudica abdica reivindica predica comunica comunico excomunica estica estico salpica repica " +
            "despica abanico borrico burrico namorico pelica botica larica futrica barrica rubrica mexerico fuxico fabrico " +
            "frederico federico penico nanico pudico impudico titica marica benfica tonica " +
            "calcula circula acumula articula especula gesticula simula dissimula ondula regula modula anula postula bajula " +
            "manipula estrangula coagula capitula intitula vincula engula engulo adula medula casulo casula azula"
        ).split(' ').toSet()
    private val adjectivesInIfico = listOf("magn", "pac", "cient", "espec", "prol", "honor", "terr", "horr", "mir", "calor", "frigor", "sopor")
    private val uo = setOf("recuo", "amuo", "atuo", "situo", "acentuo", "efetuo", "habituo", "insinuo", "atenuo", "pontuo", "flutuo", "jejuo", "averiguo")

    private val icoOrUlo = Regex("([aeiouïü])([^aeiouïü]*)(ic|ul)([oa]s?)$")
    private val vowelUo = Regex("([aeiouïü])([bcdfjlmnprstvz]+)uos?$")

    private fun byEnding(word: String): String? {
        val single = word.removeSuffix("s")
        for ((ending, others) in endings) {
            val at = when {
                word.endsWith(ending) -> word.length - ending.length
                word.endsWith(ending + "s") -> word.length - ending.length - 1
                else -> continue
            }
            return if (single in others) null else accent(word, at)
        }
        if (single !in notParticiples) participles.find(word)?.let { return accent(word, it.range.first) }
        if (single in verbs || single in uo) return null
        // "unico", "musica", "titulo", "seculo": stressed two syllables from the end.
        icoOrUlo.find(word)?.let { m ->
            val stem = word.substring(0, m.range.first)
            val ic = m.groupValues[3] == "ic"
            if (ic && m.groupValues[2] == "f" && m.groupValues[1] == "i") {
                // "justifica" is a verb; "magnifica" is not.
                if (adjectivesInIfico.none { stem.endsWith(it) }) return null
            }
            if (ic && m.groupValues[2].endsWith("pl") && !word.startsWith("supl")) return null
            if (m.groupValues[2].isEmpty()) return null
            return accent(word, m.range.first)
        }
        // "arduo", "continuo", "individuo".
        vowelUo.find(word)?.let { m -> return accent(word, m.range.first) }
        return null
    }
}
