package io.github.friendlyglobal.jacket.data

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.voice.Respelling
import org.json.JSONArray
import org.json.JSONObject

enum class BookFormat(val extension: String, @StringRes val label: Int) {
    EPUB("epub", R.string.format_epub),
    PDF("pdf", R.string.format_pdf),
    CBZ("cbz", R.string.format_comic),
    TXT("txt", R.string.format_text),

    /** An FB2 book, zipped or not: kept as it came, and read through pages made of it (see Fb2Book.kt). */
    FB2("fb2", R.string.format_fb2),
    ;

    /** Laid out in pages of words by Readium: an EPUB, and the pages made of a text file or an FB2 book. */
    val isWords: Boolean get() = this == EPUB || this == TXT || this == FB2

    /** Its place is one of Readium's alone, the same on every device: an EPUB's, and an FB2 book's (a text file's is its paragraph as well). */
    val isEpubLike: Boolean get() = this == EPUB || this == FB2
}

/** How the cover-screen widget lays the book out. */
enum class CoverStyle(@StringRes val label: Int) {
    JACKET(R.string.cover_style_jacket),
    SHELF(R.string.cover_style_shelf),
    GLANCE(R.string.cover_style_glance),
}

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    /** File name inside [Library.booksDir]. */
    val fileName: String,
    /** File name inside [Library.coversDir]. */
    val coverFile: String?,
    /** Seed for the book's Material color scheme, picked from its cover. */
    val seedColor: Int?,
    val sourceName: String,
    val sizeBytes: Long,
    val addedAt: Long,
    /** Readium position for EPUB and FB2, page index for PDF/CBZ, paragraph index for TXT (kept beside [locator]: see Library.updateLocator). */
    val position: Int = 0,
    /** How far into that page or paragraph the place is (0..1): PDF/CBZ and TXT. (Jacket 0.2 kept a chapter's scroll fraction here for EPUB.) */
    val positionOffset: Float = 0f,
    /** Positions (EPUB), pages (PDF/CBZ) or paragraphs (TXT); 0 when unknown until first opened. */
    val sectionCount: Int = 0,
    val progress: Float = 0f,
    val lastOpenedAt: Long = 0L,
    /** Exact reading location as a Readium locator (JSON), for EPUB, and for TXT and FB2, which are read through pages made of them. */
    val locator: String? = null,
    /** When the book was marked finished; 0 while unread or in progress. */
    val finishedAt: Long = 0L,
    /**
     * Your pace in this book: the milliseconds the whole of it would take at the rate you've been
     * turning its pages; 0 until that's been measured. "Time left" is worked out from it.
     */
    val pace: Float = 0f,
    /** The language the book says it is written in (a BCP 47 tag), when it says: picks the voice that reads it aloud. */
    val language: String? = null,
    /** When the book was pinned to the top of the library; 0 while it isn't. */
    val pinnedAt: Long = 0L,
    /** The ids of the [BookCollection]s it's in. */
    val collections: Set<String> = emptySet(),
    /**
     * The reader gave the book a cover of their own (a photo, or one they designed). The one it
     * came with is kept in [originalCover], with its color in [originalSeed], to go back to.
     */
    val coverCustomized: Boolean = false,
    val originalCover: String? = null,
    val originalSeed: Int? = null,
    /**
     * The SHA-256 of the file the book was made from, as hex: with [sizeBytes], what tells the same
     * file when it is added again, whatever it is called by then. Null for a book added before
     * Jacket kept it; its file is read for it when it is needed.
     */
    val sha256: String? = null,
) {
    val isFinished: Boolean get() = finishedAt > 0L

    val isPinned: Boolean get() = pinnedAt > 0L

    val percent: Int get() = (progress * 100).toInt().coerceIn(0, 100)

    val isNew: Boolean get() = lastOpenedAt == 0L

    /** Short reading status for covers and widgets, e.g. "42% read" or "Page 3 of 40". */
    fun statusLabel(resources: Resources): String = when {
        isFinished -> resources.getString(R.string.book_status_finished)
        isNew -> resources.getString(R.string.book_status_not_started)
        (format == BookFormat.PDF || format == BookFormat.CBZ) && sectionCount > 0 ->
            resources.getString(R.string.book_status_page_of, position + 1, sectionCount)
        else -> resources.getString(R.string.book_status_percent_read, percent)
    }
}

/**
 * [this] book with a new reading place: the Readium [locator], how far through the book it is
 * counted ([progress]), and what is kept beside it (see [Library.updateLocator]).
 *
 * The progress is what the library, the widgets, the cover and the reader all show, and it never
 * goes down by itself. The same place is counted a little differently each time it is counted
 * over (when the book is reopened, when its pages are laid out anew by a fold, when the voice
 * hands it to a reader or takes it back), and every saved place comes through here. So a count
 * lower than the one the book has is only taken when the reader went [back] through the book
 * themselves: a page turned back, a jump, an earlier sentence tapped. The place itself is always
 * the one given.
 */
internal fun Book.leftAt(locator: String, progress: Float, position: Int, positions: Int, offset: Float?, back: Boolean): Book =
    copy(
        locator = locator,
        progress = countedAt(progress, back),
        position = position.coerceAtLeast(0),
        positionOffset = offset?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: positionOffset,
        sectionCount = positions.coerceAtLeast(0),
    )

/**
 * [this] book left on a page of pictures (a PDF's, a comic's) by scrolling: [page] of [pages],
 * with the top of the screen [offset] of the way down it, counted as [progress] of the way
 * through the book.
 *
 * The same rule as [leftAt]: the place is always the one given, and the progress only falls when
 * the reader went [back]. A page is counted from how far down it the screen's top is, which is
 * never quite the same twice (the page is a different height folded and unfolded, and the voice
 * counts its sentence's own line, a little below the top of the screen it is shown on). The
 * sentence a PDF was last read aloud to ([Book.locator]) stays as it is: scrolling says nothing of it.
 */
internal fun Book.leftOnPage(page: Int, offset: Float, progress: Float, pages: Int, back: Boolean): Book =
    copy(
        progress = countedAt(progress, back),
        position = page.coerceAtLeast(0),
        positionOffset = offset.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: positionOffset,
        sectionCount = pages.coerceAtLeast(0),
    )

/** The progress [this] book has once a place counted as [progress] is saved: lower than it was only if the reader went [back]. */
private fun Book.countedAt(progress: Float, back: Boolean): Float {
    val counted = progress.coerceIn(0f, 1f)
    return if (back || !this.progress.isFinite()) counted else maxOf(this.progress, counted)
}

enum class ReadingFont(@StringRes val label: Int, @StringRes val description: Int) {
    LITERATA(R.string.font_literata, R.string.font_literata_description),
    HYPERLEGIBLE(R.string.font_hyperlegible, R.string.font_hyperlegible_description),
    OPEN_DYSLEXIC(R.string.font_open_dyslexic, R.string.font_open_dyslexic_description),
    SERIF(R.string.font_serif, R.string.font_serif_description),
    SANS(R.string.font_sans, R.string.font_sans_description),
    ORIGINAL(R.string.font_original, R.string.font_original_description),
}

enum class PageColor(@StringRes val label: Int) {
    BOOK(R.string.page_color_book),
    PAPER(R.string.page_color_paper),
    DARK(R.string.page_color_dark),
    BLACK(R.string.page_color_black),
}

enum class PageLayout(@StringRes val label: Int) {
    PAGES(R.string.layout_pages),
    SCROLL(R.string.layout_scroll),
}

/**
 * How a page turns: SLIDE is Readium's own strip of pages, PAPER lifts the page off and slides it
 * over the next, CURL folds it over like a leaf of a book, CARDS deals it away on a spring.
 */
enum class PageTurn(@StringRes val label: Int) {
    SLIDE(R.string.page_turn_slide),
    PAPER(R.string.page_turn_paper),
    CURL(R.string.page_turn_curl),
    CARDS(R.string.page_turn_cards),
}

enum class LibrarySort(@StringRes val label: Int) {
    RECENT(R.string.sort_recent),
    TITLE(R.string.sort_title),
    AUTHOR(R.string.sort_author),
}

/** Line spacing; BOOK keeps the publisher's. */
enum class LineSpacing(val lineHeight: Double?) { BOOK(null), TIGHT(1.25), NORMAL(1.5), LOOSE(1.8) }

/** Side margins around the text. */
enum class PageMargins(val readium: Double) { NARROW(0.5), NORMAL(1.0), WIDE(1.6) }

/** Text alignment; BOOK keeps the publisher's. Justified text is hyphenated. */
enum class TextAlignment { BOOK, START, JUSTIFY }

/** Highlight colours, as page-agnostic tints. */
enum class HighlightColor(val argb: Int) {
    YELLOW(0xFFF5C518.toInt()),
    GREEN(0xFF6CC56C.toInt()),
    BLUE(0xFF5AA9E6.toInt()),
    PINK(0xFFF06BA8.toInt()),
    PURPLE(0xFFA98BF0.toInt()),
}

enum class AnnotationKind { BOOKMARK, HIGHLIGHT }

/**
 * A bookmark or highlight. EPUB and text places are Readium locators (JSON); PDF and comics use a
 * page [section]. A text book's also keep the paragraph they are in as [section], which is all a
 * bookmark made before text books had pages has.
 */
data class Annotation(
    val id: String,
    val bookId: String,
    val kind: AnnotationKind,
    val locator: String? = null,
    val section: Int = 0,
    /** Whole-book progress (0..1), for ordering and display. */
    val progress: Float = 0f,
    val chapter: String = "",
    /** The highlighted text, or a snippet of the bookmarked page. */
    val text: String = "",
    val note: String = "",
    val color: HighlightColor = HighlightColor.YELLOW,
    val createdAt: Long = 0L,
)

/**
 * "Say it this way": a word the voice is to say another way when it reads aloud, written the way
 * it sounds ([say]). In one book ([bookId]), or in every book (null).
 */
data class Saying(
    val id: String,
    val word: String,
    val say: String,
    val bookId: String? = null,
    val createdAt: Long = 0L,
)

data class Settings(
    val coverStyle: CoverStyle = CoverStyle.JACKET,
    val showProgress: Boolean = true,
    val ambientDim: Boolean = true,
    val autoOuterScreen: Boolean = true,
    val textScale: Float = 1f,
    /** Tint the app with the last-read book's colors; otherwise follow the system palette. */
    val bookColors: Boolean = true,
    val font: ReadingFont = ReadingFont.LITERATA,
    val pageColor: PageColor = PageColor.BOOK,
    val layout: PageLayout = PageLayout.PAGES,
    val sort: LibrarySort = LibrarySort.RECENT,
    /** Read-aloud speed, 1 = the voice's normal pace. */
    val listenRate: Float = 1f,
    /** The sleep timer last set to a length of the listener's own, in minutes: offered again beside the usual ones. 0 while there's been none. */
    val sleepMinutes: Int = 0,
    val lineSpacing: LineSpacing = LineSpacing.BOOK,
    val margins: PageMargins = PageMargins.NORMAL,
    val alignment: TextAlignment = TextAlignment.BOOK,
    /** Volume buttons turn pages while reading (not while listening). */
    val volumeKeys: Boolean = false,
    /** Library filter: false shows the books you're reading and haven't started, true the finished shelf. */
    val showFinished: Boolean = false,
    val pageTurn: PageTurn = PageTurn.PAPER,
    /** Folded shut with a book open, the screen shows its cover instead of the page: reading waits until you unfold. */
    val coverWhenClosed: Boolean = false,
    /** The welcome has been seen (or skipped). */
    val onboarded: Boolean = false,
    /** The footer tells the minutes left in the chapter (once your pace is known) rather than the pages. */
    val timeLeft: Boolean = true,
    /** Swiping up and down the page's left edge sets the brightness, the right edge the warmth. */
    val edgeSwipes: Boolean = true,
    /** The reader's own brightness (0..1), or negative to leave it to the phone. */
    val brightness: Float = -1f,
    /** How warm the page is tinted (0..1). */
    val warmth: Float = 0f,
    /** While the phone is in dark theme, Paper pages are shown dark. */
    val nightPages: Boolean = false,
    /** Half-folded on a table, the page sits above the fold and the controls below it. */
    val tabletop: Boolean = true,
    /** The library shows today's reading time and the run of days read. */
    val readingStats: Boolean = true,
    /** The ambient cover shows one of the book's highlights. */
    val ambientQuote: Boolean = false,
    /** A folder (a document tree URI) whose new books are added when Jacket opens. */
    val watchFolder: String? = null,
    /** Files already taken from [watchFolder] ("name|size"): removing a book doesn't bring it back. */
    val watchSeen: Set<String> = emptySet(),
    /**
     * The voice that reads aloud: a natural voice ("pack/voice", see the voice package), "system"
     * for the phone's own, or null while nobody has chosen (then Jacket picks: a natural voice
     * where the phone can carry one). A natural voice only reads the languages it speaks; the
     * rest stay with the phone's.
     */
    val voice: String? = null,
    /** A different voice for one language, by language code ("pt" to "pack/voice" or "system"). */
    val voices: Map<String, String> = emptyMap(),
    /** The collection the library is showing (a [BookCollection] id), or null for every book. */
    val collection: String? = null,
    /** Read aloud, the small numbers that point to notes are passed over. */
    val skipNoteNumbers: Boolean = true,
)

data class LibraryState(
    val books: List<Book> = emptyList(),
    val currentId: String? = null,
    val settings: Settings = Settings(),
    val annotations: List<Annotation> = emptyList(),
    /** Time spent reading, per day (ISO date to milliseconds): today's total and the streak. */
    val reading: Map<String, Long> = emptyMap(),
    /** The reader's own collections, in the order they were made. */
    val collections: List<BookCollection> = emptyList(),
    /** Words the voice says the listener's way ("Say it this way"), in the order they were made. */
    val sayings: List<Saying> = emptyList(),
) {
    /** The book the covers show: the one picked, else the one opened most recently. */
    val current: Book?
        get() {
            // Finished books step aside for what you're reading now, unless everything's finished.
            val reading = books.filterNot { it.isFinished }
            return reading.firstOrNull { it.id == currentId }
                ?: reading.maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }
                ?: books.firstOrNull { it.id == currentId }
                ?: books.maxByOrNull { it.lastOpenedAt }
        }

    fun find(id: String?): Book? = id?.let { books.firstOrNull { b -> b.id == it } }

    /**
     * How the voice is to say words in the book [bookId]: the book's own first, then those for
     * every book, so that a word fixed both ways is said the book's way.
     */
    fun respellingsFor(bookId: String): List<Respelling> =
        (sayings.filter { it.bookId == bookId } + sayings.filter { it.bookId == null }).map { Respelling(it.word, it.say) }
}

/**
 * How many days in a row there has been reading, up to [today] (an ISO date); a day not yet read
 * in doesn't break the run that reached yesterday. A day counts from a minute of reading.
 */
fun readingStreak(reading: Map<String, Long>, today: java.time.LocalDate): Int {
    fun read(day: java.time.LocalDate) = (reading[day.toString()] ?: 0L) >= 60_000L
    var day = if (read(today)) today else today.minusDays(1)
    var run = 0
    while (read(day)) {
        run++
        day = day.minusDays(1)
    }
    return run
}

internal object LibraryJson {
    fun encode(state: LibraryState): String = JSONObject().apply {
        put("version", 1)
        put("currentId", state.currentId ?: JSONObject.NULL)
        put("settings", JSONObject().apply {
            put("coverStyle", state.settings.coverStyle.name)
            put("showProgress", state.settings.showProgress)
            put("ambientDim", state.settings.ambientDim)
            put("autoOuterScreen", state.settings.autoOuterScreen)
            put("textScale", state.settings.textScale.toDouble())
            put("bookColors", state.settings.bookColors)
            put("font", state.settings.font.name)
            put("pageColor", state.settings.pageColor.name)
            put("layout", state.settings.layout.name)
            put("sort", state.settings.sort.name)
            put("listenRate", state.settings.listenRate.toDouble())
            put("sleepMinutes", state.settings.sleepMinutes)
            put("lineSpacing", state.settings.lineSpacing.name)
            put("margins", state.settings.margins.name)
            put("alignment", state.settings.alignment.name)
            put("volumeKeys", state.settings.volumeKeys)
            put("showFinished", state.settings.showFinished)
            put("pageTurn", state.settings.pageTurn.name)
            put("coverWhenClosed", state.settings.coverWhenClosed)
            put("onboarded", state.settings.onboarded)
            put("timeLeft", state.settings.timeLeft)
            put("edgeSwipes", state.settings.edgeSwipes)
            put("brightness", state.settings.brightness.toDouble())
            put("warmth", state.settings.warmth.toDouble())
            put("nightPages", state.settings.nightPages)
            put("tabletop", state.settings.tabletop)
            put("readingStats", state.settings.readingStats)
            put("ambientQuote", state.settings.ambientQuote)
            put("watchFolder", state.settings.watchFolder ?: JSONObject.NULL)
            put("watchSeen", JSONArray(state.settings.watchSeen.toList()))
            put("voice", state.settings.voice ?: JSONObject.NULL)
            put("voices", JSONObject().apply { state.settings.voices.forEach { (language, voice) -> put(language, voice) } })
            put("collection", state.settings.collection ?: JSONObject.NULL)
            put("skipNoteNumbers", state.settings.skipNoteNumbers)
        })
        put("sayings", JSONArray().apply {
            state.sayings.forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("word", it.word); put("say", it.say)
                    put("bookId", it.bookId ?: JSONObject.NULL); put("createdAt", it.createdAt)
                })
            }
        })
        put("collections", JSONArray().apply {
            state.collections.forEach { put(JSONObject().apply { put("id", it.id); put("name", it.name); put("createdAt", it.createdAt) }) }
        })
        put("reading", JSONObject().apply { state.reading.forEach { (day, millis) -> put(day, millis) } })
        put("books", JSONArray().apply { state.books.forEach { put(it.toJson()) } })
        put("annotations", JSONArray().apply { state.annotations.forEach { put(it.toJson()) } })
    }.toString()

    /** A library as read from its file, and how many entries of the file couldn't be read. */
    class Read(val state: LibraryState, val unread: Int)

    fun decode(text: String): LibraryState = read(text).state

    /**
     * Reads a library file, old or new: anything a file doesn't say takes the value it had before
     * there was a setting for it. An entry that can't be read (a book in a format this version
     * doesn't know, a damaged one) is left out and counted, so that whoever saves over the file
     * knows it would lose something. Throws if the text isn't a library at all.
     */
    fun read(text: String): Read {
        val root = JSONObject(text)
        var unread = 0
        // A list that is there but isn't a list is as lost as its entries.
        fun list(name: String): JSONArray = root.optJSONArray(name) ?: JSONArray().also { if (root.has(name) && !root.isNull(name)) unread++ }
        fun <T : Any> JSONArray.entries(read: (JSONObject) -> T?): List<T> =
            (0 until length()).mapNotNull { i -> runCatching { read(getJSONObject(i)) }.getOrNull().also { if (it == null) unread++ } }
        val s = root.optJSONObject("settings") ?: JSONObject().also { if (root.has("settings") && !root.isNull("settings")) unread++ }
        val state = LibraryState(
            books = list("books").entries { it.toBook() },
            currentId = root.optString("currentId").takeIf { it.isNotEmpty() && it != "null" },
            settings = Settings(
                coverStyle = runCatching { CoverStyle.valueOf(s.optString("coverStyle")) }.getOrDefault(CoverStyle.JACKET),
                showProgress = s.optBoolean("showProgress", true),
                ambientDim = s.optBoolean("ambientDim", true),
                autoOuterScreen = s.optBoolean("autoOuterScreen", true),
                textScale = s.optDouble("textScale", 1.0).toFloat(),
                bookColors = s.optBoolean("bookColors", true),
                font = enumOr(s.optString("font"), ReadingFont.LITERATA),
                pageColor = enumOr(s.optString("pageColor"), PageColor.BOOK),
                layout = enumOr(s.optString("layout"), PageLayout.PAGES),
                sort = enumOr(s.optString("sort"), LibrarySort.RECENT),
                listenRate = s.optDouble("listenRate", 1.0).toFloat().takeIf { it.isFinite() && it in 0.5f..3f } ?: 1f,
                sleepMinutes = s.optInt("sleepMinutes", 0).takeIf { it in 1..999 } ?: 0,
                lineSpacing = enumOr(s.optString("lineSpacing"), LineSpacing.BOOK),
                margins = enumOr(s.optString("margins"), PageMargins.NORMAL),
                alignment = enumOr(s.optString("alignment"), TextAlignment.BOOK),
                volumeKeys = s.optBoolean("volumeKeys", false),
                showFinished = s.optBoolean("showFinished", false),
                pageTurn = enumOr(s.optString("pageTurn"), PageTurn.PAPER),
                coverWhenClosed = s.optBoolean("coverWhenClosed", false),
                // A library saved before there was a welcome belongs to someone who needs none.
                onboarded = s.optBoolean("onboarded", true),
                timeLeft = s.optBoolean("timeLeft", true),
                edgeSwipes = s.optBoolean("edgeSwipes", true),
                brightness = s.optDouble("brightness", -1.0).toFloat().takeIf { it.isFinite() } ?: -1f,
                warmth = s.optDouble("warmth", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                nightPages = s.optBoolean("nightPages", false),
                tabletop = s.optBoolean("tabletop", true),
                readingStats = s.optBoolean("readingStats", true),
                ambientQuote = s.optBoolean("ambientQuote", false),
                watchFolder = if (s.isNull("watchFolder")) null else s.optString("watchFolder").takeIf { it.isNotEmpty() },
                watchSeen = s.optJSONArray("watchSeen")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.optString(it) } }.orEmpty(),
                // Libraries saved before there were natural voices have neither.
                voice = if (s.isNull("voice")) null else s.optString("voice").takeIf { it.isNotEmpty() },
                voices = s.optJSONObject("voices")?.let { v -> v.keys().asSequence().associateWith { v.optString(it) }.filterValues { it.isNotEmpty() } }.orEmpty(),
                collection = if (s.isNull("collection")) null else s.optString("collection").takeIf { it.isNotEmpty() },
                // Libraries and backups from before there was the choice: notes' numbers are passed over.
                skipNoteNumbers = s.optBoolean("skipNoteNumbers", true),
            ),
            // Libraries from before collections have none.
            collections = list("collections").entries { c ->
                c.optString("id").takeIf { it.isNotEmpty() }?.let { id -> BookCollection(id, c.optString("name"), c.optLong("createdAt")) }
            },
            reading = root.optJSONObject("reading")?.let { days -> days.keys().asSequence().associateWith { days.optLong(it) } }.orEmpty(),
            annotations = list("annotations").entries { it.toAnnotation() },
            // Libraries and backups from before "Say it this way" have none.
            sayings = list("sayings").entries { it.toSaying() },
        )
        return Read(state, unread)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)

    private fun Book.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("format", format.name)
        put("fileName", fileName); put("coverFile", coverFile ?: JSONObject.NULL)
        put("seedColor", seedColor ?: JSONObject.NULL)
        put("sourceName", sourceName); put("sizeBytes", sizeBytes); put("addedAt", addedAt)
        put("position", position); put("positionOffset", positionOffset.toDouble())
        put("sectionCount", sectionCount); put("progress", progress.toDouble())
        put("lastOpenedAt", lastOpenedAt)
        put("locator", locator ?: JSONObject.NULL)
        put("finishedAt", finishedAt)
        put("pace", pace.toDouble())
        put("language", language ?: JSONObject.NULL)
        put("pinnedAt", pinnedAt)
        put("collections", JSONArray(collections.toList()))
        put("coverCustomized", coverCustomized)
        put("originalCover", originalCover ?: JSONObject.NULL)
        put("originalSeed", originalSeed ?: JSONObject.NULL)
        put("sha256", sha256 ?: JSONObject.NULL)
    }

    /** A bookmark or highlight as it is written to the library, a backup and the file other devices read. */
    fun Annotation.toJson() = JSONObject().apply {
        put("id", id); put("bookId", bookId); put("kind", kind.name)
        put("locator", locator ?: JSONObject.NULL); put("section", section)
        put("progress", progress.toDouble()); put("chapter", chapter); put("text", text)
        put("note", note); put("color", color.name); put("createdAt", createdAt)
    }

    fun JSONObject.toAnnotation() = Annotation(
        id = getString("id"),
        bookId = getString("bookId"),
        kind = AnnotationKind.valueOf(getString("kind")),
        locator = if (isNull("locator")) null else optString("locator").takeIf { it.isNotEmpty() },
        section = optInt("section"),
        progress = optDouble("progress", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
        chapter = optString("chapter"),
        text = optString("text"),
        note = optString("note"),
        color = enumOr(optString("color"), HighlightColor.YELLOW),
        createdAt = optLong("createdAt"),
    )

    /** A word and how to say it; null (and counted as unread) if either is missing. */
    private fun JSONObject.toSaying(): Saying? {
        val word = optString("word").trim()
        val say = optString("say").trim()
        if (word.isEmpty() || say.isEmpty()) return null
        return Saying(
            id = getString("id"),
            word = word,
            say = say,
            bookId = if (isNull("bookId")) null else optString("bookId").takeIf { it.isNotEmpty() },
            createdAt = optLong("createdAt"),
        )
    }

    private fun JSONObject.toBook() = Book(
        id = getString("id"),
        title = optString("title"),
        author = optString("author"),
        format = BookFormat.valueOf(getString("format")),
        fileName = getString("fileName"),
        coverFile = optString("coverFile").takeIf { it.isNotEmpty() && it != "null" },
        seedColor = if (isNull("seedColor") || !has("seedColor")) null else getInt("seedColor"),
        sourceName = optString("sourceName"),
        sizeBytes = optLong("sizeBytes"),
        addedAt = optLong("addedAt"),
        position = optInt("position"),
        positionOffset = optDouble("positionOffset", 0.0).toFloat(),
        sectionCount = optInt("sectionCount"),
        progress = optDouble("progress", 0.0).toFloat(),
        lastOpenedAt = optLong("lastOpenedAt"),
        locator = if (isNull("locator")) null else optString("locator").takeIf { it.isNotEmpty() },
        // Jacket 0.x had no "finished" flag; a book read to the end counts as finished.
        finishedAt = if (has("finishedAt")) optLong("finishedAt")
        else if (optDouble("progress", 0.0) >= 0.99) optLong("lastOpenedAt").coerceAtLeast(1L) else 0L,
        pace = optDouble("pace", 0.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 0f,
        language = if (isNull("language")) null else optString("language").takeIf { it.isNotEmpty() },
        pinnedAt = optLong("pinnedAt"),
        collections = optJSONArray("collections")?.let { a -> (0 until a.length()).mapNotNullTo(HashSet()) { a.optString(it).takeIf(String::isNotEmpty) } }.orEmpty(),
        coverCustomized = optBoolean("coverCustomized", false),
        originalCover = if (isNull("originalCover")) null else optString("originalCover").takeIf { it.isNotEmpty() },
        originalSeed = if (isNull("originalSeed") || !has("originalSeed")) null else getInt("originalSeed"),
        // Libraries and backups made before Jacket kept it have none.
        sha256 = if (isNull("sha256")) null else optString("sha256").takeIf { it.isNotEmpty() },
    )
}
