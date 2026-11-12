package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.importer.PdfPlace
import io.github.friendlyglobal.jacket.importer.PdfSpot
import io.github.friendlyglobal.jacket.importer.sentenceOffset
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.math.abs

/*
 * A PDF read aloud: between the voice's sentences and the PDF's pages.
 *
 * The voice reads the plain pages made of the PDF's words (see PdfWords.kt), and names each
 * sentence by the paragraph it is in ("p12") and its words. The reader shows the PDF's own pages,
 * and the book's place is one of those pages and how far down it. What follows goes from one to
 * the other: where on the PDF a sentence is, and which words are read from a place on the PDF.
 */

/** A sentence of the voice's in a PDF: the paragraph it is in, and which of the paragraph's letters it is. */
internal data class PdfSentence(val paragraph: Int, val from: Int, val to: Int)

/** Where the voice starts in a book whose pages aren't Readium's: the block of its reading, how many letters into it (spaces aside), and whether that is the sentence it stopped on. */
internal class VoiceStart(val block: Content.TextElement, val offset: Int, val resumed: Boolean)

/** A place counted again within this much of a page's height of where it was kept is the same place. */
private const val SAME_PLACE = 0.25f

/** How many of the words at a place it is told by, when it is told as words. */
private const val PLACE_WORDS = 80

/** The paragraph [paragraph] of a PDF's words, as the voice's reading of them has it; null if it isn't there. */
internal suspend fun OpenBook.pdfBlock(paragraph: Int): Content.TextElement? {
    val layout = pdf?.layout ?: return null
    val link = publication.readingOrder.getOrNull(layout.fileOf(paragraph)) ?: return null
    val name = "#" + layout.idOf(paragraph)
    return spoken.of(link.url(), link.mediaType ?: MediaType.XHTML).blocks.firstOrNull { it.locator.locations.cssSelector == name }
}

/**
 * [sentence], one of the voice's and [length] letters long, as the letters of its paragraph: the
 * way to where it is drawn on the PDF's pages. Null if it isn't one of this PDF's.
 */
internal suspend fun OpenBook.pdfSentence(sentence: Locator, length: Int): PdfSentence? {
    val layout = pdf?.layout ?: return null
    val paragraph = layout.paragraphOf(sentence.locations.cssSelector) ?: return null
    val text = pdfBlock(paragraph)?.text ?: return PdfSentence(paragraph, 0, length.coerceAtLeast(1))
    val from = sentenceOffset(text, sentence.text.highlight.orEmpty(), sentence.text.before)
    return PdfSentence(paragraph, from, (from + length.coerceAtLeast(1)).coerceAtMost(text.length).coerceAtLeast(from + 1))
}

/** Where the voice starts for [spot]: its paragraph in the voice's reading, and how far into it. */
internal suspend fun OpenBook.pdfStart(spot: PdfSpot, resumed: Boolean): VoiceStart? {
    val block = pdfBlock(spot.paragraph) ?: return null
    return VoiceStart(block, bare(block.text.take(spot.offset)).length, resumed)
}

/**
 * [spot] as a place the voice can be started from when there is no page to ask: the words there,
 * and how far down their file they are. (Not told as one of the voice's own sentences: it is a
 * place a reader was at, and the voice starts with the sentence that holds it.)
 */
internal suspend fun OpenBook.pdfLocator(spot: PdfSpot): Locator? {
    val layout = pdf?.layout ?: return null
    val link = publication.readingOrder.getOrNull(layout.fileOf(spot.paragraph)) ?: return null
    val file = spoken.of(link.url(), link.mediaType ?: MediaType.XHTML)
    val name = "#" + layout.idOf(spot.paragraph)
    val block = file.blocks.indexOfFirst { it.locator.locations.cssSelector == name }.takeIf { it >= 0 } ?: return null
    val text = file.blocks[block].text
    val offset = spot.offset.coerceIn(0, text.length)
    return Locator(
        href = link.url(),
        mediaType = link.mediaType ?: MediaType.XHTML,
        locations = Locator.Locations(progression = file.places.at(block, bare(text.take(offset)).length)),
        text = Locator.Text(highlight = text.substring(offset).take(PLACE_WORDS)),
    )
}

/**
 * The place [book], a PDF, was left at, for the voice to start from with no reader open; null to
 * start with its first words.
 *
 * The book's place is a page and how far down it. Where that is still the sentence the voice
 * last stopped on (nobody has turned the pages since), the voice takes up that sentence. Where a
 * reader has moved on, it starts with the words at the top of where they left the page; on a page
 * with no words (a scan), with the next that has any.
 */
internal suspend fun OpenBook.pdfLeft(book: Book): Locator? {
    val layout = pdf?.layout ?: return null
    val here = PdfPlace(book.position.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0)), book.positionOffset.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f)
    val stopped = book.locator?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }?.takeIf { it.isSpoken }
    if (stopped != null) {
        val at = pdfSentence(stopped, 1)?.let { layout.placeOf(it.paragraph, it.from) }
        if (at != null && samePdfPlace(at, here)) return stopped
    }
    val spot = layout.spotAt(here) ?: layout.firstOn(here.page) ?: return null
    return pdfLocator(spot)
}

/** Whether [sentence], where the voice stopped, is still where the book's place [kept] is: on that page, and about as far down it. */
internal fun samePdfPlace(sentence: PdfPlace, kept: PdfPlace): Boolean = sentence.page == kept.page && abs(sentence.within - kept.within) <= SAME_PLACE
