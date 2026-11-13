package io.github.friendlyglobal.jacket.importer

import android.graphics.BitmapFactory
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
        val info = runCatching { info(zip) }.getOrNull()
        BookMeta(info?.title, info?.writer, cover, pages.size)
    }

    /** What a comic's ComicInfo.xml says of it: its name, who wrote it, and whether it reads right to left. */
    class Info(val title: String?, val writer: String?, val rightToLeft: Boolean)

    /** The comic's ComicInfo.xml, read; null where it has none. */
    fun info(zip: ZipFile): Info? {
        val entry = zip.entries().asSequence().firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) } ?: return null
        return zip.getInputStream(entry).use(::comicInfo)
    }

    private fun comicInfo(stream: java.io.InputStream): Info {
        val parser = Xml.newPullParser().apply { setInput(stream, null) }
        val values = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name in setOf("Title", "Series", "Number", "Writer", "Manga")) {
                values[parser.name] = parser.nextText().trim()
            }
            event = parser.next()
        }
        val series = values["Series"]?.let { s -> values["Number"]?.let { "$s #$it" } ?: s }
        return Info(values["Title"]?.takeIf { it.isNotEmpty() } ?: series, values["Writer"], readsRightToLeft(values["Manga"]))
    }

    /**
     * Whether ComicInfo.xml's `Manga` says the pages read right to left. It is "Yes" for any manga
     * and "YesAndRightToLeft" for one whose pages turn the other way; only that says the direction.
     */
    internal fun readsRightToLeft(manga: String?): Boolean = manga?.trim().equals("YesAndRightToLeft", ignoreCase = true)

    /**
     * How wide the picture in [stream] is for its height; 0 where that can't be told. Only its
     * start is read, where the size is written, never the whole picture.
     */
    fun aspectOf(stream: java.io.InputStream): Float {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream.buffered(SIZE_READ), null, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth / bounds.outHeight.toFloat() else 0f
    }

    /** What is read at a time of a picture whose size is wanted: its size is written well within this. */
    private const val SIZE_READ = 16 * 1024

    /** Sorts "page2" before "page10". */
    object NaturalOrder : Comparator<String> {
        private val chunk = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]; val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    // Compare numerically: "03" == "3" < "10". Zero padding only breaks ties.
                    val a = p.trimStart('0')
                    val b = q.trimStart('0')
                    a.length.compareTo(b.length).takeIf { it != 0 }
                        ?: a.compareTo(b).takeIf { it != 0 }
                        ?: p.length.compareTo(q.length)
                } else p.compareTo(q)
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
