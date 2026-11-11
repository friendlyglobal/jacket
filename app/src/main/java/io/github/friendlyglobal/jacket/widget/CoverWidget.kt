package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class CoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWidget()
}

/**
 * The book you're reading, for home screens and — through Samsung's sub_screen meta-data —
 * the Galaxy Z Flip cover screen. Always dark, tinted by the cover. It fits whatever room it's
 * given: from a 1×1 square of cover, through a strip with the title, to the style chosen in Settings.
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
        provideContent { AsPreview { Face(context, model, cover) } }
    }

    @Composable
    private fun Face(context: Context, model: WidgetModel, cover: Bitmap?) {
        val book = model.book
        val status = model.status
        WidgetTheme(book?.seedColor) {
            if (book == null || status == null) {
                Empty(context)
            } else {
                val scheme = LocalWidgetScheme.current
                // Finished and listening wear a badge, as on the outer screen.
                val badge = remember(status.phase, scheme) { stageBadge(context, status.phase, scheme) }
                val size = LocalSize.current
                when {
                    // One row high: a strip, the cover small beside the title.
                    size.height < 100.dp && size.width >= 160.dp -> Strip(context, book, cover, model.settings, status, badge)
                    // A square or so: the cover alone, with a line for the progress.
                    size.width < 110.dp || size.height < 100.dp -> CoverOnly(context, book, cover, model.settings, status, badge)
                    else -> when (model.settings.coverStyle) {
                        CoverStyle.JACKET -> Jacket(context, book, cover, model.settings, status, badge)
                        // The shelf needs room for its big number; smaller, it wears the jacket.
                        CoverStyle.SHELF -> if (size.width >= 180.dp && size.height >= 160.dp) {
                            Shelf(context, book, cover, model.settings, status, badge)
                        } else {
                            Jacket(context, book, cover, model.settings, status, badge)
                        }
                        CoverStyle.GLANCE -> Glance(context, book, cover, model.settings, status, badge)
                    }
                }
            }
        }
    }
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
        status?.phase, status?.chapter?.takeIf { status.phase == CoverPhase.LISTENING }, status?.marks?.size,
    )

    override fun equals(other: Any?) = other is WidgetModel && other.key == key
    override fun hashCode() = key.hashCode()
}

/** The cover fills the whole widget (and the whole Flex Window), cropped like album art. */
@Composable
private fun Jacket(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    val size = LocalSize.current
    Box(base(context, book)) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover), contentDescription = context.getString(R.string.cover_of, book.title),
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
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
                            WavyProgress(status, size.width - 28.dp - if (badge != null) 36.dp else 0.dp, onScrim = true)
                            Spacer(GlanceModifier.height(2.dp))
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

/** Too small for words: the cover alone, the progress a wavy line along its foot. */
@Composable
private fun CoverOnly(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    val size = LocalSize.current
    Box(base(context, book)) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover), contentDescription = context.getString(R.string.cover_of, book.title),
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(GlanceModifier.fillMaxSize().padding(10.dp)) {
                Text(book.title, maxLines = 4, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
        if (settings.showProgress) {
            Column(GlanceModifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalAlignment = Alignment.End) {
                if (badge != null) {
                    Badge(badge, 24.dp)
                } else {
                    Box(GlanceModifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                        WavyProgress(status, size.width - 28.dp, onScrim = true, description = book.statusLabel(context.resources))
                    }
                }
            }
        }
    }
}

/** A row high: the cover small, with the title and where you are beside it. */
@Composable
private fun Strip(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    val size = LocalSize.current
    val coverHeight = (size.height - 16.dp).coerceAtLeast(24.dp)
    Row(base(context, book).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(context, cover, book.title, coverHeight * 0.66f, coverHeight)
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(book.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Text(
                if (settings.showProgress) stageLine(context, book, status) else book.author, maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
            if (settings.showProgress && status.phase != CoverPhase.FINISHED) {
                Spacer(GlanceModifier.height(2.dp))
                WavyProgress(status, size.width - 36.dp - coverHeight * 0.66f - if (badge != null) 34.dp else 0.dp)
            }
        }
        if (badge != null) {
            Spacer(GlanceModifier.width(10.dp))
            Badge(badge, 24.dp)
        }
    }
}

@Composable
private fun Shelf(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    val size = LocalSize.current
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
                            style = TextStyle(
                                color = if (status.phase == CoverPhase.ALMOST_DONE) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary,
                                fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            ),
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
            WavyProgress(status, size.width - 32.dp)
        }
    }
}

@Composable
private fun Glance(context: Context, book: Book, cover: Bitmap?, settings: Settings, status: CoverStatus, badge: Bitmap?) {
    val size = LocalSize.current
    // The cover takes what the words under it leave.
    val below = if (!settings.showProgress) 22.dp else if (status.phase == CoverPhase.FINISHED) 30.dp else 52.dp
    val coverHeight = (size.height - 24.dp - 8.dp - below).coerceIn(40.dp, (size.width - 24.dp) * 1.5f)
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
                    style = TextStyle(
                        color = if (status.phase == CoverPhase.ALMOST_DONE) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    ),
                )
            }
            val lineWidth = (coverHeight * 0.9f).coerceAtMost(size.width - 24.dp)
            WavyProgress(status, lineWidth, GlanceModifier.width(lineWidth))
        }
    }
}

/** Debounced refresh of every placed widget, the widget picker's previews and the launcher shortcuts, after library changes. */
object WidgetSync {
    private const val PREVIEWED = "previewed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Each of Jacket's widgets, as the receiver that offers it and the widget it draws. */
    private val widgets: List<Pair<KClass<out GlanceAppWidgetReceiver>, () -> GlanceAppWidget>> = listOf(
        CoverWidgetReceiver::class to ::CoverWidget,
        PlayerWidgetReceiver::class to ::PlayerWidget,
        ShelfWidgetReceiver::class to ::ShelfWidget,
        StreakWidgetReceiver::class to ::StreakWidget,
    )

    fun request(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(1_000)
            for ((_, widget) in widgets) runCatching { widget().updateAll(app) }
            runCatching { AppShortcuts.sync(app) }
            // The picker's previews follow the book and the style, not every page turned: Android
            // only lets an app set them a few times an hour.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val state = app.library.state.value
                val shown = listOf(state.current?.id, state.current?.coverFile, state.settings.coverStyle, state.settings.showProgress).toString()
                // Remembered across restarts, or every launch would spend one of those few.
                val prefs = app.getSharedPreferences("widget", Context.MODE_PRIVATE)
                for ((receiver, _) in widgets) {
                    val key = if (receiver == CoverWidgetReceiver::class) PREVIEWED else "$PREVIEWED-${receiver.simpleName}"
                    if (shown == prefs.getString(key, null)) continue
                    runCatching { GlanceAppWidgetManager(app).setWidgetPreviews(receiver) }
                        .onSuccess { if (it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit { putString(key, shown) } }
                }
            }
        }
    }
}
