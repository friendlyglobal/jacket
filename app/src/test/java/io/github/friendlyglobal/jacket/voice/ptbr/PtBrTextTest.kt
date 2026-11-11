package io.github.friendlyglobal.jacket.voice.ptbr

import org.junit.Assert.assertEquals
import org.junit.Test

class PtBrTextTest {
    @Test
    fun numbersAreSaidInPortuguese() {
        assertEquals("zero", PtBrNumbers.cardinal(0))
        assertEquals("dezesseis", PtBrNumbers.cardinal(16))
        assertEquals("vinte e um", PtBrNumbers.cardinal(21))
        assertEquals("cem", PtBrNumbers.cardinal(100))
        assertEquals("cento e um", PtBrNumbers.cardinal(101))
        assertEquals("mil", PtBrNumbers.cardinal(1000))
        assertEquals("mil e um", PtBrNumbers.cardinal(1001))
        assertEquals("mil e duzentos", PtBrNumbers.cardinal(1200))
        assertEquals("mil duzentos e cinquenta", PtBrNumbers.cardinal(1250))
        assertEquals("mil oitocentos e noventa e nove", PtBrNumbers.cardinal(1899))
        assertEquals("dois mil e vinte e seis", PtBrNumbers.cardinal(2026))
        assertEquals("um milhão duzentos e trinta e quatro mil quinhentos e sessenta e sete", PtBrNumbers.cardinal(1_234_567))
        assertEquals("dois milhões e quinhentos mil", PtBrNumbers.cardinal(2_500_000))
        assertEquals("duas", PtBrNumbers.cardinal(2, feminine = true))
        assertEquals("duzentas e vinte e uma", PtBrNumbers.cardinal(221, feminine = true))
    }

    @Test
    fun ordinalsAndRomanNumerals() {
        assertEquals("primeiro", PtBrNumbers.ordinal(1))
        assertEquals("terceira", PtBrNumbers.ordinal(3, feminine = true))
        assertEquals("vigésimo segundo", PtBrNumbers.ordinal(22))
        assertEquals("centésima décima", PtBrNumbers.ordinal(110, feminine = true))
        assertEquals(14, PtBrNumbers.roman("XIV"))
        assertEquals(1899, PtBrNumbers.roman("MDCCCXCIX"))
        assertEquals(null, PtBrNumbers.roman("CIVIL"))
        assertEquals(null, PtBrNumbers.roman("IIII"))
    }

    @Test
    fun datesMoneyAndTimeAreReadOut() {
        assertEquals(
            "No dia cinco de março de mil oitocentos e noventa e nove, o senhor Bento pagou vinte reais e cinquenta centavos.",
            PtBrText.expand("No dia 5 de março de 1899, o Sr. Bento pagou R$ 20,50."),
        )
        assertEquals("doze de março de mil novecentos e noventa e nove", PtBrText.expand("12/03/1999"))
        assertEquals("primeiro de maio", PtBrText.expand("1º de maio"))
        assertEquals("às catorze horas e trinta", PtBrText.expand("às 14h30"))
        assertEquals("uma hora", PtBrText.expand("1h"))
        assertEquals("um real", PtBrText.expand("R$ 1,00"))
        assertEquals("um milhão de dólares", PtBrText.expand("US$ 1.000.000"))
        // Old Brazilian money, as Machado wrote it.
        assertEquals("duzentos mil réis", PtBrText.expand("200$000"))
        assertEquals("um conto e quinhentos mil réis", PtBrText.expand("1:500$000"))
        assertEquals("três vírgula cinco por cento", PtBrText.expand("3,5%"))
        assertEquals("duas horas", PtBrText.expand("2 horas"))
        assertEquals("dois dias", PtBrText.expand("2 dias"))
    }

    @Test
    fun abbreviationsAreSaidInFull() {
        assertEquals("doutor Silva e a senhora Ana", PtBrText.expand("Dr. Silva e a Sra. Ana"))
        assertEquals("dom Pedro segundo e dona Maria primeira", PtBrText.expand("D. Pedro II e D. Maria I"))
        assertEquals("são Paulo, santo Antônio e santa Rita", PtBrText.expand("S. Paulo, S. Antônio e S. Rita"))
        assertEquals("vossa excelência", PtBrText.expand("V. Exa."))
        assertEquals("o capítulo doze do século dezenove", PtBrText.expand("o capítulo XII do século XIX"))
        assertEquals("a primeira vez, o terceiro dia", PtBrText.expand("a 1ª vez, o 3º dia"))
        // "etc." at the end of a sentence keeps the sentence's full stop.
        assertEquals("livros, cartas, et cétera. Depois", PtBrText.expand("livros, cartas, etc. Depois"))
        // An initial is said as its letter, and doesn't end the sentence.
        assertEquals(listOf("bˈe ˈɛly ɡˌaɾənjˈe."), PtBrPhonemes.sentences("B. L. Garnier."))
        // A chapter's number on a line of its own.
        assertEquals("catorze.", PtBrText.expand("XIV"))
    }

    @Test
    fun capitalsWithNoVowelAreSpeltOut() {
        val words = PtBrText.tokens("O CPF").filterIsInstance<PtBrText.Token.Word>().map { it.text }
        assertEquals(listOf("o", "cê", "pê", "éfe"), words)
        // Ones that can be said as a word are.
        assertEquals(listOf("a", "onu"), PtBrText.tokens("A ONU").filterIsInstance<PtBrText.Token.Word>().map { it.text })
    }

    @Test
    fun wordsAndPausesAreToldApart() {
        val tokens = PtBrText.tokens("Disse-lhe: d'água, não!")
        assertEquals(
            listOf(
                PtBrText.Token.Word("disse-lhe"), PtBrText.Token.Pause(':'), PtBrText.Token.Word("d'água"),
                PtBrText.Token.Pause(','), PtBrText.Token.Word("não"), PtBrText.Token.Pause('!'),
            ),
            tokens,
        )
    }
}
