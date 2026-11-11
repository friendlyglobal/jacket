package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.KeyEvent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import io.github.friendlyglobal.jacket.MainActivity
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.reader.ListenService
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.theme.DEFAULT_SEED
import io.github.friendlyglobal.jacket.ui.theme.SeedScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * What Jacket's widgets share: the theme (always dark, tinted by the book), the intents behind a
 * tap, the cover, the badges and the wavy progress line. Glance draws neither shapes nor waves,
 * so those are painted onto small bitmaps here.
 */

/** The scheme behind [GlanceTheme] in a widget, for what's painted onto bitmaps. */
internal val LocalWidgetScheme = staticCompositionLocalOf { SeedScheme.dark(DEFAULT_SEED) }

/**
 * Drawing the widget picker's preview: the picker shows it at a size of its own choosing, not the
 * one the layout was given, so pictures sized to the layout (the wavy line) would come out stretched.
 */
internal val LocalWidgetPreview = staticCompositionLocalOf { false }

/** [content] as the widget picker's preview (see [LocalWidgetPreview]). */
@Composable
internal fun AsPreview(content: @Composable () -> Unit) = CompositionLocalProvider(LocalWidgetPreview provides true, content = content)

/** Dark, in the colors of the book's cover (or Jacket's own teal without a book). */
@Composable
internal fun WidgetTheme(seed: Int?, content: @Composable () -> Unit) {
    val scheme = remember(seed) { SeedScheme.dark(seed ?: DEFAULT_SEED) }
    CompositionLocalProvider(LocalWidgetScheme provides scheme) {
        GlanceTheme(colors = ColorProviders(scheme), content = content)
    }
}

/** Opens [book] where you left it, or the library when there's none. */
internal fun openIntent(context: Context, book: Book?) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_MAIN
    // A distinct data URI per book keeps the PendingIntents from being merged.
    data = "jacket://book/${book?.id ?: "library"}".toUri()
    book?.let { putExtra(MainActivity.EXTRA_OPEN_BOOK, it.id) }
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

/** Opens [book] and starts reading it aloud, as the launcher's "Listen" shortcut does. */
internal fun listenIntent(context: Context, book: Book) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_MAIN
    data = "jacket://listen/${book.id}".toUri()
    putExtra(MainActivity.EXTRA_OPEN_BOOK, book.id)
    putExtra(MainActivity.EXTRA_LISTEN, true)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

/**
 * A headphone button press, delivered to the read-aloud session: Media3's service takes these
 * and plays or pauses the voice without opening the app.
 */
internal fun mediaButtonIntent(context: Context, keyCode: Int) = Intent(context, ListenService::class.java).apply {
    action = Intent.ACTION_MEDIA_BUTTON
    // Play and pause get PendingIntents of their own.
    data = "jacket://media-button/$keyCode".toUri()
    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
}

/** Formats Jacket reads aloud. */
internal val Book.canListen: Boolean get() = format == BookFormat.EPUB || format == BookFormat.TXT

/** The books you're reading, most recent first, with the current one leading; none finished or missing. */
internal fun readingNow(state: LibraryState, missing: Set<String>): List<Book> {
    val recent = state.books.filter { !it.isFinished && it.id !in missing }.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
    val current = state.current?.takeIf { book -> recent.any { it.id == book.id } }
    return listOfNotNull(current) + recent.filter { it.id != current?.id }
}

/** RemoteViews have a bitmap budget, so covers come no larger than [maxSide] pixels tall. */
internal suspend fun loadCover(library: Library, book: Book, maxSide: Int = 600): Bitmap? =
    withContext(Dispatchers.IO) { library.coverOf(book)?.let { Bitmaps.decodeFile(it, maxSide) } }

/** The widget's surface: rounded, dark, and a tap opens [book]. */
@Composable
internal fun base(context: Context, book: Book?) = GlanceModifier
    .fillMaxSize()
    .cornerRadius(24.dp)
    .background(GlanceTheme.colors.surface)
    .clickable(actionStartActivity(openIntent(context, book)))

@Composable
internal fun Label(text: String) = Text(
    text.uppercase(),
    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
    maxLines = 1,
)

/** A cover at [width] × [height]; without a picture, a tile with the title on it. */
@Composable
internal fun Cover(context: Context, cover: Bitmap?, title: String, width: Dp, height: Dp, modifier: GlanceModifier = GlanceModifier) {
    if (cover != null) {
        Image(
            provider = ImageProvider(cover), contentDescription = context.getString(R.string.cover_of, title),
            modifier = modifier.size(width, height).cornerRadius(6.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier.size(width, height).cornerRadius(6.dp).background(GlanceTheme.colors.secondaryContainer).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, maxLines = 4,
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/** The accent for a book's progress: its last stretch wears the tertiary color, as on the outer screen. */
internal fun ColorScheme.accentFor(status: CoverStatus): Color =
    if (status.phase == CoverPhase.ALMOST_DONE) tertiary else primary

/**
 * The book's progress as Material 3 Expressive draws it: a wave up to where you are, a gap, the
 * flat track for the rest with a stop dot at its end, and the chapter starts as small dots while
 * the book is open. [width] is about the width it will get; the image is stretched to fit.
 */
@Composable
internal fun WavyProgress(
    status: CoverStatus,
    width: Dp,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
    onScrim: Boolean = false,
    description: String? = null,
) {
    val context = LocalContext.current
    val scheme = LocalWidgetScheme.current
    val active = scheme.accentFor(status)
    val track = if (onScrim) Color.White.copy(alpha = 0.3f) else scheme.secondaryContainer
    if (LocalWidgetPreview.current) {
        // A plain line keeps its shape at any size.
        LinearProgressIndicator(
            progress = status.progress, modifier = modifier.height(4.dp),
            color = ColorProvider(active), backgroundColor = ColorProvider(track),
        )
        return
    }
    val bitmap = remember(width, status.progress, status.marks, active, track) {
        WidgetArt.wavyLine(context, width, status.progress, active.toArgb(), track.toArgb(), status.marks)
    }
    Image(
        provider = ImageProvider(bitmap), contentDescription = description,
        modifier = modifier.height(WidgetArt.LINE_HEIGHT), contentScale = ContentScale.FillBounds,
    )
}

/** Bitmaps for what Glance can't draw. */
internal object WidgetArt {
    /** The wavy line's box: the wave's swing plus the stroke, with a little room. */
    val LINE_HEIGHT = 12.dp
    private const val STROKE = 4f
    private const val AMPLITUDE = 3f
    private const val WAVELENGTH = 22f
    private const val GAP = 4f

    fun wavyLine(context: Context, width: Dp, progress: Float, active: Int, track: Int, marks: List<Float> = emptyList()): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (width.value * d).roundToInt().coerceIn(16, 2400)
        val h = (LINE_HEIGHT.value * d).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        val stroke = STROKE * d
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val cy = h / 2f
        val start = stroke / 2f
        val end = w - stroke / 2f
        val p = progress.coerceIn(0f, 1f)
        val x = start + (end - start) * p
        val gap = GAP * d + stroke

        // The track, flat, from just past the wave to the end.
        val trackStart = if (p > 0f) x + gap else start
        if (trackStart < end) {
            canvas.drawLine(trackStart, cy, end, cy, line.apply { color = track })
            // Chapter starts still ahead, as dots on the track.
            if (marks.size in 2..80) {
                dot.color = active
                dot.alpha = 150
                for (m in marks) {
                    val mx = start + (end - start) * m
                    if (mx > trackStart + stroke && mx < end - stroke * 2) canvas.drawCircle(mx, cy, stroke * 0.22f, dot)
                }
                dot.alpha = 255
            }
            // The stop indicator: where the book ends.
            canvas.drawCircle(end, cy, stroke / 2f, dot.apply { color = active })
        }

        // The wave, up to where you are. It flattens at the very start and end, as M3's does.
        if (p > 0f) {
            val amplitude = AMPLITUDE * d * when {
                p < 0.08f -> p / 0.08f
                p > 0.95f -> (1f - p) / 0.05f
                else -> 1f
            }
            val wavelength = WAVELENGTH * d
            val path = Path()
            var px = start
            path.moveTo(px, cy)
            while (px < x) {
                px = (px + 1f).coerceAtMost(x)
                path.lineTo(px, cy + amplitude * sin(2 * PI * (px - start) / wavelength).toFloat())
            }
            canvas.drawPath(path, line.apply { color = active })
        }
        return bitmap
    }

    /** One of Material's shapes in [color], [sizeDp] square, with an optional icon path on it. */
    fun shape(context: Context, shape: RoundedPolygon, color: Int, sizeDp: Int = BADGE_DP, iconPath: String? = null, iconColor: Int = 0): Bitmap {
        val size = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()
        // Material shapes come one unit square in size.
        matrix.setScale(size.toFloat(), size.toFloat())
        canvas.drawPath(shape.toPath().apply { transform(matrix) }, paint.apply { this.color = color })
        if (iconPath != null) {
            // The icon's 24-unit box, a little over half the shape, in its middle.
            val iconScale = size * 0.56f / 24f
            val inset = (size - 24f * iconScale) / 2f
            matrix.setScale(iconScale, iconScale)
            matrix.postTranslate(inset, inset)
            canvas.drawPath(PathParser.createPathFromPathData(iconPath).apply { transform(matrix) }, paint.apply { this.color = iconColor })
        }
        return bitmap
    }

    /** Badges are drawn once at this size and shown at whatever size the layout wants. */
    const val BADGE_DP = 48
}

/**
 * The badge a stage wears: a check in a burst once finished, headphones in a cookie while
 * listening; none otherwise.
 */
internal fun stageBadge(context: Context, phase: CoverPhase, scheme: ColorScheme): Bitmap? = when (phase) {
    CoverPhase.FINISHED -> WidgetArt.shape(context, MaterialShapes.SoftBurst, scheme.tertiary.toArgb(), iconPath = JacketIcons.CHECK_PATH, iconColor = scheme.onTertiary.toArgb())
    CoverPhase.LISTENING -> WidgetArt.shape(context, MaterialShapes.Cookie9Sided, scheme.primary.toArgb(), iconPath = JacketIcons.HEADPHONES_PATH, iconColor = scheme.onPrimary.toArgb())
    else -> null
}

@Composable
internal fun Badge(badge: Bitmap, size: Dp) =
    Image(provider = ImageProvider(badge), contentDescription = null, modifier = GlanceModifier.size(size))

/**
 * The stage in a few words: "Almost there · 93%", the chapter being read aloud (or "Listening")
 * with the percentage, otherwise the book's own status ("42% read", "Finished").
 */
internal fun stageLine(context: Context, book: Book, status: CoverStatus): String {
    val percent = context.getString(R.string.percent, status.percent)
    return when (status.phase) {
        CoverPhase.ALMOST_DONE -> context.getString(R.string.cover_almost_done) + context.getString(R.string.separator) + percent
        CoverPhase.LISTENING -> status.chapter.ifEmpty { context.getString(R.string.cover_listening) } + context.getString(R.string.separator) + percent
        else -> book.statusLabel(context.resources)
    }
}

/** The label over a book with no progress showing: what you're doing with it. */
internal fun stageLabel(context: Context, status: CoverStatus): String =
    context.getString(if (status.phase == CoverPhase.LISTENING) R.string.cover_listening else R.string.widget_reading)

/** No book yet: Jacket's mark, and a word on what to do when there's room for it. */
@Composable
internal fun Empty(context: Context) {
    val size = LocalSize.current
    Box(base(context, null).padding(12.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(R.drawable.ic_brand_mark), contentDescription = context.getString(R.string.app_name),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(if (size.height < 100.dp) 24.dp else 36.dp, if (size.height < 100.dp) 31.dp else 46.dp),
            )
            if (size.width >= 140.dp && size.height >= 100.dp) {
                Text(
                    context.getString(R.string.widget_empty), maxLines = 2,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center),
                    modifier = GlanceModifier.padding(top = 8.dp),
                )
            }
        }
    }
}
