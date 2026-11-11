package io.github.friendlyglobal.jacket.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withTranslation
import java.io.File
import kotlin.math.abs

object Bitmaps {
    /** Decodes [bytes], downsampling so the height lands near [maxHeight]. */
    fun decodeSampled(bytes: ByteArray, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun decodeFile(file: File, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The color a cover's book is tinted with: its most vivid, else its most common. */
    fun seedOf(cover: Bitmap): Int? {
        val palette = androidx.palette.graphics.Palette.from(cover).maximumColorCount(16).generate()
        return (palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch)?.rgb
    }

    /**
     * An image the reader picked, upright (as the camera meant it), about [maxHeight] tall and
     * cropped to a book's 2:3 from its middle.
     */
    fun decodeCover(context: android.content.Context, uri: android.net.Uri, maxHeight: Int = 1400): Bitmap? {
        val full = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Pixels Palette and the JPEG encoder can read.
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val height = info.size.height
                if (height > maxHeight * 2) decoder.setTargetSampleSize(height / maxHeight)
            }
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            decodeSampled(bytes, maxHeight)
        } ?: return null
        val width = minOf(full.width, full.height * 2 / 3)
        val height = minOf(full.height, full.width * 3 / 2)
        return Bitmap.createBitmap(full, (full.width - width) / 2, (full.height - height) / 2, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    fun saveJpeg(bitmap: Bitmap, file: File, maxHeight: Int) {
        val scaled = if (bitmap.height > maxHeight) {
            bitmap.scale((bitmap.width * maxHeight / bitmap.height.toFloat()).toInt().coerceAtLeast(1), maxHeight)
        } else bitmap
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
}

/** A typographic cover for books that don't ship one (plain text, some EPUBs). */
object TitleCard {
    fun render(title: String, author: String): Bitmap {
        val w = 800
        val h = 1200
        val hue = (abs(title.hashCode()) % 360).toFloat()
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ColorUtils.HSLToColor(floatArrayOf(hue, 0.42f, 0.24f)))

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.HSLToColor(floatArrayOf((hue + 40) % 360, 0.75f, 0.68f)) }
        canvas.drawCircle(w * 0.5f, h * 0.66f, w * 0.30f, accent)
        accent.color = ColorUtils.setAlphaComponent(Color.WHITE, 40)
        canvas.drawCircle(w * 0.5f, h * 0.66f, w * 0.40f, accent.apply { style = Paint.Style.STROKE; strokeWidth = 6f })

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 84f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, w - 160)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(5)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        canvas.withTranslation(80f, 150f) { layout.draw(this) }

        if (author.isNotBlank()) {
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(Color.WHITE, 210)
                textSize = 34f
                letterSpacing = 0.18f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText(author.uppercase(), w / 2f, h - 90f, authorPaint)
        }
        return bitmap
    }
}
