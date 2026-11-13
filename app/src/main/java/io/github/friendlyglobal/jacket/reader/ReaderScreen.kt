package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.friendlyglobal.jacket.ui.rememberBookFold
import io.github.friendlyglobal.jacket.data.PageLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.data.pageReadingMs
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.listening
import io.github.friendlyglobal.jacket.voices
import androidx.compose.ui.platform.LocalConfiguration
import io.github.friendlyglobal.jacket.ui.voices.SayItDialog
import io.github.friendlyglobal.jacket.ui.voices.SayItDraft
import io.github.friendlyglobal.jacket.ui.voices.SayItList
import io.github.friendlyglobal.jacket.ui.voices.VoicePicker
import io.github.friendlyglobal.jacket.ui.voices.sayingOf
import io.github.friendlyglobal.jacket.ui.voices.voiceLabel
import io.github.friendlyglobal.jacket.outer.OuterDisplay
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.LocalCoverTransition
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.SystemBarsHidden
import io.github.friendlyglobal.jacket.ui.motionRemoved
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import io.github.friendlyglobal.jacket.ui.fitOnOneLine
import io.github.friendlyglobal.jacket.ui.rememberScreenReader
import io.github.friendlyglobal.jacket.ui.rememberTimeToAct
import io.github.friendlyglobal.jacket.ui.screenReaderOn
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
 * steps through the whole book for EPUB and text, pages for PDF and comics.
 */
@Stable
class ReaderNav(
    /**
     * Audiobook mode: the player's state. It is the voice's own, which the app keeps (see
     * [Listening]): a book opened while it is being read aloud shows the player as it is.
     */
    val listen: ListenState = ListenState(),
) {
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
    /**
     * While the book is read aloud: the letters from the sentence the voice is on to the end of
     * its chapter (see [lettersLeftInChapter]); null while that isn't known. The player tells the
     * time left in the chapter by it, at the voice's own speed.
     */
    var voiceLettersLeft by mutableStateOf<Float?>(null)
    /** Where the last jump started from, offered back for a little while. */
    var cameFrom by mutableStateOf<Origin?>(null)
    var sectionName: (Int) -> String = { "" }
    /** Sections in the same group (e.g. chapter) share a value: the scrubber ticks between groups. */
    var sectionGroup: (Int) -> Int = { it }
    var jumpTo: (Int) -> Unit = {}
    var openToc: (TocItem) -> Unit = {}
    /** Previous/next buttons: by chapter for EPUB and text, by page otherwise. */
    var step: (Int) -> Unit = { jumpTo(section + it) }
    var canStep: (Int) -> Boolean = { section + it in 0 until sectionCount }

    /** Chapter starts as fractions of the book (0..1), drawn as marks on the scrubber. */
    var marks by mutableStateOf<List<Float>>(emptyList())

    /** The voice, to start and steer; null for formats that can't be read aloud. */
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
    /** A note of the book's whose number was tapped on the page, shown over it. */
    internal var note by mutableStateOf<ShownNote?>(null)
    /** A word selected on the page for "Say it this way": how the voice is to say it is asked. */
    var sayWord by mutableStateOf<String?>(null)
    /**
     * Highlights, with a note if asked, without anything being selected: the sentence being read
     * aloud, or else the first paragraph on the page. Selecting is a press and hold on the page,
     * which a screen reader and a keyboard can't do. Null for formats without highlights.
     */
    internal var highlightHere by mutableStateOf<(suspend (note: Boolean) -> Marked)?>(null)
    /** Set where a book that could have highlights can't on this phone, or has no words to highlight (a PDF: see [NoHighlights]). */
    internal var noHighlights by mutableStateOf<NoHighlights?>(null)
    /** Turns a page for the keys and the mouse wheel: +1 forward, -1 back. */
    var turnPage: (Int) -> Unit = { step(it) }
    /** The book reads right to left: the arrow keys go the other way round. */
    var rightToLeft by mutableStateOf(false)
    /** The book is laid out in pages (not one long scroll): a mouse wheel turns them. */
    var paged by mutableStateOf(false)

    /** The first page is on screen (or nothing more is coming): the opening cover may lift. */
    var contentReady by mutableStateOf(false)

    /** The book has opened: its cover is out of the way, and the page can ask for something (a locked PDF's password). */
    var opened by mutableStateOf(false)

    /** A tap on the page brings the controls up, or sends them away: for a page with no taps of its own (see [ReaderError]). */
    var toggleControls: () -> Unit = {}

    /**
     * When the reader last moved through the book themselves (uptime millis): a swipe or scroll on
     * the page, a page turned, a jump. Pages also move by themselves (re-flowing for the player, a
     * fold); only these count as the listener going elsewhere.
     */
    var movedAt = 0L

    /** The language the book itself says it is in (an EPUB does), for the voice picker; null when it doesn't say. */
    var bookLanguage by mutableStateOf<String?>(null)

    /** Read aloud, the player steps by page: a book of pages with no chapters to step by (a PDF without an outline). */
    var stepsByPage by mutableStateOf(false)
}

/**
 * The place a jump (contents, search, the scrubber) left from, and the way back. The pill names it
 * as precisely as the format allows: the chapter's name, else the page, else how far through.
 */
class Origin(
    val percent: Int,
    /** The chapter left from; empty where there's none. */
    val chapter: String = "",
    /** The page left from, counting from 1, for page-based formats; 0 otherwise. */
    val page: Int = 0,
    val back: () -> Unit,
)

/**
 * Nothing can be highlighted in the open book, and [why], in words for the reader: a PDF on a
 * phone whose Android can't hand over its words, or one with no words in it. [listed] is what
 * to say over highlights that are there all the same (made on another of the reader's devices):
 * they are listed, and can't be shown on the pages. Null where there can be none.
 */
internal class NoHighlights(val why: String, val listed: String?)

/** What came of highlighting without selecting (see [ReaderNav.highlightHere]). */
internal enum class Marked {
    /** It is highlighted (and, for a note, its editor is open). */
    DONE,

    /** It was highlighted already: that highlight is open to be changed. */
    ALREADY,

    /** There are no words there to highlight. */
    NOTHING,
}

/**
 * Highlighting without selecting, as the reader's controls offer it: what the two actions are
 * called just now (they name what will be marked: the sentence being read, or the first
 * paragraph on the page), and the doing of them.
 */
internal class QuickMarks(val highlight: String, val note: String, val onMark: (note: Boolean) -> Unit)

/**
 * Skipping by chapter from the player; by page in a book of pages that has no chapters and is read
 * aloud all the same (a PDF without an outline). Null for a book with neither.
 */
internal fun ReaderNav.chapterSteps(): ChapterSteps? = when {
    toc.isNotEmpty() -> ChapterSteps(title = chapter.ifBlank { title }, canStep = { canStep(it) }, onStep = { step(it) })
    stepsByPage -> ChapterSteps(
        title = title, canStep = { canStep(it) }, onStep = { step(it) },
        previousLabel = R.string.reader_previous_page, nextLabel = R.string.reader_next_page,
    )
    else -> null
}

/** The open book's [ReaderNav], for views that only need to report in (see [ReaderError]). */
internal val LocalReaderNav = compositionLocalOf<ReaderNav?> { null }

/** A page of pictures (a PDF's, a comic's) taken as the reader's place; [back] when they went back through the pages to get to it. */
typealias ProgressSink = (position: Int, offset: Float, progress: Float, sections: Int, back: Boolean) -> Unit

private enum class Sheet { NONE, CONTENTS, TEXT, SEARCH, VOICE, WORDS }

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
    // "Say it this way": the words the voice says the listener's way, in this book and in every book.
    val sayings = remember(libraryState.sayings, book.id) { libraryState.sayings.filter { it.bookId == book.id || it.bookId == null } }
    val respellings = remember(libraryState.sayings, book.id) { libraryState.respellingsFor(book.id) }
    var saying by remember(book.id) { mutableStateOf<SayItDraft?>(null) }
    val haptics = rememberHaptics()
    // The voice follows the book's language; a text file's was told from its words when it was
    // added (older ones go by the phone's language).
    val voices = context.voices
    val phoneLanguage = LocalConfiguration.current.locales[0]?.toLanguageTag()
    // (So is a PDF whose language couldn't be told from its words.)
    val readIn = book.language ?: if (book.format == BookFormat.TXT || book.format == BookFormat.PDF || book.format == BookFormat.FB2) phoneLanguage else null
    val voice = remember(settings.voice, settings.voices, readIn) { voices.choose(readIn, settings) }
    // On the player: who is reading, and whether the phone is keeping up with them.
    val health by voices.health.collectAsStateWithLifecycle()
    val voiceLabel = voiceLabel(readIn, settings)
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    val outerActive by outer.active.collectAsStateWithLifecycle()
    // The voice is the app's, and may be reading this book already (started from a headphone
    // button, the notification, the widget or the car): the reader then opens on that player.
    val readAloud = context.listening
    val nav = remember(book.id) { ReaderNav(readAloud.stateFor(book.id)) }
    // Opening a book ends listening to any other; a voice still reading this one when its screen
    // goes (closing the book stops it first) reads on without it.
    DisposableEffect(book.id) {
        readAloud.entered(book.id)
        onDispose { readAloud.left(book.id, nav.listen) }
    }
    // "Dark pages at night": with the phone in dark theme, Paper gives way to Dark.
    val page = rememberPageColors(
        if (settings.nightPages && settings.pageColor == PageColor.PAPER && isSystemInDarkTheme()) PageColor.DARK else settings.pageColor,
    )
    var chrome by rememberSaveable(book.id) { mutableStateOf(true) }
    // The toolbar by itself, without the bars around it: the player, shown for a moment when
    // listening starts so its controls are found, and while it turns back into the reading
    // toolbar when listening ends.
    var peek by remember(book.id) { mutableStateOf(false) }
    val barUp = chrome || peek
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    // Once you touch the controls, they stay until you dismiss them.
    var touchedChrome by remember(book.id) { mutableStateOf(false) }
    // They stay, too, for anyone finding them without eyes or fingers: under a screen reader, and
    // while a keyboard's (or a remote's) focus is on one of them. Otherwise they would be gone
    // before they could be reached. Everything that leaves by itself gives the time Android has
    // been asked for (see rememberTimeToAct).
    val screenReader by rememberScreenReader()
    val timeToAct = rememberTimeToAct()
    val controlFocus = remember(book.id) { ControlFocus() }
    val firstControl = remember { FocusRequester() }
    var focusControls by remember(book.id) { mutableIntStateOf(0) }
    // While listening they tuck away by themselves instead: when they were last touched, and where they sit.
    val controls = remember(book.id) { ControlsWatch() }
    // The menu for highlighting without selecting is open (see where it is offered, below).
    var markMenu by remember(book.id) { mutableStateOf(false) }
    val noticeTouches = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                touchedChrome = true
                controls.touchedAt = SystemClock.uptimeMillis()
                controls.pressed = event.changes.any { it.pressed }
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
            delay(timeToAct(CHROME_SHOWN_MS))
            if (!touchedChrome && !context.screenReaderOn() && !controlFocus.held) chrome = false
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
    SystemBarsHidden(hidden = coverUp || (!barUp && sheet == Sheet.NONE && !closing))
    // A sheet is a window of its own: it would sit on top of the cover.
    LaunchedEffect(coverUp) {
        if (coverUp) {
            sheet = Sheet.NONE
            nav.note = null
            saying = null
        }
    }
    val setRate: (Float) -> Unit = { rate ->
        onSettings { it.copy(listenRate = rate) }
        nav.listenController?.setRate(rate)
    }

    // This book's highlights and notes as a file, wherever the system's file picker puts it.
    val resources = LocalResources.current
    val highlightsFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(HIGHLIGHTS_MIME)) { uri ->
        if (uri != null) scope.launch {
            val text = highlightsText(book, highlightsOf(annotations), asFile = true) { highlightPlace(resources, it) }
            val saved = runCatching { saveText(context, uri, text) }
            if (saved.isSuccess) haptics.confirm() else haptics.reject()
            val message = saved.fold(
                { resources.getString(R.string.highlights_saved) },
                { resources.getString(R.string.highlights_save_failed, it.message.orEmpty()) },
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Closing the book leaves the voice reading, as going Home does: the library says it is, and
    // pauses it, as do the notification, a headset and the widget (see Listening). Opening the
    // book again finds the page at the sentence it has reached. Every book that is read aloud
    // (EPUB, PDF, FB2, text) is read on with no reader open, so none has to stop here.
    val close: () -> Unit = {
        if (!closing) {
            // First, so the cover is made knowing where in the library it will land.
            onClose()
            closing = true
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

    // A keyboard, a remote, a Bluetooth page-turner or a mouse wheel turns the pages, and Space
    // pauses the voice (see ReaderKeys). The volume keys do too when that's switched on (not while
    // listening, when they're for volume). Nothing turns under the cover of a folded phone, where
    // pages would turn unseen.
    val pageFocus = remember { PageFocus() }
    val volumeKeys by rememberUpdatedState(settings.volumeKeys)
    val pagesInSight by rememberUpdatedState(!coverUp && !closing)
    // Tab has asked for the controls: their first button takes the keyboard's focus as soon as it
    // is there (the bar may still be on its way in).
    LaunchedEffect(focusControls) {
        if (focusControls == 0) return@LaunchedEffect
        repeat(FOCUS_TRIES) {
            withFrameNanos { }
            runCatching { firstControl.requestFocus() }
            if (controlFocus.held) return@LaunchedEffect
        }
    }
    DisposableEffect(nav) {
        val target = ReaderKeys.Target(
            context = {
                KeyContext(
                    rightToLeft = nav.rightToLeft,
                    listening = nav.listen.active && nav.listenController != null,
                    volumeKeys = volumeKeys,
                    pages = pagesInSight,
                    paged = nav.paged,
                    onControl = controlFocus.held,
                )
            },
            perform = { command ->
                // (A control that has the focus keeps it: Page Down under a button turns the page, and no more.)
                if ((command == ReaderCommand.NEXT_PAGE || command == ReaderCommand.PREVIOUS_PAGE) && !controlFocus.held) pageFocus.take()
                when (command) {
                    ReaderCommand.NEXT_PAGE -> nav.turnPage(1)
                    ReaderCommand.PREVIOUS_PAGE -> nav.turnPage(-1)
                    ReaderCommand.PLAY_PAUSE -> nav.listenController?.let {
                        haptics.toggle(!nav.listen.playing)
                        it.toggle()
                    }
                    ReaderCommand.FOCUS_CONTROLS -> {
                        touchedChrome = true
                        chrome = true
                        focusControls++
                    }
                    ReaderCommand.TOGGLE_CONTROLS -> nav.toggleControls()
                    ReaderCommand.LEAVE_CONTROLS -> {
                        chrome = false
                        peek = false
                        pageFocus.take()
                    }
                    ReaderCommand.NOTHING -> {}
                }
            },
        )
        ReaderKeys.target = target
        // Only clear our own: opening the next book briefly overlaps the outgoing reader.
        onDispose { if (ReaderKeys.target === target) ReaderKeys.target = null }
    }

    // (The sleep timer runs with the voice, and the widget's skip and sleep buttons reach it there,
    // whether or not this screen is open: see ReadAloud and Listening.)
    val listen = nav.listen

    // While the book is read aloud, the controls don't stay over the text. They tuck away into the
    // footer's mini player a few seconds after the last touch, and sooner than that when they sit
    // over the line being read: the words the voice is on are never kept out of sight.
    val listening = listen.active && nav.listenController != null
    LaunchedEffect(listening, barUp, sheet == Sheet.NONE) {
        if (!barUp) {
            // Gone: whatever was known of them is out of date (a finger can't still be on them).
            controls.covering = false
            controls.pressed = false
        }
        if (!listening || !barUp || sheet != Sheet.NONE) return@LaunchedEffect
        val stays = timeToAct(LISTEN_CONTROLS_MS)
        // Arriving counts as a touch.
        controls.touchedAt = SystemClock.uptimeMillis()
        while (true) {
            delay(CONTROLS_POLL_MS)
            val now = SystemClock.uptimeMillis()
            // They stay under a finger, under a menu of their own, while the voice gets ready (the
            // player is what says so), for someone finding their way by touch with TalkBack, and
            // while a keyboard has its focus on one of them.
            // (So do they under the highlight menu, which hangs from the top bar.)
            if (controls.pressed || listen.held || listen.preparing || context.screenReaderOn() || controlFocus.held || markMenu) {
                controls.touchedAt = now
                continue
            }
            val line = if (listen.playing) nav.listenController?.spokenLine() else null
            controls.covering = line != null && ((chrome && controls.top.overlaps(line)) || controls.bottom.overlaps(line))
            if (now - controls.touchedAt >= if (controls.covering) COVERED_CONTROLS_MS else stays) break
        }
        chrome = false
        peek = false
    }
    // Listening over (closed, or the book ran out): the player turns back into the reading toolbar
    // in front of you, then leaves the page to its footer, where the sentence the voice stopped on
    // is marked for a moment. If the bar sits over that sentence it goes at once.
    var listened by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(listening) {
        if (listening) {
            listened = true
            return@LaunchedEffect
        }
        if (!listened) return@LaunchedEffect
        listened = false
        val covering = controls.covering
        controls.covering = false
        if (!barUp) return@LaunchedEffect
        val stoppedAt = SystemClock.uptimeMillis()
        if (!covering) delay(STOPPED_CONTROLS_MS)
        // Sent away by a tap on the page meanwhile: nothing left to do.
        if (!chrome && !peek) return@LaunchedEffect
        // Taken hold of meanwhile: they are the reading controls now, whole, to dismiss as ever.
        chrome = controls.pressed || controls.touchedAt > stoppedAt || context.screenReaderOn() || controlFocus.held
        peek = false
    }
    // A voice the phone can't keep up with says so on the player: bring it up to be seen.
    val struggling = voice != null && health.slow
    LaunchedEffect(struggling, listening) {
        if (!struggling) listen.warned = false else if (listening && !listen.warned && !chrome) peek = true
    }

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
                sleepMinutes = if (nav.listen.sleep.timed) nav.listen.sleepLeft else 0,
                sleepAtChapterEnd = nav.listen.sleep == SleepTimer.CHAPTER,
            )
        }.collect(library::report)
    }

    // Reading time: how long each page was up before the next (a page left open, or flipped
    // past, doesn't count), for the book's pace and today's total. Listening is counted by the
    // voice itself, as it speaks (see Listening): here, the time the player was up is taken off
    // a page's, so no minute is counted twice (see pageReadingMs), and a page listened to at
    // all says nothing of the reader's own pace.
    LaunchedEffect(book.id, nav) {
        var since = SystemClock.elapsedRealtime()
        var from = -1f
        var listened = readAloud.listeningMs(book.id)
        snapshotFlow { nav.pageKey }.collect {
            val now = SystemClock.elapsedRealtime()
            val progress = library.state.value.find(book.id)?.progress ?: return@collect
            val listenedNow = readAloud.listeningMs(book.id)
            val aloud = listenedNow - listened
            val read = if (from >= 0f) pageReadingMs(now - since, aloud) else 0L
            if (read > 0L) library.addReading(book.id, read, if (aloud <= 0L) progress - from else 0f)
            since = now
            from = progress
            listened = listenedNow
        }
    }

    // The "Listen" shortcut: start reading aloud once the book is open on its page.
    LaunchedEffect(autoListen) {
        if (!autoListen) return@LaunchedEffect
        snapshotFlow { nav.contentReady && nav.listenController != null && opening.settled }.first { it }
        // (Under a screen reader the whole of the controls stays, the way out of the book with them.)
        if (!context.screenReaderOn()) chrome = false
        nav.listenController?.start()
        peek = nav.listen.active
        onAutoListened()
    }

    // Brightness and warmth, by swiping the page's edges.
    val levels = remember { EdgeLevels(settings.brightness, settings.warmth) }
    val swipes by rememberUpdatedState(settings.edgeSwipes)
    // They are set from the text settings too (a slider each): the page follows those as well.
    LaunchedEffect(settings.brightness, settings.warmth) {
        if (levels.active == 0) {
            levels.brightness = settings.brightness
            levels.warmth = settings.warmth
        }
    }
    ReaderBrightness(levels, enabled = settings.edgeSwipes)

    // Tabletop: half-open with the fold lying flat, the page sits above it and a desk of controls below.
    val fold by rememberTabletopFold(watch = settings.tabletop)
    var windowHeight by remember { mutableIntStateOf(0) }
    var windowWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Book posture: half-open with the fold upright, the fold is the spine between the two pages
    // of the spread (see BookPosture). The pages keep clear of it, and so do the controls: they
    // sit on the right-hand page, as they would on a phone of that width. Only where there are
    // two pages to part: a book of pages, not one scrolled, nor a PDF's or a comic's single page.
    val pagedBook = book.format.isWords && settings.layout == PageLayout.PAGES
    val bookFold by rememberBookFold(watch = pagedBook)
    val spine = remember(bookFold, windowWidth, windowHeight, density, coverUp) {
        if (coverUp) null else BookPosture.spine(bookFold, windowWidth, windowHeight, with(density) { BookPosture.OFF_CENTRE.dp.toPx() })
    }
    // How far in from the left the controls start: the far side of the fold, or nowhere. They
    // move there, and back when the phone is opened flat, on the theme's own spring (at once,
    // with animations removed).
    val towardSpine by animateDpAsState(
        spine?.let { with(density) { it.right.toDp() } } ?: 0.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "beside-spine",
    )
    // (The spring runs a little past where it is going: on the way back, never past the window's edge.)
    val besideSpine = towardSpine.coerceAtLeast(0.dp)
    // The width of one page, which is all the room anything has that must not cross the fold.
    val onePage = spine?.let { with(density) { minOf(it.left, windowWidth - it.right).toDp() } }
    val belowFold = fold?.let { with(density) { (windowHeight - it.top).coerceAtLeast(0).toDp() } } ?: 0.dp
    val desk = fold?.let { with(density) { (windowHeight - it.bottom).coerceAtLeast(0).toDp() } } ?: 0.dp

    // A highlight tapped on the page opens its editor.
    val editing = annotations.firstOrNull { it.id == nav.editing }
    var finishedDismissed by rememberSaveable(book.id) { mutableStateOf(false) }
    val next = remember(libraryState.books, book.id) {
        libraryState.books.filter { it.id != book.id && !it.isFinished && library.missing.value.contains(it.id).not() }
            .maxWithOrNull(compareBy<Book>({ it.lastOpenedAt }, { it.addedAt }))
    }

    val onProgress: ProgressSink = { position, offset, progress, sections, back ->
        library.updatePage(book.id, position, offset, progress, sections, back)
    }
    // A tap on the page: the full controls, or the page to itself again (the lone toolbar goes too).
    val toggleChrome = {
        touchedChrome = true
        controls.touchedAt = SystemClock.uptimeMillis()
        if (chrome || peek) {
            chrome = false
            peek = false
        } else {
            chrome = true
        }
    }
    nav.toggleControls = toggleChrome
    SideEffect { nav.opened = opening.settled }

    // Turning a page is a swipe, a curl under the finger or a tap on the page's edge: none of which
    // a screen reader or a switch can do. The same turns are offered as actions on whatever of the
    // reader's own stands for the page just then (the footer line, the chapter slider), beside
    // bringing the controls up and sending them away, which is a tap on the page.
    val nextPageLabel = stringResource(R.string.reader_next_page)
    val previousPageLabel = stringResource(R.string.reader_previous_page)
    val hideControlsLabel = stringResource(R.string.reader_hide_controls)
    val pageTurns = remember(nav, nextPageLabel, previousPageLabel) {
        listOf(
            CustomAccessibilityAction(nextPageLabel) { nav.turnPage(1); true },
            CustomAccessibilityAction(previousPageLabel) { nav.turnPage(-1); true },
        )
    }
    val hideControls = remember(hideControlsLabel) {
        CustomAccessibilityAction(hideControlsLabel) {
            chrome = false
            peek = false
            true
        }
    }
    // Highlights and notes are made by selecting words: a press and hold on the page, which a
    // screen reader and a keyboard can't do. So the same two things are offered without it, on a
    // button of the top bar (Tab reaches it) and as actions wherever the page's own are: they
    // mark the sentence being read aloud, or else the first paragraph on the page, and say so.
    val highlightHere = nav.highlightHere
    val readingAloud = nav.listen.active && nav.listenController != null
    val highlightLabel = stringResource(if (readingAloud) R.string.reader_highlight_sentence else R.string.reader_highlight_paragraph)
    val noteLabel = stringResource(if (readingAloud) R.string.reader_note_sentence else R.string.reader_note_paragraph)
    val marks = remember(highlightHere, highlightLabel, noteLabel) {
        if (highlightHere == null) null else QuickMarks(highlightLabel, noteLabel) { note ->
            scope.launch {
                when (highlightHere(note)) {
                    Marked.DONE -> {
                        haptics.confirm()
                        // (A note's editor opens, and says what was highlighted itself.)
                        if (!note) Toast.makeText(context, R.string.reader_highlighted, Toast.LENGTH_SHORT).show()
                    }
                    Marked.ALREADY -> haptics.tick()
                    Marked.NOTHING -> {
                        haptics.reject()
                        Toast.makeText(context, R.string.reader_nothing_to_highlight, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    val markActions = remember(marks) {
        marks?.let { listOf(CustomAccessibilityAction(it.highlight) { it.onMark(false); true }, CustomAccessibilityAction(it.note) { it.onMark(true); true }) }.orEmpty()
    }
    // Chosen from a sheet, a place in the book is somewhere to read: the controls step aside for
    // it. Not under a screen reader, whose place among them would go with them.
    val stepAside = { if (!context.screenReaderOn()) chrome = false }

    val motion = MaterialTheme.motionScheme
    Box(
        Modifier.fillMaxSize().background(page.background).onSizeChanged {
            windowHeight = it.height
            windowWidth = it.width
        },
    ) {
        FocusHolder(pageFocus)
        // The page and its controls. They grow with the window the backdrop opens on them: a page
        // the size of the book under the cover, the whole screen once it is open.
        Box(
            Modifier
                .fillMaxSize()
                // Under the cover of a phone folded shut the page and its controls are out of
                // sight, and out of a screen reader's reach with it: the cover is all there is.
                .then(if (coverUp) Modifier.clearAndSetSemantics {} else Modifier)
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
                        // A text file and an FB2 book are read through pages made of them, the same way as an EPUB.
                        BookFormat.EPUB, BookFormat.TXT, BookFormat.FB2 -> EpubReader(book, settings, page, nav, haptics, annotations, respellings, spine, toggleChrome)
                        BookFormat.PDF -> PdfReader(book, settings, page, nav, haptics, annotations, respellings, onProgress, toggleChrome)
                        BookFormat.CBZ -> ComicReader(book, page, nav, haptics, onProgress, toggleChrome)
                    }
                }
            }
            // The shadow a book has down its middle, where the pages dip into the spine.
            SpineShadow(spine, dark = page.background.luminance() < 0.4f)
            Warmth(levels, enabled = settings.edgeSwipes)
            // The page fades in under the indicator when it does arrive, rather than popping up.
            AnimatedVisibility(visible = waiting, enter = EnterTransition.None, exit = fadeOut(motion.slowEffectsSpec())) {
                Box(Modifier.fillMaxSize().background(page.background)) { ReaderLoading() }
            }

            // The footer line, in the room every page keeps under it: its quiet words while you
            // read, the mini player while you listen. The toolbar sits over it when it is up.
            val playerVoice = ListenVoice(voiceLabel, natural = voice != null, struggling = struggling) { sheet = Sheet.VOICE }
            // It gives way at once to the toolbar, and hops back up once the toolbar has dropped
            // away, rather than through it.
            val footerUp by produceState(!barUp, barUp) {
                if (!barUp && !motionRemoved()) delay(FOOTER_GAP_MS)
                value = !barUp
            }
            AnimatedVisibility(
                visible = footerUp,
                enter = slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomCenter).keyboardControls(controlFocus, "footer"),
            ) {
                ReaderFooter(
                    nav, book, page, settings, playerVoice, haptics,
                    onePage = onePage,
                    pageTurns = pageTurns + markActions,
                    onControls = {
                        touchedChrome = true
                        chrome = true
                    },
                )
            }

            // After a jump: the way back to where you were, for a little while.
            JumpBack(
                nav, haptics, visible = opening.settled,
                modifier = Modifier.align(Alignment.TopCenter).absolutePadding(left = besideSpine).keyboardControls(controlFocus, "jump-back"),
            )
            // Another of the reader's devices is further on in this book: the offer to go there.
            CatchUpOffer(
                book, nav, haptics, visible = opening.settled && !closing,
                modifier = Modifier.align(Alignment.TopCenter).absolutePadding(left = besideSpine).keyboardControls(controlFocus, "catch-up"),
            )
            EdgeLevel(levels)

            AnimatedVisibility(
                visible = chrome && entrance >= 1,
                enter = slideInVertically(motion.defaultSpatialSpec()) { -it } + fadeIn(motion.defaultEffectsSpec()),
                exit = slideOutVertically(motion.fastSpatialSpec()) { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).then(noticeTouches)
                    .keyboardControls(controlFocus, "top")
                    .onGloballyPositioned { controls.top = it.boundsInWindow() },
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
                    firstControl = firstControl,
                    hideControls = hideControls,
                    marks = marks,
                    markActions = markActions,
                    markMenu = markMenu,
                    onMarkMenu = { markMenu = it },
                    // The book's name stays on its own page: the back arrow's width in from the edge.
                    titleWidth = onePage?.let { (it - TITLE_INSET).coerceAtLeast(TITLE_INSET) },
                )
            }

            // The toolbar floats over the page in the same place whether you read or listen: the
            // reading bar and the player hop out and in there (see ReaderToolbar). It floats, so it
            // can afford more spring than the bar pinned to the top edge. Leaving, it drops away in
            // sight (not faded out before it has moved), and the footer hops up after it.
            AnimatedVisibility(
                visible = barUp && entrance >= 2,
                enter = slideInVertically(motion.fastSpatialSpec()) { it } +
                    scaleIn(motion.fastSpatialSpec(), initialScale = 0.88f, transformOrigin = BottomMiddle) + fadeIn(motion.fastEffectsSpec()),
                exit = slideOutVertically(motion.fastSpatialSpec()) { it } +
                    scaleOut(motion.fastSpatialSpec(), targetScale = 0.88f, transformOrigin = BottomMiddle) + fadeOut(motion.defaultEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomCenter).absolutePadding(left = besideSpine).then(noticeTouches).keyboardControls(controlFocus, "bottom"),
            ) {
                // On one page of a book held half-open, the bar has that page's width to go by.
                CompositionLocalProvider(LocalControlsWidth provides (onePage ?: Dp.Unspecified)) {
                ReaderBottomBar(
                    nav = nav, book = book, haptics = haptics,
                    showScrubber = chrome,
                    pageActions = pageTurns + markActions + hideControls,
                    voice = playerVoice,
                    rate = settings.listenRate,
                    onRate = setRate,
                    onListen = {
                        haptics.confirm()
                        nav.listenController?.start()
                        // The bars around the toolbar leave; it stays a moment as the player, so
                        // its controls are seen, then tucks into the footer. (A voice that can't
                        // start says why, and nothing changes.) Under a screen reader the bars
                        // stay too: the way back to the library is on them.
                        if (nav.listen.active && !context.screenReaderOn()) {
                            chrome = false
                            peek = true
                        }
                    },
                    onContents = { sheet = Sheet.CONTENTS },
                    onText = { sheet = Sheet.TEXT },
                    onPlaced = { controls.bottom = it },
                )
                }
            }

            // The last page: celebrate, and offer what's next.
            // (Under a screen reader the controls never leave by themselves, so it doesn't wait for
            // them to: it sits above them.)
            AnimatedVisibility(
                visible = nav.atEnd && !finishedDismissed && (!barUp || screenReader) && !nav.listen.active && opening.settled,
                enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).absolutePadding(left = besideSpine).navigationBarsPadding()
                    .padding(bottom = if (barUp) FINISHED_OVER_CONTROLS else 48.dp)
                    .keyboardControls(controlFocus, "finished"),
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
            TabletopDesk(
                nav, book, settings, setRate, haptics,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(desk).keyboardControls(controlFocus, "desk"),
            )
        }
        OpeningBackdrop(opening, MaterialTheme.colorScheme.primaryContainer)
        if (!opening.settled) OpeningCover(book, opening)
        ClosedCover(
            coverUp, book, nav, settings, setRate, haptics, onReadHere = onReadShut, onLeave = close,
            modifier = Modifier.keyboardControls(controlFocus, "cover"),
        )
    }

    // A sheet comes up on the right-hand page too, not across the fold.
    val sheetPage = if (spine != null && onePage != null) {
        with(density) { SheetPage(width = onePage, offset = ((spine.right + windowWidth) / 2f - windowWidth / 2f).toDp()) }
    } else {
        null
    }
    CompositionLocalProvider(LocalSheetPage provides sheetPage) {
    when (sheet) {
        Sheet.CONTENTS -> ReaderSheet(onGone = { sheet = Sheet.NONE }) { leave ->
            NotesSheet(
                nav = nav,
                annotations = annotations,
                haptics = haptics,
                // You picked where to go: get out of the way and let them read. The jump waits for
                // the sheet to leave: loading a chapter would stall it halfway down.
                onPickToc = { item ->
                    stepAside()
                    leave { nav.openToc(item) }
                },
                onPick = { item ->
                    stepAside()
                    leave { nav.goTo(item) }
                },
                onEdit = { item -> leave { nav.editing = item.id } },
                onDelete = { item -> library.deleteAnnotation(item.id) },
                onShare = { shareHighlights(context, book, annotations) },
                onSave = { highlightsFile.launch(highlightsFileName(book.title, resources.getString(R.string.notes_tab_highlights))) },
                noHighlights = nav.noHighlights?.why,
                highlightsNote = nav.noHighlights?.listed,
            )
        }
        Sheet.SEARCH -> ReaderSheet(onGone = { sheet = Sheet.NONE }, fullHeight = true) { leave ->
            nav.search?.let { search ->
                SearchPanel(search, haptics) { hit ->
                    stepAside()
                    leave { search.show(hit) }
                }
            }
        }
        Sheet.TEXT -> ReaderSheet(onGone = { sheet = Sheet.NONE }) {
            ReadingControls(
                settings, onSettings,
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
                forEpub = book.format.isWords,
                lightLevels = true,
            )
        }
        Sheet.VOICE -> ReaderSheet(onGone = { sheet = Sheet.NONE }) { leave ->
            VoicePicker(
                language = readIn,
                settings = settings,
                onSettings = onSettings,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
                bookLanguage = nav.bookLanguage,
                onLanguage = { picked -> library.setLanguage(book.id, picked ?: nav.bookLanguage) },
                onWords = { leave { sheet = Sheet.WORDS } },
            )
        }
        Sheet.WORDS -> ReaderSheet(onGone = { sheet = Sheet.NONE }) {
            val everyBook = stringResource(R.string.say_scope_all)
            SayItList(
                sayings = sayings,
                // This book's own need no saying where they apply.
                scope = { if (it.bookId == null) everyBook else null },
                haptics = haptics,
                onAdd = { saying = SayItDraft() },
                onEdit = { saying = SayItDraft(it) },
                onDelete = { library.deleteSaying(it.id) },
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            )
        }
        Sheet.NONE -> {}
    }

    // A word selected on the page, sent to "Say it this way": the way it is said now, if it has one, to change.
    LaunchedEffect(nav.sayWord) {
        val word = nav.sayWord ?: return@LaunchedEffect
        nav.sayWord = null
        saying = SayItDraft(sayings.sayingOf(word, book.id), word)
    }
    saying?.let { draft ->
        SayItDialog(
            draft = draft,
            bookId = book.id,
            voice = voice,
            language = readIn,
            haptics = haptics,
            onSave = {
                library.saveSaying(it)
                saying = null
            },
            onDelete = {
                library.deleteSaying(it.id)
                saying = null
            },
            onDismiss = { saying = null },
        )
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

    // A note's number tapped on the page: the note over the page, and the way to it in its place.
    nav.note?.let { shown ->
        ReaderSheet(onGone = { nav.note = null }) { leave ->
            NoteSheet(
                shown.note, settings, haptics,
                onGoTo = {
                    stepAside()
                    leave { shown.goTo() }
                },
            )
        }
    }
    }
}

/** The room the book's name has on the top bar, short of a page's width: the back arrow before it, and a margin after. */
private val TITLE_INSET = 72.dp

/** How far the spine's shadow reaches onto each page. */
private val SPINE_SHADOW = 20.dp

/**
 * Book posture: the soft shadow down the middle of an open book, where its pages dip into the
 * spine. It tells the eye the two halves are two pages. It comes and goes with the posture on the
 * theme's own fade, and is never strong enough to read as a line: under words it would only be a
 * smudge, and the words keep clear of it anyway.
 */
@Composable
private fun SpineShadow(spine: IntRect?, dark: Boolean) {
    val strength by animateFloatAsState(if (spine != null) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "spine-shadow")
    // Kept while it fades, after the phone has been opened flat.
    var shown by remember { mutableStateOf(spine) }
    if (spine != null) shown = spine
    val at = shown ?: return
    if (strength <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        val reach = SPINE_SHADOW.toPx()
        val shade = Color.Black.copy(alpha = (if (dark) 0.3f else 0.06f) * strength)
        val left = at.left.toFloat()
        val right = at.right.toFloat()
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, shade), startX = left - reach, endX = left),
            topLeft = Offset(left - reach, 0f), size = Size(reach, size.height),
        )
        // A hinge with some width to it is in shadow all the way across.
        if (right > left) drawRect(shade, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
        drawRect(
            Brush.horizontalGradient(listOf(shade, Color.Transparent), startX = right, endX = right + reach),
            topLeft = Offset(right, 0f), size = Size(reach, size.height),
        )
    }
}

/** Where the reader's sheets come up while the phone is held half-open like a book: one page wide, and that far off the middle of the window. */
private class SheetPage(val width: Dp, val offset: Dp)

private val LocalSheetPage = compositionLocalOf<SheetPage?> { null }

/** The unseen view that keeps the window's focus for the reader (see [FocusHolder]). */
private class PageFocus {
    var view: View? = null

    fun take() {
        view?.takeIf { it.isAttachedToWindow && !it.isFocused }?.requestFocus()
    }
}

/**
 * Keeps the keys coming while a book is open. The first key pressed after fingers were in use is
 * the one that takes the window out of touch mode, and Android spends it on choosing something to
 * focus, unless a plain view has the focus already. After a touch that is never so here: the focus
 * is with the page (Readium's pager, which hands it on to its pages), with the screen as a whole,
 * or with nothing. The first press of a page-turner after every touch would be lost.
 *
 * Android says it is leaving touch mode just before it looks at the focus. This is something for
 * the window to have in focus at that moment: a view of one pixel, unseen, that TalkBack passes
 * over, and that takes the focus then. The keys themselves are taken by the activity (see
 * [ReaderKeys]). Fingers never lose the focus to it: a selection on the page keeps its handles.
 *
 * A page turned by a key takes the focus here too: left with the page, the page would ring its
 * first link in the colour of the keyboard's focus on every page turned.
 */
@Composable
private fun FocusHolder(focus: PageFocus) {
    AndroidView(
        factory = { context ->
            View(context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                defaultFocusHighlightEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                focus.view = this
            }
        },
        modifier = Modifier.size(1.dp),
    )
    val window = LocalView.current
    DisposableEffect(window) {
        val leavingTouch = ViewTreeObserver.OnTouchModeChangeListener { inTouchMode ->
            // Not from a field being typed in: its keys are its own.
            if (!inTouchMode && window.rootView.findFocus()?.onCheckIsTextEditor() != true) focus.take()
        }
        val observer = window.viewTreeObserver
        observer.addOnTouchModeChangeListener(leavingTouch)
        onDispose { if (observer.isAlive) observer.removeOnTouchModeChangeListener(leavingTouch) }
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
    val onPage = LocalSheetPage.current
    ModalBottomSheet(
        onDismissRequest = onGone,
        sheetState = state,
        // Held half-open like a book, on the right-hand page: not across the fold.
        modifier = if (onPage != null) Modifier.absoluteOffset(x = onPage.offset) else Modifier,
        sheetMaxWidth = onPage?.width ?: BottomSheetDefaults.SheetMaxWidth,
    ) { content(leave) }
}

private val AnyHeight = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
private val FullHeightOnly = setOf(SheetValue.Hidden, SheetValue.Expanded)

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
                    chapters = nav.chapterSteps(),
                )
            } else {
                Text(nav.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(footerText(nav, book, settings), stringResource(R.string.percent, book.percent)).filter { it.isNotBlank() }.joinToString(stringResource(R.string.separator)),
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

/** Everything highlighted in [book], with its notes, as text for another app: a quote per paragraph (see [highlightsText]). */
private fun shareHighlights(context: android.content.Context, book: Book, annotations: List<Annotation>) {
    val highlights = highlightsOf(annotations)
    if (highlights.isEmpty()) return
    val text = highlightsText(book, highlights) { highlightPlace(context.resources, it) }
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

/**
 * What's left of the chapter for the mini player: the minutes the voice still needs (with "Time
 * left" on), told from the sentence it is on to the chapter's end and how fast it speaks (see
 * VoicePace.kt), not from the page in view or the reader's own pace. Where that can't be told (a
 * PDF, a book without chapters, before the first sentence) it says what the footer would of the pages.
 */
@Composable
private fun listeningLeft(nav: ReaderNav, book: Book, settings: Settings): String {
    val minutes = nav.voiceLettersLeft.takeIf { settings.timeLeft }
        ?.let { voiceMinutesLeft(it, nav.listen.lettersPerMs, settings.listenRate, book.language ?: nav.bookLanguage) }
    return if (minutes != null) stringResource(R.string.reader_footer_time_left, minutes) else nav.footer
}

/**
 * Whether the keyboard's focus is on one of the reader's own controls, whichever part of the
 * screen they are on. While it is, the arrows and Space are that control's (see [readerCommand]),
 * and the controls stay where they are.
 */
@Stable
internal class ControlFocus {
    private val parts = mutableStateMapOf<String, Unit>()

    val held: Boolean get() = parts.isNotEmpty()

    fun set(part: String, focused: Boolean) {
        if (focused) parts[part] = Unit else parts.remove(part)
    }
}

/** Marks [part] of the reader's controls, so the focus being anywhere inside it is known to [focus]. */
@Composable
internal fun Modifier.keyboardControls(focus: ControlFocus, part: String): Modifier {
    // A part that leaves the screen with the focus in it takes the focus with it.
    DisposableEffect(focus, part) { onDispose { focus.set(part, false) } }
    return onFocusChanged { focus.set(part, it.hasFocus) }
}

/** How many frames the controls' first button is given to arrive and take the focus, after Tab asks for it. */
private const val FOCUS_TRIES = 30

/** Room for the controls under the end-of-book card, when both are up. */
private val FINISHED_OVER_CONTROLS = 232.dp

/** What the tucking away of the controls goes by, while listening: their last touch, and where they sit in the window. */
private class ControlsWatch {
    var touchedAt = 0L
    var pressed = false
    var top = Rect.Zero
    var bottom = Rect.Zero
    /** They sit over the line being read aloud. */
    var covering = false
}


/** While listening, the controls tuck away this long after their last touch... */
private const val LISTEN_CONTROLS_MS = 4_000L

/** ...or this long after it, when they sit over the line being read. */
private const val COVERED_CONTROLS_MS = 2_000L

/** How often they check, while they are up. */
private const val CONTROLS_POLL_MS = 250L

/**
 * When listening ends, the toolbar is seen turning back into the reading one for this long before
 * it leaves: the mark on the sentence the voice stopped on outlasts it by about a second.
 */
private const val STOPPED_CONTROLS_MS = 1_000L

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

/** The footer hops back up this long after the toolbar starts dropping away. */
private const val FOOTER_GAP_MS = 120L

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
    /** Takes the keyboard's focus when Tab asks for the controls. */
    firstControl: FocusRequester,
    /** Sends the controls away: for a screen reader, which can't tap the page to do it. */
    hideControls: CustomAccessibilityAction,
    /** Highlighting, and adding a note, without selecting anything; null where there are no highlights (a PDF, a comic). */
    marks: QuickMarks? = null,
    /** The same two, as a screen reader's actions on the book's name. */
    markActions: List<CustomAccessibilityAction> = emptyList(),
    /** Their menu is open, and the asking for it to open or close. */
    markMenu: Boolean = false,
    onMarkMenu: (Boolean) -> Unit = {},
    /** The most room the book's name may take, where it mustn't run across a fold (see [BookPosture]); null for all there is. */
    titleWidth: Dp? = null,
) {
    TopAppBar(
        title = {
            // The book and the chapter are one thing to a screen reader: where you are, as a heading.
            Column(
                (if (titleWidth != null) Modifier.widthIn(max = titleWidth) else Modifier).semantics(mergeDescendants = true) {
                    heading()
                    customActions = markActions + hideControls
                },
            ) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A new chapter rolls up into place. Where there's no chapter name (front matter, a
                // book without contents), the line folds away so the title sits in the middle of
                // the bar, level with the icons, instead of riding above an empty line.
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = nav.title.trim(),
                    transitionSpec = {
                        (slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform(clip = false) { _, _ -> motion.fastSpatialSpec() })
                    },
                    label = "chapter",
                ) { title ->
                    if (title.isNotEmpty()) {
                        Text(
                            title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.focusRequester(firstControl)) {
                Icon(JacketIcons.Back, stringResource(R.string.reader_back_to_library))
            }
        },
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
            // Highlight, or add a note, without selecting: the menu says what will be marked.
            if (marks != null) {
                Box {
                    IconButton(onClick = { onMarkMenu(true) }) { Icon(JacketIcons.Highlight, stringResource(R.string.reader_highlight_or_note)) }
                    DropdownMenu(expanded = markMenu, onDismissRequest = { onMarkMenu(false) }) {
                        DropdownMenuItem(
                            text = { Text(marks.highlight) },
                            leadingIcon = { Icon(JacketIcons.Highlight, null) },
                            onClick = {
                                onMarkMenu(false)
                                marks.onMark(false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(marks.note) },
                            leadingIcon = { Icon(JacketIcons.Note, null) },
                            onClick = {
                                onMarkMenu(false)
                                marks.onMark(true)
                            },
                        )
                    }
                }
            }
            if (outerAvailable || outerActive) {
                // One name, and on or off: a name that changed with it would be read against its own state.
                FilledTonalIconToggleButton(checked = outerActive, onCheckedChange = onOuter) {
                    Icon(JacketIcons.OuterScreen, stringResource(R.string.onboarding_cover_outside))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/**
 * The controls at the bottom of the page, floating over it: "Back to the voice" when the listener
 * has wandered off, the chapter control (the slider with the full controls, the chapter steps for
 * a narrow player: see ChapterControl), and the toolbar, which is the reading toolbar or the
 * player (the same bar, turning from one into the other where it sits).
 */
@Composable
private fun ReaderBottomBar(
    nav: ReaderNav,
    book: Book,
    haptics: Haptics,
    showScrubber: Boolean,
    /** What a screen reader or a switch can do to the page from the chapter slider: turn it, and send the controls away. */
    pageActions: List<CustomAccessibilityAction>,
    voice: ListenVoice,
    rate: Float,
    onRate: (Float) -> Unit,
    onListen: () -> Unit,
    onContents: () -> Unit,
    onText: () -> Unit,
    /** Where the controls sit in the window, as they come and go: they keep off the line being read aloud. */
    onPlaced: (Rect) -> Unit,
) {
    Column(
        // Clear of the navigation bar whether or not it is showing, so the toolbar never moves.
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.onGloballyPositioned { onPlaced(it.boundsInWindow()) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val controller = nav.listenController
            val listening = nav.listen.active && controller != null
            // Back to the voice heads the stack: above the chapter slider and the player alike.
            controller?.let { BackToVoiceButton(nav.listen, it, haptics) }
            // One chapter control, whatever is up: the slider's card with the full controls, and
            // the player's chapter steps where its bar is too narrow for them. Those ride in the
            // card when it is there, and are a pill of their own when it isn't.
            val chapters = nav.chapterSteps()
            ChapterControl(
                form = chapterForm(
                    slider = showScrubber && nav.sectionCount > 1,
                    listening = listening, steps = chapters != null, stepsOnBar = chaptersFitOnBar(),
                ),
                nav = nav, book = book, chapters = chapters, haptics = haptics, pageActions = pageActions,
            )
            // On a window too narrow for the voice's icon on the bar, its name unfolds above the
            // toolbar as it becomes the player. (Not the player's wavy line: over the page it
            // would run across the text.)
            PlayerLine(
                nav.listen, progress = nav.section.toFloat() / (nav.sectionCount - 1).coerceAtLeast(1),
                voice = voice, haptics = haptics, visible = listening, line = false,
            )
            val (previousLabel, nextLabel) = when {
                nav.toc.isNotEmpty() -> R.string.reader_previous_chapter to R.string.reader_next_chapter
                else -> R.string.reader_previous_page to R.string.reader_next_page
            }
            ReaderToolbar(
                listening = listening,
                reading = ReadingActions(
                    contentsLabel = if (nav.toc.isNotEmpty()) stringResource(R.string.reader_contents_and_notes) else stringResource(R.string.reader_notes),
                    onContents = onContents,
                    onListen = onListen.takeIf { controller != null },
                    previousLabel = stringResource(previousLabel),
                    nextLabel = stringResource(nextLabel),
                    canStep = { nav.canStep(it) },
                    onStep = { nav.step(it) },
                    onText = onText,
                ),
                listen = nav.listen,
                controller = controller,
                rate = rate,
                onRate = onRate,
                haptics = haptics,
                hasChapters = nav.toc.isNotEmpty(),
                voice = voice,
                chapters = chapters,
            )
        }
    }
}

/**
 * The line under the page, in the room every page keeps for it. While you read: what's left of the
 * chapter, and how far through the book you are. While you listen it is the mini player, in the
 * same place and no taller, so starting and stopping read-aloud never changes the page above it;
 * a tap on it brings up the full controls.
 */
@Composable
private fun ReaderFooter(
    nav: ReaderNav,
    book: Book,
    page: PageColors,
    settings: Settings,
    voice: ListenVoice,
    haptics: Haptics,
    /** The width of one page, while the phone is held half-open like a book: what the line says keeps to the left-hand page. Null for the whole line. */
    onePage: Dp? = null,
    /** Turning the page, for a screen reader or a switch (see [ReaderScreen]). */
    pageTurns: List<CustomAccessibilityAction>,
    onControls: () -> Unit,
) {
    val controller = nav.listenController
    // (The line's own margin, and a little more, short of the fold.)
    val keepToPage = if (onePage != null) Modifier.widthIn(max = (onePage - 40.dp).coerceAtLeast(120.dp)) else Modifier
    val listening = nav.listen.active && controller != null
    val motion = MaterialTheme.motionScheme
    val showControls = stringResource(R.string.listen_show_controls)
    val controlsUp by rememberUpdatedState(onControls)
    val percentRead = stringResource(R.string.reader_percent_read, book.percent)
    // While you read, with the controls away, this line is all of the reader's own that is on the
    // screen. So to a screen reader or a switch it stands for the page: it says where you are,
    // brings the controls back (what a tap on the page does), and turns the page.
    val showAll = stringResource(R.string.reader_show_controls)
    val spoken = listOf(footerText(nav, book, settings), percentRead).filter { it.isNotBlank() }.joinToString(stringResource(R.string.list_separator))
    val insets = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    Row(
        Modifier
            .fillMaxWidth()
            // Clear of a cutout at the top: above everything the line answers for.
            .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
            .then(
                if (listening) {
                    // A tap anywhere on the line brings the controls up. A screen reader isn't told
                    // so twice: it has the button at the line's end for it.
                    Modifier.pointerInput(Unit) {
                        detectTapGestures {
                            haptics.tick()
                            controlsUp()
                        }
                    }
                } else {
                    Modifier.clearAndSetSemantics {
                        contentDescription = spoken
                        onClick(label = showAll) {
                            controlsUp()
                            true
                        }
                        customActions = pageTurns
                    }
                },
            )
            .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .heightIn(min = FooterSpace)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val effects = motion.fastEffectsSpec<Float>()
        // The words give way to the player where they stand, and back.
        AnimatedContent(
            targetState = listening,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.fastSpatialSpec()) { it / 3 })
                    .togetherWith(fadeOut(effects) + slideOutVertically(motion.fastSpatialSpec()) { -it / 3 })
                    .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f),
            label = "footer-mode",
        ) { player ->
            if (player && controller != null) {
                MiniPlayer(
                    nav.listen, controller, voice,
                    left = listeningLeft(nav, book, settings),
                    progress = if (nav.chapterProgress >= 0f) nav.chapterProgress else nav.section.toFloat() / (nav.sectionCount - 1).coerceAtLeast(1),
                    colors = page,
                    haptics = haptics,
                    modifier = keepToPage,
                )
            } else {
                // "5 pages left" gives way to "4" quietly: the page is the thing that moves.
                AnimatedContent(
                    targetState = footerText(nav, book, settings),
                    transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                    label = "footer",
                ) { text ->
                    // With the phone's font size turned up, the whole of it at a smaller size, before any of it is cut off.
                    Text(
                        text, style = MaterialTheme.typography.labelMedium, color = page.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        autoSize = fitOnOneLine(MaterialTheme.typography.labelMedium),
                        modifier = keepToPage,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.percent, book.percent), style = MaterialTheme.typography.labelMedium, color = page.secondary,
            textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = percentRead },
        )
        // While you listen, a tap on the page moves the voice, so the way back to the controls
        // is a button of its own, in the corner under the thumb. Drawn at the footer's scale, it
        // keeps a full-size target, reaching a little over the page's margin like the play button.
        AnimatedVisibility(
            visible = listening,
            enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier.padding(start = 10.dp),
        ) {
            val size = IconButtonDefaults.extraSmallContainerSize()
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(
                    onClick = {
                        haptics.tick()
                        onControls()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = page.secondary.copy(alpha = 0.16f),
                        contentColor = page.text,
                    ),
                    modifier = Modifier.size(size),
                ) {
                    Icon(JacketIcons.ExpandLess, showControls, Modifier.size(IconButtonDefaults.extraSmallIconSize))
                }
            }
        }
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
    Box(
        // A tap brings the controls back, as on any page: the way out to the library is among
        // them, and they leave by themselves a moment after the book opens.
        Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { nav?.toggleControls?.invoke() }.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
