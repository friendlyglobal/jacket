package io.github.friendlyglobal.jacket.outer

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        if (cover != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                cover, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(56.dp).alpha(0.5f),
            )
        }
        if (book == null) {
            Text(
                "Jacket", style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val ratio = cover?.let { it.width / it.height.toFloat() } ?: (2f / 3f)
            val coverShape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp)
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(0.8f)
                    .aspectRatio(ratio)
                    .shadow(28.dp, coverShape)
                    .clip(coverShape),
            ) {
                if (cover != null) Image(cover, "Cover of ${book.title}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Light catching the spine, like a printed dust jacket.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = .28f), .03f to Color.White.copy(alpha = .10f), .08f to Color.Transparent,
                        ),
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(8.dp))
                Text(
                    "READING NOW", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            if (settings.showProgress) {
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth(0.8f))
                Spacer(Modifier.height(8.dp))
                Text(
                    "${book.positionLabel} · ${book.percent}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    book.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
