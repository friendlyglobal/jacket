package io.github.friendlyglobal.jacket.reader

import io.github.friendlyglobal.jacket.importer.TextLayout
import io.github.friendlyglobal.jacket.importer.TextPlace
import io.github.friendlyglobal.jacket.importer.TextSpot
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.util.mediatype.MediaType

/*
 * A text file read through pages (see TextBook.kt): telling a place on those pages as the
 * paragraph it is in, and finding on them a place that was kept as a paragraph.
 *
 * Both ways, because a text book's place is kept both ways. The page it is on is the place, as in
 * any book laid out in pages. Beside it goes the paragraph, which is all Jacket 1.1 and earlier
 * kept of a text file: an older Jacket reading this one's backup finds its place by it, and so
 * does this one for a place, a bookmark or a note that an older Jacket left.
 */

/** The first words on a page, as the page gives them: see the script that asks for them in EpubReader. */
internal class TopText(val before: String, val quote: String, val text: String, val id: String?, val at: Int)

/**
 * The paragraph the page at [page] starts in: the one its first words are in ([top]), and how far
 * into it they are. A page that couldn't say (it has no words of its own) is placed by how far
 * down its chapter file it is.
 */
internal fun TextLayout.pagePlace(top: TopText?, page: Locator): TextPlace =
    placeOf(top?.id, top?.at ?: 0) ?: TextPlace(paragraphAt(fileOf(page.href.toString()), page.locations.progression ?: 0.0))

/** The paragraph [sentence], one of the voice's, is in: read-aloud names each sentence by the element that holds it. */
internal fun TextLayout.sentencePlace(sentence: Locator): TextPlace =
    placeOf(sentence.locations.cssSelector?.removePrefix("#")) ?: TextPlace(paragraphAt(fileOf(sentence.href.toString()), sentence.locations.progression ?: 0.0))

/**
 * [spot] as a place Readium's pages can be opened at. They look for its words first (with what
 * stands around them, so the same words elsewhere in the file are not taken for them), and go to
 * the paragraph's own element when it has none, or they aren't found.
 *
 * It says nothing of how far down the file that is: only the pages can tell, once they are laid out.
 */
internal fun Publication.locatorOf(spot: TextSpot): Locator? {
    val link = readingOrder.firstOrNull { it.url().toString() == spot.href } ?: return null
    return Locator(
        href = link.url(),
        mediaType = link.mediaType ?: MediaType.XHTML,
        locations = Locator.Locations(fragments = listOf(spot.id)),
        text = if (spot.words == null) Locator.Text() else Locator.Text(before = spot.before, highlight = spot.words, after = spot.after),
    )
}

/** The top of [paragraph], as a place the pages can go to. */
internal fun Publication.locatorOfParagraph(text: TextLayout, paragraph: Int): Locator? =
    locatorOf(TextSpot(text.hrefOf(paragraph), text.idOf(paragraph), words = null))

/**
 * Which paragraphs start on the page [navigator] shows, from the first to the last; empty when
 * none does (a page in the middle of a long paragraph) or the page can't say.
 */
internal suspend fun paragraphsOn(navigator: EpubNavigatorFragment): IntRange {
    val found = runCatching {
        navigator.evaluateJavascript("$STARTING_HERE_SCRIPT(${navigator.overflow.value.scroll})")?.let(::JSONObject)
    }.getOrNull() ?: return IntRange.EMPTY
    val first = found.optInt("first", -1)
    val last = found.optInt("last", -1)
    return if (first < 0 || last < first) IntRange.EMPTY else first..last
}

/**
 * The paragraphs whose first line is on the page in view, as { first, last } (-1 for none): the
 * elements named "p12" (a paragraph's later pieces, "p12-6000", start nothing). Laid out in pages,
 * an element's first box is in view when its left edge is; scrolling, when its top is.
 */
private val STARTING_HERE_SCRIPT = """
    (function(scroll) {
      var w = window.innerWidth, h = window.innerHeight, first = -1, last = -1;
      var all = document.querySelectorAll('[id^="p"]');
      for (var i = 0; i < all.length; i++) {
        var named = /^p(\d+)$/.exec(all[i].id);
        if (!named) continue;
        var box = all[i].getClientRects()[0];
        if (!box) continue;
        var here = scroll ? (box.top >= -1 && box.top < h - 1) : (box.left >= -1 && box.left < w - 1);
        if (!here) continue;
        var number = parseInt(named[1]);
        if (first < 0 || number < first) first = number;
        if (number > last) last = number;
      }
      return { first: first, last: last };
    })
""".trimIndent()
