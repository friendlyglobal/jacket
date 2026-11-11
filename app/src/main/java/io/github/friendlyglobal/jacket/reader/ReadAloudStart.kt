package io.github.friendlyglobal.jacket.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
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
internal suspend fun SpokenFiles.readAloudStart(seen: Locator?, page: Locator, top: String? = null): Locator {
    val file = of((seen ?: page).href, page.mediaType)
    val texts = file.blocks.map { it.text }
    val block = blockShowing(texts, top) ?: startBlock(
        texts = texts,
        // Where each block is in the file as the page tells its own place, not Readium's count of blocks.
        progressions = texts.indices.map(file.places::of),
        wanted = seen?.text?.highlight,
        around = page.locations.progression,
    )
    return block?.let { file.blocks[it].locator } ?: seen ?: page
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

/** A chapter file as read-aloud reads it: its [blocks] of text, and where each is in the file (see [TextPlaces]). */
internal class SpokenFile(val blocks: List<Content.TextElement>) {
    val places = TextPlaces(blocks.map { it.text })

    /** Readium's count of each block (see [TextPlaces]), which names it outright. */
    private val counted = HashMap<Double, Int>().apply {
        blocks.forEachIndexed { block, element -> element.locator.locations.progression?.let { put(it, block) } }
    }

    /** Which block [sentence], one of the voice's, is in; null if it isn't found in this file. */
    fun blockOf(sentence: Locator): Int? {
        val at = sentence.locations
        val exact = at.progression?.let(counted::get)?.takeIf { blocks[it].locator.locations == at }
        val name = at.cssSelector
        val named = if (name == null) emptyList() else blocks.indices.filter { blocks[it].locator.locations.cssSelector == name }
        return places.blockOf(sentence.text.highlight, exact, named, at.progression)
    }
}

/**
 * The book's chapter files as read-aloud reads them. Reading one takes a moment (the whole file
 * is gone through), so the last few are kept: the voice stays in a file for a chapter at a time.
 */
internal class SpokenFiles(private val publication: Publication) {
    private val kept = LinkedHashMap<String, SpokenFile>()
    private val reading = Mutex()

    suspend fun of(file: Url, mediaType: MediaType): SpokenFile = reading.withLock {
        val name = file.removeFragment().toString()
        kept[name]?.let { return@withLock it }
        SpokenFile(publication.textBlocks(file, mediaType)).also { read ->
            // A file that couldn't be read just now (the book is closing, say) isn't kept as empty.
            if (read.blocks.isNotEmpty()) {
                kept[name] = read
                if (kept.size > KEPT) kept.remove(kept.keys.first())
            }
        }
    }

    /**
     * [sentence], one of the voice's, as a place the pages can go by: with how far down its file
     * it is, where Readium gave its block's number among the file's blocks (see [TextPlaces]).
     * Everything of the voice's that reaches the pages, or is saved as the reader's place, comes
     * through here. A sentence already told this way comes out the same; one that isn't found in
     * its file is left as it is.
     */
    suspend fun placed(sentence: Locator): Locator {
        val file = of(sentence.href, sentence.mediaType)
        val block = file.blockOf(sentence) ?: return sentence.findable()
        return sentence.findable().copyWithLocations(progression = file.places.of(block, sentence.text.highlight), totalProgression = null)
    }

    private companion object {
        const val KEPT = 3
    }
}

/** [this] sentence with as much of its words as a page can find it by (see [findableLength]); what is left off is kept as what follows them. */
internal fun Locator.findable(): Locator {
    val words = text.highlight ?: return this
    val length = findableLength(words)
    if (length == words.length) return this
    return copy(text = text.copy(highlight = words.take(length), after = words.drop(length) + text.after.orEmpty()))
}

/** Whether [this] place was saved from the voice (a sentence, named by its block) rather than from a page. */
internal val Locator.isSpoken: Boolean get() = locations.cssSelector != null && text.highlight != null
