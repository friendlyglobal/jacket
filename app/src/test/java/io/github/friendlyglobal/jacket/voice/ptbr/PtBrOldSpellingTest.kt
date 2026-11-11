package io.github.friendlyglobal.jacket.voice.ptbr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spelling of Machado de Assis's own editions, read as it is said today. */
class PtBrOldSpellingTest {
    private fun modern(vararg pairs: Pair<String, String>) {
        for ((old, new) in pairs) assertEquals(old, new, PtBrOldSpelling.modern(old))
    }

    @Test
    fun oldLettersAreBroughtUpToDate() = modern(
        "elle" to "ele", "theatro" to "teatro", "philosophia" to "filosofia", "commigo" to "comigo", "mettesse" to "metesse",
        "assumpto" to "assunto", "escripto" to "escrito", "prompto" to "pronto", "director" to "diretor", "objecto" to "objeto",
        "christão" to "cristão", "orchestra" to "orquestra", "solemne" to "solene", "alumno" to "aluno", "emquanto" to "enquanto",
        "comsigo" to "consigo", "pae" to "pai", "vae" to "vai", "geraes" to "gerais", "chapéo" to "chapéu", "quasi" to "quase",
        "hontem" to "ontem", "veiu" to "veio", "tres" to "três", "poz" to "pôs",
    )

    @Test
    fun someLettersStay() = modern(
        // A "ct" still said today, a "ch" that was never a k, a "gu" whose u is silent.
        "aspecto" to "aspecto", "fechou" to "fechou", "marchar" to "marchar", "seguida" to "seguida", "casa" to "casa",
    )

    @Test
    fun missingAccentsComeBack() = modern(
        "titulo" to "título", "habitos" to "hábitos", "lagrimas" to "lágrimas", "unico" to "único", "musica" to "música",
        "seculo" to "século", "necessario" to "necessário", "diccionarios" to "dicionários", "amavel" to "amável",
        "impossivel" to "impossível", "existencia" to "existência", "principio" to "princípio", "familia" to "família",
        "relogio" to "relógio", "collegio" to "colégio", "bellissima" to "belíssima", "magnifico" to "magnífico",
        "continuo" to "contínuo", "caido" to "caído", "construido" to "construído", "tambem" to "também",
        "difficil" to "difícil", "sciencia" to "ciência", "machina" to "máquina", "philosopho" to "filósofo",
    )

    @Test
    fun wordsStressedLikeTodayAreLeftAlone() = modern(
        // Verbs in "-ica" and "-ula", and words whose "-ia" is stressed.
        "explica" to "explica", "justifica" to "justifica", "calcula" to "calcula", "falaria" to "falaria",
        "alegria" to "alegria", "partido" to "partido", "homem" to "homem",
    )

    @Test
    fun pronounsHungOnTheVerbTheOldWay() = modern("tiral-os" to "tirá-los", "vel-a" to "vê-la", "fazel-o" to "fazê-lo", "d'elle" to "d'ele")

    @Test
    fun anOldEditionIsRecognised() {
        assertTrue(PtBrOldSpelling.looksOld("Succedeu, porém, que como eu estava cançado, fechei os olhos; elle interrompeu."))
        assertTrue(PtBrOldSpelling.looksOld("Deram curso á alcunha."))
        assertTrue(PtBrOldSpelling.looksOld("Não dispenso do theatro o collegio."))
        assertFalse(PtBrOldSpelling.looksOld("Uma noite destas, vindo da cidade para o Engenho Novo."))
        // Names keep such letters to this day, and so do English words.
        assertFalse(PtBrOldSpelling.looksOld("A Raphaella e o Matheus chegaram."))
        assertFalse(PtBrOldSpelling.looksOld("Ele foi ao shopping, feliz e happy."))
    }

    @Test
    fun theOpeningOfDomCasmurroIsSaidAsToday() {
        val old = PtBrPhonemes.sentences("Os visinhos, que não gostam dos meus habitos reclusos e calados, deram curso á alcunha.", oldSpelling = true)
        val new = PtBrPhonemes.sentences("Os vizinhos, que não gostam dos meus hábitos reclusos e calados, deram curso à alcunha.")
        assertEquals(new, old)
        assertEquals(
            PtBrPhonemes.sentences("para tirá-los outra vez do bolso"),
            PtBrPhonemes.sentences("para tiral-os outra vez do bolso", oldSpelling = true),
        )
    }
}
