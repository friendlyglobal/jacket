// Localized strings for feature.html (the Google Play feature graphic, 1024x500).
// feature.html?lang=<fastlane locale> swaps these in; with no ?lang (or ?lang=en-US) the page
// is the English markup as written. Render every locale with ./render-feature.sh.
//
// Keys:   lang   BCP 47 tag for the page (picks CJK glyph variants and line-break rules)
//         script latn (Latin and Cyrillic: Literata + Roboto Flex), arab, deva, jpan, kore, hans, hant
//         dir    "rtl" mirrors the layout (text on the right, illustration on the left)
//         tag, sub, chips   the copy on the left;  title, novel, cap   the book in the illustration
// In tag/sub/title, "|" marks an allowed line break (CJK lines only break there).
// Optional: size {tag, sub, chip, title} starting font sizes in px; the page only ever shrinks
// them to fit, never grows them.
window.FEATURE_LOCALES = {
  "es-ES": {
    lang: "es", script: "latn",
    tag: "Viste tu plegable con el libro que estás leyendo.",
    sub: "La portada de tu libro, de borde a borde en la pantalla externa. Dentro, un lector tranquilo con lectura en voz alta, resaltados y búsqueda.",
    chips: ["EPUB · PDF · Cómics · Texto", "Libre y de código abierto", "Sin rastreo"],
    title: "El largo camino a\u00a0casa", novel: "Novela", cap: "Capítulo IV"
  },
  "pt-BR": {
    lang: "pt-BR", script: "latn",
    tag: "Vista seu dobrável com o livro que você está lendo.",
    sub: "A capa do seu livro, de ponta a ponta na tela externa. Por dentro, um leitor tranquilo, com leitura em voz alta, destaques e pesquisa.",
    chips: ["EPUB · PDF · HQs · Texto", "Grátis e de código aberto", "Sem rastreamento"],
    title: "O longo caminho de volta", novel: "Romance", cap: "Capítulo IV"
  },
  "fr-FR": {
    lang: "fr", script: "latn",
    tag: "Habillez votre pliable du livre que vous lisez.",
    sub: "La couverture de votre livre, bord à bord sur l’écran externe. À l’intérieur, une liseuse sereine avec lecture à voix haute, surlignage et recherche.",
    chips: ["EPUB · PDF · BD · Texte", "Libre et open source", "Sans pistage"],
    title: "Le long retour", novel: "Roman", cap: "Chapitre IV"
  },
  "it-IT": {
    lang: "it", script: "latn",
    tag: "Il tuo pieghevole indossa il libro che stai leggendo.",
    sub: "La copertina del tuo libro, da bordo a bordo sullo schermo esterno. Dentro, un e-reader sereno con lettura ad alta voce, evidenziazioni e ricerca.",
    chips: ["EPUB · PDF · Fumetti · Testo", "Libero e open source", "Zero tracciamento"],
    title: "La lunga strada di\u00a0casa", novel: "Romanzo", cap: "Capitolo IV"
  },
  "de-DE": {
    lang: "de", script: "latn",
    tag: "Dein Foldable trägt das Buch, das du gerade liest.",
    sub: "Das Cover deines Buchs randlos auf dem Außendisplay. Innen ein ruhiger E-Reader mit Vorlesen, Markierungen und Suche.",
    chips: ["EPUB · PDF · Comics · Text", "Frei & Open Source", "Kein Tracking"],
    title: "Der lange Weg nach Hause", novel: "Roman", cap: "Kapitel IV"
  },
  "nl-NL": {
    lang: "nl", script: "latn",
    tag: "Laat je foldable het boek dragen dat je leest.",
    sub: "De omslag van je boek, van rand tot rand op het buitenscherm. Binnenin een rustige e-reader met voorlezen, markeringen en zoeken.",
    chips: ["EPUB · PDF · Strips · Tekst", "Vrij en opensource", "Geen tracking"],
    title: "De lange weg naar huis", novel: "Roman", cap: "Hoofdstuk IV"
  },
  "pl-PL": {
    lang: "pl", script: "latn",
    tag: "Ubierz swój składak w\u00a0książkę, którą czytasz.",
    sub: "Okładka książki na całym ekranie zewnętrznym. W\u00a0środku spokojny czytnik z\u00a0czytaniem na głos, zaznaczeniami i\u00a0wyszukiwaniem.",
    chips: ["EPUB · PDF · Komiksy · Tekst", "Darmowy, otwarty kod", "Bez śledzenia"],
    title: "Długa droga do\u00a0domu", novel: "Powieść", cap: "Rozdział IV"
  },
  "ru-RU": {
    lang: "ru", script: "latn",
    tag: "Оденьте складной телефон в книгу, которую читаете.",
    sub: "Обложка вашей книги\u00a0— от края до края на внешнем экране. А внутри\u00a0— спокойная читалка с\u00a0чтением вслух, выделениями и\u00a0поиском.",
    chips: ["EPUB · PDF · Комиксы · Текст", "Свободное ПО", "Без слежки"],
    title: "Долгая дорога домой", novel: "Роман", cap: "Глава IV"
  },
  "uk": {
    lang: "uk", script: "latn",
    tag: "Одягніть складаний телефон у книгу, яку читаєте.",
    sub: "Обкладинка книги на весь зовнішній екран. Усередині\u00a0— спокійна читалка з\u00a0читанням уголос, виділеннями й\u00a0пошуком.",
    chips: ["EPUB · PDF · Комікси · Текст", "Вільне ПЗ", "Без стеження"],
    title: "Довга дорога додому", novel: "Роман", cap: "Розділ IV"
  },
  "tr-TR": {
    lang: "tr", script: "latn",
    tag: "Katlanabilir telefonunuz, okuduğunuz kitabı giysin.",
    sub: "Kitabınızın kapağı, dış ekranda kenardan kenara. İçeride ise sesli okuma, vurgular ve arama sunan sakin bir e-okuyucu.",
    chips: ["EPUB · PDF · Çizgi roman · Metin", "Ücretsiz, açık kaynak", "İzleme yok"],
    title: "Eve Giden Uzun Yol", novel: "Roman", cap: "Bölüm IV"
  },
  "ar": {
    lang: "ar", script: "arab", dir: "rtl",
    tag: "دع هاتفك القابل للطي يرتدي الكتاب الذي تقرؤه.",
    sub: "غلاف كتابك يملأ الشاشة الخارجية بالكامل. وفي الداخل قارئ هادئ مع القراءة بصوت عالٍ والتمييز والبحث.",
    chips: ["EPUB · PDF · قصص مصوّرة · نصوص", "مجاني ومفتوح المصدر", "بلا تتبّع"],
    title: "طريق العودة الطويل", novel: "رواية", cap: "الفصل الرابع"
  },
  "hi-IN": {
    lang: "hi", script: "deva",
    tag: "अपने फ़ोल्डेबल को वह किताब पहनाइए, जो आप पढ़ रहे हैं।",
    sub: "आपकी किताब का कवर, बाहरी स्क्रीन पर किनारे से किनारे तक। अंदर एक सुकून भरा रीडर, जिसमें पढ़कर सुनाना, हाइलाइट और खोज है।",
    chips: ["EPUB · PDF · कॉमिक · टेक्स्ट", "मुफ़्त और ओपन सोर्स", "कोई ट्रैकिंग नहीं"],
    title: "घर का लंबा रास्ता", novel: "उपन्यास", cap: "अध्याय 4"
  },
  "id": {
    lang: "id", script: "latn",
    tag: "Ponsel lipat Anda, berbalut buku yang sedang Anda baca.",
    sub: "Sampul buku Anda, penuh dari tepi ke tepi di layar luar. Di dalam, pembaca yang tenang dengan fitur bacakan, sorotan, dan penelusuran.",
    chips: ["EPUB · PDF · Komik · Teks", "Gratis & sumber terbuka", "Tanpa pelacakan"],
    title: "Jalan Panjang Pulang", novel: "Novel", cap: "Bab IV"
  },
  "ja-JP": {
    lang: "ja", script: "jpan",
    tag: "折りたたみスマホに、|読んでいる本を|まとわせよう。",
    sub: "外側の画面には、|本の表紙を|全面に表示。|内側には、|読み上げ・|ハイライト・|検索を|備えた|落ち着いた|リーダー。",
    chips: ["EPUB · PDF · コミック · テキスト", "無料・オープンソース", "トラッキングなし"],
    title: "長い|帰り道", novel: "長編小説", cap: "第四章"
  },
  "ko-KR": {
    lang: "ko", script: "kore",
    tag: "읽고 있는 책을 폴더블에 입혀 보세요.",
    sub: "외부 화면에는 책 표지가 가득. 안쪽에는 소리\u00a0내어\u00a0읽기, 하이라이트, 검색을 갖춘 차분한 리더.",
    chips: ["EPUB · PDF · 만화 · 텍스트", "무료 오픈소스", "추적 없음"],
    title: "집으로 가는 먼 길", novel: "장편소설", cap: "제4장"
  },
  "zh-CN": {
    lang: "zh-CN", script: "hans",
    tag: "让折叠屏|穿上|你正在读的书。",
    sub: "书的封面|在外屏|满屏展示。|内屏则是|安静的阅读器，|支持朗读、|划线|和搜索。",
    chips: ["EPUB · PDF · 漫画 · 纯文本", "免费开源", "无跟踪"],
    title: "漫长的|归途", novel: "长篇小说", cap: "第四章"
  },
  "zh-TW": {
    lang: "zh-TW", script: "hant",
    tag: "讓摺疊手機|穿上|你正在讀的書。",
    sub: "書的封面|在外螢幕|滿版顯示。|內螢幕則是|沉靜的閱讀器，|支援朗讀、|畫線|和搜尋。",
    chips: ["EPUB · PDF · 漫畫 · 純文字", "免費開源", "不追蹤"],
    title: "漫長的|歸途", novel: "長篇小說", cap: "第四章"
  }
};
