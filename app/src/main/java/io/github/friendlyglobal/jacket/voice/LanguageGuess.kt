package io.github.friendlyglobal.jacket.voice

/**
 * Tells what language a text is written in, so a plain-text book gets the right voice without
 * anyone having to say. First by its script (Cyrillic, Arabic, kana...), then, for the Latin
 * alphabet, by which language's everyday words it uses most. Null when there's too little to go
 * on. It covers the languages Jacket itself speaks; anything else falls to the phone's language.
 */
fun guessLanguage(text: String): String? {
    val sample = text.take(20_000)
    var latin = 0
    var cyrillic = 0
    var arabic = 0
    var devanagari = 0
    var hangul = 0
    var kana = 0
    var han = 0
    var ukrainian = 0
    for (c in sample) {
        when (c.code) {
            in 0x0041..0x024F -> if (c.isLetter()) latin++
            in 0x0400..0x04FF -> {
                cyrillic++
                if (c in "іїєґІЇЄҐ") ukrainian++
            }
            in 0x0600..0x06FF -> arabic++
            in 0x0900..0x097F -> devanagari++
            in 0xAC00..0xD7AF -> hangul++
            in 0x3040..0x30FF -> kana++
            in 0x4E00..0x9FFF -> han++
        }
    }
    val letters = latin + cyrillic + arabic + devanagari + hangul + kana + han
    if (letters < 40) return null
    return when (maxOf(latin, cyrillic, arabic, devanagari, hangul, kana + han)) {
        cyrillic -> if (ukrainian * 40 > cyrillic) "uk" else "ru"
        arabic -> "ar"
        devanagari -> "hi"
        hangul -> "ko"
        // Japanese is written with kana among its characters; Chinese has none.
        kana + han -> if (kana * 20 > han) "ja" else "zh"
        else -> latinLanguage(sample)
    }
}

private fun latinLanguage(sample: String): String? {
    val words = sample.lowercase().split(NOT_LETTERS).filter { it.isNotEmpty() }
    if (words.size < 12) return null
    val counts = HashMap<String, Int>()
    for (word in words) counts[word] = (counts[word] ?: 0) + 1
    val scores = COMMON_WORDS.mapValues { (_, common) -> common.sumOf { counts[it] ?: 0 } }
    val (best, score) = scores.maxByOrNull { it.value } ?: return null
    // A language is only named when its small words are a fair share of the text.
    return best.takeIf { score * 12 >= words.size }
}

private val NOT_LETTERS = Regex("[^\\p{L}']+")

/** Words so common that any page has them, and that between them tell these languages apart. */
private val COMMON_WORDS: Map<String, Set<String>> = mapOf(
    "en" to "the and of to in that was his he it with as for had you not be her on at by which have from this but they she were".split(' ').toSet(),
    "es" to "el la de que y en los del se las por un para con no una su al lo como más pero sus le ya fue este ha sí porque muy".split(' ').toSet(),
    "pt" to "o a de que e do da em um para com não uma os no se na por mais as dos como mas foi ao ele das à seu sua ou quando muito nos já eu também só pelo pela até isso ela entre era depois sem mesmo aos ter seus quem nas me esse eles você".split(' ').toSet(),
    "fr" to "le la les de des et un une du en que qui dans pour pas au sur ne se ce il elle est avec son sa ses plus par je mais nous vous était comme tout".split(' ').toSet(),
    "it" to "il la di che e un una in per non con del della le si da i gli lo al come più ma era sono dei nel alla suo sua anche quando ho questo".split(' ').toSet(),
    "de" to "der die und das in den von zu mit sich des auf für ist im dem nicht ein eine als auch es an er hat aus bei wie war sie ich wir nach".split(' ').toSet(),
    "nl" to "de het een en van in dat op te zijn met voor niet aan er maar om ook als bij dan nog naar hij ze was wat die zich heeft je ik".split(' ').toSet(),
    "pl" to "i w nie na się z do to że jest jak o a po co tak za od ale ja go przez już był czy tylko tym mnie jego jej było być".split(' ').toSet(),
    "tr" to "ve bir bu da de için ile ne o gibi daha çok ben ama var en kadar sonra her mi değil ya ki olan olarak diye sen biz yok şey".split(' ').toSet(),
    "id" to "yang dan di itu dengan untuk tidak ini dari dalam akan pada juga ke saya ada mereka kami kita bisa atau sudah oleh karena dia telah".split(' ').toSet(),
)
