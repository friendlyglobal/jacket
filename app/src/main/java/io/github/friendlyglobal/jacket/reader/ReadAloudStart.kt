package io.github.friendlyglobal.jacket.reader

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Where read-aloud starts when it starts "at the page in view": [seen] is the first block of text
 * on the page as the page gives it (null if it couldn't say), [page] is where the page is.
 *
 * Readium's navigator names that block by a CSS selector written in the web view. Its read-aloud
 * then looks the selector up in its own reading of the file, which follows HTML's rules where the
 * web view followed XML's. Where the two read a file differently (a Project Gutenberg book opens
 * with an empty `<div/>`, which HTML takes to hold all that comes after it), the selector finds
 * nothing and the voice starts at the top of the chapter file, taking the page, and the reader's
 * place, with it: a hundred pages back, in a book of long files.
 *
 * So the block is found in Readium's own reading of the file, by its words, and the voice is
 * given that reading's name for it: the block holding the page's first words, [top] (see
 * [blockShowing]); failing that, the one the page's first element names (see [startBlock]).
 */
internal suspend fun Publication.readAloudStart(seen: Locator?, page: Locator, top: String? = null): Locator {
    val blocks = textBlocks((seen ?: page).href, page.mediaType)
    val block = blockShowing(blocks.map { it.text }, top) ?: startBlock(
        texts = blocks.map { it.text },
        progressions = blocks.map { it.locator.locations.progression ?: 0.0 },
        wanted = seen?.text?.highlight,
        around = page.locations.progression,
    )
    return block?.let { blocks[it].locator } ?: seen ?: page
}

/** The blocks of text in the chapter file [file], in order, as Readium's own reading of it has them. */
internal suspend fun Publication.textBlocks(file: Url, mediaType: MediaType): List<Content.TextElement> {
    val blocks = ArrayList<Content.TextElement>()
    runCatching {
        val reading = content(Locator(href = file, mediaType = mediaType))?.iterator() ?: return@runCatching
        while (reading.hasNext()) {
            val element = reading.next()
            // The reading carries on into the next file: this one is all that's wanted.
            if (!element.locator.href.isEquivalent(file)) break
            if (element is Content.TextElement) blocks += element
        }
    }
    return blocks
}
