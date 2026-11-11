package io.github.friendlyglobal.jacket.voice.ptbr

/**
 * What the rules of [PtBrPhonemes] can't know about Brazilian Portuguese words: the little words
 * said without stress, and which stressed "e" and "o" are open (é, ó) where the spelling doesn't
 * say. Portuguese writes "ele" and "ela" alike, but says "êle" and "éla"; only knowing the words
 * tells them apart. The lists go by the end of the word from its stressed vowel on ("ela", "oso"),
 * with the words that go the other way.
 */
internal object PtBrWords {
    /** Little words said exactly so in running speech, mostly with no stress of their own. */
    val fixed: Map<String, String> = mapOf(
        "a" to "a", "o" to "ʊ", "e" to "i", "as" to "as", "os" to "ʊs",
        "de" to "dʒy", "que" to "ky", "do" to "dʊ", "da" to "da", "dos" to "dʊs", "das" to "das",
        "no" to "nʊ", "na" to "na", "nos" to "nʊs", "nas" to "nas", "ao" to "aʊ", "aos" to "aʊs",
        "à" to "ˌaː", "às" to "aːs", "á" to "ˌaː", "ás" to "aːs",
        "um" to "ũŋ", "uns" to "ũŋs", "num" to "nũŋ", "nuns" to "nũŋs", "dum" to "dũŋ", "duns" to "dũŋs",
        "com" to "koŋ", "por" to "por", "se" to "sy", "me" to "my", "te" to "tʃy",
        "lhe" to "ʎˈy", "lhes" to "ʎˈys", "mas" to "mas", "ou" to "ow",
        "é" to "ɛ", "és" to "ɛs", "há" to "a", "ha" to "a", "foi" to "foɪ", "fui" to "fuɪ",
        "são" to "sɐ̃ʊ̃", "sou" to "sow", "vou" to "vow", "vai" to "vaɪ", "vão" to "vɐ̃ʊ̃",
        "tem" to "teɪŋ", "têm" to "teɪŋ", "tens" to "teɪŋs", "ser" to "ser", "ter" to "ter",
        "eu" to "eʊ", "tu" to "tu", "seu" to "seʊ", "seus" to "seʊs", "meu" to "meʊ", "meus" to "meʊs",
        "teu" to "teʊ", "teus" to "teʊs", "faz" to "fas", "pra" to "pra", "pro" to "prʊ", "pras" to "pras", "pros" to "prʊs",
        "muito" to "mwˈiŋtʊ", "muita" to "mwˈiŋtæ", "muitos" to "mwˈiŋtʊs", "muitas" to "mwˈiŋtæs",
        "esse" to "ˌesi", "esses" to "ˌesis", "nesse" to "nˌesy", "nesses" to "nˌesys", "desse" to "dˈesy", "desses" to "dˈesys",
        "neste" to "nˌestʃi", "nestes" to "nˌestʃis", "sob" to "sˈob",
        "porque" to "pˈoɾəky", "porquê" to "poɾəkˈe", "quê" to "kˈe",
        // English, which turns up in Portuguese books.
        "the" to "dy", "of" to "ɔv", "and" to "ɛnd",
    )

    /** Words said with a lighter stress than their spelling earns: they lean on their neighbours. */
    val weak: Set<String> = (
        "uma umas numa numas duma dumas para como não era eram sua suas tua tuas minha minhas nosso nossa nossos nossas " +
            "vosso vossa vossos vossas ele ela eles elas este esta estes estas nesta nestas nessa nessas " +
            "você vocês até onde nunca sempre desde qualquer quaisquer " +
            "está estão estás estou estava estavam estávamos estar esteve estive estiveram " +
            "tinha tinham tínhamos tenho temos teve tive tiveram tivera " +
            "será serão seria seriam foram pode podem podia podiam pôde posso podemos vamos"
        ).split(' ').toSet()

    /** A pronoun hung on its verb with a hyphen ("disse-me", "vê-lo"). */
    val clitics: Map<String, String> = mapOf(
        "me" to "my", "te" to "tʃy", "se" to "sy", "lhe" to "ʎˈy", "lhes" to "ʎˈys", "nos" to "nʊs", "vos" to "vʊs",
        "o" to "ʊ", "a" to "æ", "os" to "ʊs", "as" to "æs", "lo" to "lʊ", "la" to "læ", "los" to "lʊs", "las" to "læs",
        "no" to "nʊ", "na" to "næ", "nas" to "næs",
    )

    /** Words better read from another spelling: foreign ones, mostly. */
    val respelled: Map<String, String> = mapOf(
        "show" to "xou", "shopping" to "xópin", "site" to "sáite", "online" to "onláine", "internet" to "internéte",
        "email" to "iméiu", "mouse" to "máuze", "design" to "dizáin", "marketing" to "márquetin", "software" to "sófituér",
        "download" to "daunlôude", "whisky" to "uísqui", "jazz" to "djéz", "rock" to "róque", "pizza" to "pítsa",
        "madame" to "madâme", "monsieur" to "messiê", "mademoiselle" to "mademoazéle", "miss" to "míss",
        "lord" to "lórde", "lady" to "lêidi", "sir" to "sâr", "boulevard" to "bulevár", "toilette" to "toaléte",
        "bouquet" to "buquê", "soirée" to "soarê", "chic" to "xíque", "adieu" to "adiê", "bonjour" to "bonjúr",
        "merci" to "mercí", "mrs" to "míssis", "mr" to "míster", "shakespeare" to "xêiquispir", "new" to "niú", "york" to "iórque",
        "washington" to "uóxinton", "paletot" to "paletó", "garnier" to "garniê", "rue" to "rí", "street" to "istríte",
    )

    private val soundedU = listOf(
        "aguent", "enxagu", "frequ", "cinqu", "tranqu", "consequ", "subsequ", "eloqu", "loquen", "delinqu", "sequenc", "sequênc",
        "sequestr", "equestr", "linguiç", "linguist", "linguíst", "pinguim", "bilingu", "bilíngu", "trilíngu", "ambiguid",
        "contiguid", "exiguid", "antiguid", "argui", "arguí", "sagui", "unguent", "sanguent", "quinqu", "ubiqu", "iniquid",
        "obliquid", "propinqu", "lingueta",
    )

    /** Whether [word]'s "gue", "gui", "que" or "qui" keeps its u ("aguentar", "tranquilo"). */
    fun soundedU(word: String): Boolean = soundedU.any { it in word }

    private val ks = listOf(
        "táxi", "taxi", "taxí", "fix", "sex", "nex", "plex", "flex", "convex", "dox", "tóxic", "toxic", "toxin", "léxic", "lexic",
        "oxig", "óxid", "oxid", "oxít", "axil", "maxil", "flux", "sintax", "asfix", "galáx", "axiom", "boxe", "uxor", "saxo",
        "saxã", "saxõ", "prolix", "hexa", "hexâ", "apoplex", "ataxi", "profilax", "paroxis", "anex", "amplex", "ortodox",
    )
    private val s = listOf("próxim", "proxim", "máxim", "maxim", "auxíli", "auxili", "trouxe", "trouxé", "aproxim")

    /** Whether [word]'s x between vowels is "ks" ("táxi", "sexo"). */
    fun xAsKs(word: String): Boolean = ks.any { it in word }

    /** Whether it is "ss" ("próximo", "trouxe"). */
    fun xAsS(word: String): Boolean = s.any { it in word }

    // ---- open and closed vowels ----

    /** Rime, then + (open) or - (closed), then the words that go the other way. */
    private const val E_RIMES = """
ela + estrela pela
elo + cabelo modelo camelo gelo pelo selo zelo apelo desvelo cotovelo tornozelo pesadelo novelo atropelo degelo
ele + ele aquele dele nele daquele naquele
eto + preto soneto coreto folheto amuleto esqueleto boleto panfleto espeto gueto livreto graveto carreto minueto dueto quarteto terceto quinteto sexteto galeto
eta - peta poeta profeta atleta meta seta reta dieta bicicleta completa secreta direta neta concreta discreta correta quieta inquieta repleta predileta dileta obsoleta analfabeta asceta esteta indireta incorreta incompleta indiscreta seleta coleta bisneta
ete - sete dezessete repete compete promete mete submete reflete derrete remete comete acomete intromete compromete chiclete confete disquete espaguete omelete vedete croquete raquete maquete gilete pivete
ero + tempero desespero exagero esmero
era - deveras era fera primavera quimera atmosfera esfera pantera galera cratera megera espera severa sincera austera mera venera tolera supera reitera altera coopera considera prospera exagera acelera pondera gera libera modera numera recupera tempera desespera opera
eram - eram
esse - apresse confesse atravesse regresse cesse professe expresse
essem -
er - mulher colher talher mister chofer qualquer quer sequer requer malmequer
eres - mulheres colheres talheres queres requeres
el +
eis -
ez - dez
ezes -
eze - reze preze despreze
edo - credo
eda - moeda queda leda
ede + rede parede sede
ega + chega grega aconchega achega
ego - cego prego nego ego carrego entrego navego
egue + chegue aconchegue
eco + seco beco
eca + seca
eque +
esa - pesa lesa
eza - reza preza despreza embeleza
eso -
ese +
eses -
esta + cesta besta
esto + cesto
este + este deste neste
estre +
estra +
exto -
exta -
esco - pesco
esca - pesca
esmo -
esma -
esde -
erba + soberba
essa + condessa abadessa espessa
esso + gesso avesso travesso espesso
ece +
ecem +
eça - peça começa tropeça impeça despeça meça
eço - peço impeço despeço meço
elho - velho evangelho
elha - velha
ejo -
eja - inveja
eve + teve esteve
evem +
eva + deva escreva atreva
evo - levo longevo primevo
ebe +
ebem +
ebo -
eba +
edra +
edro -
etra -
etro +
egra + negra
egro -
ebre +
ebra + zebra
erto + concerto conserto acerto
erta +
erno + governo
erna +
erso +
ersa +
erço -
erça -
erde - perde
erdem +
erda - merda lerda
erdo - lerdo
erca -
erco -
erva +
ervo - servo cervo
erve +
ervem +
erbo + soberbo
ermo -
erma -
erra +
erro +
erre +
erme +
erne +
ecto +
ecta +
exo +
exa +
eia - ideia assembleia plateia geleia estreia europeia epopeia odisseia traqueia onomatopeia panaceia diarreia centopeia ateia hebreia pigmeia coreia galileia apneia cefaleia
eio -
efe +
efa +
echo -
echa - flecha mecha brecha
exe +
exem +
ege +
egem +
epe +
epa +
epo + cepo
ectro +
elga +
elva +
elta +
elto +
"""

    private const val O_RIMES = """
oso -
osa + esposa raposa mariposa
osos + esposos
osas + esposas raposas mariposas
ol + gol
or - maior menor melhor pior redor arredor suor pormenor mor major
ores - maiores menores melhores piores arredores suores pormenores
oz + arroz algoz
ozes +
ora + professora autora cantora doutora pastora tutora inspetora diretora leitora escritora pintora senhoria
oro + coro choro soro namoro decoro
ore +
orem +
oram + foram
oda + toda boda
odo + todo lodo engodo rodo iodo apodo godo
ode +
odem +
ota + gota garota marota
oto + garoto piloto roto canhoto esgoto broto boto maroto coto arroto
ote +
oga +
ogo + fogo jogo rogo pedagogo demagogo desafogo
ogue +
oca + boca
oco + oco coco soco troco toco sufoco barroco
oque +
opa + sopa estopa popa
opo + topo escopo
ope +
oba -
obo -
obe +
obem +
obra +
obro - cobro sobro
obre + sobre
ova + escova alcova
ovo - provo aprovo renovo reprovo comprovo
ove +
ovem +
ofa + fofa
ofo -
ofe +
ofre +
ofrem +
ofro -
odre -
ola + cebola tola
olo + bolo tolo rolo consolo miolo tijolo
ole + controle
olha - olha molha
olho -
olhe +
olhem +
olham +
olhas +
orta +
orto - corto importo suporto comporto exporto
orte +
orda + gorda
ordo - concordo recordo discordo
orde +
orma +
orme +
ormem +
orno -
orna +
orne +
orpo -
orco -
orca + forca
orvo -
orça -
orço -
orce +
orso - remorso
orsa +
orro -
orra - forra desforra
orre + torre
orrem +
osso - nosso vosso posso
ossa +
osse + fosse
ossem + fossem
oço -
oça + moça
osto -
osta +
oste + foste
ostos + gostos rostos
ostra - mostra amostra
ose + pose
oze - coze
orze -
oje -
oja +
ojo -
oce -
oa -
oo -
oe +
oia +
oio -
oico +
oide +
olta +
olto - volto
olte +
olve +
olvem +
olva -
olvo -
olsa -
olso -
olga +
olpe +
olfo -
olde +
olcha -
olpa -
otro -
ogro -
ogra +
oxo -
oxa -
ocha +
ocho -
opro -
"""

    private class Rime(val open: Boolean, val others: Set<String>)

    private fun table(text: String): Map<String, Rime> = text.lineSequence().filter { it.isNotBlank() }.associate { line ->
        val parts = line.trim().split(' ')
        parts[0] to Rime(parts[1] == "+", parts.drop(2).toSet())
    }

    private val eRimes = table(E_RIMES)
    private val oRimes = table(O_RIMES)

    /** Words in -o whose o is closed, but opens in the plural: "novo", "novos". */
    private val opensInPlural = (
        "corpo porto porco forno morto torto esforço reforço olho ovo povo fogo jogo novo posto grosso osso poço imposto " +
            "caroço tijolo miolo socorro destroço tremoço corvo corno coro despojo rogo troco aposto composto disposto suposto " +
            "oposto proposto exposto fosso choco"
        ).split(' ').toSet()

    /** Words in "-dora", "-tora", "-sora" that aren't someone who does something. */
    private val agentLike = setOf("senhora", "outrora", "teodora", "pandora", "isadora", "eudora")

    /** Verb stems whose "-eram", "-esse", "-er" have an open é: "fizeram", "tivesse", "puder". */
    private val strongStems = listOf(
        "fiz", "tiv", "estiv", "pud", "soub", "quis", "quiz", "vi", "d", "houv", "troux", "pus", "puz", "diss", "coub",
    )
    private val strongEndings = listOf("eram", "era", "eras", "éramos", "esse", "esses", "essem", "er", "eres", "erem", "ermos", "erdes")

    private fun strong(word: String): Boolean = strongEndings.any { ending ->
        word.endsWith(ending) && word.dropLast(ending.length).let { stem -> strongStems.any { stem.endsWith(it) && (it.length > 2 || stem == it) } }
    }

    /**
     * Whether the stressed [vowel] ('e' or 'o') of [word] is open. [rime] is the word from that
     * vowel on; [nasalNext] if an m, n or nh follows it; [glideNext] if an i or u does ("lei", "sou").
     */
    fun open(word: String, vowel: Char, rime: String, nasalNext: Boolean, glideNext: Boolean): Boolean {
        val rimes = if (vowel == 'e') eRimes else oRimes
        val single = word.removeSuffix("s")
        // Before a nasal sound Brazilians close the vowel: "pena", "nome", "sonho".
        if (nasalNext) return false
        if (vowel == 'e' && strong(word)) return true
        if (vowel == 'o' && word.endsWith("os") && single in opensInPlural) return true
        // "trabalhadora", "leitora", "professora": she who does it keeps the ô of "-or".
        if (vowel == 'o' && (rime == "ora" || rime == "oras") && single.length > 5 && single.dropLast(3).last() in "dts" && single !in agentLike) return false
        rimes[rime]?.let { return it.open != (word in it.others || single in it.others) }
        if (rime.endsWith("s")) rimes[rime.dropLast(1)]?.let { return it.open != (word in it.others || single in it.others) }
        if (glideNext) return false
        val after = rime.drop(1)
        return when {
            // papel, mel, selva; sol, volta.
            after.startsWith("l") && (after.length == 1 || after[1] !in "aeiouh") -> true
            // perto, certo; porta, forte.
            after.startsWith("r") && after.length > 1 && after[1] !in "aeiouh" -> vowel == 'e' || !after.startsWith("rr")
            rime.length == 1 -> false
            // The rest lean by the word's last vowel: "-o" keeps it closed, "-a" and "-e" open it.
            else -> single.last() != 'o' && (vowel == 'o' || single.last() == 'e')
        }
    }
}
