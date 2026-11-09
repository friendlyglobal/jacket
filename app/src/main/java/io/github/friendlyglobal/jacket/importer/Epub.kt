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

    class Package(
        val title: String?,
        val author: String?,
        /** Path of the cover image inside the container, if any. */
        val coverPath: String?,
        /** Content documents in reading order, as paths inside the container. */
        val spine: List<String>,
    )

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
        var coverId: String? = null
        val items = LinkedHashMap<String, Item>()
        val spineIds = mutableListOf<String>()

        stream.use {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(it, null)
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "title" -> if (title == null && parser.namespace == DC) title = parser.nextText().trim()
                        "creator" -> if (author == null && parser.namespace == DC) author = parser.nextText().trim()
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

        return Package(title, author, cover?.href, spine)
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
