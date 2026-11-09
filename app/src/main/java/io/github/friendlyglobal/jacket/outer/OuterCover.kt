package io.github.friendlyglobal.jacket.outer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.rememberCover
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme
import kotlinx.coroutines.delay

/** Root of the outer-display window: always dark (OLED-friendly), tinted by the book. */
@Composable
fun OuterCoverHost() {
    val library = LocalContext.current.library
    val state by library.state.collectAsStateWithLifecycle()
    val book = state.current
    JacketTheme(seed = book?.seedColor, darkTheme = true) {
        OuterCover(book, state.settings)
    }
}

/**
 * The cover, edge to edge, like album art on a lock screen. Covers are taller than they are
 * wide relative to most outer displays, so the sides are cropped rather than letterboxed.
 */
@Composable
fun OuterCover(book: Book?, settings: Settings) {
    var wakeCount by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(wakeCount, settings.ambientDim, book?.id) {
        dimmed = false
        if (settings.ambientDim) {
            delay(30_000)
            dimmed = true
        }
    }
    val brightness by animateFloatAsState(if (dimmed) 0.32f else 1f, tween(1400), label = "ambient")
    val cover = rememberCover(book)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { wakeCount++ } }
            .graphicsLayer { alpha = brightness },
    ) {
        if (book == null) {
            Text(
                "Jacket", style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        Crossfade(targetState = cover, animationSpec = tween(500), label = "cover") { image ->
            if (image != null) {
                Image(
                    image, "Cover of ${book.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Until the image is decoded: the book's own color, not a black flash.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
            }
        }

        if (settings.showProgress) {
            // A slim strip over a soft fade, so the cover still owns the whole screen.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 28.dp),
            ) {
                LinearWavyProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "${book.positionLabel} · ${book.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}
