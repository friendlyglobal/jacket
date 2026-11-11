package io.github.friendlyglobal.jacket.voice.ptbr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Brazilian speech, rule by rule. The symbols are the ones the Brazilian voices know: "ˈ" goes
 * before the stressed vowel and "ˌ" before a lighter beat; a final "a" is "æ", a final "e" is "y"
 * and a final "o" is "ʊ"; "ɾə" is an r that closes a syllable.
 */
class PtBrPhonemesTest {
    private fun said(vararg pairs: Pair<String, String>) {
        for ((word, sounds) in pairs) assertEquals(word, sounds, PtBrPhonemes.word(word))
    }

    @Test
    fun writtenAccentsCarryTheStress() = said(
        "café" to "kafˈɛ", "avó" to "avˈɔ", "avô" to "avˈo", "ônibus" to "ˈonibus", "táxi" to "tˈaksi",
        "história" to "ˌistˈɔɾjæ", "xícara" to "ʃˈikæɾæ", "país" to "paˈis", "saúde" to "sˌaˈudʒy",
    )

    @Test
    fun withoutAnAccentTheStressFallsOneFromTheEnd() = said(
        "casa" to "kˈazæ", "menino" to "mˌenˈinʊ", "cidade" to "sˌidˈadʒy", "falam" to "fˈalɐ̃ʊ̃", "homem" to "ˈomeɪŋ",
        "homens" to "ˈomeɪŋs", "dia" to "dʒˈiʲæ", "rio" to "xˈiʊ", "navio" to "navˈiʊ",
    )

    @Test
    fun wordsEndingInRLZIUAndNasalsAreStressedOnTheEnd() = said(
        "falar" to "falˈar", "papel" to "papˈɛʊ", "animal" to "ˌænimˈaʊ", "feliz" to "felˈis", "jardim" to "ʒaɾədʒˈiŋ",
        "algum" to "aʊɡˈũŋ", "irmã" to "iɾəmˈɐ̃", "coração" to "kˌoɾasˈɐ̃ʊ̃", "caiu" to "kaˈiʊ", "juiz" to "ʒuˈis",
    )

    @Test
    fun longWordsHaveLighterBeatsBeforeTheStress() = said(
        "universidade" to "ˌunivˌeɾəsidˈadʒy", "felicidade" to "fˌelisidˈadʒy", "teatro" to "tˌeˈatrʊ",
    )

    @Test
    fun openAndClosedEAndOWhereNoAccentSays() = said(
        "ele" to "ˌely", "ela" to "ˌɛlæ", "novo" to "nˈovʊ", "nova" to "nˈɔvæ", "novos" to "nˈɔvʊs",
        "formoso" to "fˌoɾəmˈozʊ", "formosa" to "fˌoɾəmˈɔzæ", "porta" to "pˈɔɾətæ", "porto" to "pˈoɾətʊ",
        "medo" to "mˈedʊ", "festa" to "fˈɛstæ", "mulher" to "muljˈɛr", "comer" to "komˈer",
        "fizeram" to "fˌizˈɛɾɐ̃ʊ̃", "comeram" to "kˌomˈeɾɐ̃ʊ̃", "sol" to "sˈɔl", "catorze" to "kˌatˈoɾəzy",
    )

    @Test
    fun nasalVowelsAndDiphthongs() = said(
        "campo" to "kˈɐ̃mpʊ", "canto" to "kˈɐ̃ŋtʊ", "tempo" to "tˈeɪmpʊ", "dente" to "dˈeɪŋtʃy", "fim" to "fˈiŋ",
        "bom" to "bˈoŋ", "mundo" to "mˈũŋdʊ", "pão" to "pˈɐ̃ʊ̃", "mãe" to "mˈɐ̃y", "põe" to "pˈõj", "lições" to "lisˈõjs",
        "também" to "tɐ̃mbˈeɪŋ", "maçã" to "masˈɐ̃", "cama" to "kˈɐ̃mæ", "banho" to "bˈɐ̃ɲʊ", "amor" to "æmˈor",
    )

    @Test
    fun tAndDBeforeISoundAreTchAndDj() = said(
        "tia" to "tʃˈiʲæ", "dia" to "dʒˈiʲæ", "noite" to "nˈoɪtʃy", "cidade" to "sˌidˈadʒy", "gente" to "ʒˈeɪŋtʃy",
        // But not before an "e" that is no "i": "teatro", "dente".
        "teatro" to "tˌeˈatrʊ",
    )

    @Test
    fun theBrazilianR() = said(
        "rato" to "xˈatʊ", "carro" to "kˈaxʊ", "honra" to "ˈoŋxæ", "prato" to "prˈatʊ", "caro" to "kˈaɾʊ",
        "mar" to "mˈar", "porta" to "pˈɔɾətæ",
    )

    @Test
    fun sBetweenVowelsAndBeforeVoicedSoundsIsZ() = said(
        "casa" to "kˈazæ", "mesmo" to "mˈezmʊ", "pasta" to "pˈastæ", "casas" to "kˈazæs", "feliz" to "felˈis",
    )

    @Test
    fun anLThatClosesASyllableIsAU() = said("brasil" to "brazˈiʊ", "alto" to "ˈaʊtʊ", "azul" to "azˈuw", "papel" to "papˈɛʊ")

    @Test
    fun lhNhChXCedillaQuAndGu() = said(
        "filho" to "fˈiljʊ", "banho" to "bˈɐ̃ɲʊ", "chuva" to "ʃˈuvæ", "exame" to "ˌezˈɐ̃my", "texto" to "tˈestʊ",
        "próximo" to "prˈɔsimʊ", "caixa" to "kˈaɪʃæ", "táxi" to "tˈaksi", "quero" to "kˈɛɾʊ", "quando" to "kwˈɐ̃ŋdʊ",
        "guerra" to "ɡˈɛxæ", "água" to "ˈaɡwæ", "aguentar" to "ˌaɡweɪŋtˈar", "gente" to "ʒˈeɪŋtʃy", "já" to "ʒˈa",
    )

    @Test
    fun twoVowelsAreOneSyllableOrTwo() = said(
        "pai" to "pˈaɪ", "noite" to "nˈoɪtʃy", "país" to "paˈis", "saúde" to "sˌaˈudʒy", "ruim" to "xuˈiŋ",
        "juiz" to "ʒuˈis", "rainha" to "xˌaˈiɲæ", "caiu" to "kaˈiʊ",
    )

    @Test
    fun littleWordsLeanOnTheirNeighbours() = said(
        "de" to "dʒy", "que" to "ky", "o" to "ʊ", "e" to "i", "um" to "ũŋ", "com" to "koŋ", "para" to "pˌaɾæ",
        "não" to "nˌɐ̃ʊ̃", "você" to "vosˌe",
    )

    @Test
    fun wordsRunTogetherInASentence() {
        assertEquals(listOf("ʊz ˈɔljʊz azˈuɪs."), PtBrPhonemes.sentences("Os olhos azuis."))
        assertEquals(listOf("falˈaɾ ˈaʊtw ɛ fˈeɪʲʊ."), PtBrPhonemes.sentences("Falar alto é feio."))
        assertEquals(
            listOf("a tˈaɾədʒj estˌavæ kˈeɪŋtʃy, iʲ ʊ mˌenˈinʊ dˌoɾəmˈiʲæ."),
            PtBrPhonemes.sentences("A tarde estava quente, e o menino dormia."),
        )
    }

    @Test
    fun punctuationMakesPausesAndSentences() {
        assertEquals(
            listOf("ˌely dʒˈisy: vˈɐ̃mʊs?", "i saˈiʊ; niŋɡˈeɪŋ xˌespoŋdˈeʊ."),
            PtBrPhonemes.sentences("Ele disse: «Vamos?» — e saiu; ninguém respondeu..."),
        )
        // A word that leans on the next has nothing to lean on before a pause.
        assertEquals(listOf("dʒˈisyʎˈy ky nˈɐ̃ʊ̃."), PtBrPhonemes.sentences("Disse-lhe que não."))
        assertEquals(emptyList<String>(), PtBrPhonemes.sentences("* * *"))
    }

    @Test
    fun noWordIsEverDropped() {
        // Not Portuguese, not in any list: still said, by the rules.
        for (word in listOf("xyzzy", "Wolfgang", "kkk", "brrr", "Ångström", "ǃXóõ")) {
            assertEquals(word, 1, PtBrPhonemes.sentences(word).size)
        }
    }

    @Test
    fun theVoiceGetsItsSymbolsOneCodePointAtATime() {
        val sounds = PtBrPhonemes.codepoints("Não.").single()
        // "ã" goes as "a"-like letter and a separate tilde.
        assertEquals("nˈɐ\u0303ʊ\u0303.", String(sounds, 0, sounds.size))
    }
}
