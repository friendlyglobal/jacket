package io.github.friendlyglobal.jacket.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object CoverCache {
    // Keyed by file path + max height; sized in bytes.
    val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

@Composable
fun rememberCover(book: Book?, maxHeight: Int = 1400): ImageBitmap? {
    val context = LocalContext.current
    val file = book?.let { context.library.coverPathOf(it) }
    val key = file?.let { "${it.path}@$maxHeight" }
    val cover by produceState(key?.let { CoverCache.cache.get(it) }, key) {
        val source = file ?: return@produceState run { value = null }
        val cacheKey = "${source.path}@$maxHeight"
        value = CoverCache.cache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            Bitmaps.decodeFile(source, maxHeight)?.asImageBitmap()?.also { CoverCache.cache.put(cacheKey, it) }
        }
    }
    return cover
}

@Composable
fun CoverImage(book: Book?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, maxHeight: Int = 900) {
    val cover = rememberCover(book, maxHeight)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (cover != null) {
            Image(cover, contentDescription = book?.let { "Cover of ${it.title}" }, contentScale = contentScale, modifier = Modifier.matchParentSize())
        }
    }
}
