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
    /** Chapter index for EPUB, page index for PDF/CBZ, paragraph index for TXT. */
    val position: Int = 0,
    /** Scroll fraction within the current chapter (EPUB only). */
    val positionOffset: Float = 0f,
    /** Chapters (EPUB) or pages (PDF/CBZ); 0 when unknown until first opened. */
    val sectionCount: Int = 0,
    val progress: Float = 0f,
    val lastOpenedAt: Long = 0L,
) {
    val percent: Int get() = (progress * 100).toInt().coerceIn(0, 100)

    val positionLabel: String
        get() = when (format) {
            BookFormat.EPUB -> "Chapter ${position + 1}"
            BookFormat.PDF, BookFormat.CBZ -> "Page ${position + 1}"
            BookFormat.TXT -> "$percent%"
        }
}

data class Settings(
    val coverStyle: CoverStyle = CoverStyle.JACKET,
    val showProgress: Boolean = true,
    val ambientDim: Boolean = true,
    val autoOuterScreen: Boolean = true,
    val textScale: Float = 1f,
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
            ),
        )
    }

    private fun Book.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("format", format.name)
        put("fileName", fileName); put("coverFile", coverFile ?: JSONObject.NULL)
        put("seedColor", seedColor ?: JSONObject.NULL)
        put("sourceName", sourceName); put("sizeBytes", sizeBytes); put("addedAt", addedAt)
        put("position", position); put("positionOffset", positionOffset.toDouble())
        put("sectionCount", sectionCount); put("progress", progress.toDouble())
        put("lastOpenedAt", lastOpenedAt)
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
    )
}
