package io.github.friendlyglobal.jacket.data

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.friendlyglobal.jacket.R
import org.json.JSONArray
import org.json.JSONObject

enum class BookFormat(val extension: String, @StringRes val label: Int) {
    EPUB("epub", R.string.format_epub),
    PDF("pdf", R.string.format_pdf),
    CBZ("cbz", R.string.format_comic),
    TXT("txt", R.string.format_text),
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
    /** When the book was marked finished; 0 while unread or in progress. */
    val finishedAt: Long = 0L,
) {
    val isFinished: Boolean get() = finishedAt > 0L

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
 * A bookmark or highlight. EPUB places are Readium locators (JSON); other formats use a page or
 * paragraph [section].
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
    val lineSpacing: LineSpacing = LineSpacing.BOOK,
    val margins: PageMargins = PageMargins.NORMAL,
    val alignment: TextAlignment = TextAlignment.BOOK,
    /** Volume buttons turn pages while reading (not while listening). */
    val volumeKeys: Boolean = false,
    /** Library filter: false shows the books you're reading and haven't started, true the finished shelf. */
    val showFinished: Boolean = false,
)

data class LibraryState(
    val books: List<Book> = emptyList(),
    val currentId: String? = null,
    val settings: Settings = Settings(),
    val annotations: List<Annotation> = emptyList(),
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
            put("lineSpacing", state.settings.lineSpacing.name)
            put("margins", state.settings.margins.name)
            put("alignment", state.settings.alignment.name)
            put("volumeKeys", state.settings.volumeKeys)
            put("showFinished", state.settings.showFinished)
        })
        put("books", JSONArray().apply { state.books.forEach { put(it.toJson()) } })
        put("annotations", JSONArray().apply { state.annotations.forEach { put(it.toJson()) } })
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
                lineSpacing = enumOr(s.optString("lineSpacing"), LineSpacing.BOOK),
                margins = enumOr(s.optString("margins"), PageMargins.NORMAL),
                alignment = enumOr(s.optString("alignment"), TextAlignment.BOOK),
                volumeKeys = s.optBoolean("volumeKeys", false),
                showFinished = s.optBoolean("showFinished", false),
            ),
            annotations = (root.optJSONArray("annotations") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).toAnnotation() }.getOrNull() }
            },
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
        put("finishedAt", finishedAt)
    }

    private fun Annotation.toJson() = JSONObject().apply {
        put("id", id); put("bookId", bookId); put("kind", kind.name)
        put("locator", locator ?: JSONObject.NULL); put("section", section)
        put("progress", progress.toDouble()); put("chapter", chapter); put("text", text)
        put("note", note); put("color", color.name); put("createdAt", createdAt)
    }

    private fun JSONObject.toAnnotation() = Annotation(
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
    )
}
