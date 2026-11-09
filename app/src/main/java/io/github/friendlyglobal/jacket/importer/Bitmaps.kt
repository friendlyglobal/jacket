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
