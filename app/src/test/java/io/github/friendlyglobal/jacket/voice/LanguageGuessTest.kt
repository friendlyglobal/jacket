package io.github.friendlyglobal.jacket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {
    @Test
    fun latinLanguagesAreToldApartByTheirEverydayWords() {
        val samples = mapOf(
            "en" to "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness, and they had everything before them.",
            "es" to "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero y los perros.",
            "pt" to "Alice estava começando a ficar muito cansada de estar sentada ao lado de sua irmã e de não ter nada para fazer, mas o livro não tinha figuras.",
            "fr" to "Longtemps, je me suis couché de bonne heure. Parfois, à peine ma bougie éteinte, mes yeux se fermaient si vite que je n'avais pas le temps de me dire que je dormais.",
            "it" to "Nel mezzo del cammin di nostra vita mi ritrovai per una selva oscura, ché la diritta via era smarrita, e non sapevo come uscire da quella che era la notte.",
            "de" to "Als Gregor Samsa eines Morgens aus unruhigen Träumen erwachte, fand er sich in seinem Bett zu einem ungeheueren Ungeziefer verwandelt, und es war nicht ein Traum.",
            "nl" to "Het was een heldere koude dag in april en de klokken sloegen dertien toen hij naar huis liep; hij had niet gedacht dat het zo laat was en dat er nog iemand op hem wachtte.",
            "pl" to "Litwo, ojczyzno moja, ty jesteś jak zdrowie; ile cię trzeba cenić, ten tylko się dowie, kto cię stracił, i nie ma go już, ale to jest tak, jak było.",
            "tr" to "Bir sabah uyandığında her şey değişmişti; o da ne yapacağını bilmiyordu ve bu kadar çok şey için hazır değildi, ama bir şey daha vardı.",
            "id" to "Pada suatu hari, seorang anak kecil yang tinggal di desa itu pergi ke hutan untuk mencari kayu dengan ayahnya, dan mereka tidak akan pulang sebelum malam.",
        )
        for ((language, text) in samples) assertEquals(language, guessLanguage(text))
    }

    @Test
    fun otherScriptsAreToldByTheirLetters() {
        assertEquals("ru", guessLanguage("Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему."))
        assertEquals("uk", guessLanguage("Садок вишневий коло хати, хрущі над вишнями гудуть, і їхній спів чути здалеку всім."))
        assertEquals("ar", guessLanguage("كان يا ما كان في قديم الزمان ملك عادل يحب شعبه ويحكم بينهم بالعدل والإحسان في كل وقت."))
        assertEquals("hi", guessLanguage("एक समय की बात है, एक छोटे से गाँव में एक बुद्धिमान बूढ़ा आदमी रहता था जो सबकी मदद करता था।"))
        assertEquals("ko", guessLanguage("옛날 옛적에 작은 마을에 지혜로운 노인이 살고 있었습니다. 그는 모든 사람을 도와주었고 모두가 그를 좋아했습니다."))
        assertEquals("ja", guessLanguage("吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"))
        assertEquals("zh", guessLanguage("话说天下大势，分久必合，合久必分。周末七国分争，并入于秦。及秦灭之后，楚、汉分争，又并入于汉。汉朝自高祖斩白蛇而起义。"))
    }

    @Test
    fun tooLittleToGoOnIsNoGuess() {
        assertNull(guessLanguage(""))
        assertNull(guessLanguage("Chapter 1"))
        assertNull(guessLanguage("1234 5678 90 --- *** 42"))
    }
}
