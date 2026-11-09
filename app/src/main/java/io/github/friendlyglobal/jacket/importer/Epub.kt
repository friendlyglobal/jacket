package io.github.friendlyglobal.jacket.importer

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Minimal EPUB 2/3 package reader: metadata, cover image and reading order. */
object Epub {
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val OPS = "http://www.idpf.org/2007/ops"

    class Package(
        val title: String?,
        val author: String?,
        /** Path of the cover image inside the container, if any. */
        val coverPath: String?,
        /** Content documents in reading order, as paths inside the container. */
        val spine: List<String>,
        /** The book's own table of contents, in reading order; empty if it has none. */
        val toc: List<TocEntry> = emptyList(),
        /** BCP 47 tag from `dc:language`, used to pick a read-aloud voice. */
        val language: String? = null,
    )

    /** One table-of-contents line: a document [path] plus an optional `#fragment` inside it. */
    class TocEntry(val title: String, val level: Int, val path: String, val fragment: String?)

    private class Item(val href: String, val mediaType: String, val properties: String)

    fun readMeta(file: File): BookMeta = ZipFile(file).use { zip ->
        val pkg = readPackage { path -> zip.getEntry(path)?.let(zip::getInputStream) }
        val cover = pkg.coverPath?.let { path ->
            zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { Bitmaps.decodeSampled(it.readBytes(), 1400) } }
        }
        BookMeta(pkg.title, pkg.author, cover, pkg.spine.size)
    }

    /** Reads the package from an unpacked book directory (used by the reader). */
    fun readPackage(dir: File): Package = readPackage { path ->
        File(dir, path).takeIf { it.isFile }?.inputStream()
    }

    fun readPackage(open: (String) -> InputStream?): Package {
        val opfPath = open("META-INF/container.xml")?.use(::rootFilePath) ?: error("Not an EPUB: missing container.xml")
        val base = opfPath.substringBeforeLast('/', "")
        val stream = open(opfPath) ?: error("Not an EPUB: missing $opfPath")

        var title: String? = null
        var author: String? = null
        var language: String? = null
        var coverId: String? = null
        var ncxId: String? = null
        val items = LinkedHashMap<String, Item>()
        val spineIds = mutableListOf<String>()

        stream.use {
            val parser = newParser(it)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "title" -> if (title == null && parser.namespace == DC) title = parser.nextText().trim()
                        "creator" -> if (author == null && parser.namespace == DC) author = parser.nextText().trim()
                        "language" -> if (language == null && parser.namespace == DC) language = parser.nextText().trim()
                        "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                            coverId = parser.getAttributeValue(null, "content")
                        }
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (id != null && href != null) {
                                items[id] = Item(
                                    href = resolve(base, href),
                                    mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                                    properties = parser.getAttributeValue(null, "properties").orEmpty(),
                                )
                            }
                        }
                        "spine" -> ncxId = parser.getAttributeValue(null, "toc")
                        "itemref" -> parser.getAttributeValue(null, "idref")?.let(spineIds::add)
                    }
                }
                event = parser.next()
            }
        }

        val images = items.values.filter { it.mediaType.startsWith("image/") }
        val cover = images.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: coverId?.let(items::get)?.takeIf { it.mediaType.startsWith("image/") }
            ?: images.firstOrNull { it.href.contains("cover", ignoreCase = true) }
        val spine = spineIds.mapNotNull(items::get)
            .filter { it.mediaType.contains("html") || it.mediaType.isEmpty() }
            .map { it.href }

        // EPUB 3 navigation document first, then the EPUB 2 NCX. A broken TOC never blocks reading.
        val nav = items.values.firstOrNull { "nav" in it.properties.split(' ') }
        val ncx = ncxId?.let(items::get) ?: items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val toc = nav?.let { n -> runCatching { open(n.href)?.use { parseNav(it, n.href.substringBeforeLast('/', "")) } }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: ncx?.let { n -> runCatching { open(n.href)?.use { parseNcx(it, n.href.substringBeforeLast('/', "")) } }.getOrNull() }
            ?: emptyList()

        return Package(title, author, cover?.href, spine, toc, language)
    }

    private fun newParser(stream: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // Tolerate HTML entities such as &nbsp; that XHTML files often use without declaring.
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        setInput(stream, null)
    }

    /** EPUB 3: the `<nav epub:type="toc">` list in the navigation document. */
    private fun parseNav(stream: InputStream, base: String): List<TocEntry> {
        val parser = newParser(stream)
        val out = mutableListOf<TocEntry>()
        var navDepth = -1
        var listDepth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "nav" -> if (navDepth < 0) {
                        val type = parser.getAttributeValue(OPS, "type") ?: parser.getAttributeValue(null, "epub:type")
                        if (type.orEmpty().split(' ').contains("toc")) navDepth = parser.depth
                    }
                    "ol" -> if (navDepth >= 0) listDepth++
                    "a" -> if (navDepth >= 0) {
                        val href = parser.getAttributeValue(null, "href")
                        val text = collectText(parser, "a")
                        if (href != null && text.isNotEmpty()) out += entry(base, href, text, (listDepth - 1).coerceAtLeast(0))
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "ol" -> if (navDepth >= 0) listDepth--
                    "nav" -> if (parser.depth == navDepth) return out
                }
            }
            event = parser.next()
        }
        return out
    }

    /** EPUB 2: nested `<navPoint>`s in the NCX file. */
    private fun parseNcx(stream: InputStream, base: String): List<TocEntry> {
        val parser = newParser(stream)
        val out = mutableListOf<TocEntry>()
        var depth = 0
        var label: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "navPoint" -> { depth++; label = null }
                    "text" -> if (depth > 0 && label == null) label = collectText(parser, "text")
                    "content" -> if (depth > 0) {
                        val src = parser.getAttributeValue(null, "src")
                        val title = label
                        if (src != null && !title.isNullOrEmpty()) out += entry(base, src, title, depth - 1)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "navPoint") {
                depth--
            }
            event = parser.next()
        }
        return out
    }

    private fun entry(base: String, href: String, title: String, level: Int) = TocEntry(
        title = title,
        level = level,
        path = resolve(base, href),
        fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(Uri::decode),
    )

    /** All text inside the current element, whitespace collapsed; leaves the parser on its end tag. */
    private fun collectText(parser: XmlPullParser, tag: String): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.depth == depth && parser.name == tag) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return text.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Extracts the container, refusing entries that would escape [dest] ("zip slip"). */
    fun unpack(file: File, dest: File) {
        val root = dest.canonicalFile
        root.mkdirs()
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                val out = File(root, entry.name).canonicalFile
                if (out == root) continue // entries like "./" name the root itself
                require(out.path.startsWith(root.path + File.separator)) { "Bad entry ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { i -> out.outputStream().use { i.copyTo(it) } }
                }
            }
        }
    }

    private fun rootFilePath(stream: InputStream): String? {
        val parser = Xml.newPullParser().apply { setInput(stream, null) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "rootfile") {
                parser.getAttributeValue(null, "full-path")?.let { return it }
            }
            event = parser.next()
        }
        return null
    }

    /** Resolves an href against the package directory into a clean container path. */
    fun resolve(base: String, href: String): String {
        val parts = if (base.isEmpty()) mutableListOf() else base.split('/').toMutableList()
        for (segment in Uri.decode(href.substringBefore('#')).split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }
}
