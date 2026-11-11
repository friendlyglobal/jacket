package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceChoiceTest {
    private val pack = VoicePack(
        id = "natural",
        name = "Natural",
        files = mapOf("model" to "model.onnx"),
        languages = listOf("en", "pt", "id"),
        voices = listOf(PackVoice("F1", "F1", 0, VoiceGender.FEMALE, 1), PackVoice("M1", "M1", 5, VoiceGender.MALE, 1)),
        license = PackLicense("Test"),
    )
    private val first = VoiceRef("natural", "F1")
    private val packs = listOf(pack)

    @Test
    fun untilSomeoneChoosesTheNaturalVoiceReadsWhatItCan() {
        assertEquals(first, chooseVoice("en-GB", null, emptyMap(), packs, first))
        assertEquals(first, chooseVoice("pt_BR", null, emptyMap(), packs, first))
        // Chinese isn't among its languages: the phone's voice.
        assertNull(chooseVoice("zh-CN", null, emptyMap(), packs, first))
        // A modest phone prefers none, and a book with no language can't be matched.
        assertNull(chooseVoice("en", null, emptyMap(), packs, null))
        assertNull(chooseVoice(null, null, emptyMap(), packs, first))
    }

    @Test
    fun theChosenVoiceWinsAndThePhoneCanBeChosenToo() {
        assertEquals(VoiceRef("natural", "M1"), chooseVoice("en", "natural/M1", emptyMap(), packs, first))
        assertNull(chooseVoice("en", VoiceRef.SYSTEM, emptyMap(), packs, first))
        // A voice that has gone (an older backup, say) leaves the phone's, rather than guessing another.
        assertNull(chooseVoice("en", "gone/X", emptyMap(), packs, first))
    }

    @Test
    fun aLanguageCanHaveItsOwnVoice() {
        val byLanguage = mapOf("pt" to "natural/M1", "en" to VoiceRef.SYSTEM)
        assertEquals(VoiceRef("natural", "M1"), chooseVoice("pt-BR", "natural/F1", byLanguage, packs, first))
        assertNull(chooseVoice("en-US", "natural/F1", byLanguage, packs, first))
        assertEquals(first, chooseVoice("id", "natural/F1", byLanguage, packs, first))
    }

    @Test
    fun androidsOldLanguageCodesAreUnderstood() {
        assertEquals("id", languageKey("in"))
        assertEquals("id", languageKey("in-ID"))
        assertEquals("pt", languageKey("pt_BR"))
        assertEquals("", languageKey(null))
        assertEquals("", languageKey("und"))
        assertEquals(first, chooseVoice("in", null, emptyMap(), packs, first))
    }

    @Test
    fun theCatalogIsRead() {
        val json = """
            {"packs":[{"id":"st","name":"ST","files":{"vocoder":"v.onnx"},"languages":["en","PT","in"],
              "voices":[{"id":"F1","speaker":0,"gender":"female","number":1},{"id":"M1","name":"Deep","speaker":5}],
              "license":{"name":"OpenRAIL-M","attribution":"© Someone"},"samples":{"en":"Hello."}},
             {"id":"broken"}]}
        """.trimIndent()
        val packs = VoiceCatalog.parse(json)
        assertEquals(1, packs.size)
        val st = packs.single()
        assertEquals(listOf("en", "pt", "id"), st.languages)
        assertEquals("voices/st", st.folder)
        assertEquals(VoiceGender.FEMALE, st.defaultVoice.gender)
        assertEquals("Deep", st.voice("M1")?.name)
        assertEquals("Hello.", st.samples["en"])
    }

    @Test
    fun voiceRefsRoundTrip() {
        assertEquals(first, VoiceRef.parse(first.toString()))
        assertNull(VoiceRef.parse("system"))
        assertNull(VoiceRef.parse(null))
        assertNull(VoiceRef.parse("a/b/c"))
    }

    @Test
    fun aBrazilianReaderKeepsTheNaturalVoiceWhateverItsAccent() {
        val pt = listOf(pack.copy(accents = mapOf("pt" to "PT")))
        val brazil = java.util.Locale.forLanguageTag("pt-BR")
        // Natural, though it speaks Portugal's Portuguese: better than the phone's for most readers.
        assertEquals(first, chooseVoice("pt", null, emptyMap(), pt, first, brazil))
        assertEquals(first, chooseVoice("pt-BR", null, emptyMap(), pt, first, java.util.Locale.US))
        assertEquals(VoiceRef("natural", "M1"), chooseVoice("pt", "natural/M1", emptyMap(), pt, first, brazil))
        // The phone's own voice, when chosen for Portuguese.
        assertNull(chooseVoice("pt", null, mapOf("pt" to VoiceRef.SYSTEM), pt, first, brazil))
        // And its accent is still known, for the picker to name.
        assertEquals(false, pt[0].soundsLike(withPhoneRegion("pt", brazil)))
        assertEquals(true, pt[0].soundsLike("pt-PT"))
    }

    @Test
    fun aBookWithoutARegionTakesThePhones() {
        val brazil = java.util.Locale.forLanguageTag("pt-BR")
        assertEquals("pt-BR", withPhoneRegion("pt", brazil))
        assertEquals("en", withPhoneRegion("en", brazil))
        assertEquals("pt-PT", withPhoneRegion("pt-PT", brazil))
        assertEquals("pt_PT", withPhoneRegion("pt_PT", brazil))
        assertNull(withPhoneRegion(null, brazil))
    }

    private val brazilian = VoicePack(
        id = "br",
        name = "Brazilian",
        files = mapOf("model" to "model.onnx"),
        languages = listOf("pt"),
        voices = listOf(PackVoice("M1", "Caio", 0, VoiceGender.MALE, 1)),
        license = PackLicense("Test"),
        accents = mapOf("pt" to "BR"),
        engine = ENGINE_PIPER,
    )
    private val both = listOf(pack.copy(accents = mapOf("pt" to "PT")), brazilian)
    private val caio = VoiceRef("br", "M1")
    private val brazil = java.util.Locale.forLanguageTag("pt-BR")
    private val portugal = java.util.Locale.forLanguageTag("pt-PT")

    @Test
    fun aBrazilianReaderGetsTheBrazilianVoice() {
        // The book says only "pt": the phone's region decides.
        assertEquals(caio, chooseVoice("pt", null, emptyMap(), both, first, brazil))
        assertEquals(first, chooseVoice("pt", null, emptyMap(), both, first, portugal))
        // The book's own region comes first.
        assertEquals(caio, chooseVoice("pt-BR", null, emptyMap(), both, first, portugal))
        assertEquals(first, chooseVoice("pt-PT", null, emptyMap(), both, first, brazil))
        // No clue at all: most readers of Portuguese are Brazilian.
        assertEquals(caio, chooseVoice("pt", null, emptyMap(), both, first, java.util.Locale.US))
        // Other languages are as before.
        assertEquals(first, chooseVoice("en", null, emptyMap(), both, first, brazil))
        // A modest phone still starts on its own voice.
        assertNull(chooseVoice("pt", null, emptyMap(), both, null, brazil))
    }

    @Test
    fun aVoicePickedForEveryBookGivesWayToTheReadersAccent() {
        // Picked for every book, it wasn't picked for its Portuguese.
        assertEquals(caio, chooseVoice("pt", "natural/M1", emptyMap(), both, first, brazil))
        assertEquals(VoiceRef("natural", "M1"), chooseVoice("en", "natural/M1", emptyMap(), both, first, brazil))
        assertEquals(VoiceRef("natural", "M1"), chooseVoice("pt", "natural/M1", emptyMap(), both, first, portugal))
        // Picked for Portuguese itself, it reads it whatever its accent.
        assertEquals(VoiceRef("natural", "M1"), chooseVoice("pt", null, mapOf("pt" to "natural/M1"), both, first, brazil))
        assertEquals(caio, chooseVoice("pt", null, mapOf("pt" to "br/M1"), both, first, portugal))
        // The phone's own voice, once chosen, is kept.
        assertNull(chooseVoice("pt", VoiceRef.SYSTEM, emptyMap(), both, first, brazil))
        // The Brazilian voice can't read English: a voice that can does.
        assertEquals(first, chooseVoice("en", "br/M1", emptyMap(), both, first, brazil))
    }

    @Test
    fun theReadersAccentIsOfferedFirst() {
        assertEquals(listOf("Caio", "F1", "M1"), voicesFor("pt", both, brazil).map { it.second.name })
        assertEquals(listOf("F1", "M1", "Caio"), voicesFor("pt", both, portugal).map { it.second.name })
        assertEquals(listOf("F1", "M1"), voicesFor("en", both, brazil).map { it.second.name })
        assertEquals("pt-BR", accentFor("pt", brazil))
        assertEquals("pt-PT", accentFor("pt", portugal))
        assertEquals("pt-BR", accentFor("pt", java.util.Locale.US))
        assertEquals("pt-PT", accentFor("pt-PT", brazil))
        assertEquals("en", accentFor("en", brazil))
    }

    @Test
    fun theCatalogSaysWhatKindOfVoiceItIs() {
        val json = """{"packs":[{"id":"c","engine":"piper","files":{"model":"m.onnx"},"languages":["pt"],"accents":{"pt":"br"},
            "voices":[{"id":"M1","name":"Caio"}],"license":{"name":"MIT"}}]}"""
        val pack = VoiceCatalog.parse(json).single()
        assertEquals(ENGINE_PIPER, pack.engine)
        assertEquals(true, pack.hasAccentOf("pt-BR"))
        assertEquals(false, pack.hasAccentOf("pt"))
    }
}
