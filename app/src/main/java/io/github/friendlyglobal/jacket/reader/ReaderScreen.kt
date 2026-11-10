package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import io.github.friendlyglobal.jacket.data.PageColor
import io.github.friendlyglobal.jacket.ui.rememberTabletopFold
import kotlin.math.ceil
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.outer.OuterDisplay
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.LocalCoverTransition
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.SystemBarsHidden
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.rememberPageColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** A table-of-contents line: its title, nesting depth, and its index in [ReaderNav.toc]. */
data class TocItem(val title: String, val level: Int, val index: Int)

/**
 * What the reader chrome shows and can ask for. Each format view fills it in: sections are
 * positions (about a page each) for EPUB, pages for PDF and comics, paragraphs for text.
 */
@Stable
class ReaderNav {
    var section by mutableIntStateOf(0)
    var sectionCount by mutableIntStateOf(1)
    /** Where you are, e.g. the chapter title or "Page 3 of 40". */
    var title by mutableStateOf("")
    /** Quiet line under the page, e.g. "4 pages left in chapter". */
    var footer by mutableStateOf("")
    var toc by mutableStateOf<List<TocItem>>(emptyList())
    var tocIndex by mutableIntStateOf(-1)
    /** The chapter you're in, by name, for formats that have chapters: the cover screen shows it. */
    var chapter by mutableStateOf("")
    /** How far through [chapter] you are (0..1); negative while unknown. */
    var chapterProgress by mutableFloatStateOf(-1f)
    /** What's left of the chapter, as a fraction of the whole book (negative while unknown): at your pace, the time left. */
    var chapterLeft by mutableFloatStateOf(-1f)
    /** Where the last jump started from, offered back for a little while. */
    var cameFrom by mutableStateOf<Origin?>(null)
    var sectionName: (Int) -> String = { "" }
    /** Sections in the same group (e.g. chapter) share a value: the scrubber ticks between groups. */
    var sectionGroup: (Int) -> Int = { it }
    var jumpTo: (Int) -> Unit = {}
    var openToc: (TocItem) -> Unit = {}
    /** Previous/next buttons: by chapter for EPUB, by page or paragraph otherwise. */
    var step: (Int) -> Unit = { jumpTo(section + it) }
    var canStep: (Int) -> Boolean = { section + it in 0 until sectionCount }

    /** Chapter starts as fractions of the book (0..1), drawn as marks on the scrubber. */
    var marks by mutableStateOf<List<Float>>(emptyList())

    /** Audiobook mode; [listenController] is null for formats that can't be read aloud. */
    val listen = ListenState()
    var listenController by mutableStateOf<ListenController?>(null)

    /** Bookmarks: the page on screen, whether a mark falls on it, and going to one. */
    var place: suspend () -> Place? = { null }
    var onScreen: (Annotation) -> Boolean = { false }
    var goTo: (Annotation) -> Unit = {}
    /** Changes whenever the page on screen does, so bookmark state follows. */
    var pageKey by mutableIntStateOf(0)

    /** Search inside the book, for formats that have it. */
    var search by mutableStateOf<BookSearch?>(null)
    /** The reader has reached the book's last page. */
    var atEnd by mutableStateOf(false)
    /** A highlight tapped on the page, to edit. */
    var editing by mutableStateOf<String?>(null)
    /** Turns a page for the volume keys: +1 forward, -1 back. */
    var turnPage: (Int) -> Unit = { step(it) }

    /** The first page is on screen (or nothing more is coming): the opening cover may lift. */
    var contentReady by mutableStateOf(false)
}

/** The place a jump (contents, search, the scrubber) left from: how far through the book, and the way back. */
class Origin(val percent: Int, val back: () -> Unit)

/** The open book's [ReaderNav], for views that only need to report in (see [ReaderError]). */
internal val LocalReaderNav = compositionLocalOf<ReaderNav?> { null }

/** Where the activity sends volume-key presses while a book is open and volume paging is on. */
object ReaderKeys {
    var handler: ((Int) -> Unit)? = null
}

typealias ProgressSink = (position: Int, offset: Float, progress: Float, sections: Int) -> Unit

private enum class Sheet { NONE, CONTENTS, TEXT, SEARCH }

@Composable
fun ReaderScreen(
    book: Book,
    settings: Settings,
    outer: OuterDisplay,
    onSettings: ((Settings) -> Settings) -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    /** The phone is folded shut on this book with "Cover when closed" on: the cover takes the screen. */
    coverUp: Boolean = false,
    /** Back on that cover: read here after all. */
    onReadShut: () -> Unit = {},
    /** Opened to be listened to (the "Listen" shortcut): read-aloud starts with the first page. */
    autoListen: Boolean = false,
    onAutoListened: () -> Unit = {},
) {
    val context = LocalContext.current
    val library = context.library
    val libraryState by library.state.collectAsStateWithLifecycle()
    val annotations = remember(libraryState.annotations, book.id) { libraryState.annotations.filter { it.bookId == book.id } }
    val haptics = rememberHaptics()
    // Plain text is read aloud with Jacket's own speech engine; EPUB uses Readium's.
    val speaker = remember(book.id) { if (book.format == BookFormat.TXT) Speaker(context) else null }
    DisposableEffect(speaker) { onDispose { speaker?.shutdown() } }
    LaunchedEffect(speaker, settings.listenRate) { speaker?.setRate(settings.listenRate) }
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    val outerActive by outer.active.collectAsStateWithLifecycle()
    val nav = remember(book.id) { ReaderNav() }
    // "Dark pages at night": with the phone in dark theme, Paper gives way to Dark.
    val page = rememberPageColors(
        if (settings.nightPages && settings.pageColor == PageColor.PAPER && isSystemInDarkTheme()) PageColor.DARK else settings.pageColor,
    )
    var chrome by rememberSaveable(book.id) { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    // Once you touch the controls, they stay until you dismiss them.
    var touchedChrome by remember(book.id) { mutableStateOf(false) }
    val noticeTouches = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                touchedChrome = true
            }
        }
    }

    // Opening and closing the book (see BookOpening): the cover lands, waits for the first page,
    // swings open; closing folds the page back under it and sends it home to the library.
    val opening = remember(book.id) { BookOpening() }
    val flight = LocalCoverTransition.current?.visibility?.transition
    var closing by remember(book.id) { mutableStateOf(false) }
    // The library has been asked to take over, and the cover is flying home.
    var left by remember(book.id) { mutableStateOf(false) }
    // Which controls have made their entrance: none, the top bar, then the toolbar too.
    var entrance by remember(book.id) { mutableIntStateOf(0) }
    // The page itself is built once the cover is down. Building it is heavy (for an EPUB,
    // Readium inflates a fragment and three web views on the main thread), and done mid-flight
    // it would cost the cover most of its frames; a cover lying still can spare them.
    var staged by remember(book.id) { mutableStateOf(flight == null || flight.landed) }
    // The book opened before its page was there (a slow one): a loading indicator stands in.
    var waiting by remember(book.id) { mutableStateOf(false) }
    val leave by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    LaunchedEffect(opening, closing) {
        if (closing) {
            entrance = 0
            // With animations removed there is nothing to fold: the page gives way to the library.
            if (!motionRemoved()) opening.fold()
            left = true
            leave()
            return@LaunchedEffect
        }
        if (flight != null && !flight.landed) {
            // The flight is a spring: the cover is down, give or take a pixel or two, well before
            // the spring calls it finished. That is the moment for the tick, and to start on the page.
            val takeoff = withFrameNanos { it }
            var flown = 0L
            while (!flight.landed && flown < TOUCHDOWN_NANOS) flown = withFrameNanos { it } - takeoff
            haptics.tick()
        }
        staged = true
        if (flight != null) snapshotFlow { flight.landed }.first { it }
        // The library has left the screen: marking the book as opened earlier would reshuffle it
        // mid-flight and move the cover's starting point.
        repeat(2) { withFrameNanos { } }
        library.markOpened(book.id)
        if (!nav.contentReady) {
            val breathing = launch { opening.breathe() }
            withTimeoutOrNull(HOLD_CAP_MS) { snapshotFlow { nav.contentReady }.first { it } }
            breathing.cancel()
        }
        if (!nav.contentReady) {
            waiting = true
            launch {
                withTimeoutOrNull(WAITING_CAP_MS) { snapshotFlow { nav.contentReady }.first { it } }
                waiting = false
            }
        }
        // A frame for the page to reach the screen, behind the cover.
        withFrameNanos { }
        launch {
            // The controls arrive once the page is there, one after the other...
            snapshotFlow { opening.open.value >= BookOpening.REVEALED }.first { it }
            entrance = 1
            if (!motionRemoved()) delay(CHROME_STAGGER_MS)
            entrance = 2
            // ...stay long enough to be discovered, then get out of the way.
            delay(CHROME_SHOWN_MS)
            if (!touchedChrome) chrome = false
        }
        opening.reveal()
    }
    // Opened again while its cover was still flying home: start over from the closed book.
    if (flight != null) {
        LaunchedEffect(flight.targetState) {
            if (left && flight.targetState == EnterExitState.Visible) {
                left = false
                closing = false
            }
        }
    }
    // Closing shows the system bars straight away, so the library has its final layout (and the
    // cover its landing spot) by the time it appears.
    SystemBarsHidden(hidden = coverUp || (!chrome && sheet == Sheet.NONE && !closing))
    // A sheet is a window of its own: it would sit on top of the cover.
    LaunchedEffect(coverUp) { if (coverUp) sheet = Sheet.NONE }
    val setRate: (Float) -> Unit = { rate ->
        onSettings { it.copy(listenRate = rate) }
        nav.listenController?.setRate(rate)
    }

    val close: () -> Unit = {
        if (!closing) {
            // First, so the cover is made knowing where in the library it will land.
            onClose()
            closing = true
            nav.listenController?.stop()
        }
    }

    // Back: the page starts to fold under your thumb. Let go and the book closes; change your mind
    // and it springs flat again. Once it is closing, further presses are swallowed.
    // (Not while the cover of a folded phone is up: that has its own way back.)
    PredictiveBackHandler(enabled = !coverUp) { events ->
        if (closing) return@PredictiveBackHandler events.collect { }
        try {
            events.collect { if (opening.settled) opening.peek(it.progress) }
            close()
        } catch (e: CancellationException) {
            if (opening.settled) scope.launch { opening.unpeek() }
            throw e
        }
    }

    // Volume keys turn pages while reading (not while listening, when they're for volume).
    // Nor under the cover of a folded phone, where pages would turn unseen.
    DisposableEffect(settings.volumeKeys, nav.listen.active, coverUp) {
        val handler: ((Int) -> Unit)? = if (settings.volumeKeys && !nav.listen.active && !coverUp) { delta -> nav.turnPage(delta) } else null
        ReaderKeys.handler = handler
        // Only clear our own: opening the next book briefly overlaps the outgoing reader.
        onDispose { if (ReaderKeys.handler === handler) ReaderKeys.handler = null }
    }

    // Sleep timer: pause after the chosen time, or when the chapter changes.
    val listen = nav.listen
    LaunchedEffect(listen.sleep, listen.sleepEndsAt) {
        val timer = listen.sleep
        if (timer == SleepTimer.OFF) return@LaunchedEffect
        if (timer == SleepTimer.CHAPTER) {
            // Whichever comes first: the voice moving into the next chapter file, or the page
            // (following the voice) reaching the next chapter in the contents.
            val start = listen.spokenFile to nav.tocIndex
            snapshotFlow { listen.spokenFile to nav.tocIndex }.first { (file, chapter) ->
                (file != start.first && file >= 0 && start.first >= 0) || chapter != start.second
            }
        } else {
            delay((listen.sleepEndsAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
        if (listen.playing) nav.listenController?.toggle()
        listen.sleep = SleepTimer.OFF
    }
    // Stopping read-aloud clears the timer.
    LaunchedEffect(listen.active) { if (!listen.active) listen.sleep = SleepTimer.OFF }

    // The cover screen and the widget follow along: the chapter, the place in it (to the nearest
    // half percent: about a page), and read-aloud.
    DisposableEffect(book.id) { onDispose { library.endMoment(book.id) } }
    LaunchedEffect(book.id, nav) {
        snapshotFlow {
            val place = nav.chapterProgress
            ReadingMoment(
                bookId = book.id,
                chapter = nav.chapter,
                chapterProgress = if (place < 0f) -1f else (place * 200).roundToInt() / 200f,
                listening = nav.listen.active,
                playing = nav.listen.playing,
                marks = nav.marks,
            )
        }.collect(library::report)
    }

    // Reading time: how long each page was up before the next (a page left open, or flipped
    // past, doesn't count), for the book's pace and today's total. Listening isn't reading pace.
    LaunchedEffect(book.id, nav) {
        var since = SystemClock.elapsedRealtime()
        var from = -1f
        snapshotFlow { nav.pageKey }.collect {
            val now = SystemClock.elapsedRealtime()
            val progress = library.state.value.find(book.id)?.progress ?: return@collect
            val spent = now - since
            if (from >= 0f && spent in SHORTEST_PAGE_MS..LONGEST_PAGE_MS && !nav.listen.active) {
                library.addReading(book.id, spent, progress - from)
            }
            since = now
            from = progress
        }
    }

    // The "Listen" shortcut: start reading aloud once the book is open on its page.
    LaunchedEffect(autoListen) {
        if (!autoListen) return@LaunchedEffect
        snapshotFlow { nav.contentReady && nav.listenController != null && opening.settled }.first { it }
        chrome = false
        nav.listenController?.start()
        onAutoListened()
    }

    // Brightness and warmth, by swiping the page's edges.
    val levels = remember { EdgeLevels(settings.brightness, settings.warmth) }
    val swipes by rememberUpdatedState(settings.edgeSwipes)
    ReaderBrightness(levels, enabled = settings.edgeSwipes)

    // Tabletop: half-open with the fold lying flat, the page sits above it and a desk of controls below.
    val fold by rememberTabletopFold(watch = settings.tabletop)
    var windowHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val belowFold = fold?.let { with(density) { (windowHeight - it.top).coerceAtLeast(0).toDp() } } ?: 0.dp
    val desk = fold?.let { with(density) { (windowHeight - it.bottom).coerceAtLeast(0).toDp() } } ?: 0.dp

    // A highlight tapped on the page opens its editor.
    val editing = annotations.firstOrNull { it.id == nav.editing }
    var finishedDismissed by rememberSaveable(book.id) { mutableStateOf(false) }
    val next = remember(libraryState.books, book.id) {
        libraryState.books.filter { it.id != book.id && !it.isFinished && library.missing.value.contains(it.id).not() }
            .maxWithOrNull(compareBy<Book>({ it.lastOpenedAt }, { it.addedAt }))
    }

    val onProgress: ProgressSink = { position, offset, progress, sections ->
        library.updatePosition(book.id, position, offset, progress, sections)
    }
    val toggleChrome = {
        touchedChrome = true
        chrome = !chrome
    }

    val motion = MaterialTheme.motionScheme
    Box(Modifier.fillMaxSize().background(page.background).onSizeChanged { windowHeight = it.height }) {
        // The page and its controls. They grow with the window the backdrop opens on them: a page
        // the size of the book under the cover, the whole screen once it is open.
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = belowFold)
                .openingPage(opening)
                .pointerInput(levels) {
                    edgeSwipes(
                        levels,
                        enabled = { swipes && opening.settled },
                        phoneBrightness = {
                            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                        },
                        haptics = haptics,
                        onSettled = { onSettings { it.copy(brightness = levels.brightness, warmth = levels.warmth) } },
                    )
                },
        ) {
            if (staged) {
                CompositionLocalProvider(LocalReaderNav provides nav) {
                    when (book.format) {
                        BookFormat.EPUB -> EpubReader(book, settings, page, nav, haptics, annotations, toggleChrome)
                        BookFormat.PDF -> PdfReader(book, page, nav, haptics, onProgress, toggleChrome)
                        BookFormat.CBZ -> ComicReader(book, page, nav, haptics, onProgress, toggleChrome)
                        BookFormat.TXT -> TextReader(book, settings, page, nav, haptics, requireNotNull(speaker), onProgress, toggleChrome)
                    }
                }
            }
            Warmth(levels, enabled = settings.edgeSwipes)
            // The page fades in under the indicator when it does arrive, rather than popping up.
            AnimatedVisibility(visible = waiting, enter = EnterTransition.None, exit = fadeOut(motion.slowEffectsSpec())) {
                Box(Modifier.fillMaxSize().background(page.background)) { ReaderLoading() }
            }

            AnimatedVisibility(
                visible = !chrome && !nav.listen.active,
                enter = fadeIn(motion.slowEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { ReaderFooter(nav, book, page, settings) }

            // After a jump: the way back to where you were, for a little while.
            JumpBack(nav, haptics, visible = opening.settled, modifier = Modifier.align(Alignment.TopCenter))
            EdgeLevel(levels)

            AnimatedVisibility(
                visible = chrome && entrance >= 1,
                enter = slideInVertically(motion.defaultSpatialSpec()) { -it } + fadeIn(motion.defaultEffectsSpec()),
                exit = slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).then(noticeTouches),
            ) {
                val marked = remember(nav.pageKey, annotations) {
                    annotations.firstOrNull { it.kind == AnnotationKind.BOOKMARK && nav.onScreen(it) }
                }
                ReaderTopBar(
                    book = book, nav = nav, outerAvailable = outerAvailable, outerActive = outerActive,
                    bookmarked = marked != null,
                    onBookmark = {
                        if (marked != null) {
                            haptics.toggle(false)
                            library.deleteAnnotation(marked.id)
                        } else {
                            scope.launch {
                                val here = nav.place() ?: return@launch
                                haptics.toggle(true)
                                library.addAnnotation(
                                    Annotation(
                                        id = java.util.UUID.randomUUID().toString(), bookId = book.id,
                                        kind = AnnotationKind.BOOKMARK, locator = here.locator, section = here.section,
                                        progress = here.progress, chapter = here.chapter, text = here.snippet,
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    },
                    onSearch = { haptics.tick(); sheet = Sheet.SEARCH },
                    onOuter = { on -> haptics.toggle(on); outer.toggle() },
                    onBack = close,
                )
            }

            // While listening, the player stays even when the rest of the controls are hidden. The
            // toolbar floats, so it can afford more spring than the bar pinned to the top edge.
            AnimatedVisibility(
                visible = (chrome || nav.listen.active) && entrance >= 2,
                enter = slideInVertically(motion.fastSpatialSpec()) { it } +
                    scaleIn(motion.fastSpatialSpec(), initialScale = 0.88f) + fadeIn(motion.defaultEffectsSpec()),
                exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).then(noticeTouches),
            ) {
                ReaderBottomBar(
                    nav = nav, book = book, haptics = haptics,
                    showScrubber = chrome,
                    rate = settings.listenRate,
                    onRate = setRate,
                    onListen = {
                        haptics.confirm()
                        chrome = false
                        nav.listenController?.start()
                    },
                    onContents = { sheet = Sheet.CONTENTS },
                    onText = { sheet = Sheet.TEXT },
                )
            }

            // The last page: celebrate, and offer what's next.
            AnimatedVisibility(
                visible = nav.atEnd && !finishedDismissed && !chrome && !nav.listen.active && opening.settled,
                enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp),
            ) {
                LaunchedEffect(Unit) { haptics.confirm() }
                FinishedCard(
                    book = book,
                    next = next,
                    onFinish = { haptics.confirm(); library.setFinished(book.id, true) },
                    onNext = { onOpen(it.id) },
                    onLibrary = close,
                    onDismiss = { haptics.tick(); finishedDismissed = true },
                )
            }
        }

        // Over everything, so nothing of the reader shows until the book opens: the backdrop the
        // closed book lies on, and the cover itself (not needed while reading).
        if (fold != null && opening.settled) {
            TabletopDesk(nav, book, settings, setRate, haptics, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(desk))
        }
        OpeningBackdrop(opening, MaterialTheme.colorScheme.primaryContainer)
        if (!opening.settled) OpeningCover(book, opening)
        ClosedCover(coverUp, book, nav, settings, setRate, haptics, onReadHere = onReadShut, onLeave = close)
    }

    when (sheet) {
        Sheet.CONTENTS -> ReaderSheet(onGone = { sheet = Sheet.NONE }) { leave ->
            NotesSheet(
                nav = nav,
                annotations = annotations,
                haptics = haptics,
                // You picked where to go: get out of the way and let them read. The jump waits for
                // the sheet to leave: loading a chapter would stall it halfway down.
                onPickToc = { item ->
                    chrome = false
                    leave { nav.openToc(item) }
                },
                onPick = { item ->
                    chrome = false
                    leave { nav.goTo(item) }
                },
                onEdit = { item -> leave { nav.editing = item.id } },
                onDelete = { item -> library.deleteAnnotation(item.id) },
                onShare = { shareHighlights(context, book, annotations) },
            )
        }
        Sheet.SEARCH -> ReaderSheet(onGone = { sheet = Sheet.NONE }, fullHeight = true) { leave ->
            nav.search?.let { search ->
                SearchPanel(search, haptics) { hit ->
                    chrome = false
                    leave { search.show(hit) }
                }
            }
        }
        Sheet.TEXT -> ReaderSheet(onGone = { sheet = Sheet.NONE }) {
            ReadingControls(
                settings, onSettings,
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
                forEpub = book.format == BookFormat.EPUB,
            )
        }
        Sheet.NONE -> {}
    }

    if (editing != null) {
        ReaderSheet(onGone = { nav.editing = null }) { leave ->
            HighlightEditor(
                annotation = editing,
                haptics = haptics,
                onChange = { changed -> library.updateAnnotation(changed.id) { changed } },
                // Gone first, then deleted: the sheet shows the highlight until it has left.
                onDelete = { leave { library.deleteAnnotation(editing.id) } },
                onDone = { leave {} },
            )
        }
    }
}

/**
 * One of the reader's bottom sheets. Choosing something in it calls `leave`: the sheet slides
 * away on its own spring, and only then is it dropped from the composition and the given
 * follow-up run. (Dropped at once, a sheet just vanishes from the middle of the screen.)
 */
@Composable
private fun ReaderSheet(
    onGone: () -> Unit,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.(leave: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberBottomSheetState(SheetValue.Hidden, if (fullHeight) FullHeightOnly else AnyHeight)
    val scope = rememberCoroutineScope()
    val gone by rememberUpdatedState(onGone)
    val leave: (() -> Unit) -> Unit = { then ->
        scope.launch {
            try {
                state.hide()
            } finally {
                gone()
                then()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onGone, sheetState = state) { content(leave) }
}

private val AnyHeight = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
private val FullHeightOnly = setOf(SheetValue.Hidden, SheetValue.Expanded)

/** A page up for less than this was flipped past; for more than the other, left open. Neither is reading. */
private const val SHORTEST_PAGE_MS = 3_000L
private const val LONGEST_PAGE_MS = 180_000L

/** How long the way back from a jump stays on offer. */
private const val JUMP_BACK_MS = 12_000L

/**
 * "Back · 42%": after a jump through the contents, a search or the scrubber, a pill that takes
 * you back to the page you left. It goes away by itself.
 */
@Composable
private fun JumpBack(nav: ReaderNav, haptics: Haptics, visible: Boolean, modifier: Modifier = Modifier) {
    val origin = nav.cameFrom
    // Kept while the pill fades, after the offer itself has gone.
    var shown by remember { mutableStateOf(origin) }
    if (origin != null) shown = origin
    LaunchedEffect(origin) {
        if (origin == null) return@LaunchedEffect
        delay(JUMP_BACK_MS)
        if (nav.cameFrom === origin) nav.cameFrom = null
    }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = origin != null && visible,
        enter = scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f) + fadeIn(motion.fastEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f) + fadeOut(motion.fastEffectsSpec()),
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 76.dp),
    ) {
        Surface(
            onClick = {
                haptics.tick()
                shown?.back?.invoke()
                nav.cameFrom = null
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(JacketIcons.Back, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.common_back) + " · " + stringResource(R.string.percent, shown?.percent ?: 0),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * The lower half of a phone standing half-open on a table: where you are, and two broad buttons
 * to turn the page without picking the phone up; or, while the book is read aloud, the sentence
 * being spoken and the player.
 */
@Composable
private fun TabletopDesk(nav: ReaderNav, book: Book, settings: Settings, onRate: (Float) -> Unit, haptics: Haptics, modifier: Modifier = Modifier) {
    val controller = nav.listenController
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (nav.listen.active && controller != null) {
                SpokenPhrase(nav.listen, settings)
                Spacer(Modifier.height(20.dp))
                ListenBar(
                    nav.listen, controller, settings.listenRate, onRate, haptics,
                    progress = nav.section.toFloat() / (nav.sectionCount - 1).coerceAtLeast(1),
                    hasChapters = nav.toc.isNotEmpty(),
                )
            } else {
                Text(nav.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(footerText(nav, book, settings), stringResource(R.string.percent, book.percent)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { nav.turnPage(-1) }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 72.dp, max = 132.dp).fillMaxHeight(),
                    ) { Icon(JacketIcons.Previous, stringResource(R.string.reader_previous_page), Modifier.size(32.dp)) }
                    FilledTonalButton(
                        onClick = { nav.turnPage(1) }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 72.dp, max = 132.dp).fillMaxHeight(),
                    ) { Icon(JacketIcons.Next, stringResource(R.string.reader_next_page), Modifier.size(32.dp)) }
                }
            }
        }
    }
}

/** Everything highlighted in [book], with its notes, as text for another app: a quote per paragraph. */
private fun shareHighlights(context: android.content.Context, book: Book, annotations: List<Annotation>) {
    val highlights = annotations.filter { it.kind == AnnotationKind.HIGHLIGHT && it.text.isNotBlank() }.sortedBy { it.progress }
    if (highlights.isEmpty()) return
    val text = buildString {
        append("# ").append(book.title)
        if (book.author.isNotBlank()) append(" — ").append(book.author)
        for (highlight in highlights) {
            append("\n\n> ").append(highlight.text.trim())
            val place = listOf(highlight.chapter, context.getString(R.string.percent, (highlight.progress * 100).toInt())).filter { it.isNotBlank() }
            append("\n(").append(place.joinToString(" · ")).append(')')
            if (highlight.note.isNotBlank()) append("\n").append(highlight.note.trim())
        }
    }
    runCatching {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, book.title)
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(send, null))
    }
}

/**
 * The quiet line under the page: the minutes left in the chapter once your pace in this book is
 * known (and "Time left" is on), otherwise whatever the format reports (pages left, the page number).
 */
@Composable
private fun footerText(nav: ReaderNav, book: Book, settings: Settings): String {
    val left = nav.chapterLeft
    return if (settings.timeLeft && book.pace > 0f && left >= 0f && !nav.atEnd) {
        stringResource(R.string.reader_footer_time_left, ceil(book.pace * left / 60_000f).toInt().coerceAtLeast(1))
    } else {
        nav.footer
    }
}

/** The longest the landed cover waits for the first page; after that the book opens on a loading indicator. */
private const val HOLD_CAP_MS = 2_500L

/** ...which gives way to whatever is there after this long, page or no page. */
private const val WAITING_CAP_MS = 8_000L

/** Animation time into the cover's flight by which it has touched down, though the spring hasn't settled. */
private const val TOUCHDOWN_NANOS = 340_000_000L

/** The cover has landed in the reader: its flight (the screen transition it belongs to) is over. */
private val Transition<EnterExitState>.landed: Boolean
    get() = currentState == EnterExitState.Visible && targetState == EnterExitState.Visible && !isRunning

/** The toolbar follows the top bar in by this much. */
private const val CHROME_STAGGER_MS = 90L

/** How long the controls stay after arriving, unless you touch them. */
private const val CHROME_SHOWN_MS = 2_400L

@Composable
private fun ReaderTopBar(
    book: Book,
    nav: ReaderNav,
    outerAvailable: Boolean,
    outerActive: Boolean,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onSearch: () -> Unit,
    onOuter: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A new chapter rolls up into place.
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = nav.title,
                    transitionSpec = {
                        (slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform(clip = false))
                    },
                    label = "chapter",
                ) { title ->
                    Text(
                        title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, stringResource(R.string.reader_back_to_library)) } },
        actions = {
            if (nav.search != null) {
                IconButton(onClick = onSearch) { Icon(JacketIcons.Search, stringResource(R.string.reader_search)) }
            }
            // The ribbon drops in and bounces when you mark the page, and gives a small shrug when
            // you take it off. Not when the bar merely appears over a page that was marked already.
            val drop = remember { Animatable(1f) }
            var shown by remember { mutableStateOf(bookmarked) }
            LaunchedEffect(bookmarked) {
                if (bookmarked == shown) return@LaunchedEffect
                shown = bookmarked
                drop.snapTo(if (bookmarked) 0f else 0.55f)
                drop.animateTo(1f, spring(dampingRatio = if (bookmarked) 0.35f else 0.6f, stiffness = 500f))
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (bookmarked) JacketIcons.BookmarkFilled else JacketIcons.Bookmark,
                    if (bookmarked) stringResource(R.string.reader_bookmark_remove) else stringResource(R.string.reader_bookmark_add),
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    modifier = Modifier.graphicsLayer {
                        translationY = (1f - drop.value) * -10.dp.toPx()
                        val scale = 0.7f + 0.3f * drop.value
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }
            if (outerAvailable || outerActive) {
                FilledTonalIconToggleButton(checked = outerActive, onCheckedChange = onOuter) {
                    Icon(
                        JacketIcons.OuterScreen,
                        if (outerActive) stringResource(R.string.reader_outer_hide) else stringResource(R.string.reader_outer_show),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun ReaderBottomBar(
    nav: ReaderNav,
    book: Book,
    haptics: Haptics,
    showScrubber: Boolean,
    rate: Float,
    onRate: (Float) -> Unit,
    onListen: () -> Unit,
    onContents: () -> Unit,
    onText: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = showScrubber && nav.sectionCount > 1) {
            Column {
                Scrubber(nav, book, haptics)
                Spacer(Modifier.height(12.dp))
            }
        }
        val controller = nav.listenController
        val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        // The reading toolbar and the audiobook player swap places with a springy scale.
        AnimatedContent(
            targetState = nav.listen.active && controller != null,
            transitionSpec = {
                (fadeIn() + scaleIn(spring, initialScale = 0.85f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.85f))
            },
            label = "toolbar",
        ) { listening ->
            if (listening && controller != null) {
                ListenBar(
                    nav.listen, controller, rate, onRate, haptics,
                    progress = nav.section.toFloat() / (nav.sectionCount - 1).coerceAtLeast(1),
                    hasChapters = nav.toc.isNotEmpty(),
                )
            } else {
                HorizontalFloatingToolbar(
                    expanded = true,
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { haptics.tick(); onText() }) {
                            Icon(JacketIcons.TextFormat, stringResource(R.string.reader_text_settings))
                        }
                    },
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                ) {
                    IconButton(onClick = { haptics.tick(); onContents() }) {
                        Icon(
                            JacketIcons.Contents,
                            if (nav.toc.isNotEmpty()) stringResource(R.string.reader_contents_and_notes) else stringResource(R.string.reader_notes),
                        )
                    }
                    if (controller != null) {
                        IconButton(onClick = onListen) { Icon(JacketIcons.Headphones, stringResource(R.string.reader_listen)) }
                    }
                    val (previousLabel, nextLabel) = when {
                        nav.toc.isNotEmpty() -> R.string.reader_previous_chapter to R.string.reader_next_chapter
                        book.format == BookFormat.TXT -> R.string.reader_previous_paragraph to R.string.reader_next_paragraph
                        else -> R.string.reader_previous_page to R.string.reader_next_page
                    }
                    IconButton(
                        onClick = { haptics.tick(); nav.step(-1) },
                        enabled = nav.canStep(-1),
                    ) { Icon(JacketIcons.Previous, stringResource(previousLabel)) }
                    IconButton(
                        onClick = { haptics.tick(); nav.step(1) },
                        enabled = nav.canStep(1),
                    ) { Icon(JacketIcons.Next, stringResource(nextLabel)) }
                }
            }
        }
    }
}

/** Drag through the book, with a tick per chapter and a preview of where you'll land. */
@Composable
private fun Scrubber(nav: ReaderNav, book: Book, haptics: Haptics) {
    var preview by remember { mutableStateOf<Float?>(null) }
    val last = (nav.sectionCount - 1).coerceAtLeast(1)
    val target = preview?.roundToInt() ?: nav.section
    val slider = remember(nav.sectionCount) {
        SliderState(nav.section.toFloat(), if (nav.sectionCount in 3..60) nav.sectionCount - 2 else 0, 0f..last.toFloat())
    }
    // Follow page turns while not being dragged.
    LaunchedEffect(nav.section) { if (preview == null) slider.value = nav.section.toFloat() }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (preview != null) nav.sectionName(target) else nav.title,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.percent, if (preview != null) target * 100 / last else book.percent),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box {
                Slider(
                    state = slider,
                    onValueChange = { value ->
                        if (nav.sectionGroup(value.roundToInt()) != nav.sectionGroup(target)) haptics.tick()
                        preview = value
                        slider.value = value
                    },
                    onValueChangeFinished = {
                        preview?.let { nav.jumpTo(it.roundToInt()) }
                        preview = null
                        haptics.confirm()
                    },
                )
                // Chapter starts, as small marks along the track.
                val marks = nav.marks
                if (marks.size in 2..80) {
                    val onActive = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    val onInactive = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    Canvas(Modifier.matchParentSize()) {
                        val inset = 8.dp.toPx()
                        val radius = 1.5.dp.toPx()
                        val current = slider.value / last
                        for (mark in marks) {
                            if (mark <= 0.002f || mark >= 0.998f) continue
                            val x = inset + mark * (size.width - 2 * inset)
                            drawCircle(if (mark < current) onActive else onInactive, radius, Offset(x, size.height / 2))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderFooter(nav: ReaderNav, book: Book, page: PageColors, settings: Settings) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "5 pages left" gives way to "4" quietly: the page is the thing that moves.
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = footerText(nav, book, settings),
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            modifier = Modifier.weight(1f),
            label = "footer",
        ) { text ->
            Text(text, style = MaterialTheme.typography.labelMedium, color = page.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.percent, book.percent), style = MaterialTheme.typography.labelMedium, color = page.secondary, textAlign = TextAlign.End)
    }
}

@Composable
internal fun ReaderLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
}

@Composable
internal fun ReaderError(message: String) {
    // Nothing more is coming: the cover may open on the message.
    val nav = LocalReaderNav.current
    SideEffect { nav?.contentReady = true }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
