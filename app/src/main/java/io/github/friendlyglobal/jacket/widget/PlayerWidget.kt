package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

/**
 * The book you're reading with a big play button: from the home screen, start it reading aloud,
 * or pause and resume the voice without opening Jacket. Books that can't be read aloud (PDFs,
 * comics) get an open button instead. One row high it's a bar; taller, the cover grows with it.
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library = context.library
        val initial = PlayerModel(library.state.value, library.moment.value, context.resources)
        val initialCover = initial.book?.let { loadCover(library, it, 400) }
        provideContent {
            val model by remember {
                combine(library.state, library.moment) { state, moment -> PlayerModel(state, moment, context.resources) }
                    .distinctUntilChanged()
            }.collectAsState(initial)
            val cover by produceState(initialCover, model.book?.coverFile) {
                value = model.book?.let { loadCover(library, it, 400) }
            }
            Player(context, model, cover)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val library = context.library
        val model = PlayerModel(library.state.value, null, context.resources)
        val cover = model.book?.let { loadCover(library, it, 400) }
        provideContent { AsPreview { Player(context, model, cover) } }
    }
}

private class PlayerModel(state: LibraryState, moment: ReadingMoment?, resources: Resources) {
    val book: Book? = state.current
    val status: CoverStatus? = book?.let { CoverStatus.of(it, moment) }
    /** Read-aloud is on for this book: the button plays and pauses it in place. */
    val listening: Boolean = status?.phase == CoverPhase.LISTENING
    val playing: Boolean = listening && status?.playing == true
    private val key = listOf(
        book?.id, book?.title, book?.author, book?.coverFile, book?.seedColor, book?.percent, book?.statusLabel(resources),
        status?.phase, status?.chapter, playing, status?.marks?.size,
    )

    override fun equals(other: Any?) = other is PlayerModel && other.key == key
    override fun hashCode() = key.hashCode()
}

@Composable
private fun Player(context: Context, model: PlayerModel, cover: Bitmap?) {
    val book = model.book
    val status = model.status
    WidgetTheme(book?.seedColor) {
        if (book == null || status == null) {
            Empty(context)
            return@WidgetTheme
        }
        val size = LocalSize.current
        val tall = size.height >= 110.dp
        val button = if (tall) 64.dp else 48.dp
        val coverHeight = (size.height - 24.dp).coerceIn(32.dp, 150.dp)
        val showCover = size.width >= 200.dp
        Row(base(context, book).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showCover) {
                Cover(context, cover, book.title, coverHeight * 0.66f, coverHeight)
                Spacer(GlanceModifier.width(12.dp))
            }
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    book.title, maxLines = if (tall) 2 else 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (tall) 16.sp else 14.sp, fontWeight = FontWeight.Bold),
                )
                if (tall && book.author.isNotEmpty()) {
                    Text(book.author, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                }
                Text(
                    stageLine(context, book, status), maxLines = 1,
                    style = TextStyle(
                        color = if (model.listening) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (model.listening) FontWeight.Medium else FontWeight.Normal,
                    ),
                )
                if (status.phase != CoverPhase.FINISHED) {
                    Spacer(GlanceModifier.height(if (tall) 8.dp else 4.dp))
                    val used = 24.dp + 12.dp + button + if (showCover) coverHeight * 0.66f + 12.dp else 0.dp
                    WavyProgress(status, size.width - used)
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            PlayButton(context, book, model, button)
        }
    }
}

/**
 * Play while the book isn't being read aloud opens it and starts the voice; once it is, play and
 * pause go straight to the read-aloud session, as a headphone button would. Starting the voice
 * again needs the service in the foreground; pausing it doesn't.
 */
@Composable
private fun PlayButton(context: Context, book: Book, model: PlayerModel, size: Dp) {
    val (icon, label, action) = when {
        !book.canListen -> Triple(R.drawable.ic_widget_open, R.string.library_menu_open, actionStartActivity(openIntent(context, book)))
        model.playing -> Triple(
            R.drawable.ic_widget_pause, R.string.listen_pause,
            actionStartService(mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PAUSE), isForegroundService = false),
        )
        model.listening -> Triple(
            R.drawable.ic_widget_play, R.string.listen_play,
            actionStartService(mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY), isForegroundService = true),
        )
        else -> Triple(R.drawable.ic_widget_play, R.string.reader_listen, actionStartActivity(listenIntent(context, book)))
    }
    SquareIconButton(
        imageProvider = ImageProvider(icon),
        contentDescription = context.getString(label),
        onClick = action,
        modifier = GlanceModifier.size(size),
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary,
    )
}
