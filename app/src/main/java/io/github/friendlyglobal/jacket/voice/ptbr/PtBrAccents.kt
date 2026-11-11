package io.github.friendlyglobal.jacket.voice.ptbr

/**
 * Portuguese words that carry an accent in today's spelling: for putting it back where an old
 * edition leaves it off ("titulo", "lagrimas", "facil"). Gathered from public-domain Brazilian
 * novels in modern spelling, with common ones added by hand; the spelling rules of
 * [PtBrOldSpelling] take care of the rest.
 */
internal object PtBrAccents {
    private const val WORDS =
        "abatê abominável abundância abóbada acabrunhá aceitará acessível acessório acharás acidália acompanhá acrimônia acácia admiráveis admirável " +
        "adultério adultérios adversária adversárias adversário adversários advertência advérbio adágio adúltero afável agradáveis agradável aimoré " +
        "aimorés alcácere alcáçar alfândega alguém alimárias aliás aljôfar almíscar altíssimos além alívio amantética ampará amásia amável ananás " +
        "angélica angélico angélicos angústia angústias antártico antígua antítese antônio anágua anéis anônima anônimo anúncio anúncios aparência " +
        "aparências apreciáveis apreciável aproveitável apólices após apóstolo apóstrofes aquáticas aquém araribá ararê argênteas aristocrática " +
        "aristocrático aristocráticos aritmética armazém arnês aromática aromáticas arrancá arrebóis arsínoe artifícios artéria artística artístico " +
        "artísticos arábia aspérrimos assédio assíduo assíria astúrias atacá atirá atlântico através atraída atribuía atribuíam atribuíram atrás " +
        "atônito audácia aurélia auréola ausência açúcar aérea bacharéis balaústre balbúrdia barém batéis baú belígero benefício benefícios " +
        "benevolência benévolo binóculo bizâncio blasfêmia blasfêmias bofé boticário botânica bravíssimo breviário brás brâmenes bulício bálsamo " +
        "bárbara bárbaro bárbaros bélica bélico bétis bênção bênçãos bóreas bônus búfalo bússola cadáver cadáveres cadência café calendário calíope " +
        "canapé canárias canários capadócios capítulo capítulos característica carmânia cartório caráter caríbdis carícia carícias catástrofe " +
        "católica católico caído caídos caíra caíram caísse cecília celibatário cemitério cenário cerimônia cerimônias certíssima certíssimo chaminé " +
        "chapéu chapéus chaúl chácara chávena cipó cipós circunstância circunstâncias ciência ciências ciúme ciúmes claríssimo clemência clássica " +
        "clássico clérigo clímax coincidência colégio colégios colérico colônia compôs comédia comércio concluída concluído condescendência " +
        "conferência confidência confidências consciência conseqüência conseqüências consolá constituía constância consórcio conteúdo continência " +
        "contraídas contraído contrária contrário contrários contágio contém contínuas contínuo convencê conveniência conveniências convivência " +
        "convém convênio coronéis corpulência correrás correspondência cortês crepúsculo crisólitas critério cruéis crástina crânio crédito crédulo " +
        "crês crítico crônica cubículo cumprirá cálculo cálculos cálice cálices cálix cápsula cárcere cáspios cáustico câmara câncer cândida cândido " +
        "cântico célebre céptico cérebro césar cético céus cílios cípria círculo círio cítara cítia cítico cócegas código cólera cólica cópia cópias " +
        "cômico cômicos cômoda cômodas cômodo côncava côncavo cônego cônjuge cônscia cônsul côvado cúmplice cúmplices cúpula cútis dardânia dará " +
        "declínio decrépito decência defenderá deixarás delícia delícias delírio delírios demónio demônios denunciá depósito desagradável descrevê " +
        "desdém desfavoráveis desobediência despóticos destruí destruía destruída destruído destruídos desânimo desígnio detestável detrás detêm " +
        "diabólico diabólicos diagnóstico dicionário difíceis difícil diligência dilúvio dirá dirás discípulos dispôs distraía distraída distraído " +
        "distraíra distraísse distribuía distribuídos ditérios divergência divórcio dizíeis diáfana diáfanas diáfano diáfanos diálogo diárias " +
        "doméstica domésticas doméstico domésticos domínio dramática duodécima dádiva dádivas débil débito década décima décimo délio dêem dívida " +
        "dívidas dóceis dócil dólar dóris dúvida dúvidas dúzia dúzias econômica edifício edifícios eflúvios efêmera efígie egoísmo egoísta egrégio " +
        "egípcia elegância eloquência eloqüência elástica elétrica elétrico emergência emissário empório emília emílio encaminhá enxárcia enérgico " +
        "episódio episódios epopéia epílogo epístola equilíbrio equívoco equívocos escritório escrutínio escrúpulo escrúpulos escárnio escândalo " +
        "escória esmagá espedaçá espetáculo esplêndida esplêndido esplêndidos espontâneo espádua espáduas espécie espécies espírito espíritos espólio " +
        "essência essências estará estatuária estrépito estrídulo estágio estática estático estáticos estátua estávamos estâmago estéreis estéril " +
        "estígio estímulos estômago estúpida estúpido etiópia etérea etíopes européias evidência excelência excêntrico exercício exercícios " +
        "exigências existência experiência experiências explicará explícito expô expôs extermínio extraordinária extraordinário extravagância " +
        "extravagâncias extático exuberância exército exércitos exílio exímia exótica fabrício facécias faláveis família famílias fantástica " +
        "fantásticas fantástico fanática fará farás fascínio fastídio fastígio fatídica favoráveis favorável fazê faísca felicitá fenômeno fenômenos " +
        "finíssima finíssimo finório fiéis flexível fontâneo formidáveis formidável fortíssima fortíssimo fortíssimos fragrância francês freguês " +
        "freqüência frágeis frágil fráguas frêmito frívola frívolo fróis fumatório fábrica fábricas fábula fábulas fáceis fácil férias férreo férteis " +
        "fértil férula férvida férvidos fêmea fênix fígado física físico físicos fórmula fórum fósforo fósforos fóssil fôlego fôsseis fúcsias fúnebre " +
        "fúria fúrias fúteis fútil galé galés gangético genealógica germânia gidá glória glórias goitacá goitacás grandíssima grandíssimo gravíssimos " +
        "gritárão grátis grécia grêmio gália gávea gáveas gélido gênero gêneros gênio gótico gôndola haverá havê hemisfério hemorróidas hercúlea " +
        "heroína heroísmo herói heróica heróis hespéria hierosólima hipótese história histórias homeopática homicídio horrível hortênsias hospício " +
        "hotéis hábeis hábil hábito hábitos hálito hástia hélice hélio hércules hífen hórrido hórridos hóspeda hóspede hóspedes húmido idênticos " +
        "idílio ignorância ilhéu ilustríssima ilícito iminência impaciência impassível impenetrável imperceptíveis imperceptível imperdoável " +
        "impertinência impertinências imperturbável implacável importância impossíveis impossível impropério imprudência império impôs imóveis imóvel " +
        "inabalável inacessível inalterável incompreensível inconstância incontinência inconveniência incorrigível incríveis incrível incumbência " +
        "incurável incêndio incêndios incógnita incógnito incógnitos incômodo incômodos indecifrável indefinível indigência indispensável indivíduo " +
        "indivíduos indizível indolência indomável indulgência indício indícios indígenas indústria inefável inesgotável inevitável inexplicáveis " +
        "inexplicável inexprimível inexpugnábil inexpugnável infalível infatigável infiéis inflexível influência influía influído infortúnio " +
        "infortúnios infâmia infância inglês ingênua inimitável injúria injúrias inocência inqualificável insensível insistência insolências " +
        "instantânea instantâneo instituía instituído instância instâncias insuportáveis insuportável insânia insídias insígnias inteligência " +
        "interminável intermédio interrogatório intolerável intrépido intrínseco invariável invencível inverossímil invisível involuntária " +
        "involuntário invólucro inédito inércia inês início iníqua inúbia inúmeros inúteis inútil irreconciliável irremediável irresistíveis " +
        "irresistível irrevogável itália itálico jacarandá jacaré jibóias josé judiciária jurídicos juízes juízo juízos jóia jóias júbilo júpiter " +
        "júri lavatório legítima legítimo legítimos lelê lençóis levarás literárias litígio livrá longínquo ludíbrio lusitânia lusíadas luís lábio " +
        "lábios lácteo lágrima lágrimas lápis lâmina lâmpada lânguida lânguido lépido lêem líbia lícita lícito líder límpida límpidas límpido " +
        "límpidos língua línguas líquida líquido lírica lírico lírio lírios lísia lívida lívido lívidos lógica lúcido lúcifer lúcio lúgubre lúgubres " +
        "madrepérola magistério magnânimas magnânimo magníficas magnífico magníficos magnólias maledicência malévolo malévolos malícia mandarás " +
        "mandíbulas mantém marmórea marquês martírio marítima marítimas marítimo massília matéria matérias maçuá melancólica melancólico memoráveis " +
        "memória memórias mercê mercês mercúrio meritória metáfora micróbio milionária milícias ministério misericórdia miseráveis miserável " +
        "missionário mistério mistérios miséria misérias miúda miúdas miúdo mobília modéstia moléstia monetário monossílabo monólogo monótona " +
        "monótono mortífero moscóvia multilíngue murmúrio murmúrios mágica mágoa mágoas máquina máquinas márcio mário mármore mártir mártires máscara " +
        "máxima máximas máximo médica médico médicos médio mérito método mímica mínias mínima mínimo mísera mísero míseros móbil módica mórbido " +
        "móveis móvel músculos música mútua mútuo naufrágio naufrágios necessária necessárias necessário negócio negócios nenúfares ninguém niterói " +
        "notáveis notável notícia notícias nácar nádegas náufrago náusea néctar nédia néscio névoa nítidas nítido nívea níveo níveos nódoa núcleo " +
        "número números obediência obstáculo obstáculos obséquio ocorrência odoríferos ofícios opulência opôs oratório ordinária ordinário orquídeas " +
        "oráculo oásis paciência pacífica pagará painéis palmatória palácio palácios papéis parabéns paraíba paraíso parlamentário parténope parábola " +
        "pastéis patrimônio patrocínio patética países pecuniária pedilúvio perdulário perdôo perfídia peripécia periódicos perjúrio perpétua " +
        "perpétuo perspicácia pertencerá período petróleo petrópolis petulância pilhá pilhérias pincéis planície platônico plácida plácido plágio " +
        "poderá poderás podíamos podíeis polícia política político políticos pontapé português portátil porá porás porém possuía possuíam possuída " +
        "possuído possuísse possível potência poética poético precipício predomínio preferência prejuízos prelúdio presidência presságio prestígio " +
        "presídio pretérito previdência princípio princípios privilégio privilégios prodígio prodígios profético prognóstico progénie promontório " +
        "pronúncia propósito propósitos propôs protegê provarás providência providências provável provérbio província províncias proíbo prudência " +
        "prática práticas prédio prémio prémios prévio príncipe pródigo prólogo própria próprias próprio próprios próspera próspero próxima próximo " +
        "psicológica purpúrea purpúreas página páginas pálida pálidas pálido pálidos pálpebras párseos páscoa pássaro pássaros pátio pátria pátrias " +
        "pátrio pátrios pâmpanos pânico pérfida pérfido pérola pérolas pérsia péssima péssimo pétala pétalas pétrea pêndula pêndulo pênsil pêssego " +
        "pílulas pírolas pólen pólvora póstumo pública público públicos púlpito púrpura quartéis quererá quererás queríeis quilômetro quíloa " +
        "raciocínio raciocínios rapé razoável raízes realizá reanimá recém recôndito recônditos refluíam refrigério refúgio relâmpados relâmpago " +
        "relâmpagos relíquias relógio relógios reminiscências remédio remédios repreensível represália represálias repugnância repúdio resistência " +
        "respeitável restituía restituída restituído reticência reticências retraíra retribuía retribuído retrós retê revólver ridícula ridículo " +
        "ridículos robério romântica românticas romântico românticos rosário ruído ruídos ruína ruínas rábula rádio rápida rápidas rápido rápidos " +
        "rédea rédeas régio régios réplica réprobo répteis réptil réstia rígido ríspido ródope rósea róseo rótula rótulas rótulo rústico saberá " +
        "saberás sabíeis sacrificá sacrifício sacrilégio sacrílego salário santarém santuário santíssima sapé sarcástica sarcófago sardônico satanás " +
        "satisfazê satânica satânico saíam saída saídas saído saíra saíram saísse saíssem saúde selvática semicírculo sensível sertório será serás " +
        "silêncio simpática sinhá sinônimo soberbíssimo socorrê sofrível sofá solitária solitário solícito suavíssimo subsistência subsídio " +
        "subterfúgio subterrâneo sufrágio suicídio sulfúrico sumário superfícia superfície suplício suplícios supôs surpreendê sábado sábados sábio " +
        "sábios sátira sândalo século séquito série sério sérios sétimo sílaba símbolo síncope síntese sóbria sócio sócios sólida sólido sólio " +
        "sórdido sótão sôfrego súbita súbito súcia súplica súplicas súplice tamandaré também telégrafo temerária temíveis temível teotônio terríbil " +
        "terríveis terrível terá terás tolerância tomás tomáveis tormentório trajetória trará trazê traída traído traísse trimilionésima troféu " +
        "troféus tráfego tráfico trágico trás trânsito trégua trépido trêmula trêmulas trêmulo trêmulos três tróia turíbulo tábua tábuas tácito " +
        "tágides tálamos tática tântalo técnicos tédio tépida tépido términos tétis tênis tênue têxtil tílburi tímida tímidas tímido tímidos tímpano " +
        "tínhamos título títulos tórax tórrido túmida túmulo túnica unânime urgências utensílios vandália vaticínio veemência vencerá venerável " +
        "verossímil verá verás verídica vestuário vestígio vestígios vigilância vigário vigília violência visionário visível vitupério vitupérios " +
        "vitória vitórias viverás viúva viúvo vocábulo você vocês voluntários volátil volúpia volúvel vácuo válido válvula várias vário vários várzea " +
        "várzeas vândalo vândalos vénus véspera vésperas véus vêem víbora vício vícios vínculo vírus vítima vítimas vítreo vômito xácara xícara " +
        "zéfiro ácido áfrica ágeis ágil água águas álbum álbuns álcool álvaro ápice árduas árduo área áreas ária árido árvore árvores ásia áspera " +
        "ásperas áspero ásperos átomo áurea áureo áureos ávido ávidos âmbar âmbito âncora âncoras ângelo ângulo ângulos ânimo ânimos ânsia ânsias " +
        "ébrio écarté échos época épocas érable éter êxito êxtase ícone ídolo ídolos ímpar ímpeto ímpetos ímã ínclitas ínclitos índia índice índico " +
        "índio índios índole ínfimo ínsula íntegro íntima íntimas íntimo íntimos íris óbito óbvio ócio óculos ódio ódios óleo ópera ópio órbita " +
        "órbitas órfã órfão órfãos órgão órgãos ósseo ótima ótimo ônibus ônus úlcera úlceras última últimas último últimos úmida úmidas úmido úmidos " +
        "úngaro única únicas único únicos úteis útero útil"

    /** [word] without its acute and circumflex accents. */
    fun bare(word: String): String = buildString(word.length) {
        for (c in word) append(
            when (c) {
                'á', 'â' -> 'a'
                'é', 'ê' -> 'e'
                'í' -> 'i'
                'ó', 'ô' -> 'o'
                'ú' -> 'u'
                else -> c
            },
        )
    }

    /** The accented word by its spelling without the accent. */
    val restore: Map<String, String> by lazy { WORDS.split(' ').filter { it.isNotEmpty() }.associateBy(::bare) }
}
