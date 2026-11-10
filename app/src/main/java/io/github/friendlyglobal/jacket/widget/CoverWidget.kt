package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.friendlyglobal.jacket.MainActivity
import io.github.friendlyglobal.jacket.R
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.theme.DEFAULT_SEED
import io.github.friendlyglobal.jacket.ui.theme.SeedScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class CoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWidget()
}

/**
 * The book you're reading, for home screens and — through Samsung's sub_screen meta-data —
 * the Galaxy Z Flip cover screen. Always dark, tinted by the cover.
 */
class CoverWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library = context.library
        val initial = WidgetModel(library.state.value, library.moment.value, context.resources)
        val initialCover = initial.book?.let { loadCover(library, it) }
        provideContent {
            // Subscribed for as long as the widget session lives, so picking another book, reading
            // on or starting to listen shows up without waiting for the session to restart.
            val model by remember {
                combine(library.state, library.moment) { state, moment -> WidgetModel(state, moment, context.resources) }
                    .distinctUntilChanged()
            }.collectAsState(initial)
            val cover by produceState(initialCover, model.book?.coverFile) {
                value = model.book?.let { loadCover(library, it) }
            }
            Face(context, model, cover)
        }
    }

    /**
     * What the widget picker shows (Android 15 and later): the widget as it would be placed, with
     * your book on it. Before that, the picker falls back on the static preview in the manifest.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val library = context.library
        val model = WidgetModel(library.state.value, null, context.resources)
        val cover = model.book?.let { loadCover(library, it) }
        provideContent { Face(context, model, cover) }
    }

    @Composable
    private fun Face(context: Context, model: WidgetModel, cover: Bitmap?) {
        val book = model.book
        val status = model.status
        val scheme = remember(book?.seedColor) { SeedScheme.dark(book?.seedColor ?: DEFAULT_SEED) }
        GlanceTheme(colors = ColorProviders(scheme)) {
            if (book == null || status == null) {
                Empty(context)
            } else {
                // Finished and listening wear a badge, as on the outer screen.
                val badge = remember(status.phase, scheme) { stageBadge(context, status.phase, scheme) }
                when (model.settings.coverStyle) {
                    CoverStyle.JACKET -> Jacket(context, book, cover, model.settings, status, badge)
                    CoverStyle.SHELF -> Shelf(context, book, cover, model.settings, status, badge)
                    CoverStyle.GLANCE -> Glance(context, book, cover, model.settings, status, badge)
                }
            }
        }
    }

    // RemoteViews have a bitmap budget, so keep the cover modest.
    private suspend fun loadCover(library: Library, book: Book): Bitmap? =
        withContext(Dispatchers.IO) { library.coverOf(book)?.let { Bitmaps.decodeFile(it, 600) } }
}

/**
 * What the widget shows. Equality covers only visible fields, so scrolling within a page
 * doesn't push a new widget layout; a new whole percent or chapter does.
 */
private class WidgetModel(state: LibraryState, moment: ReadingMoment?, resources: Resources) {
    val book: Book? = state.current
    val settings: Settings = state.settings
    /** The stage the book is at, as the outer screen tells it (see [CoverStatus]). */
    val status: CoverStatus? = book?.let { CoverStatus.of(it, moment) }
    private val key = listOf(
        book?.id, book?.title, book?.author, book?.coverFile, book?.seedColor,
        book?.percent, book?.statusLabel(resources), settings.coverStyle, settings.showProgress,
        status?.phase, status?.chapter?.takeIf { status.phase == CoverPhase.LISTENING },
    )

    override fun equals(other: Any?) = other is WidgetModel && other.key == key
    override fun hashCode() = key.hashCode()
}

private fun openIntent(context: Context, book: Book?) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_MAIN
    // A distinct data URI per book keeps the PendingIntents from being merged.
    data = "jacket://book/${book?.id ?: "library"}".toUri()
    book?.let { putExtra(MainActivity.EXTRA_OPEN_BOOK, it.id) }
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
}

@Composable
private fun base(context: Context, book: Book?) = GlanceModifier
    .fillMaxSize()
    .cornerRadius(24.dp)
    .background(GlanceTheme.colors.surface)
    .clickable(actionStartActivity(openIntent(context, book)))

@Composable
private fun Label(text: String) = Text(
    text.uppercase(),
    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold),
)

@Composable
private fun Cover(context: Context, cover: Bitmap?, title: String, width: Dp, height: Dp) {
    if (cover != null) {
        Image(
            provider = ImageProvider(cover), contentDescription = context.getString(R.string.cover_of, title),
            modifier = GlanceModifier.size(width, height).cornerRadius(6.dp),
            contentScale = androidx.glance.layout.ContentScale.Fit,
        )
    }
}

/** The book's progress; the last stretch wears the accent color, as on the outer screen. */
@Composable
private fun Progress(status: CoverStatus, modifier: GlanceModifier = GlanceModifier.fillMaxWidth()) = LinearProgressIndicator(
    progress = status.progress, modifier = modifier.height(6.dp),
    color = accent(status), backgroundColor = GlanceTheme.colors.secondaryContainer,
)

@Composable
private fun accent(status: CoverStatus) =
    if (status.phase == CoverPhase.ALMOST_DONE) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary

/**
 * The stage in a few words: "Almost there · 93%", the chapter being read aloud (or "Listening")
 * with the percentage, otherwise the book's own status ("42% read", "Finished").
 */
private fun stageLine(context: Context, book: Book, status: CoverStatus): String {
    val percent = context.getString(R.string.percent, status.percent)
    return when (status.phase) {
        CoverPhase.ALMOST_DONE -> context.getString(R.string.cover_almost_done) + " · " + percent
        CoverPhase.LISTENING -> status.chapter.ifEmpty { context.getString(R.string.cover_listening) } + " · " + percent
        else -> book.statusLabel(context.resources)
    }
}

/** The label over a book with no progress showing: what you're doing with it. */
private fun stageLabel(context: Context, status: CoverStatus): String =
    context.getString(if (status.phase == CoverPhase.LISTENING) R.string.cover_listening else R.string.widget_reading)

/**
 * The badge a stage wears: a check in a burst once finished, headphones in a cookie while
 * listening; none otherwise. Drawn onto a bitmap, since Glance has neither shapes nor vector icons.
 */
private fun stageBadge(context: Context, phase: CoverPhase, scheme: ColorScheme): Bitmap? = when (phase) {
    CoverPhase.FINISHED -> badge(context, MaterialShapes.SoftBurst, scheme.tertiary.toArgb(), JacketIcons.CHECK_PATH, scheme.onTertiary.toArgb())
    CoverPhase.LISTENING -> badge(context, MaterialShapes.Cookie9Sided, scheme.primary.toArgb(), JacketIcons.HEADPHONES_PATH, scheme.onPrimary.toArgb())
    else -> null
}

private fun badge(context: Context, shape: RoundedPolygon, color: Int, iconPath: String, iconColor: Int): Bitmap {
    val size = (BADGE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val matrix = Matrix()
    // Material shapes come one unit square in size.
    matrix.setScale(size.toFloat(), size.toFloat())
    canvas.drawPath(shape.toPath().apply { transform(matrix) }, paint.apply { this.color = color })
    // The icon's 24-unit box, a little over half the badge, in its middle.
    val iconScale = size * 0.56f / 24f
    val inset = (size - 24f * iconScale) / 2f
    matrix.setScale(iconScale, iconScale)
    matrix.postTranslate(inset, inset)
    canvas.drawPath(PathParser.createPathFromPathData(iconPath).apply { transform(matrix) }, paint.apply { this.color = iconColor })
    return bitmap
}

/** Badges are drawn once at this size and shown at whatever size the style wants. */
private const val BADGE_DP = 48

@Composable
private fun Badge(badge: Bitmap, size: Dp) =
    Image(provider = ImageProvider(badge), contentDescription = null, modifier = GlanceModifier.size(size))

/** The cover fills the whole widget (and the whole Flex Window), cropped like album art. */
@Composable
private fun Jacket(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    Box(base(context, book)) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover), contentDescription = context.getString(R.string.cover_of, book.title),
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = androidx.glance.layout.ContentScale.Crop,
            )
        } else {
            Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                Label(stageLabel(context, status))
                Spacer(GlanceModifier.height(6.dp))
                Text(book.title, maxLines = 3, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold))
            }
        }
        if (settings.showProgress) {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                Row(
                    GlanceModifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (badge != null) {
                        Badge(badge, 26.dp)
                        Spacer(GlanceModifier.width(10.dp))
                    }
                    Column(GlanceModifier.defaultWeight()) {
                        // A finished book has no line left to fill: the check says it.
                        if (status.phase != CoverPhase.FINISHED) {
                            Progress(status)
                            Spacer(GlanceModifier.height(4.dp))
                        }
                        Text(
                            stageLine(context, book, status), maxLines = 1,
                            style = TextStyle(
                                color = ColorProvider(Color.White), fontSize = if (status.phase == CoverPhase.FINISHED) 14.sp else 11.sp,
                                fontWeight = if (status.phase == CoverPhase.FINISHED) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    Column(base(context, book).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(context, cover, book.title, 56.dp, 84.dp)
            Spacer(GlanceModifier.width(14.dp))
            Column {
                if (!settings.showProgress) {
                    Label(stageLabel(context, status))
                } else if (status.phase == CoverPhase.FINISHED && badge != null) {
                    // In place of the percentage: it would only say 100.
                    Badge(badge, 44.dp)
                    Spacer(GlanceModifier.height(4.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            context.getString(R.string.percent, book.percent),
                            style = TextStyle(color = accent(status), fontSize = 40.sp, fontWeight = FontWeight.Bold),
                        )
                        if (badge != null) {
                            Spacer(GlanceModifier.width(8.dp))
                            Badge(badge, 28.dp)
                        }
                    }
                }
                Text(
                    when (status.phase) {
                        CoverPhase.ALMOST_DONE -> context.getString(R.string.cover_almost_done)
                        CoverPhase.LISTENING -> status.chapter.ifEmpty { context.getString(R.string.cover_listening) }
                        else -> book.statusLabel(context.resources)
                    },
                    maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        Text(book.title, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        if (book.author.isNotEmpty()) {
            Text(book.author, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
        if (settings.showProgress && status.phase != CoverPhase.FINISHED) {
            Spacer(GlanceModifier.defaultWeight())
            Progress(status)
        }
    }
}

@Composable
private fun Glance(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    val size = LocalSize.current
    val coverHeight = (size.height * 0.62f).coerceAtLeast(60.dp)
    Column(base(context, book).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Cover(context, cover, book.title, coverHeight * 0.66f, coverHeight)
        Spacer(GlanceModifier.height(8.dp))
        if (!settings.showProgress) {
            Text(book.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        } else if (status.phase == CoverPhase.FINISHED && badge != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(badge, 24.dp)
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    book.statusLabel(context.resources), maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    Badge(badge, 22.dp)
                    Spacer(GlanceModifier.width(6.dp))
                }
                Text(
                    context.getString(R.string.percent, book.percent),
                    style = TextStyle(color = accent(status), fontSize = 26.sp, fontWeight = FontWeight.Bold),
                )
            }
            Progress(status, GlanceModifier.width(coverHeight * 0.9f))
        }
    }
}

@Composable
private fun Empty(context: Context) {
    Box(base(context, null).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(context.getString(R.string.app_name), style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Text(context.getString(R.string.widget_empty), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

/** Debounced refresh of every placed widget, the widget picker's preview and the launcher shortcuts, after library changes. */
object WidgetSync {
    private const val PREVIEWED = "previewed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun request(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(1_000)
            runCatching { CoverWidget().updateAll(app) }
            runCatching { AppShortcuts.sync(app) }
            // The picker's preview follows the book and the style, not every page turned: Android
            // only lets an app set it a few times an hour.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val state = app.library.state.value
                val shown = listOf(state.current?.id, state.current?.coverFile, state.settings.coverStyle, state.settings.showProgress).toString()
                // Remembered across restarts, or every launch would spend one of those few.
                val prefs = app.getSharedPreferences("widget", Context.MODE_PRIVATE)
                if (shown != prefs.getString(PREVIEWED, null)) {
                    runCatching { GlanceAppWidgetManager(app).setWidgetPreviews(CoverWidgetReceiver::class) }
                        .onSuccess { if (it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit { putString(PREVIEWED, shown) } }
                }
            }
        }
    }
}
