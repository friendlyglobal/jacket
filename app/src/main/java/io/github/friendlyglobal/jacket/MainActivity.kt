package io.github.friendlyglobal.jacket

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.friendlyglobal.jacket.outer.OuterCoverHost
import io.github.friendlyglobal.jacket.outer.OuterDisplay
import io.github.friendlyglobal.jacket.reader.ReaderScreen
import io.github.friendlyglobal.jacket.ui.home.HomeScreen
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<String?>(null)
    private val importing = MutableStateFlow(false)
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private lateinit var outer: OuterDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        outer = OuterDisplay(this) { OuterCoverHost() }
        if (savedInstanceState == null) handle(intent)
        setContent {
            JacketRoot(outer, openRequests, importing, messages, onImport = { importUris(it) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Widget taps open a book; "Open with" and "Share" hand us files to import. */
    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_OPEN_BOOK)?.let {
            openRequests.value = it
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> listOf(stream)
                    // Shared text (not a file) becomes a plain-text book.
                    !text.isNullOrBlank() -> listOf(sharedTextFile(text, intent.getStringExtra(Intent.EXTRA_SUBJECT)))
                    else -> emptyList()
                }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) importUris(uris, openAfter = uris.size == 1)
    }

    private fun sharedTextFile(text: String, subject: String?): Uri {
        val name = subject?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ")?.take(80)?.trim()
            ?: "Shared text"
        val file = File(File(cacheDir, "shared").apply { mkdirs() }, "$name.txt")
        file.writeText(text)
        return Uri.fromFile(file)
    }

    private fun importUris(uris: List<Uri>, openAfter: Boolean = false) {
        lifecycleScope.launch {
            importing.value = true
            val report = try {
                library.import(uris)
            } finally {
                importing.value = false
            }
            messages.tryEmit(report.message)
            if (openAfter) report.imported.firstOrNull()?.let { openRequests.value = it.id }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK = "io.github.friendlyglobal.jacket.OPEN_BOOK"
    }
}

@Composable
private fun JacketRoot(
    outer: OuterDisplay,
    openRequests: MutableStateFlow<String?>,
    importing: StateFlow<Boolean>,
    messages: SharedFlow<String>,
    onImport: (List<Uri>) -> Unit,
) {
    val library = LocalContext.current.library
    val state by library.state.collectAsStateWithLifecycle()
    val busy by importing.collectAsStateWithLifecycle()
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    val request by openRequests.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(request) {
        val id = request ?: return@LaunchedEffect
        if (library.state.value.find(id) != null) {
            library.setCurrent(id)
            openId = id
        }
        openRequests.value = null
    }
    LaunchedEffect(Unit) {
        messages.collect { if (it.isNotEmpty()) snackbar.showSnackbar(it) }
    }

    val openBook = state.find(openId)
    val reading = openBook != null
    // The outer display follows "is a book open", not a particular reader screen, so switching
    // books keeps it on. A manual toggle in the reader sticks until reading stops.
    LaunchedEffect(reading, outerAvailable, state.settings.autoOuterScreen) {
        if (!reading) outer.stop()
        else if (outerAvailable && state.settings.autoOuterScreen) outer.start()
    }

    JacketTheme(seed = (openBook ?: state.current)?.seedColor) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AnimatedContent(
                targetState = openBook?.id,
                transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f)) togetherWith fadeOut(tween(140)) },
                label = "screen",
            ) { id ->
                val book = state.find(id)
                if (book == null) {
                    HomeScreen(
                        state = state,
                        outerAvailable = outerAvailable,
                        importing = busy,
                        snackbar = snackbar,
                        onImport = onImport,
                        onOpen = { openId = it.id },
                        onSelect = { library.setCurrent(it.id) },
                        onDelete = { library.delete(it.id) },
                        onSettings = library::updateSettings,
                    )
                } else {
                    ReaderScreen(book, state.settings, outer, onBack = { openId = null })
                }
            }
        }
        BackHandler(enabled = openId != null) { openId = null }
    }
}
