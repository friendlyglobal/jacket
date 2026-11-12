package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.importer.PdfLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.coroutines.coroutineContext

/*
 * Search inside a PDF.
 *
 * Where the phone's Android hands over a PDF's words (see PdfWords.kt), they are searched: the
 * words as they are read aloud, in paragraphs, so a word broken at a line's end or a sentence
 * that runs over a page is found whole, and running titles and page numbers are left out. Each
 * result says the page it is on and what stands around it; choosing one goes to its page and
 * marks it where it is drawn, by the same way the sentence being read aloud is marked.
 */

/** A stretch of a PDF's words: the letters [from] until [to] of [paragraph]. */
internal class PdfMatch(val paragraph: Int, val from: Int, val to: Int)

/** A result as the list shows it: the words found and what stands before and after them. */
internal class PdfQuote(val before: String, val match: String, val after: String)

/** Finding what was typed in a PDF's paragraphs, and saying where: apart from Android, so it can be tested. */
internal object PdfFinding {
    /** How much is quoted either side of what was found. */
    private const val BEFORE = 80
    private const val AFTER = 100

    /** Where [query] is in [text], the words of [paragraph]. */
    fun matches(paragraph: Int, text: String, query: TextSearch.Query): List<PdfMatch> =
        TextSearch.find(text, query).map { PdfMatch(paragraph, it.first, it.last + 1) }

    /** [match] in [text], its paragraph's words, with what stands around it; cut at a word where it can be. */
    fun quote(text: String, match: PdfMatch): PdfQuote {
        val from = match.from.coerceIn(0, text.length)
        val to = match.to.coerceIn(from, text.length)
        var start = (from - BEFORE).coerceAtLeast(0)
        if (start > 0) text.indexOf(' ', start).takeIf { it in start until from }?.let { start = it + 1 }
        var end = (to + AFTER).coerceAtMost(text.length)
        if (end < text.length) text.lastIndexOf(' ', end).takeIf { it > to }?.let { end = it }
        return PdfQuote(text.substring(start, from), text.substring(from, to), text.substring(to, end))
    }

    /** The page [match] is on (from 0), as [layout] has its paragraph laid out; null if it has no place on the pages. */
    fun pageOf(layout: PdfLayout, match: PdfMatch): Int? = layout.placeOf(match.paragraph, match.from)?.page
}

/**
 * Search for the PDF open in its reader. [paragraphs] hands over the words of one of the files
 * the PDF is read aloud from, each paragraph with its number; [go] takes the pages to a result,
 * and [mark] marks it on them (null takes the mark away).
 */
internal class PdfSearch(
    private val layout: PdfLayout,
    private val paragraphs: suspend (file: Int) -> List<Pair<Int, String>>,
    private val pageName: (Int) -> String,
    private val scope: CoroutineScope,
    private val go: (PdfMatch) -> Unit,
    private val mark: suspend (PdfMatch?, String) -> Unit,
) : BookSearch {
    private class Found(val match: PdfMatch, val text: String)

    private val found = ArrayList<Found>()

    /** The PDF's words, kept once read: a second search needn't read them again. */
    private val read = HashMap<Int, List<Pair<Int, String>>>()
    private val reading = Mutex()
    private var marking: Job? = null

    override suspend fun search(query: String, onHits: (List<SearchHit>) -> Unit) {
        found.clear()
        val wanted = TextSearch.query(query)
        if (wanted.blank) return
        for (file in 0 until layout.fileCount) {
            coroutineContext.ensureActive()
            val words = reading.withLock { read[file] ?: paragraphs(file).also { read[file] = it } }
            val matches = withContext(Dispatchers.Default) {
                words.flatMap { (paragraph, text) -> PdfFinding.matches(paragraph, text, wanted).map { Found(it, text) } }
            }
            coroutineContext.ensureActive()
            val batch = matches.take(MOST - found.size).mapNotNull { hit ->
                val page = PdfFinding.pageOf(layout, hit.match) ?: return@mapNotNull null
                val quote = PdfFinding.quote(hit.text, hit.match)
                found += hit
                SearchHit(index = found.lastIndex, chapter = pageName(page), before = quote.before, match = quote.match, after = quote.after)
            }
            if (batch.isNotEmpty()) onHits(batch)
            if (found.size >= MOST) break
        }
    }

    override fun show(hit: SearchHit) {
        val chosen = found.getOrNull(hit.index) ?: return
        go(chosen.match)
        marking?.cancel()
        marking = scope.launch { mark(chosen.match, chosen.text.substring(chosen.match.from, chosen.match.to)) }
    }

    override fun clear() {
        found.clear()
        marking?.cancel()
        marking = scope.launch { mark(null, "") }
    }

    private companion object {
        /** No more results than this are listed: a word found this often is no way to find a place. */
        const val MOST = 5_000
    }
}

/** The words of file [file] of the pages [open]'s PDF is read aloud from, each paragraph with its number. */
internal suspend fun OpenBook.pdfParagraphs(file: Int): List<Pair<Int, String>> {
    val layout = pdf?.layout ?: return emptyList()
    val link = publication.readingOrder.getOrNull(file) ?: return emptyList()
    return withContext(Dispatchers.IO) {
        publication.textBlocks(link.url(), link.mediaType ?: MediaType.XHTML).mapNotNull { block ->
            layout.paragraphOf(block.locator.locations.cssSelector)?.let { it to block.text }
        }
    }
}
