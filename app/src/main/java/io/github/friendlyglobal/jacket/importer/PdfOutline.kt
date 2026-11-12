package io.github.friendlyglobal.jacket.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/*
 * A PDF's outline: the contents its maker gave it, as most book-length PDFs have.
 *
 * Android's PDF reader draws pages and hands over their text, but says nothing of the outline. It
 * is a small part of the file, and is read here straight from it: the table that says where each
 * of the file's objects is (in either of the two ways a PDF keeps it), the outline's entries, and
 * the pages they point at. Nothing else of the file is read or understood, and nothing is drawn.
 *
 * It is a convenience, so it is read cautiously: a file that is damaged, locked with a password,
 * or simply odd gives no outline, and the PDF is then stepped through page by page as before.
 */
internal object PdfOutline {
    /** The outline of [file], in order; empty if it has none, or it can't be read. */
    fun read(file: File): List<PdfOutlineEntry> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            // Mapped rather than read: only the few parts that are looked at are ever loaded.
            if (raf.length() > Int.MAX_VALUE) return emptyList()
            read(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
        }
    }.getOrDefault(emptyList())

    fun read(data: ByteBuffer): List<PdfOutlineEntry> = runCatching { Document(data).outline() }.getOrDefault(emptyList())

    // ---- What a PDF is made of ----------------------------------------------------------------

    private class Name(val value: String)
    private class Ref(val number: Int)
    private class Bytes(val value: ByteArray)
    private class Keyword(val value: String)
    private class Stream(val about: Map<*, *>, val data: ByteBuffer, val start: Int)

    /** Reads PDF objects out of [data], from [at] on. */
    private class Parser(private val data: ByteBuffer, var at: Int) {
        private val end = data.limit()

        private fun peek(ahead: Int = 0): Int = if (at + ahead < end) data.get(at + ahead).toInt() and 0xFF else -1

        fun skipSpace() {
            while (at < end) {
                val c = peek()
                if (c == '%'.code) {
                    while (at < end && peek() != '\n'.code && peek() != '\r'.code) at++
                } else if (c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32) at++ else return
            }
        }

        private fun delimiter(c: Int): Boolean = c < 0 || c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 || c.toChar() in "()<>[]{}/%"

        fun value(depth: Int = 0): Any? {
            if (depth > DEEPEST) error("Too deep")
            skipSpace()
            val c = peek()
            return when {
                c < 0 -> error("Ended")
                c == '<'.code && peek(1) == '<'.code -> {
                    at += 2
                    val out = HashMap<String, Any?>()
                    while (true) {
                        skipSpace()
                        if (peek() == '>'.code && peek(1) == '>'.code) {
                            at += 2
                            break
                        }
                        val key = value(depth + 1) as? Name ?: error("No name")
                        out[key.value] = value(depth + 1)
                    }
                    out
                }
                c == '<'.code -> {
                    at++
                    val out = ByteArrayOutputStream()
                    var half = -1
                    while (at < end) {
                        val d = peek()
                        at++
                        if (d == '>'.code) break
                        val digit = Character.digit(d, 16)
                        if (digit < 0) continue
                        if (half < 0) half = digit else {
                            out.write(half * 16 + digit)
                            half = -1
                        }
                    }
                    if (half >= 0) out.write(half * 16)
                    Bytes(out.toByteArray())
                }
                c == '('.code -> {
                    at++
                    val out = ByteArrayOutputStream()
                    var open = 1
                    while (at < end) {
                        var d = peek()
                        at++
                        if (d == '\\'.code) {
                            d = peek()
                            at++
                            when (d.toChar()) {
                                'n' -> out.write(10)
                                'r' -> out.write(13)
                                't' -> out.write(9)
                                'b' -> out.write(8)
                                'f' -> out.write(12)
                                '\r' -> if (peek() == '\n'.code) at++
                                '\n' -> {}
                                in '0'..'7' -> {
                                    var code = d - '0'.code
                                    repeat(2) {
                                        if (peek() in '0'.code..'7'.code) {
                                            code = code * 8 + (peek() - '0'.code)
                                            at++
                                        }
                                    }
                                    out.write(code and 0xFF)
                                }
                                else -> out.write(d)
                            }
                        } else if (d == '('.code) {
                            open++
                            out.write(d)
                        } else if (d == ')'.code) {
                            if (--open == 0) break
                            out.write(d)
                        } else {
                            out.write(d)
                        }
                    }
                    Bytes(out.toByteArray())
                }
                c == '/'.code -> {
                    at++
                    val out = StringBuilder()
                    while (!delimiter(peek())) {
                        var d = peek()
                        at++
                        if (d == '#'.code && Character.digit(peek(), 16) >= 0 && Character.digit(peek(1), 16) >= 0) {
                            d = Character.digit(peek(), 16) * 16 + Character.digit(peek(1), 16)
                            at += 2
                        }
                        out.append(d.toChar())
                    }
                    Name(out.toString())
                }
                c == '['.code -> {
                    at++
                    val out = ArrayList<Any?>()
                    while (true) {
                        skipSpace()
                        if (peek() == ']'.code) {
                            at++
                            break
                        }
                        out += value(depth + 1)
                    }
                    out
                }
                c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> {
                    val number = word()
                    val whole = number.toLongOrNull()
                    // "12 0 R" points at object 12.
                    if (whole != null && whole in 0..Int.MAX_VALUE) {
                        val back = at
                        skipSpace()
                        if (peek() in '0'.code..'9'.code && word().toIntOrNull() != null) {
                            skipSpace()
                            if (peek() == 'R'.code && delimiter(peek(1))) {
                                at++
                                return Ref(whole.toInt())
                            }
                        }
                        at = back
                    }
                    whole ?: number.toDoubleOrNull() ?: 0.0
                }
                else -> when (val word = word()) {
                    "true" -> true
                    "false" -> false
                    "null" -> null
                    "" -> error("Not a PDF object")
                    else -> Keyword(word)
                }
            }
        }

        /** The run of ordinary characters at [at]: a number or a keyword. */
        fun word(): String {
            val from = at
            while (!delimiter(peek())) at++
            return String(ByteArray(at - from) { data.get(from + it) }, Charsets.ISO_8859_1)
        }

        /** After a dictionary: the start of the stream it describes, or -1 if it describes none. */
        fun streamStart(): Int {
            val back = at
            skipSpace()
            if (word() != "stream") {
                at = back
                return -1
            }
            if (peek() == '\r'.code) at++
            if (peek() == '\n'.code) at++
            return at
        }
    }

    private class Document(private val data: ByteBuffer) {
        /** Where each object is: its place in the file, or (packed) the object that holds it and its number there. */
        private val places = HashMap<Int, Long>()
        private val trailer = HashMap<String, Any?>()
        private val packs = LinkedHashMap<Int, Pair<ByteBuffer, IntArray>>()
        private var loads = 0

        init {
            val tail = ByteArray(minOf(data.limit(), 2048)) { data.get(data.limit() - minOf(data.limit(), 2048) + it) }
            val marker = String(tail, Charsets.ISO_8859_1).lastIndexOf("startxref")
            if (marker < 0) error("No table of objects")
            var at: Long = Parser(data, data.limit() - tail.size + marker + 9).value() as? Long ?: error("No table of objects")
            // The newest table first; what it says of an object stands over what the older ones say.
            val seen = HashSet<Long>()
            while (at in 0 until data.limit() && seen.add(at) && seen.size < 64) at = table(at.toInt()) ?: break
        }

        /** Reads the table of objects at [at], in either of its two forms; the place of the table before it, if any. */
        private fun table(at: Int): Long? {
            val parser = Parser(data, at)
            parser.skipSpace()
            val about: Map<*, *>
            if (data.get(parser.at).toInt().toChar() == 'x') {
                parser.word()
                while (true) {
                    parser.skipSpace()
                    val back = parser.at
                    val first = parser.word().toIntOrNull()
                    if (first == null) {
                        parser.at = back
                        break
                    }
                    parser.skipSpace()
                    val count = parser.word().toIntOrNull() ?: error("Damaged table")
                    for (i in 0 until count) {
                        parser.skipSpace()
                        val offset = parser.word().toLongOrNull() ?: error("Damaged table")
                        parser.skipSpace()
                        parser.word()
                        parser.skipSpace()
                        if (parser.word() == "n" && offset > 0) places.putIfAbsent(first + i, offset)
                    }
                }
                if ((parser.value() as? Keyword)?.value != "trailer") error("No trailer")
                about = parser.value() as? Map<*, *> ?: error("No trailer")
                // A file kept both ways at once: the packed objects are listed in a table of the other kind.
                (about["XRefStm"] as? Long)?.let { runCatching { table(it.toInt()) } }
            } else {
                val stream = objectAt(at) as? Stream ?: error("No table of objects")
                about = stream.about
                val widths = (about["W"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: error("Damaged table")
                if (widths.size < 3 || widths.any { it !in 0..8 }) error("Damaged table")
                val size = (about["Size"] as? Long)?.toInt() ?: 0
                val index = (about["Index"] as? List<*>)?.map { (it as? Long)?.toInt() ?: 0 } ?: listOf(0, size)
                val rows = decoded(stream)
                val row = widths.sum()
                var read = 0
                fun field(width: Int, default: Long): Long {
                    if (width == 0) return default
                    var value = 0L
                    repeat(width) { value = (value shl 8) or (rows.get(read++).toLong() and 0xFF) }
                    return value
                }
                for (pair in 0 until index.size / 2) {
                    for (i in 0 until index[pair * 2 + 1]) {
                        if (read + row > rows.limit()) break
                        val kind = field(widths[0], 1)
                        val second = field(widths[1], 0)
                        val third = field(widths[2], 0)
                        val number = index[pair * 2] + i
                        when (kind) {
                            1L -> if (second > 0) places.putIfAbsent(number, second)
                            // Packed: told apart from a place in the file by its sign.
                            2L -> places.putIfAbsent(number, -((second shl 20) or (third and 0xFFFFF)) - 1)
                        }
                    }
                }
            }
            for ((key, value) in about) if (key is String) trailer.putIfAbsent(key, value)
            return about["Prev"] as? Long
        }

        /** The object written at [at] in the file ("12 0 obj ..."): a stream is given with where its bytes begin. */
        private fun objectAt(at: Int): Any? {
            val parser = Parser(data, at)
            parser.value()
            parser.value()
            if ((parser.value() as? Keyword)?.value != "obj") error("Not an object")
            val value = parser.value()
            if (value is Map<*, *>) {
                val start = parser.streamStart()
                if (start >= 0) return Stream(value, data, start)
            }
            return value
        }

        fun resolve(value: Any?, depth: Int = 0): Any? {
            if (value !is Ref) return value
            if (depth > 16 || ++loads > MOST_LOADS) error("Too much to read")
            val place = places[value.number] ?: return null
            if (place >= 0) return resolve(objectAt(place.toInt()), depth + 1)
            val packed = -(place + 1)
            val (bytes, offsets) = pack((packed shr 20).toInt())
            val at = offsets.getOrNull((packed and 0xFFFFF).toInt()) ?: return null
            return resolve(Parser(bytes, at).value(), depth + 1)
        }

        /** The objects packed into object [number], unpacked: their bytes, and where each begins in them. */
        private fun pack(number: Int): Pair<ByteBuffer, IntArray> = packs.getOrPut(number) {
            val place = places[number]?.takeIf { it >= 0 } ?: error("No such pack")
            val stream = objectAt(place.toInt()) as? Stream ?: error("No such pack")
            val bytes = decoded(stream)
            val count = (stream.about["N"] as? Long)?.toInt() ?: 0
            val first = (stream.about["First"] as? Long)?.toInt() ?: 0
            val head = Parser(bytes, 0)
            val offsets = IntArray(count) {
                head.value()
                first + ((head.value() as? Long)?.toInt() ?: 0)
            }
            if (packs.size >= KEPT_PACKS) packs.remove(packs.keys.first())
            bytes to offsets
        }

        /** The bytes of [stream] as they were before they were squeezed. */
        private fun decoded(stream: Stream): ByteBuffer {
            val length = when (val told = stream.about["Length"]) {
                is Long -> told.toInt()
                is Ref -> (resolve(told) as? Long)?.toInt()
                else -> null
            }?.takeIf { it >= 0 && stream.start + it <= data.limit() } ?: error("No length")
            val raw = ByteArray(length) { data.get(stream.start + it) }
            val filters = when (val filter = stream.about["Filter"]) {
                null -> emptyList()
                is Name -> listOf(filter.value)
                is List<*> -> filter.map { (it as? Name)?.value }
                else -> error("Unknown filter")
            }
            if (filters.isEmpty()) return ByteBuffer.wrap(raw)
            if (filters != listOf("FlateDecode")) error("Unknown filter")
            val inflater = Inflater()
            val out = ByteArrayOutputStream(length * 3)
            try {
                inflater.setInput(raw)
                val buffer = ByteArray(1 shl 14)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                    if (out.size() > LARGEST_STREAM) error("Too large")
                }
            } finally {
                inflater.end()
            }
            val parms = when (val p = stream.about["DecodeParms"]) {
                is Map<*, *> -> p
                is List<*> -> p.firstOrNull() as? Map<*, *>
                else -> null
            }
            val predictor = (parms?.get("Predictor") as? Long)?.toInt() ?: 1
            val bytes = out.toByteArray()
            if (predictor < 10) return if (predictor == 1) ByteBuffer.wrap(bytes) else error("Unknown predictor")
            // Each row was stored as its difference from the row above (or the byte before): put back.
            val columns = (parms?.get("Columns") as? Long)?.toInt() ?: 1
            val colors = (parms?.get("Colors") as? Long)?.toInt() ?: 1
            val bits = (parms?.get("BitsPerComponent") as? Long)?.toInt() ?: 8
            val step = ((colors * bits + 7) / 8).coerceAtLeast(1)
            val row = ((columns * colors * bits + 7) / 8).coerceAtLeast(1)
            val rows = bytes.size / (row + 1)
            val plain = ByteArray(rows * row)
            for (r in 0 until rows) {
                val kind = bytes[r * (row + 1)].toInt()
                for (i in 0 until row) {
                    val x = bytes[r * (row + 1) + 1 + i].toInt() and 0xFF
                    val a = if (i >= step) plain[r * row + i - step].toInt() and 0xFF else 0
                    val b = if (r > 0) plain[(r - 1) * row + i].toInt() and 0xFF else 0
                    val c = if (r > 0 && i >= step) plain[(r - 1) * row + i - step].toInt() and 0xFF else 0
                    val value = when (kind) {
                        0 -> x
                        1 -> x + a
                        2 -> x + b
                        3 -> x + (a + b) / 2
                        4 -> {
                            val p = a + b - c
                            val pa = kotlin.math.abs(p - a)
                            val pb = kotlin.math.abs(p - b)
                            val pc = kotlin.math.abs(p - c)
                            x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        }
                        else -> error("Unknown row")
                    }
                    plain[r * row + i] = value.toByte()
                }
            }
            return ByteBuffer.wrap(plain)
        }

        private fun dict(value: Any?): Map<*, *>? = when (val found = resolve(value)) {
            is Map<*, *> -> found
            is Stream -> found.about
            else -> null
        }

        fun outline(): List<PdfOutlineEntry> {
            // A locked file's titles are scrambled.
            if (trailer["Encrypt"] != null) return emptyList()
            val catalog = dict(trailer["Root"]) ?: return emptyList()
            val outlines = dict(catalog["Outlines"]) ?: return emptyList()
            if (outlines["First"] == null) return emptyList()
            val pages by lazy { pageNumbers(catalog) }
            /** Each page's foot and height, in the file's own units; null for one shown turned on its side, which is measured another way. */
            val sizes = HashMap<Int, Pair<Float, Float>?>()

            /** How far down its page a destination points (0..1), where it says how high up the page it is. */
            fun within(page: Ref, kind: String?, top: Any?): Float? {
                val up = (top as? Number)?.toFloat() ?: return null
                if (kind != "XYZ" && kind != "FitH" && kind != "FitBH") return null
                val (foot, height) = sizes.getOrPut(page.number) {
                    // A page takes its size from the pages above it where it gives none itself.
                    var node = dict(page)
                    var box: List<*>? = null
                    var turned = 0L
                    var hops = 0
                    while (node != null && box == null && hops++ < 32) {
                        box = resolve(node["CropBox"] ?: node["MediaBox"]) as? List<*>
                        if (turned == 0L) turned = (resolve(node["Rotate"]) as? Long) ?: 0L
                        node = dict(node["Parent"])
                    }
                    val edges = box?.map { (resolve(it) as? Number)?.toFloat() ?: 0f }
                    if (edges == null || edges.size < 4 || turned % 360L != 0L || edges[3] <= edges[1]) null else edges[1] to edges[3] - edges[1]
                } ?: return null
                return (1f - (up - foot) / height).takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            }

            fun destination(value: Any?, depth: Int = 0): Pair<Int, Float?>? {
                if (depth > 4) return null
                return when (val dest = resolve(value)) {
                    is List<*> -> {
                        val kind = (dest.getOrNull(1) as? Name)?.value
                        val top = if (kind == "XYZ") dest.getOrNull(3) else dest.getOrNull(2)
                        when (val page = dest.firstOrNull()) {
                            is Ref -> pages[page.number]?.let { it to within(page, kind, resolve(top)) }
                            is Long -> page.toInt() to null
                            else -> null
                        }
                    }
                    is Map<*, *> -> destination(dest["D"], depth + 1)
                    is Name -> destination(dict(catalog["Dests"])?.get(dest.value), depth + 1)
                    is Bytes -> destination(named(dict(dict(catalog["Names"])?.get("Dests")), dest.value, 0), depth + 1)
                    else -> null
                }
            }

            val out = ArrayList<PdfOutlineEntry>()
            val seen = HashSet<Int>()
            // Entries in order: each one's children before its next sibling.
            val pending = ArrayDeque<Pair<Any?, Int>>()
            pending.addLast(outlines["First"] to 0)
            while (pending.isNotEmpty() && out.size < MOST_ENTRIES) {
                val (ref, level) = pending.removeLast()
                if (ref is Ref && !seen.add(ref.number)) continue
                val item = dict(ref) ?: continue
                item["Next"]?.let { pending.addLast(it to level) }
                item["First"]?.let { if (level < DEEPEST_OUTLINE) pending.addLast(it to level + 1) }
                val title = (resolve(item["Title"]) as? Bytes)?.let { text(it.value) }.orEmpty()
                val target = runCatching {
                    item["Dest"]?.let { destination(it) } ?: dict(item["A"])?.takeIf { (it["S"] as? Name)?.value == "GoTo" }?.let { destination(it["D"]) }
                }.getOrNull()
                if (title.isNotEmpty() && target != null && target.first >= 0) out += PdfOutlineEntry(title, level, target.first, target.second)
            }
            return out
        }

        /** Looks [name] up in a tree of names: the way a PDF keeps its named places. */
        private fun named(node: Map<*, *>?, name: ByteArray, depth: Int): Any? {
            if (node == null || depth > 12) return null
            (resolve(node["Names"]) as? List<*>)?.let { names ->
                for (i in 0 until names.size / 2) if ((resolve(names[i * 2]) as? Bytes)?.value?.contentEquals(name) == true) return names[i * 2 + 1]
            }
            (resolve(node["Kids"]) as? List<*>)?.forEach { kid ->
                val child = dict(kid) ?: return@forEach
                val limits = resolve(child["Limits"]) as? List<*>
                val low = (resolve(limits?.getOrNull(0)) as? Bytes)?.value
                val high = (resolve(limits?.getOrNull(1)) as? Bytes)?.value
                if (low != null && high != null && (compare(name, low) < 0 || compare(name, high) > 0)) return@forEach
                named(child, name, depth + 1)?.let { return it }
            }
            return null
        }

        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        /** Which page (from 0) each page object is: the pages are a tree, read from left to right. */
        private fun pageNumbers(catalog: Map<*, *>): Map<Int, Int> {
            val out = HashMap<Int, Int>()
            val seen = HashSet<Int>()
            val pending = ArrayDeque<Any?>()
            pending.addLast(catalog["Pages"])
            var count = 0
            while (pending.isNotEmpty() && count < MOST_PAGES) {
                val ref = pending.removeLast()
                if (ref is Ref && !seen.add(ref.number)) continue
                val node = dict(ref) ?: continue
                val kids = resolve(node["Kids"]) as? List<*>
                if (kids != null) {
                    for (kid in kids.asReversed()) pending.addLast(kid)
                } else if (ref is Ref) {
                    out[ref.number] = count++
                }
            }
            return out
        }
    }

    /** A PDF's text as words: it is written in one of three ways, told apart by how it begins. */
    private fun text(bytes: ByteArray): String {
        fun starts(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        val raw = when {
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> buildString(bytes.size) {
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    append(
                        when (c) {
                            in 0x18..0x1F -> LOW[c - 0x18]
                            in 0x80..0xA0 -> HIGH[c - 0x80]
                            else -> c.toChar()
                        },
                    )
                }
            }
        }
        return raw.map { if (it < ' ' || it == '�' || it == '­') ' ' else it }.joinToString("").replace(SPACES, " ").trim()
    }

    private val SPACES = Regex("\\s+")

    /** Where a PDF's own alphabet differs from Latin-1: the accents at 0x18, and the punctuation and letters from 0x80. */
    private const val LOW = "˘ˇˆ˙˝˛˚˜"
    private const val HIGH = "•†‡…—–ƒ⁄‹›−‰„“”‘’‚™ﬁﬂ" +
        "ŁŒŠŸŽıłœšž €"

    private const val DEEPEST = 64
    private const val DEEPEST_OUTLINE = 8
    private const val MOST_ENTRIES = 5_000
    private const val MOST_PAGES = 100_000
    private const val MOST_LOADS = 600_000
    private const val KEPT_PACKS = 12
    private const val LARGEST_STREAM = 64 * 1024 * 1024
}
