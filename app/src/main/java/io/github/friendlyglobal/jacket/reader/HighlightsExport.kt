package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.LibraryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * A book's highlights and notes as text: shared with another app, or saved as a Markdown file.
 * Both say the same thing in the same order: the book, then each highlight as a quote, where in
 * the book it is, and the note written on it.
 */

/** The file type highlights are saved as: plain text that any notes app opens, and many lay out. */
internal const val HIGHLIGHTS_MIME = "text/markdown"

/** The highlights among [annotations] that have words to show, in the order they come in the book. */
internal fun highlightsOf(annotations: List<Annotation>): List<Annotation> =
    annotations.filter { it.kind == AnnotationKind.HIGHLIGHT && it.text.isNotBlank() }.sortedBy { it.progress }

/** Where in its book [highlight] is, in words: "Chapter 3 · 42%", and for one in a PDF its page too: "Chapter 3 · Page 12 · 42%". */
internal fun highlightPlace(resources: Resources, highlight: Annotation): String = highlightPlace(
    highlight,
    separator = resources.getString(R.string.separator),
    page = { resources.getString(R.string.reader_page_number, it) },
    percent = { resources.getString(R.string.percent, it) },
)

/** The same, with the words to say it in: what goes between its parts, a [page] by its number (from 1), and a [percent]. */
internal fun highlightPlace(highlight: Annotation, separator: String, page: (Int) -> String, percent: (Int) -> String): String =
    listOfNotNull(highlight.chapter, highlight.pdfPage?.let { page(it + 1) }, percent((highlight.progress * 100).toInt()))
        .filter { it.isNotBlank() }.joinToString(separator)

/**
 * Everything highlighted in [book], with its notes, as text: a quote per paragraph. [place] says
 * where a highlight is (see [highlightPlace]).
 *
 * For another app ([asFile] false) it is as compact as a message should be. As a file it is the
 * same text set out as Markdown: the quote, where it is, and the note are each a paragraph of
 * their own (run together, Markdown would take all three for the quote), and a quote of several
 * lines is a quote on every one of them.
 */
internal fun highlightsText(book: Book, highlights: List<Annotation>, asFile: Boolean = false, place: (Annotation) -> String): String = buildString {
    append("# ").append(book.title.trim())
    if (book.author.isNotBlank()) append(" — ").append(book.author.trim())
    val between = if (asFile) "\n\n" else "\n"
    for (highlight in highlights) {
        val quote = highlight.text.trim()
        append("\n\n> ").append(if (asFile) quote.lines().joinToString("\n> ") { it.trim() } else quote)
        append(between).append('(').append(place(highlight)).append(')')
        if (highlight.note.isNotBlank()) append(between).append(highlight.note.trim())
    }
    if (asFile) append('\n')
}

/**
 * Every book's highlights in one Markdown file: the books that have any, by title, each under its
 * own heading. Null when nothing has been highlighted yet.
 */
internal fun allHighlightsMarkdown(state: LibraryState, place: (Annotation) -> String): String? {
    val byBook = state.annotations.groupBy { it.bookId }
    val books = state.books
        .mapNotNull { book -> highlightsOf(byBook[book.id].orEmpty()).takeIf { it.isNotEmpty() }?.let { book to it } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first.title })
    if (books.isEmpty()) return null
    return books.joinToString("\n") { (book, highlights) -> highlightsText(book, highlights, asFile = true, place) }
}

/**
 * The name offered for a highlights file: what it is of ([title], a book's or the app's), the word
 * for highlights in the reader's language, and, for the whole library's, the day. Without the
 * characters a file's name can't have.
 */
internal fun highlightsFileName(title: String, highlightsWord: String, day: String? = null): String {
    val name = listOf(title, highlightsWord, day.orEmpty()).joinToString(" ")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120).trim()
    return "$name.md"
}

/** Writes [text] to the file the reader picked at [uri]. Throws if it can't be written. */
internal suspend fun saveText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.settings_backup_cant_write))
    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
}
