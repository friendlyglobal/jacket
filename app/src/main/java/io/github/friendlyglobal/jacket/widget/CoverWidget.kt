package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.theme.DEFAULT_SEED
import io.github.friendlyglobal.jacket.ui.theme.SeedScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val initial = WidgetModel(library.state.value)
        val initialCover = initial.book?.let { loadCover(library, it) }
        provideContent {
            // Subscribed for as long as the widget session lives, so picking another book or
            // reading on shows up without waiting for the session to restart.
            val model by remember { library.state.map(::WidgetModel).distinctUntilChanged() }.collectAsState(initial)
            val book = model.book
            val cover by produceState(initialCover, book?.coverFile) {
                value = book?.let { loadCover(library, it) }
            }
            val scheme = remember(book?.seedColor) { SeedScheme.dark(book?.seedColor ?: DEFAULT_SEED) }
            GlanceTheme(colors = ColorProviders(scheme)) {
                if (book == null) Empty(context) else when (model.settings.coverStyle) {
                    CoverStyle.JACKET -> Jacket(context, book, cover, model.settings)
                    CoverStyle.SHELF -> Shelf(context, book, cover, model.settings)
                    CoverStyle.GLANCE -> Glance(context, book, cover, model.settings)
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
private class WidgetModel(state: LibraryState) {
    val book: Book? = state.current
    val settings: Settings = state.settings
    private val key = listOf(
        book?.id, book?.title, book?.author, book?.coverFile, book?.seedColor,
        book?.percent, book?.statusLabel, settings.coverStyle, settings.showProgress,
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
private fun Cover(cover: Bitmap?, title: String, width: Dp, height: Dp) {
    if (cover != null) {
        Image(
            provider = ImageProvider(cover), contentDescription = "Cover of $title",
            modifier = GlanceModifier.size(width, height).cornerRadius(6.dp),
            contentScale = androidx.glance.layout.ContentScale.Fit,
        )
    }
}

@Composable
private fun Progress(book: Book, modifier: GlanceModifier = GlanceModifier.fillMaxWidth()) = LinearProgressIndicator(
    progress = book.progress, modifier = modifier.height(6.dp),
    color = GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.secondaryContainer,
)

/** The cover fills the whole widget (and the whole Flex Window), cropped like album art. */
@Composable
private fun Jacket(context: Context, book: Book, cover: Bitmap?, settings: Settings) {
    Box(base(context, book)) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover), contentDescription = "Cover of ${book.title}",
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = androidx.glance.layout.ContentScale.Crop,
            )
        } else {
            Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                Label("Reading")
                Spacer(GlanceModifier.height(6.dp))
                Text(book.title, maxLines = 3, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold))
            }
        }
        if (settings.showProgress) {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                Column(GlanceModifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Progress(book)
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        book.statusLabel,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Shelf(context: Context, book: Book, cover: Bitmap?, settings: Settings) {
    Column(base(context, book).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, book.title, 56.dp, 84.dp)
            Spacer(GlanceModifier.width(14.dp))
            Column {
                if (settings.showProgress) {
                    Text("${book.percent}%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold))
                } else {
                    Label("Reading")
                }
                Text(book.statusLabel, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        Text(book.title, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        if (book.author.isNotEmpty()) {
            Text(book.author, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
        if (settings.showProgress) {
            Spacer(GlanceModifier.defaultWeight())
            Progress(book)
        }
    }
}

@Composable
private fun Glance(context: Context, book: Book, cover: Bitmap?, settings: Settings) {
    val size = LocalSize.current
    val coverHeight = (size.height * 0.62f).coerceAtLeast(60.dp)
    Column(base(context, book).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Cover(cover, book.title, coverHeight * 0.66f, coverHeight)
        Spacer(GlanceModifier.height(8.dp))
        if (settings.showProgress) {
            Text("${book.percent}%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Progress(book, GlanceModifier.width(coverHeight * 0.9f))
        } else {
            Text(book.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun Empty(context: Context) {
    Box(base(context, null).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Jacket", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Text("Add a book to wear its cover", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

/** Debounced refresh of every placed widget after library changes. */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun request(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(1_000)
            runCatching { CoverWidget().updateAll(app) }
        }
    }
}
