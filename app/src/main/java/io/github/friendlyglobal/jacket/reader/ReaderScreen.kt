package io.github.friendlyglobal.jacket.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.outer.OuterDisplay
import io.github.friendlyglobal.jacket.ui.JacketIcons

/** Previous/next commands and a position label, published by whichever format view is showing. */
class ReaderNav {
    var canPrevious by mutableStateOf(false)
    var canNext by mutableStateOf(false)
    var label by mutableStateOf("")
    var onPrevious: () -> Unit = {}
    var onNext: () -> Unit = {}
}

typealias ProgressSink = (position: Int, offset: Float, progress: Float, sections: Int) -> Unit

@Composable
fun ReaderScreen(book: Book, settings: Settings, outer: OuterDisplay, onBack: () -> Unit) {
    val library = LocalContext.current.library
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    val outerActive by outer.active.collectAsStateWithLifecycle()
    val nav = remember(book.id) { ReaderNav() }

    LaunchedEffect(book.id) { library.markOpened(book.id) }

    val onProgress: ProgressSink = { position, offset, progress, sections ->
        library.updatePosition(book.id, position, offset, progress, sections)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (book.author.isNotEmpty()) {
                                Text(
                                    book.author, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, "Back to library") } },
                    actions = {
                        if (outerAvailable || outerActive) {
                            FilledTonalIconToggleButton(checked = outerActive, onCheckedChange = { outer.toggle() }) {
                                Icon(JacketIcons.OuterScreen, if (outerActive) "Hide cover on outer screen" else "Show cover on outer screen")
                            }
                        }
                    },
                )
                LinearWavyProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (book.format) {
                BookFormat.EPUB -> EpubReader(book, settings.textScale, nav, onProgress)
                BookFormat.PDF -> PdfReader(book, nav, onProgress)
                BookFormat.CBZ -> ComicReader(book, nav, onProgress)
                BookFormat.TXT -> TextReader(book, settings.textScale, nav, onProgress)
            }
            ReaderToolbar(
                nav = nav,
                textControls = book.format == BookFormat.EPUB || book.format == BookFormat.TXT,
                onTextScale = { delta -> library.updateSettings { it.copy(textScale = (it.textScale + delta).coerceIn(0.7f, 2f)) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ReaderToolbar(nav: ReaderNav, textControls: Boolean, onTextScale: (Float) -> Unit, modifier: Modifier) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
    ) {
        IconButton(onClick = { nav.onPrevious() }, enabled = nav.canPrevious) { Icon(JacketIcons.Previous, "Previous") }
        Text(
            nav.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        IconButton(onClick = { nav.onNext() }, enabled = nav.canNext) { Icon(JacketIcons.Next, "Next") }
        if (textControls) {
            IconButton(onClick = { onTextScale(-0.1f) }) { Icon(JacketIcons.TextSmaller, "Smaller text") }
            IconButton(onClick = { onTextScale(0.1f) }) { Icon(JacketIcons.TextLarger, "Larger text") }
        }
    }
}

@Composable
internal fun ReaderLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
}

@Composable
internal fun ReaderError(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
