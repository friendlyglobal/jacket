package io.github.friendlyglobal.jacket.importer

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/*
 * A text file as a book.
 *
 * A text file (and text shared from another app, which is kept as one) is read through a small
 * EPUB made from it: Readium then lays it out in pages, reads it aloud, and lets it be highlighted
 * and searched, exactly as it does a book that came as an EPUB. The text file stays the book: it
 * is what the library keeps, what a backup names, and what is added again after a restore. The
 * EPUB is only its pages, made again whenever they are missing or were made by an older Jacket.
 *
 * The pages are made the same way every time from the same text, and every paragraph of the file
 * is named in them by its number ("p12"). That number is how Jacket has always kept a place in a
 * text file, so a place, a bookmark or a note saved by any version finds its paragraph here.
 */

/** Reading the bytes of a text file as text, whatever it was saved as. */
internal object TextDecoding {
    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /**
     * [bytes] as text. A file that says how it was saved (a mark at its very start) is read that
     * way; one with a nought in every other byte is UTF-16; one that reads as UTF-8 is UTF-8.
     * Anything else was saved the way some older program or other alphabet does: it is told by
     * what it reads as (see [older]), and failing that read as Western European, which loses
     * nothing of any byte.
     */
    fun decode(bytes: ByteArray): String {
        marked(bytes)?.let { (charset, mark) -> return String(bytes, mark, bytes.size - mark, charset) }
        // Before UTF-8: a nought is a character there too, so plain UTF-16 would pass for it.
        unmarkedUtf16(bytes)?.let { return String(bytes, it) }
        strictly(bytes, Charsets.UTF_8)?.let { return it }
        return String(bytes, older(bytes) ?: WINDOWS_1252)
    }

    /**
     * Which of the encodings from before UTF-8 a file was saved in, where its bytes make that
     * plain: the ones still met in novels passed around as text files, in Japanese (Shift-JIS,
     * EUC-JP), Chinese (GBK, Big5), Korean (EUC-KR) and Russian or Ukrainian (Windows-1251,
     * KOI8-R). Each is tried, and taken only if what comes out reads like that language: kana
     * among the characters, the commonest Hangul syllables or Chinese characters, the commonest
     * Cyrillic letters in small type. Null for anything else, a Western European file among them.
     */
    internal fun older(bytes: ByteArray): Charset? {
        // The first pages say as much as the whole; up to a line's end, so no character is cut in two.
        var sample = if (bytes.size > SAMPLE) bytes.copyOf(SAMPLE) else bytes
        if (bytes.size > SAMPLE) sample.lastIndexOf('\n'.code.toByte()).takeIf { it > 0 }?.let { sample = sample.copyOf(it + 1) }
        val high = sample.count { it < 0 }
        if (high == 0) return null
        fun named(name: String) = runCatching { Charset.forName(name) }.getOrNull()
        val eastAsian = EAST_ASIAN.mapNotNull { (name, reads) ->
            val charset = named(name) ?: return@mapNotNull null
            val text = strictly(sample, charset) ?: return@mapNotNull null
            (charset to reads(text)).takeIf { it.second > 0.0 }
        }
        eastAsian.maxByOrNull { it.second }?.let { return it.first }
        // An alphabet of its own in one byte a letter: most of the letters are above ASCII.
        val latin = sample.count { it in 'a'.code.toByte()..'z'.code.toByte() || it in 'A'.code.toByte()..'Z'.code.toByte() }
        if (high * 10 < (high + latin) * 4) return null
        return CYRILLIC.mapNotNull { name -> named(name)?.let { it to cyrillic(String(sample, it), high) } }
            .filter { it.second >= 0.35 }.maxByOrNull { it.second }?.first
    }

    private const val SAMPLE = 48_000

    /** How much like Japanese, Korean or Chinese a text reads (0 for not at all), for each encoding of them. */
    private val EAST_ASIAN: List<Pair<String, (String) -> Double>> = listOf(
        "Shift_JIS" to ::japanese, "EUC-JP" to ::japanese, "EUC-KR" to ::korean,
        "GBK" to { text -> chinese(text, COMMON_SIMPLIFIED) }, "Big5" to { text -> chinese(text, COMMON_TRADITIONAL) },
    )
    private val CYRILLIC = listOf("windows-1251", "KOI8-R")

    /** Japanese is written with kana among its characters: a fair share of them, or it isn't Japanese. */
    private fun japanese(text: String): Double {
        val wide = text.count { it.code >= 0x3000 }
        val kana = text.count { it.code in 0x3040..0x30FF }
        return if (wide >= 10 && kana * 5 >= wide) 1.0 + kana.toDouble() / wide else 0.0
    }

    /** Chinese bytes read as Korean give Hangul too, but not the syllables Korean is full of. */
    private fun korean(text: String): Double {
        val hangul = text.count { it.code in 0xAC00..0xD7AF }
        val hits = text.count { it in COMMON_HANGUL }
        return if (hangul >= 10 && hits * 8 >= hangul) 1.0 + hits.toDouble() / hangul else 0.0
    }

    /** Chinese has a few characters on every line: bytes read the wrong way give rare ones instead. */
    private fun chinese(text: String, common: String): Double {
        val han = text.count { it.code in 0x4E00..0x9FFF }
        val hits = text.count { it in common }
        return if (han >= 10 && hits * 12 >= han) hits.toDouble() / han else 0.0
    }

    /** The share of the commonest letters among a text's Cyrillic ones; 0 unless it is mostly Cyrillic, and mostly in small letters. */
    private fun cyrillic(text: String, high: Int): Double {
        val letters = text.count { it.code in 0x0400..0x04FF }
        val small = text.count { it.code in 0x0430..0x045F || it == 'ґ' }
        val common = text.count { it in "оеаинтсрвлі" }
        return if (letters * 2 >= high && small * 2 > letters) common.toDouble() / letters else 0.0
    }

    private const val COMMON_HANGUL = "이다는에을의가고지한하로서도기은를있나사리니어그아자라대시수것들인으와게해만일정여"
    private const val COMMON_SIMPLIFIED = "的一是不了人我在有他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长"
    private const val COMMON_TRADITIONAL = "的一是不了人我在有他這中大來上國個到說們為子和你地出道也時年得就那要下以生會自著去之過家學對可她裡後小麼心多天而能好都然沒日於起還發成事只作當想看文無開手十用主行方又如前所本見經頭面公同三已老從動兩長"

    /** The encoding a mark at the start of [bytes] names, and how many bytes the mark takes. */
    private fun marked(bytes: ByteArray): Pair<Charset, Int>? {
        fun starts(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        fun named(name: String) = runCatching { Charset.forName(name) }.getOrNull()
        return when {
            starts(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            starts(0xFF, 0xFE, 0x00, 0x00) -> named("UTF-32LE")?.let { it to 4 }
            starts(0x00, 0x00, 0xFE, 0xFF) -> named("UTF-32BE")?.let { it to 4 }
            starts(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            starts(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> null
        }
    }

    private fun strictly(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * UTF-16 saved without its mark: in text of a Western alphabet every other byte is a nought,
     * which no other way of saving text has.
     */
    private fun unmarkedUtf16(bytes: ByteArray): Charset? {
        val pairs = minOf(bytes.size, 4096) / 2
        if (pairs < 2) return null
        var first = 0
        var second = 0
        for (pair in 0 until pairs) {
            if (bytes[pair * 2] == 0.toByte()) first++
            if (bytes[pair * 2 + 1] == 0.toByte()) second++
        }
        return when {
            second * 10 >= pairs * 4 && first * 20 <= pairs -> Charsets.UTF_16LE
            first * 10 >= pairs * 4 && second * 20 <= pairs -> Charsets.UTF_16BE
            else -> null
        }
    }
}

/**
 * The paragraphs of a text file, in order. Paragraphs are set apart by blank lines, and lines
 * wrapped inside one are joined; a file with no blank lines has a paragraph to a line.
 *
 * This is how Jacket has cut text into paragraphs since its first version, and a place in a text
 * book is kept as the number of its paragraph here: it must go on giving the same paragraphs for
 * the same text.
 */
internal fun paragraphsOf(text: String): List<String> {
    val whole = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    val blocks = whole.split(BLANK_LINES).map { it.trim() }.filter { it.isNotEmpty() }
    val paragraphs = if (blocks.size <= 1) whole.lines().map { it.trim() }.filter { it.isNotEmpty() } else blocks
    return paragraphs.map { it.replace(WRAPPED_LINE, " ") }
}

private val BLANK_LINES = Regex("\n\\s*\n")
private val WRAPPED_LINE = Regex("\\s*\n\\s*")

/** Splits [text] into pieces of at most [max] characters, preferring sentence, then word, boundaries. */
internal fun pieces(text: String, max: Int): List<Pair<Int, String>> {
    if (text.length <= max) return listOf(0 to text)
    val out = mutableListOf<Pair<Int, String>>()
    var start = 0
    while (start < text.length) {
        var end = (start + max).coerceAtMost(text.length)
        if (end < text.length) {
            val window = text.substring(start, end)
            // End of the last full sentence in the window (delimiter included, so the next piece
            // starts cleanly), else the last space, else a hard cut.
            val sentenceEnd = listOf(". ", "! ", "? ", "; ", "。").maxOf { delimiter ->
                window.lastIndexOf(delimiter).let { if (it < 0) -1 else it + delimiter.length }
            }
            val cut = when {
                sentenceEnd > max / 2 -> sentenceEnd
                window.lastIndexOf(' ') > max / 2 -> window.lastIndexOf(' ') + 1
                // Never between the two halves of a character outside the basic plane.
                window.last().isHighSurrogate() && window.length > 1 -> window.length - 1
                else -> window.length
            }
            end = start + cut
        }
        out += start to text.substring(start, end)
        start = end
    }
    return out
}

/**
 * Whether a paragraph is the mark of a change of scene: a row of asterisks, a rule of dashes, a
 * lone ornament. It is set in the middle of the page. (It has no words, so no voice is given it.)
 */
internal fun isSceneBreak(paragraph: String): Boolean =
    paragraph.length <= LONGEST_BREAK && paragraph.any { !it.isWhitespace() } && paragraph.all { it.isWhitespace() || it in BREAK_MARKS }

private const val LONGEST_BREAK = 40
private const val BREAK_MARKS = "*-_=~#·•◆◇★☆—–⁂❦❧+⁎✦✧❖○●□■†‡§"

/** A paragraph that is a chapter's heading: which of its characters are the title, and how deep in the contents it is (1 the top). */
internal class Heading(val from: Int, val to: Int, val level: Int)

/**
 * The heading a paragraph is, if it is one. Only what can hardly be anything else: "Chapter 4",
 * "CHAPTER IV. The Rabbit", "Capítulo 12", "第三章", a lone "Prologue"; and, in a Markdown file
 * ([markdown]), a line that starts with "#". An ordinary sentence that happens to begin with
 * "Part" is not one.
 */
internal fun headingOf(paragraph: String, markdown: Boolean): Heading? {
    if (markdown) {
        MARKDOWN_HEADING.find(paragraph)?.let { found ->
            val title = found.groups[2] ?: return@let
            if (title.value.isNotBlank()) return Heading(title.range.first, title.range.last + 1, found.groupValues[1].length)
        }
    }
    if (paragraph.length > LONGEST_HEADING) return null
    val whole = Heading(0, paragraph.length, 1)
    if (LONE_HEADING.matches(paragraph) || NUMBERED_EAST_ASIAN.containsMatchIn(paragraph)) return whole
    val numbered = NUMBERED_CHAPTER.find(paragraph) ?: return null
    val rest = paragraph.substring(numbered.range.last + 1)
    // After the number: nothing, a title set off by a full stop or a dash, or a title that isn't a sentence.
    val titled = rest.isBlank() || rest.trimStart().first() in ".:—–-" || (rest.first().isWhitespace() && rest.trimEnd().last() !in ".!?…,;:")
    return whole.takeIf { titled }
}

private const val LONGEST_HEADING = 80
private val MARKDOWN_HEADING = Regex("^(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$")
private const val CHAPTER_WORDS = "chapter|chapitre|capítulo|capitulo|capitolo|kapitel|hoofdstuk|rozdział|глава|розділ|bölüm|bab|" +
    "part|parte|partie|teil|deel|część|часть|частина|book|livro|libro|livre|buch|boek|act|acto|ato|acte|akt|scene|cena|escena|scène|szene|canto"
private const val NUMBER_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" +
    "seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last"
// The words in any case; a Roman numeral only in capitals ("Part I", not "part mill").
private val NUMBERED_CHAPTER = Regex(
    "^(?iu:$CHAPTER_WORDS)\\s+(?:\\d{1,4}|[IVXLCDM]{1,8}(?![\\p{L}\\d])|(?iu:(?:the\\s+)?(?:$NUMBER_WORDS)(?:[- ](?:$NUMBER_WORDS))?)(?![\\p{L}\\d]))",
)
private val NUMBERED_EAST_ASIAN = Regex("^(?:第\\s*[0-9０-９〇零一二三四五六七八九十百千两兩]+\\s*[章回节節卷部篇幕话話]|제\\s*\\d+\\s*[장부편화막])")
private val LONE_HEADING = Regex(
    "^(?:prologue|epilogue|introduction|preface|foreword|afterword|contents|table of contents|acknowledge?ments|appendix(?:\\s+[A-Z\\d]{1,4})?|" +
        "prólogo|epílogo|introdução|prefácio|posfácio|índice|sumário|agradecimentos|prologo|epilogo|introducción|prefacio|" +
        "introduzione|prefazione|indice|einleitung|vorwort|nachwort|inhalt|préface|épilogue|avant-propos|table des matières|" +
        "inleiding|voorwoord|nawoord|inhoud|wstęp|przedmowa|posłowie|spis treści|пролог|эпилог|введение|предисловие|послесловие|оглавление|содержание|" +
        "епілог|вступ|передмова|післямова|зміст|önsöz|giriş|sonsöz|içindekiler|prakata|pendahuluan|daftar isi|" +
        "プロローグ|エピローグ|序章|終章|目次|まえがき|あとがき|序言|前言|后记|後記|尾声|尾聲|楔子|引子|目录|目錄|프롤로그|에필로그|서문|머리말|차례|목차)[.:]?$",
    setOf(RegexOption.IGNORE_CASE),
)

/** What a text file says of itself in its first lines, as Project Gutenberg's do: "Title: ...", "Author: ...". */
internal class TextCredits(val title: String?, val author: String?)

internal fun creditsOf(paragraphs: List<String>): TextCredits {
    fun said(label: Regex): String? = paragraphs.asSequence().take(CREDITS_WITHIN)
        .firstNotNullOfOrNull { label.find(it)?.groupValues?.get(1)?.trim()?.takeIf { name -> name.isNotEmpty() && name.length <= LONGEST_CREDIT } }
    return TextCredits(said(TITLE_LINE), said(AUTHOR_LINE))
}

private const val CREDITS_WITHIN = 40
private const val LONGEST_CREDIT = 200
private val TITLE_LINE = Regex("^Title:\\s*(.+)$")
private val AUTHOR_LINE = Regex("^Author:\\s*(.+)$")

/**
 * A name for text shared from another app that came without one: its first line if that is short
 * enough to be a headline, otherwise its first sentence, or its first words. Null when there is
 * nothing to name it by.
 */
internal fun titleOfSharedText(text: String): String? {
    val first = text.lineSequence().map { it.trim().trimStart('#').replace(SPACES, " ").trim() }.firstOrNull { line -> line.any { it.isLetterOrDigit() } }
        ?: return null
    if (first.length <= LONGEST_SHARED_TITLE) return first.trimEnd('.', ':', ';', ',')
    // A first sentence that would do for a title, if it is neither a word or two nor too long.
    SENTENCE_END.find(first)?.range?.first?.takeIf { it in SHORTEST_SHARED_TITLE..LONGEST_SHARED_TITLE }?.let { end ->
        return first.substring(0, end + 1).trimEnd('.', '。')
    }
    // The first words that fit, to the end of a word.
    val cut = first.take(LONGEST_SHARED_TITLE + 1).substringBeforeLast(' ', first.take(LONGEST_SHARED_TITLE))
    return cut.trimEnd('.', ':', ';', ',', '—', '–', '-') + "…"
}

private const val LONGEST_SHARED_TITLE = 60
private const val SHORTEST_SHARED_TITLE = 12
private val SENTENCE_END = Regex("[.!?](?=\\s)|[。！？]")
private val SPACES = Regex("\\s+")

/** A place in a text book: which paragraph (counting from 0), and how far into it (0..1). */
internal data class TextPlace(val paragraph: Int, val within: Float = 0f)

/**
 * Where a [TextPlace] is in the book's pages: the chapter file ([href]), the element that holds
 * the paragraph ([id]), and, for a place part of the way into a paragraph, the [words] it starts
 * with and what stands before and after them in the file.
 */
internal class TextSpot(val href: String, val id: String, val words: String?, val before: String = "", val after: String = "")

/** A text file as a book: its paragraphs, and the pages made of them. See the top of this file. */
internal class TextBook private constructor(
    /** The file's paragraphs (see [paragraphsOf]). */
    val paragraphs: List<String>,
    markdown: Boolean,
) {
    enum class Kind { TEXT, HEADING, BREAK }

    /**
     * One element of the pages: a paragraph, a heading, the mark of a change of scene, or one
     * piece of a paragraph too long to be laid out whole. [text] is what it shows, which is
     * [paragraph]'s characters from [from] on.
     */
    class Block(val paragraph: Int, val from: Int, val text: String, val kind: Kind, val level: Int, private val first: Boolean) {
        val id: String get() = if (first) "p$paragraph" else "p$paragraph-$from"
    }

    /** One chapter file: [blocks] of the book's, and the first file of the chapter it is in ([group]). */
    class Part(val index: Int, val blocks: IntRange, val group: Int) {
        val href: String get() = hrefOf(index)
    }

    /** A line of the contents. [level] is 0 at the top. */
    class Entry(val title: String, val level: Int, val href: String)

    /** What the book says of itself on its pages and to a media player. */
    class About(val id: String, val title: String, val author: String = "", val language: String? = null)

    val blocks: List<Block>
    val parts: List<Part>
    val contents: List<Entry>

    /** The first of [blocks] that each paragraph is shown by. */
    private val firstBlock = IntArray(paragraphs.size)

    /** How deep the shallowest heading is: that one is set as a chapter's. */
    private val topLevel: Int

    init {
        val found = ArrayList<Block>(paragraphs.size)
        paragraphs.forEachIndexed { index, raw ->
            firstBlock[index] = found.size
            val shown = xmlSafe(raw)
            val heading = headingOf(shown, markdown)
            when {
                heading != null -> found += Block(index, heading.from, shown.substring(heading.from, heading.to), Kind.HEADING, heading.level, first = true)
                isSceneBreak(shown) -> found += Block(index, 0, shown, Kind.BREAK, 0, first = true)
                else -> pieces(shown, LONGEST_BLOCK).forEach { (from, piece) -> found += Block(index, from, piece, Kind.TEXT, 0, first = from == 0) }
            }
        }
        blocks = found

        // Chapter files: a heading starts a new one once the one before has some pages to it, and
        // no file holds more than a web view lays out without effort.
        val files = ArrayList<Part>()
        var start = 0
        var letters = 0
        var group = 0
        fun close(end: Int) {
            if (end <= start) return
            if (blocks[start].kind == Kind.HEADING) group = files.size
            files += Part(files.size, start until end, group)
            start = end
            letters = 0
        }
        blocks.forEachIndexed { index, block ->
            val chapter = block.kind == Kind.HEADING && letters >= NEW_FILE_AT_HEADING
            val full = letters > 0 && letters + block.text.length > MOST_IN_A_FILE
            if (chapter || full) close(index)
            letters += block.text.length
        }
        close(blocks.size)
        parts = files

        // The contents: every heading, by how deep it is among them. No level is skipped on the way down.
        val top = blocks.filter { it.kind == Kind.HEADING }.minOfOrNull { it.level } ?: 0
        topLevel = top
        var deepest = -1
        contents = parts.flatMap { part ->
            part.blocks.mapNotNull { index ->
                val block = blocks[index].takeIf { it.kind == Kind.HEADING } ?: return@mapNotNull null
                val level = (block.level - top).coerceIn(0, deepest + 1)
                deepest = level
                Entry(block.text.replace(SPACES, " ").trim(), level, if (index == part.blocks.first) part.href else "${part.href}#${block.id}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Places
    // ---------------------------------------------------------------------------------------------

    /**
     * Where [place] is in the pages. The top of a paragraph is told by the paragraph alone; a
     * place further into one by the words there too, since a long paragraph runs over several pages.
     */
    fun spotOf(place: TextPlace): TextSpot? {
        if (paragraphs.isEmpty()) return null
        val paragraph = place.paragraph.coerceIn(0, paragraphs.lastIndex)
        val within = if (place.paragraph == paragraph && place.within.isFinite()) place.within.coerceIn(0f, 1f) else 0f
        val firstOf = firstBlock[paragraph]
        val lastOf = (if (paragraph < paragraphs.lastIndex) firstBlock[paragraph + 1] else blocks.size) - 1
        val at = (within * paragraphs[paragraph].length).toInt()
        // The piece of the paragraph that holds the place.
        val index = (firstOf..lastOf).lastOrNull { blocks[it].from <= at } ?: firstOf
        val block = blocks[index]
        val part = parts.first { index in it.blocks }
        val text = block.text
        // Back to the start of the word the place falls in; and not so near the end that too few words are left to tell it by.
        var from = (at - block.from).coerceIn(0, text.length).coerceAtMost((text.length - SPOT_WORDS).coerceAtLeast(0))
        while (from > 0 && !text[from - 1].isWhitespace()) from--
        val words = text.substring(from, (from + SPOT_WORDS).coerceAtMost(text.length))
        if (from == 0 || words.trim().length < FEWEST_SPOT_WORDS) return TextSpot(part.href, block.id, words = null)
        val before = (if (index > part.blocks.first) blocks[index - 1].text.takeLast(SPOT_CONTEXT) else "") + "\n" + text.substring(0, from)
        val after = text.substring(from + words.length) + "\n" + (if (index < part.blocks.last) blocks[index + 1].text.take(SPOT_CONTEXT) else "")
        return TextSpot(part.href, block.id, words, before.takeLast(SPOT_CONTEXT), after.take(SPOT_CONTEXT))
    }

    /** The compact form of where everything is, for the reader to keep while the book is open. */
    fun layout(): TextLayout {
        val partOf = IntArray(paragraphs.size)
        val before = IntArray(paragraphs.size)
        val letters = IntArray(parts.size)
        for (part in parts) {
            var sum = 0
            for (index in part.blocks) {
                val block = blocks[index]
                if (firstBlock[block.paragraph] == index) {
                    partOf[block.paragraph] = part.index
                    before[block.paragraph] = sum
                }
                sum += block.text.length + PARAGRAPH_ROOM
            }
            letters[part.index] = sum
        }
        return TextLayout(IntArray(paragraphs.size) { paragraphs[it].length }, partOf, before, letters, IntArray(parts.size) { parts[it].group }, contents.isNotEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // The pages, as an EPUB
    // ---------------------------------------------------------------------------------------------

    /** Writes the book's pages to [out] as an EPUB, and closes it. */
    fun write(out: OutputStream, about: About) {
        val rightToLeft = about.language?.substringBefore('-')?.lowercase() in RIGHT_TO_LEFT
        ZipOutputStream(out).use { zip ->
            zip.setComment(STAMP)
            // First, and stored as it is: that is how an EPUB is known.
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                    time = WRITTEN
                },
            )
            zip.write(mime)
            zip.closeEntry()
            fun file(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = WRITTEN })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            file("META-INF/container.xml", CONTAINER)
            file(PACKAGE, packageOf(about, rightToLeft))
            if (contents.isNotEmpty()) file(NAV, navOf(about))
            file(STYLE, CSS)
            for (part in parts) file(part.href, pageOf(part, about, rightToLeft))
        }
    }

    private fun packageOf(about: About, rightToLeft: Boolean) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("<dc:identifier id=\"id\">urn:jacket:text:").append(escaped(about.id)).append("</dc:identifier>\n")
        append("<dc:title>").append(escaped(xmlSafe(about.title))).append("</dc:title>\n")
        if (about.author.isNotBlank()) append("<dc:creator>").append(escaped(xmlSafe(about.author))).append("</dc:creator>\n")
        // A text whose language couldn't be told says nothing: the reader then goes by the phone's.
        if (!about.language.isNullOrBlank()) append("<dc:language>").append(escaped(about.language)).append("</dc:language>\n")
        append("<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"style\" href=\"$STYLE\" media-type=\"text/css\"/>\n")
        if (contents.isNotEmpty()) append("<item id=\"nav\" href=\"$NAV\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        for (part in parts) append("<item id=\"part${part.index + 1}\" href=\"${part.href}\" media-type=\"application/xhtml+xml\"/>\n")
        append("</manifest>\n")
        append(if (rightToLeft) "<spine page-progression-direction=\"rtl\">\n" else "<spine>\n")
        for (part in parts) append("<itemref idref=\"part${part.index + 1}\"/>\n")
        append("</spine>\n</package>\n")
    }

    private fun navOf(about: About) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
        append("<head><meta charset=\"utf-8\"/><title>").append(escaped(xmlSafe(about.title))).append("</title></head>\n")
        append("<body>\n<nav epub:type=\"toc\">\n<ol>\n")
        contents.forEachIndexed { index, entry ->
            append("<li><a href=\"").append(entry.href).append("\">").append(escaped(entry.title)).append("</a>")
            val next = contents.getOrNull(index + 1)?.level ?: 0
            when {
                // The entries under this one are a list inside it.
                next > entry.level -> append("\n<ol>\n")
                else -> {
                    append("</li>\n")
                    repeat(entry.level - next) { append("</ol>\n</li>\n") }
                }
            }
        }
        append("</ol>\n</nav>\n</body>\n</html>\n")
    }

    private fun pageOf(part: Part, about: About, rightToLeft: Boolean) = buildString {
        val language = about.language?.takeIf { it.isNotBlank() }?.let(::escaped)
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\"")
        if (language != null) append(" lang=\"$language\" xml:lang=\"$language\"")
        if (rightToLeft) append(" dir=\"rtl\"")
        append(">\n<head><meta charset=\"utf-8\"/><title>").append(escaped(xmlSafe(about.title))).append("</title>")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"../$STYLE\"/></head>\n<body>\n")
        for (index in part.blocks) {
            val block = blocks[index]
            // Each paragraph leans the way its own words run, whatever the rest of the book does.
            val named = "id=\"${block.id}\" dir=\"auto\""
            when (block.kind) {
                Kind.HEADING -> {
                    val tag = "h${(block.level - topLevel + 2).coerceIn(2, 4)}"
                    append("<$tag $named>").append(escaped(block.text)).append("</$tag>\n")
                }
                Kind.BREAK -> append("<p class=\"break\" $named>").append(escaped(block.text)).append("</p>\n")
                Kind.TEXT -> {
                    // A paragraph cut in pieces runs on from one to the next.
                    val runsOn = blocks.getOrNull(index + 1)?.let { it.paragraph == block.paragraph } == true
                    append(if (runsOn) "<p class=\"more\" $named>" else "<p $named>").append(escaped(block.text)).append("</p>\n")
                }
            }
        }
        append("</body>\n</html>\n")
    }

    companion object {
        /** Goes up whenever the same text would be given other pages: pages made by an older number are made again. */
        const val VERSION = 1
        private const val STAMP = "jacket-text-book $VERSION"

        /** A paragraph longer than this is laid out, and read aloud, as several pieces. */
        const val LONGEST_BLOCK = 6_000

        /** A heading begins a new chapter file once the file before it holds this much; and no file holds more than the other. */
        const val NEW_FILE_AT_HEADING = 8_000
        const val MOST_IN_A_FILE = 60_000

        /** How many of a place's words it is told by, the fewest that tell anything, and how much of what is around them. */
        private const val SPOT_WORDS = 60
        private const val FEWEST_SPOT_WORDS = 24
        private const val SPOT_CONTEXT = 40

        /** What a paragraph takes on the page beside its letters: the space after it, and its short last line. */
        const val PARAGRAPH_ROOM = 40

        private const val PACKAGE = "content.opf"
        private const val NAV = "nav.xhtml"
        private const val STYLE = "style.css"

        /** The same for every book, so the same text always gives the same file. */
        private const val WRITTEN = 1_767_225_600_000L

        private val RIGHT_TO_LEFT = setOf("ar", "he", "iw", "fa", "ur", "yi", "ps", "sd", "ug")

        /** The chapter file numbered [index] (from 0), as the book's own files name it. */
        fun hrefOf(index: Int): String = "text/part%04d.xhtml".format(java.util.Locale.ROOT, index + 1)

        /** The book of [text]. [markdown] for a file that says it is Markdown: its "#" lines are headings. */
        fun of(text: String, markdown: Boolean = false): TextBook = TextBook(paragraphsOf(text), markdown)

        /** Whether [name], a file's, says it is Markdown. */
        fun isMarkdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

        /** Whether [file] holds pages as this version makes them. */
        fun isCurrent(file: File): Boolean = runCatching { ZipFile(file).use { it.comment == STAMP } }.getOrDefault(false)

        private const val CONTAINER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "<rootfiles><rootfile full-path=\"$PACKAGE\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n</container>\n"

        // Paragraphs set apart by a little space, as the text reader always showed them; a long
        // word (a web address) breaks rather than run off the page.
        private const val CSS = "p { margin: 0 0 0.75em; overflow-wrap: break-word; }\n" +
            "p.more { margin-bottom: 0; }\n" +
            "p.break { margin: 1.5em 0; text-align: center; }\n" +
            "h2, h3, h4 { margin: 1.6em 0 0.8em; line-height: 1.25; overflow-wrap: break-word; }\n" +
            "h2 { font-size: 1.4em; }\nh3 { font-size: 1.2em; }\nh4 { font-size: 1.05em; }\n"

        private fun escaped(text: String): String {
            if (text.none { it == '&' || it == '<' || it == '>' || it == '"' }) return text
            return buildString(text.length + 16) {
                for (c in text) when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }

        /**
         * [text] with every character an XML file can't hold (a control character, half of a
         * character outside the basic plane) as a space: one such byte in a file, and a web view
         * shows an error in place of the chapter. The length stays the same, so places still line up.
         */
        internal fun xmlSafe(text: String): String {
            fun fits(index: Int): Boolean {
                val c = text[index]
                return when {
                    c == '\t' || c == '\n' || c == '\r' -> true
                    c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> false
                    c.isHighSurrogate() -> index + 1 < text.length && text[index + 1].isLowSurrogate()
                    c.isLowSurrogate() -> index > 0 && text[index - 1].isHighSurrogate()
                    else -> true
                }
            }
            if (text.indices.all(::fits)) return text
            return buildString(text.length) { text.indices.forEach { append(if (fits(it)) text[it] else ' ') } }
        }
    }
}

/**
 * Where a text book's paragraphs are in its pages, without the text itself: what the reader keeps
 * while the book is open, to tell which paragraph a page or a sentence is in.
 */
internal class TextLayout(
    /** How many characters each paragraph has. */
    private val lengths: IntArray,
    /** The chapter file each paragraph starts in. */
    private val partOf: IntArray,
    /** How much of its file comes before each paragraph, and how much each file holds in all (see [TextBook.PARAGRAPH_ROOM]). */
    private val before: IntArray,
    private val letters: IntArray,
    private val groups: IntArray,
    /** The book has chapters: headings were found in it. */
    val hasContents: Boolean,
) {
    val paragraphs: Int get() = lengths.size
    val files: Int get() = letters.size

    /** The chapter file [paragraph] starts in, and its name there. */
    fun hrefOf(paragraph: Int): String = TextBook.hrefOf(partOf.getOrElse(paragraph) { 0 })
    fun idOf(paragraph: Int): String = "p$paragraph"

    /** Which chapter file [href] names (from 0); -1 if it isn't one of this book's. */
    fun fileOf(href: String): Int {
        val number = PART.find(href.substringBefore('#'))?.groupValues?.get(1)?.toIntOrNull() ?: return -1
        return (number - 1).takeIf { it in 0 until files } ?: -1
    }

    /** The first file of the chapter [file] is in: files a long chapter was cut into give the same one. */
    fun groupOf(file: Int): Int = groups.getOrElse(file) { file }

    /**
     * The place an element of the pages names: [id] is the element's ("p12", or "p12-6000" for a
     * later piece of a long paragraph), [at] how many characters into it. Null if it isn't a paragraph's.
     */
    fun placeOf(id: String?, at: Int = 0): TextPlace? {
        val found = ID.matchEntire(id.orEmpty()) ?: return null
        val paragraph = found.groupValues[1].toIntOrNull()?.takeIf { it in 0 until paragraphs } ?: return null
        val from = found.groupValues[2].toIntOrNull() ?: 0
        val length = lengths[paragraph]
        return TextPlace(paragraph, if (length <= 0) 0f else ((from + at.coerceAtLeast(0)).toFloat() / length).coerceIn(0f, 1f))
    }

    /**
     * The paragraph [progression] (0..1) of the way down chapter file [file], as near as the count
     * of its letters tells: for a page that couldn't say which paragraph it starts with.
     */
    fun paragraphAt(file: Int, progression: Double): Int {
        if (paragraphs == 0) return 0
        // The paragraphs that start in this file are a run of them: found by halving.
        var low = 0
        var high = paragraphs
        while (low < high) {
            val middle = (low + high) / 2
            if (partOf[middle] < file) low = middle + 1 else high = middle
        }
        val first = low
        // A file that only carries on a paragraph begun before it.
        if (first >= paragraphs || partOf[first] != file) return (first - 1).coerceIn(0, paragraphs - 1)
        val target = progression.coerceIn(0.0, 1.0) * letters[file]
        var at = first
        while (at + 1 < paragraphs && partOf[at + 1] == file && before[at + 1] <= target) at++
        // Above the first paragraph that starts here is the end of the one before.
        return if (at == first && before[first] > target && first > 0) first - 1 else at
    }

    /**
     * The place a text book was left at by a Jacket that kept only the paragraph: [position] of
     * [count] paragraphs, and [offset] of the way into it. Null for a book never opened.
     *
     * The paragraphs are the same ones, cut the same way, so the number is the place. Only when
     * the file reads differently now (it was saved in a way older Jackets misread, so they counted
     * other paragraphs) is the place taken to be as far through the book as it was.
     */
    fun saved(position: Int, offset: Float, count: Int): TextPlace? {
        if (paragraphs == 0 || (position <= 0 && !(offset > 0f))) return null
        val within = if (offset.isFinite()) offset.coerceIn(0f, 1f) else 0f
        if (count <= 0 || count == paragraphs) return TextPlace(position.coerceIn(0, paragraphs - 1), if (position < paragraphs) within else 0f)
        return TextPlace(((position + within) / count * paragraphs).toInt().coerceIn(0, paragraphs - 1))
    }

    /**
     * The paragraph a bookmark kept by its number is on: [section], which was [progress] of the
     * way through the paragraphs. The number is the paragraph unless the file reads differently
     * now (see [saved]): then it is the paragraph as far through the book.
     */
    fun marked(section: Int, progress: Float): Int {
        if (paragraphs == 0) return 0
        val counted = if (section > 0 && progress > 0f && progress.isFinite()) (section / progress).roundToInt() else paragraphs
        val same = kotlin.math.abs(counted - paragraphs) <= maxOf(1, paragraphs / 200)
        return (if (same || !progress.isFinite()) section else (progress.coerceIn(0f, 1f) * paragraphs).toInt()).coerceIn(0, paragraphs - 1)
    }

    private companion object {
        val PART = Regex("(?:^|/)text/part(\\d{4,})\\.xhtml$")
        val ID = Regex("p(\\d+)(?:-(\\d+))?")
    }
}
