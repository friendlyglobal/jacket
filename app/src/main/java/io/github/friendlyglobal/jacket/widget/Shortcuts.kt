package io.github.friendlyglobal.jacket.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import io.github.friendlyglobal.jacket.MainActivity
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a long press on Jacket's icon offers: the book you're reading and the one before it, each
 * under its own cover, and "Listen" to carry on with the current one read aloud.
 */
object AppShortcuts {
    private var shown: Any? = null

    /** Brings the shortcuts up to date with the library; does nothing while they already are. */
    suspend fun sync(app: Context) = withContext(Dispatchers.IO) {
        val library = app.library
        val state = library.state.value
        val absent = library.missing.value
        val recent = state.books.filter { !it.isFinished && it.id !in absent }.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        val current = state.current?.takeIf { book -> recent.any { it.id == book.id } } ?: recent.firstOrNull()
        val books = listOfNotNull(current) + recent.filter { it.id != current?.id }.take(1)
        val wanted = books.map { it.id to it.title }
        if (wanted == shown) return@withContext
        val shortcuts = buildList {
            books.forEachIndexed { rank, book ->
                add(
                    ShortcutInfoCompat.Builder(app, "book-${book.id}")
                        .setShortLabel(book.title.ifBlank { app.getString(R.string.book_untitled) })
                        .setIcon(icon(app, book))
                        .setIntent(open(app, book, listen = false))
                        .setRank(rank)
                        .build(),
                )
            }
            // Formats that can be read aloud.
            current?.takeIf { it.canListen && it.id !in library.wordless.value }?.let { book ->
                add(
                    ShortcutInfoCompat.Builder(app, "listen")
                        .setShortLabel(app.getString(R.string.reader_listen))
                        .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                        .setIntent(open(app, book, listen = true))
                        .setRank(books.size)
                        .build(),
                )
            }
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts) }.onSuccess { shown = wanted }
    }

    private fun open(app: Context, book: Book, listen: Boolean) = Intent(app, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        // A distinct data URI per shortcut keeps their intents apart.
        data = "jacket://${if (listen) "listen" else "book"}/${book.id}".toUri()
        putExtra(MainActivity.EXTRA_OPEN_BOOK, book.id)
        putExtra(MainActivity.EXTRA_LISTEN, listen)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    /** The middle of the cover, as a square. */
    private fun icon(app: Context, book: Book): IconCompat {
        val cover = app.library.coverOf(book)?.let { Bitmaps.decodeFile(it, 288) }
            ?: return IconCompat.createWithResource(app, R.mipmap.ic_launcher)
        val side = minOf(cover.width, cover.height)
        val square = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        return IconCompat.createWithBitmap(square)
    }
}
