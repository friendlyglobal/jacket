package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class ShelfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShelfWidget()
}

/**
 * The books you're reading, side by side, as many as fit: the current one first, then the rest
 * by when you last had them open. Each opens with a tap, and wears its progress under it.
 */
class ShelfWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library = context.library
        val initial = ShelfModel(library.state.value, library.missing.value, library.moment.value)
        val initialCovers = covers(context, initial.books)
        provideContent {
            val model by remember {
                combine(library.state, library.missing, library.moment) { state, missing, moment -> ShelfModel(state, missing, moment) }
                    .distinctUntilChanged()
            }.collectAsState(initial)
            val covers by produceState(initialCovers, model.books.map { it.id to it.coverFile }) {
                value = covers(context, model.books)
            }
            Shelf(context, model, covers)
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val library = context.library
        val model = ShelfModel(library.state.value, library.missing.value, null)
        val covers = covers(context, model.books)
        provideContent { AsPreview { Shelf(context, model, covers) } }
    }

    private suspend fun covers(context: Context, books: List<Book>): Map<String, Bitmap> =
        books.mapNotNull { book -> loadCover(context.library, book, COVER_PX)?.let { book.id to it } }.toMap()

    private companion object {
        /** Small covers: up to eight of them share the widget's bitmap budget. */
        const val COVER_PX = 360
    }
}

private class ShelfModel(state: LibraryState, missing: Set<String>, moment: ReadingMoment?) {
    val books: List<Book> = readingNow(state, missing).take(MAX_BOOKS)
    val statuses: Map<String, CoverStatus> = books.associate { it.id to CoverStatus.of(it, moment) }
    val seed: Int? = books.firstOrNull()?.seedColor
    val showProgress: Boolean = state.settings.showProgress
    private val key = listOf(books.map { listOf(it.id, it.title, it.coverFile, it.percent) }, statuses.values.map { it.phase }, seed, showProgress)

    override fun equals(other: Any?) = other is ShelfModel && other.key == key
    override fun hashCode() = key.hashCode()

    companion object {
        const val MAX_BOOKS = 8
    }
}

@Composable
private fun Shelf(context: Context, model: ShelfModel, covers: Map<String, Bitmap>) {
    WidgetTheme(model.seed) {
        if (model.books.isEmpty()) {
            Empty(context)
            return@WidgetTheme
        }
        val size = LocalSize.current
        val padding = 12.dp
        val gap = 10.dp
        // Room under each cover for its line, and for its title once the widget is tall enough.
        val titled = size.height >= 170.dp
        val under = (if (model.showProgress) WidgetArt.LINE_HEIGHT + 4.dp else 0.dp) + (if (titled) 32.dp else 0.dp)
        val coverHeight = (size.height - padding * 2 - under).coerceAtLeast(36.dp)
        val coverWidth = (coverHeight * 0.66f).coerceAtMost(size.width - padding * 2)
        val fits = ((size.width - padding * 2 + gap) / (coverWidth + gap)).toInt().coerceIn(1, ShelfModel.MAX_BOOKS)
        val books = model.books.take(fits)
        Row(base(context, books.first()).padding(padding), verticalAlignment = Alignment.Top) {
            books.forEachIndexed { index, book ->
                if (index > 0) Spacer(GlanceModifier.width(gap))
                Column(
                    GlanceModifier.width(coverWidth).clickable(actionStartActivity(openIntent(context, book))),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Cover(context, covers[book.id], book.title, coverWidth, coverHeight)
                    val status = model.statuses.getValue(book.id)
                    if (model.showProgress) {
                        Spacer(GlanceModifier.height(4.dp))
                        WavyProgress(status, coverWidth, GlanceModifier.width(coverWidth), description = book.statusLabel(context.resources))
                    }
                    if (titled) {
                        Text(
                            book.title, maxLines = 2,
                            style = TextStyle(
                                color = if (index == 0) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 11.sp, fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            ),
                            modifier = GlanceModifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
