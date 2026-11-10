package io.github.friendlyglobal.jacket

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.zIndex
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.friendlyglobal.jacket.data.ImportReport
import io.github.friendlyglobal.jacket.data.LibrarySort
import io.github.friendlyglobal.jacket.outer.OuterCoverHost
import io.github.friendlyglobal.jacket.outer.OuterDisplay
import io.github.friendlyglobal.jacket.reader.ReaderFragments
import io.github.friendlyglobal.jacket.reader.ReaderKeys
import io.github.friendlyglobal.jacket.reader.ReaderScreen
import io.github.friendlyglobal.jacket.ui.CoverSpot
import io.github.friendlyglobal.jacket.ui.CoverTransition
import io.github.friendlyglobal.jacket.ui.KeepScreenOn
import io.github.friendlyglobal.jacket.ui.LocalCoverSpot
import io.github.friendlyglobal.jacket.ui.LocalCoverTransition
import io.github.friendlyglobal.jacket.ui.home.HomeScreen
import io.github.friendlyglobal.jacket.ui.home.heroBook
import io.github.friendlyglobal.jacket.ui.home.onFinishedShelf
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.settings.LicensesScreen
import io.github.friendlyglobal.jacket.ui.settings.SettingsScreen
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

// AppCompat because Readium's reader is a Fragment that needs an AppCompat activity and theme.
class MainActivity : AppCompatActivity() {
    private val openRequests = MutableStateFlow<String?>(null)
    private val importing = MutableStateFlow(false)
    private val reports = MutableSharedFlow<ImportReport>(extraBufferCapacity = 8)
    private lateinit var outer: OuterDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Must be in place before super.onCreate, which restores fragments.
        supportFragmentManager.fragmentFactory = ReaderFragments
        super.onCreate(savedInstanceState)
        ReaderFragments.removeRestoredReaders(supportFragmentManager)
        outer = OuterDisplay(this) { OuterCoverHost() }
        if (savedInstanceState == null) handle(intent)
        setContent {
            JacketRoot(outer, openRequests, importing, reports, onImport = { importUris(it) })
        }
    }

    /** Volume keys turn pages while reading, when that's switched on (see [ReaderKeys]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            else -> 0
        }
        val handler = ReaderKeys.handler
        if (delta != 0 && handler != null) {
            // Both the press and the release are ours, so the volume panel never appears.
            if (event.action == KeyEvent.ACTION_DOWN) handler(delta)
            return true
        }
        return super.dispatchKeyEvent(event)
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
            reports.tryEmit(report)
            if (openAfter) report.imported.firstOrNull()?.let { openRequests.value = it.id }
        }
    }

    companion object {
        const val EXTRA_OPEN_BOOK = "io.github.friendlyglobal.jacket.OPEN_BOOK"
    }
}

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val LICENSES = "licenses"
private const val READ = "read:"

@Composable
private fun JacketRoot(
    outer: OuterDisplay,
    openRequests: MutableStateFlow<String?>,
    importing: StateFlow<Boolean>,
    reports: SharedFlow<ImportReport>,
    onImport: (List<Uri>) -> Unit,
) {
    val library = LocalContext.current.library
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    val state by library.state.collectAsStateWithLifecycle()
    val missing by library.missing.collectAsStateWithLifecycle()
    val busy by importing.collectAsStateWithLifecycle()
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    val request by openRequests.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(HOME) }
    val snackbar = remember { SnackbarHostState() }
    // Kept here so closing a book can bring the place its cover lands into view.
    val libraryGrid = rememberLazyGridState()

    val openBook = state.find(destination.takeIf { it.startsWith(READ) }?.removePrefix(READ))
    // Where the open book's cover took off from in the library, then where it will land.
    var coverSpot by rememberSaveable { mutableStateOf(CoverSpot.HERO) }
    // The reader marks the book as opened once its cover has landed; doing it here would
    // re-render the library mid-flight and move the cover's starting point.
    val open = { id: String, spot: CoverSpot? ->
        haptics.tick()
        // Not opened from a cover (a widget, the next book): its hero card if it has one.
        coverSpot = spot ?: if (state.heroBook(missing)?.id == id) CoverSpot.HERO else CoverSpot.TILE
        destination = READ + id
    }

    LaunchedEffect(request) {
        val id = request ?: return@LaunchedEffect
        if (library.state.value.find(id) != null) open(id, null)
        openRequests.value = null
    }
    LaunchedEffect(Unit) {
        reports.collect { report ->
            if (report.failed.isEmpty()) haptics.confirm() else haptics.reject()
            val message = report.message(resources)
            if (message.isNotEmpty()) snackbar.showSnackbar(message)
        }
    }
    // A removed book can't stay open.
    LaunchedEffect(destination, openBook) {
        if (destination.startsWith(READ) && openBook == null) destination = HOME
    }

    // The outer display follows "is a book open", not a particular reader screen, so switching
    // books keeps it on. A manual toggle in the reader sticks until reading stops.
    val reading = openBook != null
    LaunchedEffect(reading, outerAvailable, state.settings.autoOuterScreen) {
        if (!reading) outer.stop()
        else if (outerAvailable && state.settings.autoOuterScreen) outer.start()
    }
    // Here rather than in the reader: switching books composes two readers for a moment, and the
    // outgoing one must not turn the screen timeout back on.
    if (reading) KeepScreenOn()

    // Colors: the library wears the last book you read; the system palette if there is none or
    // "Colors from your book" is off. Browsing never re-tints the app. A reader brings its own
    // book's colors with it instead of re-tinting everything as it opens: the theme's colors
    // can't change without recomposing the whole screen, frame after frame, under the flying
    // cover. The library takes up the new book's colors while it is out of sight.
    val bookColors = state.settings.bookColors
    val librarySeed = if (bookColors) state.current?.takeIf { !it.isNew }?.seedColor else null

    JacketTheme(seed = librarySeed, animated = openBook == null) {
        val motion = MaterialTheme.motionScheme
        Surface(color = MaterialTheme.colorScheme.background) {
            SharedTransitionLayout {
                val screens = updateTransition(destination, label = "screens")
                screens.AnimatedContent(
                    transitionSpec = {
                        val spatial = tween<androidx.compose.ui.unit.IntOffset>(380)
                        val fromBook = initialState.startsWith(READ)
                        val toBook = targetState.startsWith(READ)
                        when {
                            targetState == LICENSES || (targetState == SETTINGS && initialState != LICENSES) ->
                                (slideInHorizontally(spatial) { it / 3 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(spatial) { -it / 10 } + fadeOut(tween(160)))
                            initialState == LICENSES || initialState == SETTINGS ->
                                (slideInHorizontally(spatial) { -it / 10 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(spatial) { it / 3 } + fadeOut(tween(160)))
                            // Book to book: a crossfade, and the next book opens its own cover.
                            fromBook && toBook -> fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(200))
                            // Into a book: the library recedes behind the flying cover, dimmed by the
                            // reader's backdrop coming up over it on the same spring, so the two
                            // read as one movement. It stays underneath until the cover has landed:
                            // nothing shows through the fade.
                            toBook -> fadeIn(motion.slowSpatialSpec()) togetherWith
                                (scaleOut(motion.slowSpatialSpec(), targetScale = RECEDED) + ExitTransition.KeepUntilTransitionsFinished)
                            // Out of one: the backdrop clears and the library comes forward again
                            // underneath it, while the cover flies home.
                            else -> (scaleIn(motion.defaultSpatialSpec(), initialScale = RECEDED) togetherWith fadeOut(motion.defaultSpatialSpec()))
                                .apply { targetContentZIndex = -1f }
                        }
                    },
                ) { target ->
                    CompositionLocalProvider(
                        LocalCoverTransition provides CoverTransition(this@SharedTransitionLayout, this@AnimatedContent),
                        LocalCoverSpot provides coverSpot,
                    ) {
                        val book = state.find(target.takeIf { it.startsWith(READ) }?.removePrefix(READ))
                        // A screen on its way out, or the library while a cover is in the air, takes no
                        // touches: a tap then would start a second transition on top of the first.
                        val leaving = transition.targetState != EnterExitState.Visible
                        val coverFlying = screens.isRunning &&
                            (screens.currentState.startsWith(READ) || screens.targetState.startsWith(READ))
                        if (book == null && (leaving || coverFlying)) TouchShield()
                        when {
                            target == SETTINGS -> SettingsScreen(
                                state = state,
                                outerAvailable = outerAvailable,
                                onSettings = library::updateSettings,
                                onBack = { destination = HOME },
                                onLicenses = { destination = LICENSES },
                            )
                            target == LICENSES -> LicensesScreen(onBack = { destination = SETTINGS })
                            book != null -> JacketTheme(seed = if (bookColors) book.seedColor else null, animated = false) {
                                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                                    ReaderScreen(
                                        book = book,
                                        settings = state.settings,
                                        outer = outer,
                                        onSettings = library::updateSettings,
                                        onOpen = { open(it, null) },
                                        onClose = {
                                            // "Recent" has moved the book just read to the top of the
                                            // library: bring that into view, so its cover has
                                            // somewhere to land.
                                            val newest = state.books.maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }
                                            if (state.settings.sort == LibrarySort.RECENT && newest?.id == book.id &&
                                                libraryGrid.firstVisibleItemIndex > 0
                                            ) {
                                                libraryGrid.requestScrollToItem(0)
                                            }
                                            // It lands on the hero card when that shows this book and
                                            // is in view (it heads the grid), else back on its tile.
                                            val onHero = !state.onFinishedShelf && state.heroBook(missing)?.id == book.id &&
                                                libraryGrid.firstVisibleItemIndex == 0
                                            coverSpot = if (onHero) CoverSpot.HERO else CoverSpot.TILE
                                        },
                                        onBack = { destination = HOME },
                                    )
                                }
                            }
                            else -> HomeScreen(
                                state = state,
                                outerAvailable = outerAvailable,
                                importing = busy,
                                snackbar = snackbar,
                                onImport = onImport,
                                onOpen = { book, spot -> open(book.id, spot) },
                                onShowOnCover = { library.setCurrent(it.id) },
                                onDelete = { library.delete(it.id) },
                                onSort = { sort -> library.updateSettings { it.copy(sort = sort) } },
                                onOpenSettings = { destination = SETTINGS },
                                missing = missing,
                                onShelf = { finished -> library.updateSettings { it.copy(showFinished = finished) } },
                                onFinished = { book, done -> library.setFinished(book.id, done) },
                                grid = libraryGrid,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How far back the library sits while a book is open in front of it. */
private const val RECEDED = 0.92f

/** Swallows every touch on the screen it is composed in. */
@Composable
private fun TouchShield() {
    Box(
        Modifier.fillMaxSize().zIndex(1f).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
    )
}
