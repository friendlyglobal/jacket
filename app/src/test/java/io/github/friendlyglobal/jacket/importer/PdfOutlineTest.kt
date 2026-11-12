package io.github.friendlyglobal.jacket.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater

/** A PDF's outline, read straight from the file (see PdfOutline.kt). */
class PdfOutlineTest {
    /**
     * A PDF of four pages and no words, with an outline that points at them in each of the ways a
     * PDF can: outright, through an action, by a name, and by a name kept in a tree of names.
     */
    private val objects = mapOf(
        1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R /Dests 20 0 R /Names << /Dests 21 0 R >> >>",
        2 to "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 4 /MediaBox [0 0 400 600] >>",
        3 to "<< /Type /Pages /Parent 2 0 R /Kids [5 0 R 6 0 R] /Count 2 >>",
        4 to "<< /Type /Pages /Parent 2 0 R /Kids [7 0 R 8 0 R] /Count 2 >>",
        5 to "<< /Type /Page /Parent 3 0 R >>",
        6 to "<< /Type /Page /Parent 3 0 R >>",
        7 to "<< /Type /Page /Parent 4 0 R >>",
        8 to "<< /Type /Page /Parent 4 0 R /MediaBox [0 0 400 800] >>",
        10 to "<< /Type /Outlines /First 11 0 R /Last 14 0 R /Count 4 >>",
        11 to "<< /Title (Chapter One) /Parent 10 0 R /Next 12 0 R /Dest [5 0 R /XYZ 0 600 0] >>",
        // "Capítulo 2", as a PDF writes what its own alphabet can't.
        12 to "<< /Title <FEFF00430061007000ED00740075006C006F00200032> /Parent 10 0 R /Prev 11 0 R /Next 14 0 R /First 13 0 R /Last 13 0 R /Count 1 " +
            "/A << /S /GoTo /D [6 0 R /XYZ 0 300 0] >> >>",
        13 to "<< /Title (A section \\(named\\)) /Parent 12 0 R /Dest /sec >>",
        14 to "<< /Title (The \\220end\\220) /Parent 10 0 R /Prev 12 0 R /Dest (last) >>",
        20 to "<< /sec [7 0 R /FitH 450] >>",
        21 to "<< /Kids [22 0 R] >>",
        22 to "<< /Limits [(a) (z)] /Names [(last) << /D [8 0 R /Fit] >>] >>",
    )

    private fun check(entries: List<PdfOutlineEntry>) {
        assertEquals(listOf("Chapter One", "Capítulo 2", "A section (named)", "The ’end’"), entries.map { it.title })
        assertEquals(listOf(0, 0, 1, 0), entries.map { it.level })
        assertEquals(listOf(0, 1, 2, 3), entries.map { it.page })
        assertEquals(0f, entries[0].within!!, 0.001f)
        assertEquals(0.5f, entries[1].within!!, 0.001f)
        assertEquals(0.25f, entries[2].within!!, 0.001f)
        // "Fit the page": nowhere on it in particular.
        assertNull(entries[3].within)
    }

    /** The objects as a PDF with a plain table of where each one is. */
    private fun plain(objects: Map<Int, String>, trailer: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val places = HashMap<Int, Int>()
        for ((number, body) in objects) {
            places[number] = out.size()
            out.write("$number 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val table = out.size()
        val size = objects.keys.max() + 1
        out.write("xref\n0 $size\n".toByteArray())
        for (number in 0 until size) {
            val place = places[number]
            out.write((if (place == null) "0000000000 65535 f \n" else "%010d 00000 n \n".format(place)).toByteArray())
        }
        out.write("trailer\n<< /Size $size /Root 1 0 R $trailer>>\nstartxref\n$table\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    private fun squeezed(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    }

    /** The same objects as newer PDFs keep them: packed together and squeezed, with the table squeezed too. */
    private fun packed(objects: Map<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray())
        val numbers = objects.keys.sorted()
        val bodies = StringBuilder()
        val head = StringBuilder()
        for (number in numbers) {
            head.append("$number ${bodies.length} ")
            bodies.append(objects.getValue(number)).append('\n')
        }
        val pack = squeezed((head.toString() + bodies).toByteArray(Charsets.ISO_8859_1))
        val packNumber = numbers.max() + 1
        val packAt = out.size()
        out.write("$packNumber 0 obj\n<< /Type /ObjStm /N ${numbers.size} /First ${head.length} /Length ${pack.size} /Filter /FlateDecode >>\nstream\n".toByteArray())
        out.write(pack)
        out.write("\nendstream\nendobj\n".toByteArray())

        // The table: a row for each object, each stored as its difference from the row above.
        val tableNumber = packNumber + 1
        val tableAt = out.size()
        val rows = ArrayList<ByteArray>()
        for (number in 0..tableNumber) {
            val row = ByteArray(6)
            fun set(kind: Int, second: Int, third: Int) {
                row[0] = kind.toByte()
                row[1] = (second shr 16).toByte(); row[2] = (second shr 8).toByte(); row[3] = second.toByte()
                row[4] = (third shr 8).toByte(); row[5] = third.toByte()
            }
            when {
                number in objects -> set(2, packNumber, numbers.indexOf(number))
                number == packNumber -> set(1, packAt, 0)
                number == tableNumber -> set(1, tableAt, 0)
                else -> set(0, 0, 65535)
            }
            rows += row
        }
        val stored = ByteArrayOutputStream()
        var above = ByteArray(6)
        for (row in rows) {
            stored.write(2)
            for (i in row.indices) stored.write((row[i] - above[i]) and 0xFF)
            above = row
        }
        val table = squeezed(stored.toByteArray())
        out.write(
            ("$tableNumber 0 obj\n<< /Type /XRef /Size ${tableNumber + 1} /W [1 3 2] /Root 1 0 R /Length ${table.size} /Filter /FlateDecode " +
                "/DecodeParms << /Predictor 12 /Columns 6 >> >>\nstream\n").toByteArray(),
        )
        out.write(table)
        out.write("\nendstream\nendobj\nstartxref\n$tableAt\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun readsAnOutlineHoweverItPointsAtItsPages() = check(PdfOutline.read(ByteBuffer.wrap(plain(objects))))

    @Test
    fun readsAPdfWhoseObjectsArePackedAndSqueezed() = check(PdfOutline.read(ByteBuffer.wrap(packed(objects))))

    @Test
    fun readsTheOutlineLibreOfficeWrites() {
        val bytes = javaClass.getResourceAsStream("/pdf/outline.pdf")!!.use { it.readBytes() }
        val entries = PdfOutline.read(ByteBuffer.wrap(bytes))
        assertEquals(listOf("Chapter One. The Bank", "Chapter Two. The Well", "Falling", "Dinah", "Chapter Three. The Hall"), entries.map { it.title })
        assertEquals(listOf(0, 0, 1, 1, 0), entries.map { it.level })
        assertEquals(listOf(1, 2, 2, 3, 4), entries.map { it.page })
        // Chapters open at the top of their page (under its margin), sections part of the way down.
        assertEquals(0.1f, entries[0].within!!, 0.02f)
        assertEquals(0.72f, entries[2].within!!, 0.02f)
        assertEquals(0.38f, entries[3].within!!, 0.02f)
    }

    @Test
    fun aPdfWithoutAnOutlineHasNone() {
        assertEquals(0, PdfOutline.read(ByteBuffer.wrap(plain(objects - 10 + (1 to "<< /Type /Catalog /Pages 2 0 R >>")))).size)
    }

    @Test
    fun aLockedPdfGivesNoOutline() {
        // Its titles are scrambled: better none than nonsense.
        assertEquals(0, PdfOutline.read(ByteBuffer.wrap(plain(objects, trailer = "/Encrypt << /Filter /Standard >> "))).size)
    }

    @Test
    fun anOutlineThatGoesRoundInCirclesEnds() {
        val looped = objects + (14 to "<< /Title (Round again) /Parent 10 0 R /Next 11 0 R /Dest [8 0 R /Fit] >>")
        assertEquals(4, PdfOutline.read(ByteBuffer.wrap(plain(looped))).size)
    }

    @Test
    fun whatIsNotAPdfGivesNoOutline() {
        assertEquals(0, PdfOutline.read(ByteBuffer.wrap("Not a PDF at all".toByteArray())).size)
        assertEquals(0, PdfOutline.read(ByteBuffer.wrap(ByteArray(0))).size)
        // Cut off in the middle.
        val whole = plain(objects)
        assertEquals(0, PdfOutline.read(ByteBuffer.wrap(whole.copyOf(whole.size / 2))).size)
    }
}
