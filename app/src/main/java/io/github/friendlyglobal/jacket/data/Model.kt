package io.github.friendlyglobal.jacket.data

import org.json.JSONArray
import org.json.JSONObject

enum class BookFormat(val extension: String, val label: String) {
    EPUB("epub", "EPUB"),
    PDF("pdf", "PDF"),
    CBZ("cbz", "Comic"),
    TXT("txt", "Text"),
}

/** How the cover-screen widget lays the book out. */
enum class CoverStyle(val label: String) {
    JACKET("Jacket"),
    SHELF("Shelf"),
    GLANCE("Glance"),
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
    /** Readium position for EPUB, page index for PDF/CBZ, paragraph index for TXT. */
    val position: Int = 0,
    /** Scroll fraction within the current chapter (EPUB only). */
    val positionOffset: Float = 0f,
    /** Positions (EPUB) or pages (PDF/CBZ); 0 when unknown until first opened. */
    val sectionCount: Int = 0,
    val progress: Float = 0f,
    val lastOpenedAt: Long = 0L,
    /** Exact reading location as a Readium locator (JSON), for EPUB. */
    val locator: String? = null,
) {
    val percent: Int get() = (progress * 100).toInt().coerceIn(0, 100)

    val isNew: Boolean get() = lastOpenedAt == 0L

    /** Short reading status for covers and widgets, e.g. "42% read" or "Page 3 of 40". */
    val statusLabel: String
        get() = when {
            isNew -> "Not started"
            (format == BookFormat.PDF || format == BookFormat.CBZ) && sectionCount > 0 ->
                "Page ${position + 1} of $sectionCount"
            else -> "$percent% read"
        }
}

enum class ReadingFont(val label: String, val description: String) {
    LITERATA("Literata", "Google's typeface for long reading"),
    HYPERLEGIBLE("Atkinson Hyperlegible", "Distinct letterforms for low vision"),
    OPEN_DYSLEXIC("OpenDyslexic", "Weighted letters that resist flipping"),
    SERIF("System serif", "Your phone's serif font"),
    SANS("System sans", "Your phone's interface font"),
    ORIGINAL("Book's own", "Whatever the publisher chose"),
}

enum class PageColor(val label: String) {
    BOOK("Book"),
    PAPER("Paper"),
    DARK("Dark"),
    BLACK("Black"),
}

enum class PageLayout(val label: String) {
    PAGES("Pages"),
    SCROLL("Scroll"),
}

enum class LibrarySort(val label: String) {
    RECENT("Recent"),
    TITLE("Title"),
    AUTHOR("Author"),
}

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
)

data class LibraryState(
    val books: List<Book> = emptyList(),
    val currentId: String? = null,
    val settings: Settings = Settings(),
) {
    /** The book the covers show: the one picked, else the one opened most recently. */
    val current: Book?
        get() = books.firstOrNull { it.id == currentId } ?: books.maxByOrNull { it.lastOpenedAt }

    fun find(id: String?): Book? = id?.let { books.firstOrNull { b -> b.id == it } }
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
        })
        put("books", JSONArray().apply { state.books.forEach { put(it.toJson()) } })
    }.toString()

    fun decode(text: String): LibraryState {
        val root = JSONObject(text)
        val s = root.optJSONObject("settings") ?: JSONObject()
        val books = root.optJSONArray("books") ?: JSONArray()
        return LibraryState(
            books = (0 until books.length()).mapNotNull { i -> runCatching { books.getJSONObject(i).toBook() }.getOrNull() },
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
            ),
        )
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
    )
}
