package io.github.friendlyglobal.jacket.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** CBZ comics: a zip of page images, optionally with a ComicInfo.xml. */
object Comic {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    fun pageNames(zip: ZipFile): List<String> = zip.entries().asSequence()
        .filter { !it.isDirectory && !it.name.startsWith("__MACOSX") && !it.name.substringAfterLast('/').startsWith(".") }
        .filter { it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
        .map { it.name }
        .sortedWith(NaturalOrder)
        .toList()

    fun readMeta(file: File): BookMeta = ZipFile(file).use { zip ->
        val pages = pageNames(zip)
        require(pages.isNotEmpty()) { "No pages in comic" }
        val cover = zip.getInputStream(zip.getEntry(pages.first())).use { Bitmaps.decodeSampled(it.readBytes(), 1400) }
        val info = zip.entries().asSequence().firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?.let { entry -> runCatching { zip.getInputStream(entry).use(::comicInfo) }.getOrNull() }
        BookMeta(info?.first, info?.second, cover, pages.size)
    }

    private fun comicInfo(stream: java.io.InputStream): Pair<String?, String?> {
        val parser = Xml.newPullParser().apply { setInput(stream, null) }
        val values = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name in setOf("Title", "Series", "Number", "Writer")) {
                values[parser.name] = parser.nextText().trim()
            }
            event = parser.next()
        }
        val series = values["Series"]?.let { s -> values["Number"]?.let { "$s #$it" } ?: s }
        return (values["Title"]?.takeIf { it.isNotEmpty() } ?: series) to values["Writer"]
    }

    /** Sorts "page2" before "page10". */
    object NaturalOrder : Comparator<String> {
        private val chunk = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]; val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.compareTo(q)
                } else p.compareTo(q)
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
