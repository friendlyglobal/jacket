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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.friendlyglobal.jacket.ui.home.FreeBooksScreen
import io.github.friendlyglobal.jacket.ui.home.HomeScreen
import io.github.friendlyglobal.jacket.ui.home.Onboarding
import io.github.friendlyglobal.jacket.ui.home.heroBook
import io.github.friendlyglobal.jacket.ui.home.libraryPaneWidth
import io.github.friendlyglobal.jacket.ui.home.onFinishedShelf
import io.github.friendlyglobal.jacket.ui.Foldables
import io.github.friendlyglobal.jacket.ui.rememberFoldable
import io.github.friendlyglobal.jacket.ui.rememberFoldedShut
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** A book asked for by the "Listen" shortcut: it starts reading aloud as it opens. */
    private val listenRequests = MutableStateFlow<String?>(null)
    private val importing = MutableStateFlow(false)
    private val reports = MutableSharedFlow<ImportReport>(extraBufferCapacity = 8)
    private lateinit var outer: OuterDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Must be in place before super.onCreate, which restores fragments.
        supportFragmentManager.fragmentFactory = ReaderFragments
        super.onCreate(savedInstanceState)
        ReaderFragments.removeRestoredReaders(supportFragmentManager)
        // It wears the cover of the book that is open, which a finished book opened again isn't
        // the library's current one.
        outer = OuterDisplay(this) { OuterCoverHost(bookId = outer.bookId) }
        if (savedInstanceState == null) handle(intent)
        setContent {
            JacketRoot(outer, openRequests, listenRequests, importing, reports, onImport = { importUris(it) }, onSample = ::openSample)
        }
    }

    /** Each time Jacket comes to the front: anything new in the watched folder joins the library. */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { library.scanWatchedFolder()?.let(reports::tryEmit) }
    }

    /**
     * Leaving the screen: what hasn't been saved yet is saved now. Android can close Jacket from
     * here without telling it (an update does), and the last page turned is where the reader is.
     */
    override fun onStop() {
        super.onStop()
        library.flush()
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
        // Debug builds only: see the welcome as a foldable or as any other phone does, whatever
        // this device is.  adb shell am start -n <app>/.MainActivity -\-es jacket.device phone|foldable
        if (Foldables.debuggable(this)) {
            when (intent.getStringExtra(EXTRA_DEVICE)) {
                "phone" -> Foldables.override.value = false
                "foldable" -> Foldables.override.value = true
                "auto" -> Foldables.override.value = null
            }
        }
        intent.getStringExtra(EXTRA_OPEN_BOOK)?.let {
            if (intent.getBooleanExtra(EXTRA_LISTEN, false)) listenRequests.value = it
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

    /**
     * The book that comes with Jacket (The Time Machine, in the public domain), for trying the
     * reader with nothing of your own to hand: added to the library like any file, and opened.
     */
    private fun openSample() {
        lifecycleScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    File(File(cacheDir, "sample").apply { mkdirs() }, SAMPLE_NAME).also { target ->
                        assets.open(SAMPLE_ASSET).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                }
            }.getOrNull()
            if (file == null) {
                Toast.makeText(this@MainActivity, R.string.sample_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            importUris(listOf(Uri.fromFile(file)), openAfter = true)
        }
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
        const val EXTRA_OPEN_BOOK = "app.usefriendly.jacket.OPEN_BOOK"
        const val EXTRA_LISTEN = "app.usefriendly.jacket.LISTEN"
        private const val EXTRA_DEVICE = "jacket.device"
        private const val SAMPLE_ASSET = "sample/the-time-machine.epub"
        private const val SAMPLE_NAME = "The Time Machine.epub"
    }
}

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val LICENSES = "licenses"
private const val FREE_BOOKS = "free-books"
private const val READ = "read:"

@Composable
private fun JacketRoot(
    outer: OuterDisplay,
    openRequests: MutableStateFlow<String?>,
    listenRequests: MutableStateFlow<String?>,
    importing: StateFlow<Boolean>,
    reports: SharedFlow<ImportReport>,
    onImport: (List<Uri>) -> Unit,
    onSample: () -> Unit,
) {
    val library = LocalContext.current.library
    val resources = LocalResources.current
    val haptics = rememberHaptics()
    val state by library.state.collectAsStateWithLifecycle()
    val missing by library.missing.collectAsStateWithLifecycle()
    val busy by importing.collectAsStateWithLifecycle()
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    // A phone that folds is welcomed with its outer screen; any other isn't told about one.
    val foldable by rememberFoldable(outerAvailable)
    val request by openRequests.collectAsStateWithLifecycle()
    val listenFor by listenRequests.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(HOME) }
    val snackbar = remember { SnackbarHostState() }
    // Kept here so closing a book can bring the place its cover lands into view.
    val libraryGrid = rememberLazyGridState()
    // Wide enough that the book you're reading has a pane of its own beside the shelves.
    val libraryTwoPane = libraryPaneWidth() != null

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
    // The collector below outlives many recompositions: it opens books as the library is now.
    val openNow by rememberUpdatedState(open)
    LaunchedEffect(Unit) {
        reports.collect { report ->
            if (report.failed.isEmpty()) haptics.confirm() else haptics.reject()
            val message = report.message(resources)
            if (message.isEmpty()) return@collect
            // One book, added from the library: a tap on the snackbar opens it. (Opened from
            // another app, it is already on its way to the reader.)
            val added = report.imported.singleOrNull()?.takeIf { report.failed.isEmpty() && openRequests.value != it.id && destination == HOME }
            val answer = snackbar.showSnackbar(
                message,
                actionLabel = added?.let { resources.getString(R.string.library_menu_open) },
                duration = SnackbarDuration.Short,
            )
            if (answer == SnackbarResult.ActionPerformed && added != null && destination == HOME) openNow(added.id, null)
        }
    }
    // A removed book can't stay open. Asked of the library as it is now, not of what this
    // composition saw: started cold by a widget or a shortcut, the book is asked for before this
    // first runs, and going by the stale view would send it straight back to the library.
    LaunchedEffect(destination, openBook) {
        if (destination.startsWith(READ) && library.state.value.find(destination.removePrefix(READ)) == null) destination = HOME
    }

    // The outer display follows "is a book open", not a particular reader screen, so switching
    // books keeps it on. A manual toggle in the reader sticks until reading stops.
    val reading = openBook != null
    SideEffect { outer.bookId = openBook?.id }
    LaunchedEffect(reading, outerAvailable, state.settings.autoOuterScreen) {
        if (!reading) outer.stop()
        else if (outerAvailable && state.settings.autoOuterScreen) outer.start()
    }
    // Here rather than in the reader: switching books composes two readers for a moment, and the
    // outgoing one must not turn the screen timeout back on.
    if (reading) KeepScreenOn()

    // "Cover when closed": folded shut on an open book, the screen wears its cover and reading
    // waits until the phone is opened. Back on the cover reads here anyway, until the next unfold.
    val foldedShut by rememberFoldedShut(watch = reading && state.settings.coverWhenClosed)
    var readShut by remember { mutableStateOf(false) }
    LaunchedEffect(foldedShut) { if (!foldedShut) readShut = false }
    val wearingCover = reading && state.settings.coverWhenClosed && foldedShut && !readShut

    // The welcome comes before the first book. A book arriving some other way (opened from a file
    // manager, shared from another app) is welcome enough.
    val welcome = !state.settings.onboarded && state.books.isEmpty() && !busy
    LaunchedEffect(state.books.isEmpty()) {
        if (state.books.isNotEmpty() && !state.settings.onboarded) library.updateSettings { it.copy(onboarded = true) }
    }

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
                            targetState == LICENSES || targetState == FREE_BOOKS || (targetState == SETTINGS && initialState != LICENSES) ->
                                (slideInHorizontally(spatial) { it / 3 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(spatial) { -it / 10 } + fadeOut(tween(160)))
                            initialState == LICENSES || initialState == SETTINGS || initialState == FREE_BOOKS ->
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
                            target == FREE_BOOKS -> FreeBooksScreen(
                                state = state,
                                onBack = { destination = HOME },
                                onOpen = { book -> open(book.id, CoverSpot.TILE) },
                            )
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
                                            // is in view (it heads the grid, or has a pane of its own
                                            // on a wide screen), else back on its tile.
                                            val onHero = state.heroBook(missing)?.id == book.id &&
                                                (libraryTwoPane || !state.onFinishedShelf && libraryGrid.firstVisibleItemIndex == 0)
                                            coverSpot = if (onHero) CoverSpot.HERO else CoverSpot.TILE
                                        },
                                        onBack = { destination = HOME },
                                        coverUp = wearingCover,
                                        onReadShut = { readShut = true },
                                        autoListen = listenFor == book.id,
                                        onAutoListened = { listenRequests.value = null },
                                    )
                                }
                            }
                            else -> {
                                // Decided once, as this library comes into being.
                                val backFromSettings = remember { screens.currentState == SETTINGS }
                                // The welcome steps aside for the library it was the way into.
                                AnimatedContent(
                                    targetState = welcome,
                                    transitionSpec = {
                                        (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                                            .togetherWith(fadeOut(motion.fastEffectsSpec()))
                                    },
                                    label = "welcome",
                                ) { welcoming ->
                                    if (welcoming) {
                                        Onboarding(
                                            foldable = foldable,
                                            settings = state.settings,
                                            onSettings = library::updateSettings,
                                            onImport = onImport,
                                            onSample = onSample,
                                            onDone = { library.updateSettings { it.copy(onboarded = true) } },
                                        )
                                    } else {
                                        HomeScreen(
                                            backFromSettings = backFromSettings,
                                            state = state,
                                            outerAvailable = outerAvailable,
                                            importing = busy,
                                            snackbar = snackbar,
                                            onImport = onImport,
                                            onSample = onSample,
                                            foldable = foldable,
                                            onOpen = { book, spot -> open(book.id, spot) },
                                            onShowOnCover = { library.setCurrent(it.id) },
                                            onDelete = { library.delete(it.id) },
                                            onSort = { sort -> library.updateSettings { it.copy(sort = sort) } },
                                            onOpenSettings = { destination = SETTINGS },
                                            missing = missing,
                                            onShelf = { finished -> library.updateSettings { it.copy(showFinished = finished) } },
                                            onFinished = { book, done -> library.setFinished(book.id, done) },
                                            onListen = { book ->
                                                listenRequests.value = book.id
                                                open(book.id, CoverSpot.HERO)
                                            },
                                            organizer = library,
                                            onFreeBooks = { destination = FREE_BOOKS },
                                            grid = libraryGrid,
                                        )
                                    }
                                }
                            }
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
