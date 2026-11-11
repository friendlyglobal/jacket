package io.github.friendlyglobal.jacket.reader

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areSystemBarsVisible
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStarted
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.HighlightColor
import io.github.friendlyglobal.jacket.data.PageLayout
import io.github.friendlyglobal.jacket.data.PageTurn
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.readium
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.importer.Epub
import io.github.friendlyglobal.jacket.voice.NaturalTtsEngineProvider
import io.github.friendlyglobal.jacket.voice.NaturalTtsPreferences
import io.github.friendlyglobal.jacket.voice.regionOf
import io.github.friendlyglobal.jacket.voice.withPhoneRegion
import io.github.friendlyglobal.jacket.voice.SentenceLookahead
import io.github.friendlyglobal.jacket.voice.VoiceRef
import io.github.friendlyglobal.jacket.voice.languageKey
import io.github.friendlyglobal.jacket.voices
import org.readium.r2.shared.util.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.Publication
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * Room under the page for the quiet footer ("4 pages left in chapter · 42%"), which is the mini
 * player while the book is read aloud. It is the same room whether you read or listen: the page
 * never changes size, so it never re-flows, when read-aloud starts or stops.
 */
internal val FooterSpace = 36.dp

/** The scrubber's resolution: any book, long or short, is this many steps. */
private const val SCRUB_STEPS = 1000

/** A page move this soon after the voice turned the page is the voice's, not the listener's. */
private const val FOLLOW_SETTLE_MS = 900L

/** Nor is one this soon after the page was put back from a fold, an unfold or a turn of the phone. */
private const val RESIZE_SETTLE_MS = 1_500L

/** A page move this soon after the reader's own swipe, page turn or jump is theirs. */
private const val LISTENER_MOVE_MS = 2_000L

/** How long Readium is given to report the page it was sent back to, and how many times it is sent. */
private const val ARRIVAL_MS = 1_500L
private const val PUT_BACK_TRIES = 3

/** Scrolling, how long Readium takes to report where a move has left it. */
private const val SCROLL_SETTLE_MS = 300L

/** How long a navigator just made is waited on for its first report of a page. */
private const val FIRST_PAGE_MS = 10_000L

/** While the voice speaks its place is saved this often, and whenever it pauses or the screen is left. */
private const val SPOKEN_SAVE_MS = 20_000L

/**
 * EPUB, rendered by Readium: real pagination (or continuous scroll), publisher styles, spreads
 * when unfolded, and read-aloud. Jacket supplies the chrome around it.
 */
@Composable
fun EpubReader(
    book: Book,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    annotations: List<Annotation>,
    onCenterTap: () -> Unit,
) {
    val context = LocalContext.current
    val opened by produceState<Result<Opened>?>(null, book.id) {
        val result = runCatching {
            // Not cancellable: backing out mid-open must still hand the book over to be closed.
            withContext(Dispatchers.IO + NonCancellable) {
                val file = context.library.fileOf(book)
                val publication = context.readium.open(file)
                val spoken = SpokenFiles(publication)
                val saved = savedLocator(book, publication, file)
                // A sentence the voice stopped on, as Jacket 1.0.0 saved it, says which of its file's
                // blocks it is in, not where on the pages: told the pages' way before the book is
                // opened at it. (One saved since comes out as it went in.)
                val start = if (saved?.isSpoken == true) spoken.placed(saved) else saved
                Opened(publication, start, spoken)
            }
        }
        value = result
        awaitDispose { result.getOrNull()?.publication?.let(::closeSoon) }
    }
    val result = opened ?: return ReaderLoading()
    val (publication, start, spoken) = result.getOrElse {
        return ReaderError(stringResource(R.string.reader_error_open_book, it.message.orEmpty()))
    }
    // A copy-protected book (Adobe's DRM, Readium LCP: a shop's or a library's) opens, but locked,
    // and Readium's pages refuse a locked book by throwing: Jacket would close under the reader.
    if (publication.isRestricted) return ReaderError(stringResource(R.string.reader_error_protected))
    if (publication.readingOrder.isEmpty()) return ReaderError(stringResource(R.string.reader_error_no_pages))
    EpubPages(book, publication, start, spoken, settings, page, nav, haptics, annotations, onCenterTap)
}

/** A book opened: where to start it, and its files as read-aloud reads them. */
private data class Opened(val publication: Publication, val start: Locator?, val spoken: SpokenFiles)

/** Closes the book once its pages have been torn down (the fragment goes after composition). */
private fun closeSoon(publication: Publication) {
    Handler(Looper.getMainLooper()).postDelayed({ publication.close() }, 2_000)
}

@Composable
private fun EpubPages(
    book: Book,
    publication: Publication,
    start: Locator?,
    /** The book's files as read-aloud reads them: the voice's place is told in the pages' terms through these. */
    spokenFiles: SpokenFiles,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    annotations: List<Annotation>,
    onCenterTap: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val library = context.library
    val selectionMenu = remember(publication) { SelectionMenu(context) }
    val scope = rememberCoroutineScope()
    val contents = remember(publication) { Contents(publication) }
    val preferences = epubPreferences(settings, page)
    val scroll = settings.layout == PageLayout.SCROLL
    val tap by rememberUpdatedState(onCenterTap)
    val turner = remember { PageTurner(scope) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var positions by remember { mutableStateOf<List<Locator>>(emptyList()) }
    // Page within the current chapter file, as Readium lays it out.
    var filePage by remember { mutableStateOf(FilePage()) }

    // The place being kept while pages re-flow, and the putting back of them (see ResizeKeeper).
    val resize = remember { ResizeKeeper() }
    var current by remember { mutableStateOf<Locator?>(null) }
    // The sentence the voice is on, while the book is read aloud (see where the voice reports it).
    // A book left on a sentence opens with that sentence as its place, until the reader moves on:
    // saved as it is, and counted again only once it is seen on its page (see saveSpoken).
    val voiceAt = remember {
        SpokenPlace().apply {
            sentence = start?.takeIf { it.isSpoken }
            saved = sentence
        }
    }

    /**
     * The place to keep while the pages re-flow, and to open a navigator at: the page in front of
     * the reader ([page], as Readium reports it), or the sentence the voice has reached out of
     * sight of it (see [placeKept]).
     */
    fun placeToKeep(page: Locator? = current): Locator? =
        placeKept(voiceAt.sentence, voiceAt.unseen) { resize.place ?: resize.keep(page) }
    // The settings a navigator is made with; changes made later are submitted to it as they come.
    val settingsNow by rememberUpdatedState(preferences)

    val navigators = remember(publication) { EpubNavigatorFactory(publication) }

    /** Readium's navigator for this book, opening at [from] (the first page if null). */
    fun navigatorAt(from: Locator?) = navigators.createFragmentFactory(
        initialLocator = from,
        initialPreferences = settingsNow,
        listener = object : EpubNavigatorFragment.Listener {
            override fun onExternalLinkActivated(url: AbsoluteUrl) = openLink(context, url)
        },
        paginationListener = object : EpubNavigatorFragment.PaginationListener {
            override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                // The chapter on screen has loaded and found its place: the opening cover may lift.
                // (onPageLoaded can't tell: it also fires for the neighbours Readium preloads.)
                // Unless the page is still to be put at the place being kept: then that lifts it.
                if (resize.target == null) nav.contentReady = true
                val next = FilePage(locator.href.toString(), pageIndex, totalPages)
                val old = filePage
                // A page turn ticks; a re-flow (new size, new font) changes the page count and doesn't.
                val turned = old.href.isNotEmpty() &&
                    (next.href != old.href || (next.count == old.count && next.index != old.index))
                // During an animated turn the tick comes when the page settles, not when it starts.
                if (turned && !navigatorScrolls(navigator) && !turner.busy) haptics.tick()
                filePage = next
            }
        },
        configuration = readerConfiguration(selectionMenu),
    )

    // The fragment manager asks for a navigator when the book opens, and again whenever Compose
    // rebuilds this part of the screen. Each one starts from where the reader is now: made once,
    // with the place the book was opened at, a rebuilt navigator went back there, however far the
    // reader had come since. (Starting read-aloud used to rebuild it, every time.)
    val factory = remember(publication) {
        object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                val from = resize.target ?: placeToKeep() ?: start
                // Kept until the navigator is seen on that page: what it reports before then is
                // where it is on its way, not where the reader is.
                resize.target = from
                // The page count the last navigator reported is not this one's.
                filePage = FilePage()
                return navigatorAt(from).instantiate(classLoader, className)
            }
        }
    }

    LaunchedEffect(publication) { positions = runCatching { publication.positions() }.getOrDefault(emptyList()) }

    // Page turns that slide like paper (see PageTurner): drags, edge taps, arrow and volume keys.
    // Only while the controls are hidden, so the page copy never carries the toolbars with it.
    val barsVisible = WindowInsets.areSystemBarsVisible
    val chromeShowing by rememberUpdatedState(barsVisible)
    turner.navigator = navigator
    val pageTurn by rememberUpdatedState(settings.pageTurn)
    // "Slide" leaves turning to Readium's own strip of pages.
    turner.enabled = { !scroll && !chromeShowing && pageTurn != PageTurn.SLIDE }
    turner.style = settings.pageTurn
    turner.paper = page.background
    turner.onTurned = { haptics.tick() }
    val minEdgePx = with(LocalDensity.current) { 80.dp.toPx() }

    // Taps: the outer thirds turn pages (not while scrolling), anything else toggles the controls.
    // While the book is read aloud, a tap on the words is taken first (see voiceTap below).
    val voiceTap = remember { VoiceTap() }
    DisposableEffect(navigator) {
        val n = navigator ?: return@DisposableEffect onDispose {}
        val minEdge = minEdgePx
        val reading = object : InputListener {
            override fun onTap(event: TapEvent): Boolean = voiceTap.onTap(n, event.point)
        }
        val edges = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (n.overflow.value.scroll) return false
                val width = n.publicationView.width.toFloat()
                val edge = maxOf(width * 0.3f, minEdge)
                return when {
                    event.point.x < edge -> { turner.turnToward(toRight = false); true }
                    event.point.x > width - edge -> { turner.turnToward(toRight = true); true }
                    else -> false
                }
            }

            override fun onKey(event: org.readium.r2.navigator.input.KeyEvent): Boolean {
                if (event.type != org.readium.r2.navigator.input.KeyEvent.Type.Down || event.modifiers.isNotEmpty()) return false
                return when (event.key) {
                    org.readium.r2.navigator.input.Key.ArrowLeft -> { turner.turnToward(toRight = false); true }
                    org.readium.r2.navigator.input.Key.ArrowRight -> { turner.turnToward(toRight = true); true }
                    org.readium.r2.navigator.input.Key.ArrowDown, org.readium.r2.navigator.input.Key.Space -> { turner.turn(1); true }
                    org.readium.r2.navigator.input.Key.ArrowUp -> { turner.turn(-1); true }
                    else -> false
                }
            }
        }
        val center = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                tap()
                return true
            }
        }
        // In this order: the first to take a tap has it.
        n.addInputListener(reading)
        n.addInputListener(edges)
        n.addInputListener(center)
        onDispose {
            n.removeInputListener(reading)
            n.removeInputListener(edges)
            n.removeInputListener(center)
        }
    }

    // Where you are: saved, shown in the chrome, and used for chapter tracking.
    // 0 while the page is as it should be; 1 while it is re-flowing out of sight (see below).
    // It comes and goes on the theme's own fades, like everything else that appears in the reader.
    val reflowVeil = remember { Animatable(0f) }
    val veilOn = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val veilOff = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    var located by remember { mutableStateOf<Located?>(null) }
    // Sub-chapter anchors of the file on screen, kept while you stay in it (for scrubber previews).
    var anchors by remember { mutableStateOf<Located?>(null) }
    val spans = remember(positions) { if (positions.isEmpty()) null else Spans(positions, contents) }
    // The first and last pages of the book: the page gives a little and says no.
    turner.onBlocked = { haptics.reject() }
    turner.canTurn = canTurn@{ direction ->
        val here = current ?: return@canTurn true
        val file = filePage
        // Between a turn and Readium's report of it, assume there's more.
        if (file.href != here.href.toString() || file.count <= 0) return@canTurn true
        val resource = contents.resourceOf(here.href)
        if (direction > 0) resource != contents.fileCount - 1 || file.index < file.count - 1 else resource != 0 || file.index > 0
    }
    /**
     * Saves the page at [locator] as the reader's place, [progress] of the way through the book.
     * Not every page Readium reports is that. While pages re-flow, and while a navigator is on its
     * way to the page it opens at, it reports wherever it happens to be: the old page number in
     * the new pages, which in a long chapter is dozens of pages off. And while the voice is
     * speaking, the place is the voice's, whatever the page shows.
     */
    suspend fun savePage(locator: Locator, progress: Double) {
        fun notTheReaders() = resize.target != null || voiceHoldsThePlace(nav.listen.active, nav.listen.playing)
        if (notTheReaders()) return
        // Saved with the first words on the page, as a bookmark is. How far down the file a page
        // starts shifts with every layout (folded or not, a text size, a blank page Readium adds
        // at the end): by that alone the book reopens a page out. The words are where they are.
        val place = navigator?.let { locator.byFirstWords(it) } ?: locator
        // Asking the page took a moment, and the pages may have started to re-flow in it.
        if (notTheReaders()) return
        resize.known = locator to place
        val position = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0)
        library.updateLocator(book.id, place.toJSON().toString(), progress.toFloat(), position, positions.size)
        // The reader has moved on from where the voice last was.
        voiceAt.sentence = null
        voiceAt.unseen = false
    }

    val owner = LocalLifecycleOwner.current

    /** How many pages Readium counted in the file when it reported [place]; null if it hasn't said. */
    fun pagesAt(place: Locator): Int? = filePage.takeIf { it.count > 0 && it.href == place.href.toString() }?.count

    /**
     * How far down its file the page in front of the listener starts, when that page is where the
     * voice is: it has followed the voice there, and [sentence] is on it. Null when it isn't, or
     * can't be told: out of sight, being put back after a fold, or turned by the listener to look
     * elsewhere.
     */
    fun pageWithVoice(sentence: Locator): Double? {
        val shown = current ?: return null
        val n = navigator ?: return null
        if (voiceAt.unseen || nav.listen.offPage || resize.target != null) return null
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return null
        // A page the listener has just turned to is theirs (read-aloud is about to say they have left the voice).
        if (SystemClock.uptimeMillis() - nav.movedAt < LISTENER_MOVE_MS) return null
        return shown.locations.progression?.takeIf { shown.shows(sentence, pagesAt(shown), n.overflow.value) }
    }

    /**
     * Saves the sentence the voice is on as the reader's place. While the voice is speaking, the
     * book's place is that sentence. It is saved as the voice goes, because the page can't be
     * asked: shut in a pocket or behind another app it doesn't turn, and Readium goes on reporting
     * the page the screen went dark on.
     *
     * How far through the book that is, is told the way a page tells it, so that listening and
     * reading count alike and the number doesn't jump between the two: by the page the sentence is
     * on, while that page is in front of the listener, and otherwise by where the sentence is in
     * its file (see [SpokenFiles.placed], which every sentence here has been through, and
     * [spokenWithin]).
     */
    fun saveSpoken() {
        val sentence = voiceAt.sentence ?: return
        val file = contents.resourceOf(sentence.href)
        val told = sentence.locations.progression ?: 0.0
        val page = pageWithVoice(sentence)
        // Scrolling, there are no pages to count from.
        val pages = current?.takeIf { !navigatorScrolls(navigator) && contents.resourceOf(it.href) == file }?.let(::pagesAt)
        val seen = voiceAt.seenOn?.takeIf { it.file == file }
        // In sight on a page: what that page counts, beside the sentence's own count.
        if (page != null) voiceAt.seenOn = SeenOn(file, page, pages, pageLead(page, told, seen?.lead, moved = seen != null && seen.pages == pages && seen.page != page))
        val within = spokenWithin(told, page, voiceAt.seenOn?.takeIf { it.file == file }?.lead, pages)
        // While the pages are put back after a fold, in sight, the count stays as it is: the page
        // can't be asked for a moment, and counted without it the number would flicker.
        val reflowing = page == null && resize.target != null && !voiceAt.unseen
        val held = voiceAt.savedProgress.takeIf { reflowing && it >= 0.0 && voiceAt.saved?.href == sentence.href }
        val progress = held ?: spans?.total(file, within) ?: return
        // A sentence already saved is saved again only when its page has something new to say (the
        // voice has turned it): not to be counted over again, another way, as the screen goes dark.
        if (sentence === voiceAt.saved && (page == null || progress == voiceAt.savedProgress)) return
        // The Readium position it falls in (about a page of text), kept beside every saved place.
        val position = positions.indexOfLast { (it.locations.totalProgression ?: 0.0) <= progress }.coerceAtLeast(0)
        library.updateLocator(book.id, sentence.toJSON().toString(), progress.toFloat(), position, positions.size)
        voiceAt.saved = sentence
        voiceAt.savedProgress = progress
        voiceAt.savedAt = SystemClock.uptimeMillis()
    }

    /**
     * Puts the page back at the place being kept once the pages have settled into their new
     * layout, and sees it there: Readium reporting the page that holds the place is what ends the
     * keeping. [veiled] hides a page already in view while it jumps about.
     */
    fun putBack(veiled: Boolean = true) {
        // The place from before the first change: pages re-flow through several sizes as a phone
        // folds, and it may be folded and unfolded again without a page being turned.
        if (resize.target == null) resize.target = placeToKeep()
        resize.job?.cancel()
        resize.job = scope.launch {
            // The text would be seen jumping about while the pages re-flow and the place is
            // found again: it goes under a veil of the page's own colour meanwhile.
            if (veiled) launch { reflowVeil.animateTo(1f, veilOn) }
            try {
                val n = navigator ?: return@launch
                // Readium only moves a page that is on show. A phone shut on the book re-flows it
                // unseen: the page is put back when the phone is opened again.
                owner.lifecycle.withStarted { }
                repeat(PUT_BACK_TRIES) { tried ->
                    val pages = n.awaitLayout(resize.size)
                    val target = resize.target ?: return@launch
                    val before = n.currentLocator.value
                    n.go(target.onPage(pages, n.overflow.value), animated = false)
                    resize.place = target
                    resize.restoredAt = SystemClock.uptimeMillis()
                    // Scrolling there are no pages to count: its word is waited for instead.
                    if (n.overflow.value.scroll) delay(SCROLL_SETTLE_MS)
                    // Readium says where it is a moment after it gets there. What it had said before
                    // only counts if it was said of these pages: the same number of them.
                    // With nothing new said in all that time, what it last said stands.
                    val said = withTimeoutOrNull(ARRIVAL_MS) { n.currentLocator.first { it !== before || pagesAt(it) == pages } }
                        ?: n.currentLocator.value
                    // A book being opened has waited behind its cover for this, and waits no longer.
                    nav.contentReady = true
                    if (said.shows(target, pagesAt(said) ?: pages, n.overflow.value)) {
                        resize.target = null
                        // The page now showing is the one those words are on.
                        if (target.text.highlight != null) resize.known = said to target
                        // A moment for the page gone to to be drawn.
                        delay(60)
                        return@launch
                    }
                    // Its words still not found near where the place was: the last try goes by the place alone.
                    if (tried == PUT_BACK_TRIES - 2 && target.text.highlight != null) resize.target = target.byProgression()
                }
                // It won't go there. What it shows is what the reader sees: the place saved stays
                // as it was until they turn a page themselves.
                resize.target = null
            } finally {
                nav.contentReady = true
                // Cut short by another re-flow, the veil stays for that one to lift.
                if (isActive) launch { reflowVeil.animateTo(0f, veilOff) }
            }
        }
    }

    // A navigator just made is seen to the page it opens at, before the opening cover lifts.
    LaunchedEffect(navigator) {
        val n = navigator ?: return@LaunchedEffect
        if (resize.target == null) return@LaunchedEffect
        // Its first report of a page: until then it only repeats where it was told to open. A
        // book of fixed pages reports none, and is taken at its word.
        val reported = withTimeoutOrNull(FIRST_PAGE_MS) { snapshotFlow { filePage }.first { it.count > 0 } }
        // The pages have re-flowed meanwhile, and are being put back as it is.
        if (resize.job?.isActive == true) return@LaunchedEffect
        val kept = resize.target ?: return@LaunchedEffect
        val opened = n.currentLocator.value
        if (reported == null || opened.shows(kept, reported.count, n.overflow.value)) {
            resize.target = null
            // The page it opened on is the one the saved words are on.
            if (kept.text.highlight != null) resize.known = opened to kept
            if (reported != null) nav.contentReady = true
        } else {
            // Readium opens the page nearest to a place, which can be the one after it; and words
            // that come twice in a book can be found in the wrong place, far from where they were.
            if (kept.text.highlight != null) resize.target = kept.byProgression()
            putBack(veiled = false)
        }
    }

    // Settings apply live. New type or spacing re-flows the pages as a new size does, and the
    // place is kept through it the same way. A new colour moves nothing.
    LaunchedEffect(navigator, preferences) {
        val n = navigator ?: return@LaunchedEffect
        val before = resize.settings
        resize.settings = preferences
        val reflows = before != null &&
            before.copy(theme = preferences.theme, backgroundColor = preferences.backgroundColor, textColor = preferences.textColor) != preferences
        // Kept before the new settings reach the pages.
        if (reflows && resize.target == null) resize.target = placeToKeep()
        n.submitPreferences(preferences)
        if (reflows) putBack()
    }

    LaunchedEffect(navigator, spans, scroll) {
        val n = navigator ?: return@LaunchedEffect
        n.currentLocator.collectLatest { locator ->
            val before = current
            current = locator
            // The file's page count changing under the reader is a re-flow, whatever caused it (a
            // font or a picture arriving late): Readium has kept the page number, not the place.
            val pages = filePage.takeIf { pagesAt(locator) != null }
            val were = resize.pages
            if (resize.target == null && pages != null && were != null && were.href == pages.href && were.count != pages.count) {
                resize.target = placeToKeep(before)
                if (resize.target != null) putBack()
            }
            if (pages != null) resize.pages = pages
            // Moving on after the pages were put back (turning a page, say) makes this the place to keep.
            if (resize.target == null && SystemClock.uptimeMillis() - resize.restoredAt > RESIZE_SETTLE_MS) resize.place = null
            val resource = contents.resourceOf(locator.href)
            // Readium's positions are coarse (about a page of compressed text), so progress is
            // measured within the file: smooth even for a book that is one long file.
            val progress = spans?.total(resource, locator.locations.progression ?: 0.0)
                ?: locator.locations.totalProgression ?: 0.0
            nav.sectionCount = SCRUB_STEPS
            nav.section = (progress * (SCRUB_STEPS - 1)).roundToInt()
            savePage(locator, progress)
            // The voice has turned the page: its place is counted from this one now.
            if (voiceHoldsThePlace(nav.listen.active, nav.listen.playing)) saveSpoken()

            // For the cover screen: the chapter's name and how far through it this page is.
            fun placeInChapter() {
                nav.chapter = contents.titleOf(nav.tocIndex).orEmpty()
                val span = spans?.let { contents.span(nav.tocIndex, it, located) }
                nav.chapterProgress = if (span == null) -1f else {
                    ((progress - span.start) / (span.endInclusive - span.start)).toFloat().coerceIn(0f, 1f)
                }
                nav.chapterLeft = if (span == null) -1f else (span.endInclusive - progress).toFloat().coerceAtLeast(0f)
            }

            // Chapter: by file, or, in a file with sub-chapter anchors, by asking the page where
            // they are (keeping the last answer meanwhile, so nothing flickers).
            if (!contents.hasAnchors(resource)) {
                located = null
                nav.tocIndex = contents.chapterAt(resource)
                nav.title = contents.titleOf(nav.tocIndex) ?: locator.title ?: ""
                placeInChapter()
                return@collectLatest
            }
            if (located?.resource != resource) {
                // New file: its own chapter until the page says otherwise.
                located = null
                nav.tocIndex = contents.chapterAt(resource)
                nav.title = contents.titleOf(nav.tocIndex) ?: locator.title ?: ""
            }
            delay(120)
            val here = contents.locate(n, resource, scroll) ?: return@collectLatest
            located = here
            anchors = here
            nav.tocIndex = here.chapter
            nav.title = contents.titleOf(here.chapter) ?: locator.title ?: ""
            placeInChapter()
        }
    }
    // Readium reports the page count after the location, so the footer follows both.
    LaunchedEffect(current, located, filePage, scroll, nav.title) {
        val locator = current ?: return@LaunchedEffect
        nav.footer = footerFor(resources, scroll, nav.title, filePage, locator, located)
        nav.pageKey++
        // The last page of the last file (or the bottom of it, scrolling): the end of the book.
        val lastFile = contents.resourceOf(locator.href) == contents.fileCount - 1
        nav.atEnd = lastFile && if (scroll) {
            // Scrolling, Readium's progression stops a screen short of 1: ask the page.
            val n = navigator
            n != null && runCatching { n.evaluateJavascript(AT_BOTTOM_SCRIPT) }.getOrNull() == "true"
        } else {
            filePage.href == locator.href.toString() && filePage.count > 0 && filePage.index >= filePage.count - 1
        }
        // The last page is all of the book, though it starts a little before the end.
        if (nav.atEnd) {
            if (nav.chapterProgress >= 0f) nav.chapterProgress = 1f
            savePage(locator, 1.0)
        }
    }

    // Highlights, drawn over the text; tapping one opens its editor.
    val highlights = remember(annotations) { annotations.filter { it.kind == AnnotationKind.HIGHLIGHT && it.locator != null } }
    LaunchedEffect(navigator, highlights) {
        val n = navigator ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            val locator = h.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() } ?: return@mapNotNull null
            Decoration(h.id, locator, Marker(h.color.argb, noted = h.note.isNotBlank()))
        }
        n.applyDecorations(decorations, HIGHLIGHTS)
    }
    DisposableEffect(navigator) {
        val n = navigator ?: return@DisposableEffect onDispose {}
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                haptics.tick()
                nav.editing = event.decoration.id
                return true
            }
        }
        n.addDecorationListener(HIGHLIGHTS, listener)
        onDispose { n.removeDecorationListener(listener) }
    }

    // Selected text: highlight it, add a note, copy, share, or hand it to Translate and friends.
    selectionMenu.onAction = { action, processor ->
        scope.launch {
            val n = navigator ?: return@launch
            val selection = n.currentSelection() ?: return@launch
            val text = selection.locator.text.highlight?.trim().orEmpty()
            n.clearSelection()
            if (text.isEmpty()) return@launch
            when (action) {
                SelectionMenu.Action.HIGHLIGHT, SelectionMenu.Action.NOTE -> {
                    haptics.confirm()
                    val resource = contents.resourceOf(selection.locator.href)
                    val highlight = Annotation(
                        id = UUID.randomUUID().toString(),
                        bookId = book.id,
                        kind = AnnotationKind.HIGHLIGHT,
                        locator = selection.locator.toJSON().toString(),
                        progress = (spans?.total(resource, selection.locator.locations.progression ?: 0.0) ?: 0.0).toFloat(),
                        chapter = nav.title,
                        text = text,
                        color = annotations.lastOrNull { it.kind == AnnotationKind.HIGHLIGHT }?.color ?: HighlightColor.YELLOW,
                        createdAt = System.currentTimeMillis(),
                    )
                    library.addAnnotation(highlight)
                    if (action == SelectionMenu.Action.NOTE) nav.editing = highlight.id
                }
                SelectionMenu.Action.COPY -> {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(book.title, text))
                }
                SelectionMenu.Action.SHARE -> runCatching {
                    val quote = if (book.author.isNotBlank()) {
                        resources.getString(R.string.reader_share_quote_author, text, book.title, book.author)
                    } else {
                        resources.getString(R.string.reader_share_quote, text, book.title)
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, quote)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SelectionMenu.Action.PROCESS -> runCatching {
                    val info = processor?.activityInfo ?: return@runCatching
                    context.startActivity(
                        Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                            .setClassName(info.packageName, info.name)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    // Bookmarks: remember the page by the first words on it, and find it again by them.
    nav.place = place@{
        val n = navigator ?: return@place null
        val locator = current ?: return@place null
        val top = runCatching { n.evaluateJavascript(TOP_TEXT_SCRIPT)?.let(::JSONObject) }.getOrNull()
        val quote = top?.optString("quote").orEmpty()
        val marked = if (quote.isNotBlank()) {
            locator.copy(text = Locator.Text(before = top?.optString("before"), highlight = quote))
        } else {
            locator.byProgression()
        }
        val resource = contents.resourceOf(locator.href)
        Place(
            locator = marked.toJSON().toString(),
            section = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0),
            progress = (spans?.total(resource, locator.locations.progression ?: 0.0) ?: 0.0).toFloat(),
            chapter = nav.title,
            snippet = top?.optString("text").orEmpty(),
        )
    }
    nav.onScreen = onScreen@{ mark ->
        val here = current ?: return@onScreen false
        val at = mark.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() } ?: return@onScreen false
        if (contents.resourceOf(at.href) != contents.resourceOf(here.href)) return@onScreen false
        val p = at.locations.progression ?: return@onScreen false
        val file = filePage
        if (!scroll && file.href == here.href.toString() && file.count > 0) {
            p >= file.index.toDouble() / file.count - 1e-6 && p < (file.index + 1.0) / file.count - 1e-6
        } else {
            kotlin.math.abs(p - (here.locations.progression ?: 0.0)) < 0.03
        }
    }
    // A jump remembers the page it left from, so the reader can offer the way back.
    val markOrigin = {
        nav.movedAt = SystemClock.uptimeMillis()
        val here = current
        val n = navigator
        if (here != null && n != null) {
            val pages = filePage.count.takeIf { filePage.href == here.href.toString() && it > 0 }
            val place = here.byProgression().onPage(pages, n.overflow.value)
            nav.cameFrom = Origin(
                percent = nav.section * 100 / (nav.sectionCount - 1).coerceAtLeast(1),
                chapter = nav.title.trim(),
            ) { navigator?.go(place, animated = false) }
        }
    }
    nav.goTo = { mark ->
        mark.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?.let {
                markOrigin()
                navigator?.go(it, animated = false)
            }
    }
    nav.turnPage = { delta ->
        nav.movedAt = SystemClock.uptimeMillis()
        turner.turn(delta)
    }

    // Search the whole book; results stream in as Readium works through it.
    val search = remember(publication) {
        if (publication.isSearchable) EpubSearch(publication, contents, { navigator }, scope) else null
    }
    search?.tint = page.link.toArgb()
    search?.beforeShow = markOrigin
    nav.search = search

    // Chapter starts as marks on the scrubber.
    LaunchedEffect(spans, anchors) {
        val s = spans ?: return@LaunchedEffect
        nav.marks = contents.entries.mapNotNull { entry ->
            if (entry.level > 0 || entry.resource < 0) return@mapNotNull null
            val within = if (entry.fragment == null) 0.0 else {
                anchors?.takeIf { it.resource == entry.resource }?.starts?.firstOrNull { it.first == contents.entries.indexOf(entry) }?.second
                    ?: return@mapNotNull null
            }
            s.total(entry.resource, within)?.toFloat()
        }.distinct().sorted()
    }

    val listen = remember(publication) {
        ReadAloud(
            context, publication, spokenFiles, book.id, { navigator }, nav.listen, scope,
            fileOf = { href -> contents.resourceOf(href) },
            resizing = { resize.job?.isActive == true || SystemClock.uptimeMillis() - resize.restoredAt < RESIZE_SETTLE_MS },
            movedAt = { nav.movedAt },
        ).apply {
            // The place a book was left at is the sentence the voice stopped on, if it was being
            // read aloud: listening again on that page carries on from it.
            stoppedAt = book.locator?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }
        }
    }
    listen.colors = page.link.toArgb() to page.background.toArgb()

    // While the voice is speaking, the book's place is the sentence it is on (see saveSpoken).
    listen.onSpoken = { sentence ->
        voiceAt.sentence = sentence
        // Out of sight, the page can't follow (unless the listener had left it to look elsewhere).
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !nav.listen.offPage) voiceAt.unseen = true
        // Often enough that little is heard twice if the app is closed under the voice, and no
        // oftener: every save writes the library and redraws the widgets.
        if (SystemClock.uptimeMillis() - voiceAt.savedAt >= SPOKEN_SAVE_MS) saveSpoken()
    }
    // Paused or stopped: the sentence the voice stopped on is saved there and then.
    // And once the book is open on its page: a sentence it was left on is counted from the page
    // it is now on. (It may have been saved out of sight of any page, and Jacket 1.0.0 saved it
    // by a count of its own, far from the pages'.)
    LaunchedEffect(nav.listen.playing, spans, nav.contentReady) { if (!nav.listen.playing && nav.contentReady) saveSpoken() }
    // So is the one it is on when the screen is left. Coming back after the voice read on unseen,
    // the page goes to where the voice is (or stopped).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveSpoken() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val sentence = voiceAt.sentence
        if (voiceAt.unseen && sentence != null) {
            voiceAt.unseen = false
            if (resize.target != null) {
                // The pages re-flowed out of sight as well (the phone was shut on the book) and wait
                // to be put back: at the sentence, not at the page the phone was shut on.
                resize.target = sentence
                putBack()
            } else {
                navigator?.go(sentence, animated = false)
                // An unfolding phone shows its pages a moment before it changes their size. Until
                // Readium is seen at the sentence, that is still the place to put them back at.
                resize.place = sentence
                resize.restoredAt = SystemClock.uptimeMillis()
            }
        }
    }

    // The voice follows the book's language by itself. A book that gives the wrong one can be
    // told another, and is then read in that whatever its pages claim.
    val voices = context.voices
    val said = publication.metadata.language?.code
    nav.bookLanguage = said
    LaunchedEffect(book.id, said) { if (book.language == null && said != null) library.setLanguage(book.id, said) }
    val readIn = book.language ?: said
    val voice = remember(settings.voice, settings.voices, readIn) { voices.choose(readIn, settings) }
    val chosenLanguage = readIn?.takeIf { said != null && languageKey(it) != languageKey(said) }
    listen.voice = voice
    listen.language = chosenLanguage
    // The phone's own voice is asked for the reader's accent: "pt" read on a Brazilian phone is "pt-BR".
    listen.phoneVoiceLanguage = withPhoneRegion(readIn)?.takeIf { regionOf(it).isNotEmpty() && regionOf(said).isEmpty() }
    LaunchedEffect(listen, voice, chosenLanguage) { listen.retune() }
    // Closing the player moves nothing: the page followed the voice, so the sentence it stopped on
    // is in front of you, and is marked for a moment (see ReadAloud.markHere). Only a listener who
    // had gone to look elsewhere is not there: the page they chose stays, and the way back to
    // where the voice stopped is offered like the way back from any jump.
    listen.hereAppears = MaterialTheme.motionScheme.defaultEffectsSpec()
    listen.hereFades = MaterialTheme.motionScheme.slowEffectsSpec()
    listen.onStoppedElsewhere = { spoken ->
        val resource = contents.resourceOf(spoken.href)
        val progress = spoken.locations.progression?.let { spans?.total(resource, it) } ?: spoken.locations.totalProgression
        nav.cameFrom = Origin(
            percent = progress?.let { (it * 100).toInt().coerceIn(0, 100) } ?: (nav.section * 100 / (nav.sectionCount - 1).coerceAtLeast(1)),
            chapter = contents.titleOf(contents.chapterAt(resource)).orEmpty(),
        ) {
            navigator?.go(spoken, animated = false)
            listen.markHere(spoken)
        }
    }
    LaunchedEffect(listen, settings.listenRate) { listen.setRate(settings.listenRate) }

    DisposableEffect(navigator, spans, anchors, contents) {
        // The scrubber: the whole book in SCRUB_STEPS steps, previewing the chapter under your thumb.
        fun chapterAt(step: Int): Int {
            val (resource, within) = spans?.locate(step / (SCRUB_STEPS - 1.0)) ?: return -1
            return contents.chapterAt(resource, within, anchors)
        }
        nav.toc = contents.entries.mapIndexed { i, e -> TocItem(e.title, e.level, i) }
        nav.openToc = { item ->
            contents.entries.getOrNull(item.index)?.let {
                markOrigin()
                navigator?.go(it.link, animated = false)
            }
        }
        nav.sectionName = { step -> contents.titleOf(chapterAt(step)).orEmpty() }
        nav.sectionGroup = { step -> chapterAt(step) }
        nav.jumpTo = { step ->
            markOrigin()
            scope.launch {
                publication.locateProgression(step / (SCRUB_STEPS - 1.0))?.let { navigator?.go(it, animated = false) }
            }
        }
        nav.step = { delta ->
            contents.step(nav.tocIndex, delta)?.let {
                markOrigin()
                navigator?.go(it, animated = false)
            }
        }
        nav.canStep = { delta -> contents.step(nav.tocIndex, delta) != null }
        onDispose {}
    }
    // Read-aloud lives as long as the book is open, whatever else changes. Another book opened
    // over this one (from a widget, a shortcut) ends it mid-sentence: that sentence is the place.
    val saveLastSpoken by rememberUpdatedState { saveSpoken() }
    DisposableEffect(listen) {
        nav.listenController = listen
        onDispose {
            saveLastSpoken()
            listen.stop()
            nav.listenController = null
        }
    }
    // While the book is read aloud, a tap on a sentence has the voice read on from there, wherever
    // it was; a tap anywhere else (a margin, a picture, the space between paragraphs) brings up the
    // controls as ever. Page turns are by swiping, meanwhile: the whole page is there to tap.
    voiceTap.onTap = { n, point ->
        if (!nav.listen.active) {
            false
        } else {
            scope.launch {
                when (listen.listenAt(n, point)) {
                    TapOutcome.MOVED -> haptics.tick()
                    TapOutcome.LOST -> haptics.reject()
                    TapOutcome.NO_TEXT -> tap()
                }
            }
            true
        }
    }

    // Pad for the system bars as if they were always showing, so pages never re-flow when the
    // controls come and go.
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.statusBarsIgnoringVisibility
                    .union(WindowInsets.navigationBarsIgnoringVisibility)
                    .union(WindowInsets.displayCutout),
            )
            // The footer's room, and only ever that: the player floats over the page and rests in
            // the footer, so reading aloud leaves the page exactly as it is.
            .padding(bottom = FooterSpace)
            // Readium loses your place when the page area changes size (it computes the corrected
            // scroll offset but never applies it): folding, unfolding, rotating. Remember where
            // you were and go back once the pages have re-flowed.
            // A finger dragging across the page (a swipe, a scroll) is the reader moving on themselves;
            // watched before the page's own gestures, without taking anything from them.
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        var dragged = 0f
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            dragged += (change.position - change.previousPosition).getDistance()
                            if (dragged > slop) nav.movedAt = SystemClock.uptimeMillis()
                            if (!change.pressed) break
                        }
                    }
                }
            }
            .pointerInput(turner) { pageTurnGestures(turner) }
            .onSizeChanged { size ->
                val old = resize.size
                resize.size = size
                if (old == IntSize.Zero || old == size) return@onSizeChanged
                putBack()
            },
    ) {
        // The fragment manager asks our factory for the navigator as this composes.
        DisposableEffect(factory) {
            ReaderFragments.epub = factory
            onDispose { if (ReaderFragments.epub === factory) ReaderFragments.epub = null }
        }
        PageBelow(turner)
        AndroidFragment<EpubNavigatorFragment>(Modifier.fillMaxSize().livePage(turner)) { fragment -> navigator = fragment }
        PageAbove(turner)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reflowVeil.value }.background(page.background))
    }
}

/**
 * Keeps the reader's place while the pages re-flow (a fold, a turn of the phone, a new text size)
 * and while a navigator finds the page it opens at. Readium keeps the page *number* through a
 * re-flow, not the place, and reports that page as where the reader is. So from the moment the
 * pages change until they have been put back, the place is [target], and nothing Readium reports
 * is saved as the reader's.
 */
private class ResizeKeeper {
    var size = IntSize.Zero
    /** The place to put the page back at; null once Readium has been seen there. */
    var target: Locator? = null
    var job: Job? = null
    /** The place the last putting-back aimed for, until the reader moves on. */
    var place: Locator? = null
    var restoredAt = 0L
    /** The file on screen and its page count, as last reported: a new count is a re-flow. */
    var pages: FilePage? = null
    /** The settings the pages were last laid out with. */
    var settings: EpubPreferences? = null
    /** The last page saved as the reader's place: as Readium reported it, and as known by its first words. */
    var known: Pair<Locator, Locator>? = null

    /**
     * The place to keep for the page Readium reports at [here]: by its first words when they are
     * known (the same words are on the page it is put back to, however the pages now fall), else
     * by how far down the file it is.
     */
    fun keep(here: Locator?): Locator? = known?.takeIf { it.first === here }?.second ?: here?.byProgression()
}

/** The sentence the voice is on, and what has been done about it. */
private class SpokenPlace {
    var sentence: Locator? = null
    /** The sentence last saved as the book's place, how far through the book it was saved as, and when. */
    var saved: Locator? = null
    var savedProgress = -1.0
    var savedAt = 0L
    /** The voice has read on while the page was out of sight and couldn't follow. */
    var unseen = false
    /** The page the voice was last seen on (see [spokenWithin]). */
    var seenOn: SeenOn? = null
}

/**
 * The page the voice was last seen on: the chapter [file] it is in, how far down the file the
 * [page] starts, how many [pages] the file had, and what the page's count of the place is ahead
 * of the sentence's own ([lead], see [pageLead]).
 */
private class SeenOn(val file: Int, val page: Double, val pages: Int?, val lead: Double)

/**
 * Whether Readium, reporting [this] place, is on the page that holds [kept]. A place kept by its
 * words (a page's first words, the sentence the voice was on) is found by them, on whichever page
 * they now fall: it need only be somewhere near where it was (see [Pages.holdsWords]).
 */
private fun Locator.shows(kept: Locator, pages: Int?, overflow: OverflowableNavigator.Overflow): Boolean {
    if (!href.removeFragment().isEquivalent(kept.href.removeFragment())) return false
    val at = locations.progression ?: return false
    val wanted = kept.locations.progression ?: return true
    return when {
        kept.text.highlight != null -> Pages.holdsWords(at, wanted, pages)
        pages == null || pages <= 0 || overflow.scroll -> Pages.near(at, wanted)
        overflow.readingProgression == ReadingProgression.LTR -> Pages.shows(at, wanted, pages)
        // Right to left, Readium counts its pages the other way: within a page is as near as can be told.
        else -> kotlin.math.abs(at - wanted) < 1.0 / pages
    }
}

/**
 * The same place, located by its progression through the file only: the text snippet and
 * element id in a live locator point at the start of a Readium position, not at your page.
 */
private fun Locator.byProgression(): Locator =
    copy(text = Locator.Text()).copyWithLocations(fragments = emptyList())

/**
 * The page at [this] place, known by the first words on it as well (see [TOP_TEXT_SCRIPT]): the
 * way back to the same page however the pages fall next time. Too few words to tell one place
 * from another (a chapter's number at the top of a page), and it stays a place in the file only.
 */
private suspend fun Locator.byFirstWords(navigator: EpubNavigatorFragment): Locator {
    val top = runCatching { navigator.evaluateJavascript(TOP_TEXT_SCRIPT)?.let(::JSONObject) }.getOrNull()
    val words = top?.optString("quote").orEmpty()
    if (words.trim().length < FIRST_WORDS_ENOUGH) return byProgression()
    return byProgression().copy(text = Locator.Text(before = top?.optString("before"), highlight = words))
}

/** How many letters of a page's first words are enough to find it by. */
private const val FIRST_WORDS_ENOUGH = 24

/**
 * Waits until the page has re-flowed for the new [size] (the web view reports that size and its
 * page count holds still), then returns that page count; null if it doesn't settle in time.
 */
private suspend fun EpubNavigatorFragment.awaitLayout(size: IntSize): Int? {
    var last = -1
    repeat(30) {
        delay(100)
        val json = runCatching { evaluateJavascript(LAYOUT_SCRIPT)?.let(::JSONObject) }.getOrNull() ?: return@repeat
        val width = json.optDouble("w")
        val height = json.optDouble("h")
        val pages = json.optInt("pages", -1)
        if (kotlin.math.abs(width - size.width) <= 3 && kotlin.math.abs(height - size.height) <= 3) {
            if (pages == last && pages >= 1) return pages
            last = pages
        }
    }
    return null
}

private const val LAYOUT_SCRIPT = "(function(){var s=document.scrollingElement,d=window.devicePixelRatio;" +
    "return {w: window.innerWidth*d, h: window.innerHeight*d, pages: Math.round(s.scrollWidth/window.innerWidth)};})()"

/**
 * Readium opens the page *nearest* to a progression, which can be the page after the one holding
 * it. Aim a quarter page in, so the page that holds [this] place is the one that opens.
 */
private fun Locator.onPage(pages: Int?, overflow: OverflowableNavigator.Overflow): Locator {
    val progression = locations.progression ?: return this
    if (pages == null || pages <= 0 || overflow.scroll || overflow.readingProgression != ReadingProgression.LTR) return this
    return copyWithLocations(progression = Pages.aim(progression, pages))
}

private fun navigatorScrolls(navigator: EpubNavigatorFragment?): Boolean =
    navigator?.overflow?.value?.scroll == true

private data class FilePage(val href: String = "", val index: Int = 0, val count: Int = 0)

private fun footerFor(resources: Resources, scroll: Boolean, chapter: String, file: FilePage, locator: Locator, here: Located?): String {
    if (scroll) return chapter
    val left = when {
        here?.pagesToNext != null -> here.pagesToNext
        file.href == locator.href.toString() && file.count > 0 -> file.count - 1 - file.index
        else -> return chapter
    }.coerceAtLeast(0)
    return if (left == 0) {
        resources.getString(R.string.reader_footer_last_page)
    } else {
        resources.getQuantityString(R.plurals.reader_footer_pages_left, left, left)
    }
}

/**
 * The place the book was left at; null to open at the first page. A saved place that can't be
 * read, or that names a file the book doesn't have, falls back on what was saved beside it rather
 * than on page one (see [reopen]).
 */
private suspend fun savedLocator(book: Book, publication: Publication, file: File): Locator? {
    val saved = book.locator?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }
    val files = publication.readingOrder
    val usable = saved != null && files.any { it.url().removeFragment().isEquivalent(saved.href.removeFragment()) }
    // Only asked for when the saved place can't be used: counting them reads the whole book.
    val positions = if (usable || book.locator == null) emptyList() else runCatching { publication.positions() }.getOrDefault(emptyList())
    return when (val plan = reopen(book.locator != null, usable, book.position, positions.size, book.progress, book.positionOffset)) {
        Reopen.Saved -> saved
        is Reopen.AtProgress -> {
            // The same sum that made the progress, the other way round: the file, and how far down it.
            val (resource, within) = if (positions.isEmpty()) null to 0.0 else Spans(positions, Contents(publication)).locate(plan.progress)
            resource?.let(files::getOrNull)?.let(publication::locatorFromLink)?.copyWithLocations(progression = within)
                ?: publication.locateProgression(plan.progress)
        }
        is Reopen.AtPosition -> positions.getOrNull(plan.index)
        is Reopen.AtChapter -> {
            // 0.2 counted chapters in its own spine, which kept non-linear items (a cover, say) that
            // Readium leaves out: find the chapter by its file, not its number.
            val path = runCatching {
                ZipFile(file).use { zip -> Epub.readPackage { zip.getEntry(it)?.let(zip::getInputStream) }.spine }
            }.getOrNull()?.getOrNull(plan.chapter) ?: return null
            val link = files.firstOrNull { it.url().path?.removePrefix("/") == path } ?: return null
            publication.locatorFromLink(link)?.copyWithLocations(progression = plan.offset)
        }
        Reopen.Start -> null
    }
}

private fun openLink(context: Context, url: AbsoluteUrl) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.reader_no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------------------------------------
// Contents: chapters and sub-chapters, and which one you're in
// ---------------------------------------------------------------------------------------------

private class TocEntry(val title: String, val level: Int, val link: Link, val resource: Int, val fragment: String?)

/**
 * Where on the page the reader is, relative to the chapter anchors in [resource]: the chapter
 * you're in, pages until the next one, and where each anchored chapter starts (0..1 in the file).
 */
private class Located(val resource: Int, val chapter: Int, val pagesToNext: Int?, val starts: List<Pair<Int, Double>>)

/** Where each file of the book starts, as a fraction of the whole (from Readium's positions). */
private class Spans(positions: List<Locator>, contents: Contents) {
    private val start = DoubleArray(contents.fileCount + 1) { Double.NaN }

    init {
        for (p in positions) {
            val file = contents.resourceOf(p.href)
            val at = p.locations.totalProgression ?: continue
            if (file >= 0 && (start[file].isNaN() || at < start[file])) start[file] = at
        }
        start[contents.fileCount] = 1.0
        // A file without positions (empty, say) takes up no room.
        for (file in contents.fileCount - 1 downTo 0) if (start[file].isNaN()) start[file] = start[file + 1]
    }

    /** Whole-book progress of [progression] (0..1) through [file]. */
    fun total(file: Int, progression: Double): Double? {
        if (file !in 0 until start.size - 1) return null
        return start[file] + progression.coerceIn(0.0, 1.0) * (start[file + 1] - start[file])
    }

    /** The file at whole-book [total] progress, and how far through it that is. */
    fun locate(total: Double): Pair<Int, Double> {
        var file = 0
        for (i in 0 until start.size - 1) if (start[i] <= total) file = i
        val span = start[file + 1] - start[file]
        return file to if (span > 0) ((total - start[file]) / span).coerceIn(0.0, 1.0) else 0.0
    }
}

private class Contents(private val publication: Publication) {
    private val files = publication.readingOrder.map { it.url().removeFragment() }
    val entries: List<TocEntry> = buildList {
        fun walk(links: List<Link>, level: Int) {
            for (link in links) {
                val url = link.url()
                val title = link.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val resource = files.indexOfFirst { it.isEquivalent(url.removeFragment()) }
                if (title.isNotEmpty()) add(TocEntry(title, level, link, resource, url.fragment?.takeIf { it.isNotEmpty() }))
                walk(link.children, if (title.isNotEmpty()) level + 1 else level)
            }
        }
        walk(publication.tableOfContents, 0)
    }

    /** For each file: the chapter in effect where it starts, and where it ends. */
    private val atStart = IntArray(files.size) { -1 }
    private val atEnd = IntArray(files.size) { -1 }

    init {
        var carry = -1
        for (file in files.indices) {
            val here = entries.indices.filter { entries[it].resource == file }
            atStart[file] = here.firstOrNull { entries[it].fragment == null } ?: here.firstOrNull() ?: carry
            if (here.isNotEmpty()) carry = here.last()
            atEnd[file] = carry
        }
    }

    val fileCount: Int get() = files.size

    fun resourceOf(href: org.readium.r2.shared.util.Url): Int {
        val target = href.removeFragment()
        return files.indexOfFirst { it.isEquivalent(target) }
    }

    fun chapterAt(resource: Int): Int = atStart.getOrElse(resource) { -1 }

    fun hasAnchors(resource: Int): Boolean = entries.any { it.resource == resource && it.fragment != null }

    /** The chapter at [within] (0..1) through [resource], using its anchor positions if [known]. */
    fun chapterAt(resource: Int, within: Double, known: Located?): Int {
        if (known == null || known.resource != resource || known.starts.isEmpty()) return chapterAt(resource)
        return known.starts.lastOrNull { (_, start) -> start <= within + 0.0005 }?.first ?: beforeAnchors(resource)
    }

    /** The chapter in effect before a file's first anchor: its own entry, else the previous file's last. */
    private fun beforeAnchors(resource: Int): Int =
        entries.indices.firstOrNull { entries[it].resource == resource && entries[it].fragment == null }
            ?: atEnd.getOrElse(resource - 1) { -1 }

    fun titleOf(chapter: Int): String? = entries.getOrNull(chapter)?.title

    /**
     * Where [chapter] starts and ends, as fractions of the whole book: from its own start to the
     * next entry that starts further on (a chapter and its first section can share a start).
     * Anchored entries are placed by [known] when it covers their file, else where the file starts.
     */
    fun span(chapter: Int, spans: Spans, known: Located?): ClosedFloatingPointRange<Double>? {
        fun startOf(index: Int): Double? {
            val entry = entries.getOrNull(index) ?: return null
            val within = if (entry.fragment == null) 0.0 else {
                known?.takeIf { it.resource == entry.resource }?.starts?.firstOrNull { it.first == index }?.second ?: 0.0
            }
            return spans.total(entry.resource, within)
        }
        val start = startOf(chapter) ?: return null
        val end = (chapter + 1 until entries.size).firstNotNullOfOrNull { next -> startOf(next)?.takeIf { it > start + 1e-6 } } ?: 1.0
        return start..end
    }

    /** The chapter [delta] entries away in the contents (from before the first: the first). */
    fun step(current: Int, delta: Int): Link? {
        if (entries.isEmpty()) return null
        val target = if (current < 0) (if (delta > 0) 0 else -1) else current + delta
        return entries.getOrNull(target)?.link
    }

    /**
     * Asks the page which sub-chapter anchors are behind you: the last one passed is the chapter
     * you're in, the next one tells how many pages are left before it.
     */
    suspend fun locate(navigator: EpubNavigatorFragment, resource: Int, scroll: Boolean): Located? {
        val anchored = entries.indices.filter { entries[it].resource == resource && entries[it].fragment != null }
        if (anchored.isEmpty()) return null
        val ids = JSONArray(anchored.map { entries[it].fragment })
        val result = runCatching { navigator.evaluateJavascript(anchorScript(ids, scroll)) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(result) }.getOrNull() ?: return null
        val passed = json.optInt("cur", -1)
        // Before the first anchor: the file's own entry, else whatever the previous file ended in.
        val chapter = if (passed >= 0) anchored[passed] else beforeAnchors(resource)
        val pagesToNext = if (scroll || json.isNull("left")) null else json.optInt("left")
        val starts = json.optJSONArray("starts")?.let { array ->
            anchored.indices.mapNotNull { i -> array.optDouble(i, -1.0).takeIf { it >= 0 }?.let { anchored[i] to it } }
        }.orEmpty().sortedBy { it.second }
        return Located(resource, chapter, pagesToNext, starts)
    }
}

private fun anchorScript(ids: JSONArray, scroll: Boolean) = """
    (function(ids, scroll) {
      var w = window.innerWidth, h = window.innerHeight, cur = -1, next = null, left = null, starts = [];
      var se = document.scrollingElement;
      var screen = Math.round(se.scrollLeft / w);
      for (var i = 0; i < ids.length; i++) {
        var el = document.getElementById(ids[i]) || document.getElementsByName(ids[i])[0];
        if (!el) { starts.push(-1); continue; }
        var r = el.getBoundingClientRect();
        var p = scroll ? r.top : r.left;
        if (p < (scroll ? h * 0.3 : w - 1)) cur = i;
        else if (next === null || p < next) {
          next = p;
          // Screens still holding this chapter: up to the anchor's screen, which counts only if
          // the anchor sits below the top of its first column.
          var x = r.left + se.scrollLeft, at = Math.floor(x / w);
          var atTop = x - at * w < w * 0.25 && r.top < h * 0.2;
          left = at - screen - (atTop ? 1 : 0);
        }
        // How far through the file this anchor sits (0..1).
        starts.push(scroll ? (r.top + se.scrollTop) / se.scrollHeight : (r.left + se.scrollLeft) / se.scrollWidth);
      }
      return { cur: cur, left: left, starts: starts };
    })($ids, $scroll)
""".trimIndent()

// ---------------------------------------------------------------------------------------------
// Highlights, bookmarks and search
// ---------------------------------------------------------------------------------------------

private const val HIGHLIGHTS = "highlights"

private const val AT_BOTTOM_SCRIPT =
    "(function(){var s=document.scrollingElement;return s.scrollTop+window.innerHeight>=s.scrollHeight-8;})()"
private const val SEARCH = "search"

/**
 * The first words on the page: where they start (the word under the top-left of the text), a
 * short exact quote to find them again, and a tidy snippet for the bookmark list.
 */
private val TOP_TEXT_SCRIPT = """
    (function() {
      var w = window.innerWidth, h = window.innerHeight, hit = null;
      for (var y = 0.03; y < 0.6 && !hit; y += 0.03) {
        for (var x = 0.06; x < 0.45 && !hit; x += 0.1) {
          var c = document.caretRangeFromPoint(w * x, h * y);
          if (c && c.startContainer.nodeType === 3 && c.startContainer.textContent.slice(c.startOffset).trim()) hit = c;
        }
      }
      if (!hit) return null;
      var node = hit.startContainer, t = node.textContent, off = hit.startOffset;
      while (off > 0 && /\S/.test(t[off - 1])) off--;
      var quote = t.slice(off, off + 60);
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      walker.currentNode = node;
      var text = t.slice(off), n = walker.nextNode();
      while (n && text.length < 200) { text += ' ' + n.textContent; n = walker.nextNode(); }
      return { before: t.slice(Math.max(0, off - 40), off), quote: quote, text: text.replace(/\s+/g, ' ').trim().slice(0, 160) };
    })()
""".trimIndent()

/** Full-text search with Readium's search service; results are marked on the page when shown. */
private class EpubSearch(
    private val publication: Publication,
    private val contents: Contents,
    private val navigator: () -> EpubNavigatorFragment?,
    private val scope: CoroutineScope,
) : BookSearch {
    var tint: Int = 0
    /** Called just before the page jumps to a result. */
    var beforeShow: () -> Unit = {}
    private val found = mutableListOf<Locator>()

    override suspend fun search(query: String, onHits: (List<SearchHit>) -> Unit) {
        found.clear()
        val iterator = publication.search(query) ?: return
        try {
            while (true) {
                val page = iterator.next().getOrNull() ?: break
                val batch = page.locators.map { locator ->
                    found += locator
                    val chapter = contents.titleOf(contents.chapterAt(contents.resourceOf(locator.href))) ?: locator.title
                    SearchHit(
                        index = found.lastIndex,
                        chapter = chapter.orEmpty(),
                        before = locator.text.before.orEmpty(),
                        match = locator.text.highlight.orEmpty(),
                        after = locator.text.after.orEmpty(),
                    )
                }
                if (batch.isNotEmpty()) onHits(batch)
            }
        } finally {
            iterator.close()
        }
    }

    override fun show(hit: SearchHit) {
        val locator = found.getOrNull(hit.index) ?: return
        val n = navigator() ?: return
        beforeShow()
        n.go(locator, animated = false)
        scope.launch { n.applyDecorations(listOf(Decoration("hit", locator, Marker(tint))), SEARCH) }
    }

    override fun clear() {
        found.clear()
        val n = navigator() ?: return
        scope.launch { n.applyDecorations(emptyList(), SEARCH) }
    }
}

// ---------------------------------------------------------------------------------------------
// Read aloud: Readium's TTS, with the words inking in as they're spoken
// ---------------------------------------------------------------------------------------------

/** Where a tap on the page goes while the book is read aloud: set once read-aloud is there to take it. */
private class VoiceTap {
    /** True if it took the tap at [point] (in the navigator's pixels) on the page [EpubNavigatorFragment] shows. */
    var onTap: (EpubNavigatorFragment, PointF) -> Boolean = { _, _ -> false }
}

/** What a tap on the page did while the book was read aloud. */
private enum class TapOutcome {
    /** It wasn't on the words: it's the page's ordinary tap. */
    NO_TEXT,

    /** The voice went to the sentence tapped. */
    MOVED,

    /** It was on words the voice's reading of the file couldn't find. */
    LOST,
}

private class ReadAloud(
    private val context: Context,
    private val publication: Publication,
    /** The book's files as the voice reads them: where its sentences are, in the pages' terms. */
    private val files: SpokenFiles,
    private val bookId: String,
    private val navigator: () -> EpubNavigatorFragment?,
    private val listen: ListenState,
    private val scope: CoroutineScope,
    /** Which chapter file a locator is in, so the sleep timer knows when one ends. */
    private val fileOf: (org.readium.r2.shared.util.Url) -> Int,
    /** The page is being put back after the phone folded, unfolded or turned: not the listener moving it. */
    private val resizing: () -> Boolean = { false },
    /** When the reader last moved through the book themselves (see [ReaderNav.movedAt]). */
    private val movedAt: () -> Long = { 0L },
) : ListenController {
    /** When the page last moved to follow the voice: moves just after are the voice's, not the listener's. */
    private var followedAt = 0L

    /**
     * Listening stopped while the listener was looking elsewhere in the book: the sentence the
     * voice had reached, which isn't on the page in front of them.
     */
    var onStoppedElsewhere: (Locator) -> Unit = {}
    /** How the mark left where the voice stopped comes and goes (the theme's fades). */
    var hereAppears: AnimationSpec<Float> = snap()
    var hereFades: AnimationSpec<Float> = snap()
    private var here: Job? = null

    /**
     * The voice has started on a sentence. Told whether or not the page can follow: shut in a
     * pocket or behind another app it can't turn, and this is the only word of where the voice is.
     * The sentence is told as a place the pages can go by (see [SpokenFiles.placed]).
     */
    var onSpoken: (Locator) -> Unit = {}

    /** The sentence the voice is on, as Readium's read-aloud names it, and ([lastPlaced]) as the pages are sent to it. */
    private var lastSpoken: Locator? = null
    private var lastPlaced: Locator? = null

    /**
     * The sentence the voice stopped on (or, from the book's saved place, last stopped on): started
     * again on the page that holds it, the voice carries on from it rather than from the top of the page.
     */
    var stoppedAt: Locator? = null
    private var mediaSession: androidx.media3.session.MediaSession? = null
    /** Highlight tint and page color, for the read-along decorations. */
    var colors: Pair<Int, Int> = 0 to 0
    private var speed = 1f

    /** The natural voice that reads this book, or null for the phone's own. */
    var voice: VoiceRef? = null

    /** The language the listener chose to read in, when it isn't the one the book gives. */
    var language: String? = null
    /** The language, with the reader's region, to ask the phone's own voice for; null to go by the book. */
    var phoneVoiceLanguage: String? = null

    override val previousLabel = R.string.listen_previous_sentence
    override val nextLabel = R.string.listen_next_sentence
    private val systemFactory by lazy { AndroidTtsNavigatorFactory(context.applicationContext as Application, publication) }

    /** Readium's read-aloud navigator, whichever voice is behind it. */
    private var tts: TtsNavigator<*, *, *, *>? = null
    private var submitRate: (Float) -> Unit = {}
    private var session: Job? = null

    override fun start() {
        tts?.let {
            it.play()
            return
        }
        // A fresh start reads from the page in view: the listener is where the voice is.
        listen.offPage = false
        // Still starting up (the voice takes a moment): one session at a time.
        if (session?.isActive == true) return
        begin(from = null)
    }

    /** Starts reading at [from], or at the top of what's on screen. */
    private fun begin(from: Locator?, playing: Boolean = true) {
        // The voice is back: the mark of where it stopped has done its work.
        here?.cancel()
        tappedSentence = null
        listen.active = true
        listen.playing = playing
        // A natural voice needs a moment to load and to make its first sentence: until its first
        // word the player says so, rather than look as if it were already reading.
        listen.preparing = playing && voice != null
        session = scope.launch {
            // Started again on the page where it stopped: the sentence it stopped on, in the block of
            // Readium's own reading of the file that holds it.
            val resume = if (from == null) navigator()?.let { resumeOn(it) } else null
            // Otherwise the first block of text on screen (wherever the page is, if it can't say), as
            // Readium's own read-aloud knows it: named the page's way, the voice could start at the
            // top of the chapter file instead, and take the reader's place there with it.
            val start = from ?: resume?.first?.locator ?: navigator()?.let { n ->
                // What the page shows from its first word on finds the block wherever the page begins.
                val top = runCatching { n.evaluateJavascript(TOP_TEXT_SCRIPT)?.let(::JSONObject)?.optString("text") }.getOrNull()
                files.readAloudStart(n.firstVisibleElementLocator(), n.currentLocator.value, top)
            }
            val tts = open(start) ?: return@launch
            // Readium can only start at a block of text, and the first one on a page has usually
            // begun on the page before. A fresh start passes over the sentences that lie wholly
            // before the page in view, so the voice begins with the first one in front of the
            // listener and the page has no call to move; a change of voice passes over the ones
            // before the sentence it interrupted.
            // Carrying on from where it stopped, it steps on to that sentence first (and from there,
            // still, past any that lie wholly before the page).
            val onPage = if (from == null) {
                resume?.let { (block, offset) -> stepTo(tts, bare(block.text), offset) }
                navigator()?.let { skipToPage(tts, it) } == true
            } else {
                skipToSentence(tts, from).let { false }
            }
            if (!isActive) {
                // Stopped while the voice was starting.
                tts.close()
                return@launch
            }
            this@ReadAloud.tts = tts
            // Lock screen, notification and headphone controls; keeps playing with the screen off.
            mediaSession = ListenService.attach(context.applicationContext, tts.asMedia3Player(), bookId)
            launch {
                // A voice that hasn't been told to play yet reports "not playing": that is where it
                // starts from, not a pause, and showing it would flick the player to paused and back.
                var started = !playing
                tts.playback.collect { playback ->
                    val now = playback.playWhenReady && playback.state is TtsNavigator.State.Ready
                    if (now) started = true
                    // Held still while a tap moves the voice: it carries on reading where it lands.
                    if (started && !jumping) {
                        listen.playing = now
                        if (!now) listen.preparing = false
                    }
                    when (val state = playback.state) {
                        is TtsNavigator.State.Ended -> stop()
                        is TtsNavigator.State.Failure -> fail(context.getString(R.string.listen_stopped_error, state.error.message))
                        else -> {}
                    }
                }
            }
            launch {
                // A page the voice didn't turn (a swipe, a scroll, a jump) means the listener went to
                // look at something: the page stops following until they go back. The navigator is
                // swapped when the phone folds, so whichever is current is watched.
                snapshotFlow { navigator() }.filterNotNull().collectLatest { n ->
                    n.currentLocator.drop(1).collect {
                        val now = SystemClock.uptimeMillis()
                        // Only when the listener moved it (a swipe, a page turn, a jump): a page re-flowing
                        // by itself (the player arriving, the controls) isn't going elsewhere.
                        val byListener = now - movedAt() < LISTENER_MOVE_MS
                        if (listen.active && !listen.offPage && byListener && now - followedAt > FOLLOW_SETTLE_MS && !resizing()) listen.offPage = true
                    }
                }
            }
            launch {
                var utterance: Locator? = null
                // The sentence a fresh start begins with is in front of the listener already,
                // though it may have begun on the page before: the page stays where it is for it.
                var stay = onPage
                tts.location.collect { location ->
                    // The sentences a tap moves the voice past aren't shown, said or followed.
                    if (jumping) return@collect
                    listen.spokenFile = fileOf(location.href)
                    // Paused, the voice reports no word: the sentence keeps the ink it has.
                    val spokenTo = location.range?.last?.plus(1)
                    // Its first word: it's reading now.
                    if (spokenTo != null) listen.preparing = false
                    if (location.utterance != listen.phrase) {
                        listen.phrase = location.utterance
                        listen.spoken = spokenTo ?: 0
                    } else if (spokenTo != null) {
                        listen.spoken = spokenTo
                    }
                    // A new sentence: while the voice speaks, it is the reader's place in the book.
                    // The pages are told where it is in their own terms, not in read-aloud's: sent
                    // after a sentence they can't find by its words, they go by where it is.
                    if (location.utteranceLocator != lastSpoken) {
                        val placed = files.placed(location.utteranceLocator)
                        lastPlaced = placed
                        onSpoken(placed)
                    }
                    lastSpoken = location.utteranceLocator
                    val n = navigator() ?: return@collect
                    // Turn the page to follow the voice, unless the listener has gone to look elsewhere.
                    if (location.utteranceLocator != utterance) {
                        utterance = location.utteranceLocator
                        // A tapped sentence is in front of the listener: no call to move the page for it.
                        if (!listen.offPage && !stay && location.utteranceLocator != tappedSentence) {
                            followedAt = SystemClock.uptimeMillis()
                            n.go(lastPlaced ?: location.utteranceLocator, animated = false)
                            followedAt = SystemClock.uptimeMillis()
                        }
                        stay = false
                    }
                    n.applyDecorations(decorationsFor(location), GROUP)
                }
            }
            if (playing) tts.play()
        }
    }

    /**
     * Passes over the sentences that lie wholly before the page in view, so a fresh start begins
     * with the first sentence the listener can see. The page itself says where each one is: it is
     * laid out there as an invisible decoration, the way the read-along highlight is, and asked
     * for. True if the voice now stands on a sentence that is, at least in part, on the page;
     * otherwise it is left where Readium started it.
     */
    private suspend fun skipToPage(tts: TtsNavigator<*, *, *, *>, n: EpubNavigatorFragment): Boolean {
        val overflow = n.overflow.value
        val script = "$WHERE_SCRIPT(${overflow.scroll}, ${overflow.readingProgression == ReadingProgression.RTL})"
        val first = tts.location.value
        var passed = 0
        // How many sentences in a row, just passed, the page couldn't say where they are.
        var unplaced = 0
        // Which laying-out of the probe the page last answered for: each one is numbered.
        var answered = -1
        try {
            while (passed < MAX_START_SKIPS) {
                val at = tts.location.value
                // Not into the next chapter file: its pages aren't the one being asked.
                if (at.href != first.href) break
                n.applyDecorations(listOf(Decoration("probe", at.utteranceLocator.findable(), HereMark(0))), PROBE)
                // Readium lays a decoration out on the page's next frame, not at once: ask until
                // this one is there. (One the page can't find never is.)
                val found = withTimeoutOrNull(PROBE_MS) {
                    var answer: JSONObject? = null
                    while (answer == null) {
                        answer = runCatching { n.evaluateJavascript(script)?.let(::JSONObject) }.getOrNull()?.takeIf { it.optInt("n", -1) > answered }
                        if (answer == null) delay(PROBE_POLL_MS)
                    }
                    answer
                }
                if (found != null) answered = found.optInt("n", -1)
                when (found?.optInt("at", UNPLACED) ?: UNPLACED) {
                    0 -> {
                        // The sentences just before it that the page couldn't place may be the
                        // page's first: the voice begins with them. Better heard again than not at all.
                        repeat(unplaced) { if (!skipSentence(tts, tts.location.value, forward = false)) return true }
                        return true
                    }
                    -1 -> {
                        unplaced = 0
                        if (skipSentence(tts, at, forward = true)) passed++ else break
                    }
                    // A sentence the page can't place says nothing of where the page is: the next
                    // one will. (Taken for one after the page, it sent the voice back to the top
                    // of its paragraph, and the page after it: pages back, in a long one.)
                    UNPLACED -> if (++unplaced <= MAX_UNPLACED && skipSentence(tts, at, forward = true)) passed++ else break
                    else -> {
                        // Past the page: it was one of those the page couldn't place, if any.
                        if (unplaced == 0) break
                        repeat(unplaced) { if (!skipSentence(tts, tts.location.value, forward = false)) return true }
                        return true
                    }
                }
            }
            // No sentence found on the page: back to where it started, and the old way from there.
            repeat(passed) { if (!skipSentence(tts, tts.location.value, forward = false)) return false }
            return false
        } finally {
            withContext(NonCancellable) { runCatching { n.applyDecorations(emptyList(), PROBE) } }
        }
    }

    /**
     * Passes over the sentences of its paragraph that come before [sentence]: a change of voice
     * carries on from the sentence it interrupted, not from the top of the paragraph. (Voices
     * don't all cut sentences alike, so the one holding its first words will do.)
     */
    private suspend fun skipToSentence(tts: TtsNavigator<*, *, *, *>, sentence: Locator) {
        val wanted = sentence.text.highlight?.takeIf { it.isNotBlank() } ?: return
        repeat(MAX_START_SKIPS) {
            val at = tts.location.value
            val reached = at.utterance == wanted || at.utterance.contains(wanted.take(SAME_SENTENCE_CHARS)) ||
                wanted.contains(at.utterance.take(SAME_SENTENCE_CHARS))
            // Not past the paragraph it was in: better a sentence heard twice than one never heard.
            if (reached || at.utteranceLocator.locations != sentence.locations || !skipSentence(tts, at, forward = true)) return
        }
    }

    /**
     * Steps the voice, not speaking, sentence by sentence to the one holding the letter [offset]
     * letters into [text] (its block, spaces aside). Starting from the block after it, it steps back.
     */
    private suspend fun stepTo(tts: TtsNavigator<*, *, *, *>, text: String, offset: Int) {
        var first = true
        var onward = false
        repeat(MAX_START_SKIPS) {
            val at = tts.location.value
            val said = bare(at.utterance)
            // Where the sentence starts in the block: voices cut and space sentences their own
            // way, so it is known by its opening.
            val start = text.indexOf(said.take(SAME_SENTENCE_CHARS))
            val forward = when {
                start < 0 -> if (first) false else return
                start > offset -> false
                start + said.length <= offset -> true
                else -> return
            }
            first = false
            // Turned back after going on: the offset fell between two of the voice's sentences.
            // The earlier one will do.
            val turning = onward && !forward
            onward = forward
            if (!skipSentence(tts, at, forward) || turning) return
        }
    }

    /**
     * Where the voice stopped ([stoppedAt]), if that sentence is on the page [n] shows, at least in
     * part: the block of Readium's reading holding it, and how far into the block (spaces aside)
     * it begins. Null when the listener has gone on to another page, or it can't be found.
     */
    private suspend fun resumeOn(n: EpubNavigatorFragment): Pair<Content.TextElement, Int>? {
        val sentence = stoppedAt ?: return null
        val said = sentence.text.highlight?.let(::bare)?.takeIf { it.isNotEmpty() } ?: return null
        if (!n.currentLocator.value.href.isEquivalent(sentence.href)) return null
        val opening = said.take(SAME_SENTENCE_CHARS)
        val file = files.of(sentence.href, sentence.mediaType)
        // Its own block, by name; else the one block that holds its words.
        val block = file.blockOf(sentence)?.let(file.blocks::get) ?: return null
        // On the page in view? The page says, the sentence laid out there as an unseen mark.
        try {
            n.applyDecorations(listOf(Decoration("resume", sentence.findable(), HereMark(0))), PROBE)
            val script = "$WHERE_SCRIPT(${n.overflow.value.scroll}, ${n.overflow.value.readingProgression == ReadingProgression.RTL})"
            val found = withTimeoutOrNull(PROBE_MS) {
                var answer: JSONObject? = null
                while (answer == null) {
                    answer = runCatching { n.evaluateJavascript(script)?.let(::JSONObject) }.getOrNull()
                    if (answer == null) delay(PROBE_POLL_MS)
                }
                answer
            }
            if (found?.optInt("at", 1) != 0) return null
        } finally {
            withContext(NonCancellable) { runCatching { n.applyDecorations(emptyList(), PROBE) } }
        }
        return block to bare(block.text).indexOf(opening)
    }

    /** Moves the voice, not yet speaking, to the sentence after [at], or the one before it; false if it can't. */
    private suspend fun skipSentence(tts: TtsNavigator<*, *, *, *>, at: TtsNavigator.Location, forward: Boolean): Boolean {
        if (forward) {
            if (!tts.hasNextUtterance()) return false
            tts.skipToNextUtterance()
        } else {
            if (!tts.hasPreviousUtterance()) return false
            tts.skipToPreviousUtterance()
        }
        return withTimeoutOrNull(START_SKIP_MS) { tts.location.first { it.utteranceLocator != at.utteranceLocator } } != null
    }

    /**
     * Opens Readium's read-aloud navigator on the chosen voice: a natural one if there is one for
     * this book, otherwise (or if it can't be loaded) the phone's own. Null, with the reason
     * shown, if neither can speak.
     */
    private suspend fun open(from: Locator?): TtsNavigator<*, *, *, *>? {
        val app = context.applicationContext as Application
        val listener = object : TtsNavigator.Listener {
            override fun onStopRequested() = stop()
        }
        val forced = language?.let(::Language)
        voice?.let { ref ->
            val lookahead = SentenceLookahead()
            val natural = TtsNavigatorFactory(
                app, publication, NaturalTtsEngineProvider(context.voices, ref, lookahead, language),
                tokenizerFactory = lookahead::tokenizer,
            )?.createNavigator(listener, from, NaturalTtsPreferences(language = forced, speed = speed.toDouble()))?.getOrNull()
            if (natural != null) {
                submitRate = { rate -> natural.submitPreferences(NaturalTtsPreferences(language = forced, speed = rate.toDouble())) }
                return natural
            }
            // The natural voice can't be loaded (too little memory, say): the phone's reads on.
            Toast.makeText(context, R.string.voice_fell_back, Toast.LENGTH_LONG).show()
            listen.preparing = false
        }
        val factory = systemFactory ?: run {
            fail(context.getString(R.string.listen_no_readable_text))
            return null
        }
        // The phone's voice speaks the reader's accent when the book only names its language.
        val heard = forced ?: phoneVoiceLanguage?.let(::Language)
        val system = factory.createNavigator(listener, from, AndroidTtsPreferences(language = heard, speed = speed.toDouble())).getOrElse {
            fail(context.getString(R.string.listen_no_voice))
            return null
        }
        submitRate = { rate -> system.submitPreferences(AndroidTtsPreferences(language = heard, speed = rate.toDouble())) }
        return system
    }

    /** The voice or the language was changed while listening: carry on with it from the same sentence. */
    fun retune() {
        val running = tts ?: return
        val playing = running.playback.value.playWhenReady
        val at = lastSpoken
        session?.cancel()
        mediaSession?.let(ListenService::detach)
        mediaSession = null
        running.close()
        tts = null
        begin(at, playing)
    }

    /**
     * The sentence being read gets a soft highlight; the words still to come sit under a veil of
     * the page color, so each one inks in, light to full black (or white), as it's spoken.
     */
    private fun decorationsFor(location: TtsNavigator.Location): List<Decoration> {
        val (tint, paper) = colors
        val sentence = location.utteranceLocator
        val spoken = location.range?.last?.plus(1) ?: 0
        val unread = location.utterance.length - spoken
        return buildList {
            add(Decoration("sentence", sentence, Decoration.Style.Highlight(tint = tint)))
            if (unread > 0 && location.utterance.substring(spoken).isNotBlank()) {
                val rest = sentence.copy(text = sentence.text.substring(spoken until location.utterance.length))
                add(Decoration("unread", rest, UnreadVeil(paper.withAlpha(0.6f))))
            }
        }
    }

    override fun backToVoice() {
        listen.offPage = false
        val at = lastPlaced ?: lastSpoken ?: return
        scope.launch {
            followedAt = SystemClock.uptimeMillis()
            navigator()?.go(at, animated = false)
            followedAt = SystemClock.uptimeMillis()
        }
    }

    /** The voice is being moved to a tapped sentence (see [listenAt]). */
    private var jumping = false

    /** The sentence a tap sent the voice to: in front of the listener already, so the page stays put for it. */
    private var tappedSentence: Locator? = null

    /**
     * "Read from here": a tap on a sentence on the page [n] shows sends the voice to that sentence,
     * and it reads on from there, paused or not before. [point] is where the tap was, as Readium
     * gives it. The page is asked for the words under the finger, and they are found in Readium's
     * own reading of the file, which is where the voice reads from.
     */
    suspend fun listenAt(n: EpubNavigatorFragment, point: PointF): TapOutcome {
        val tts = tts ?: return TapOutcome.NO_TEXT
        val tapped = runCatching { n.evaluateJavascript("$TAPPED_SCRIPT(${point.x}, ${point.y})")?.let(::JSONObject) }.getOrNull()
            ?: return TapOutcome.NO_TEXT
        if (jumping) return TapOutcome.MOVED
        val page = n.currentLocator.value
        val blocks = files.of(page.href, page.mediaType).blocks
        val spot = tappedAt(blocks.map { it.text }, tapped.optString("before"), tapped.optString("after"))
        if (spot == null) return TapOutcome.LOST
        jumpTo(tts, blocks[spot.block], spot.offset)
        return TapOutcome.MOVED
    }

    /**
     * Moves the voice to the sentence of [block] holding the letter [offset] letters in (spaces
     * aside), held still on the way, so the sentences it passes are neither heard nor shown.
     *
     * Readium can only send a voice to a block, and a paused voice sent there doesn't say where
     * it is until it next moves: it is moved on by a sentence to say so, and from there forward or
     * back, sentence by sentence, to the one the tap is in.
     */
    private suspend fun jumpTo(tts: TtsNavigator<*, *, *, *>, block: Content.TextElement, offset: Int) {
        here?.cancel()
        jumping = true
        try {
            tts.pause()
            val text = bare(block.text)
            val was = tts.location.value
            val reading = bare(was.utterance)
            // Already in the block (a tap on the paragraph being read), the voice only steps to the
            // sentence; sent there, it could land on the very sentence it is on, and say nothing.
            val inBlock = was.utteranceLocator.locations == block.locator.locations ||
                (reading.length >= SAME_SENTENCE_CHARS && text.contains(reading.take(SAME_SENTENCE_CHARS)))
            if (!inBlock) {
                tts.go(block.locator)
                tts.skipToNextUtterance()
                // Readium finds the block by reading the file up to it, which in a long chapter
                // file takes a moment.
                withTimeoutOrNull(JUMP_GO_MS) { tts.location.first { it.utteranceLocator != was.utteranceLocator } }
            }
            // A block of one sentence: moving on said so from the next block. Back into it.
            stepTo(tts, text, offset)
        } finally {
            jumping = false
            // Wherever it landed, it reads on from there, the page as it is (unless listening was
            // stopped meanwhile).
            if (this.tts === tts && listen.active) {
                val now = tts.location.value
                tappedSentence = now.utteranceLocator
                listen.offPage = false
                listen.phrase = now.utterance
                listen.spoken = 0
                navigator()?.let { n -> scope.launch { n.applyDecorations(decorationsFor(now), GROUP) } }
                listen.preparing = voice != null
                listen.playing = true
                tts.play()
            }
        }
    }

    override fun toggle() {
        val tts = tts ?: return start() // start() ignores taps while it's still starting up
        if (tts.playback.value.playWhenReady) tts.pause() else tts.play()
    }

    override fun skip(delta: Int) {
        val tts = tts ?: return
        if (delta < 0) tts.skipToPreviousUtterance() else tts.skipToNextUtterance()
    }

    override fun setRate(rate: Float) {
        speed = rate
        if (tts != null) submitRate(rate)
    }

    /**
     * Where the voice is on the screen: the line holding the next word to be spoken (the first of
     * those still under the veil), else the last line of the sentence that is on this page. Asked
     * of the page itself, which is where the read-along decorations were laid out.
     */
    override suspend fun spokenLine(): Rect? {
        if (!listen.active || listen.offPage) return null
        return navigator()?.let { lineOnPage(it) }
    }

    /** The line of the read-along that is on the page [n] is showing, in the window's pixels; null if none of it is. */
    private suspend fun lineOnPage(n: EpubNavigatorFragment): Rect? = runCatching {
        val line = n.evaluateJavascript(SPOKEN_LINE_SCRIPT)?.let(::JSONObject) ?: return null
        val at = IntArray(2).also(n.publicationView::getLocationInWindow)
        Rect(
            at[0] + line.getDouble("l").toFloat(), at[1] + line.getDouble("t").toFloat(),
            at[0] + line.getDouble("r").toFloat(), at[1] + line.getDouble("b").toFloat(),
        )
    }.getOrNull()

    /**
     * "You were here": the sentence the voice stopped on keeps its highlight for a moment, a
     * little stronger, then lets go of it, on the theme's own fades. Nothing else on the page
     * changes. With animations removed it simply shows, and goes.
     */
    fun markHere(sentence: Locator) {
        here?.cancel()
        here = scope.launch {
            val n = navigator() ?: return@launch
            val tint = colors.first
            // It starts from about the strength the read-along highlight had.
            val strength = Animatable(HERE_FROM)
            try {
                coroutineScope {
                    val painter = launch {
                        snapshotFlow { (strength.value * HERE_STEPS).roundToInt() }.collect { step ->
                            val wash = HERE_ALPHA * step / HERE_STEPS
                            n.applyDecorations(
                                if (step <= 0) emptyList() else listOf(Decoration("here", sentence, HereMark(tint.withAlpha(wash)))),
                                HERE,
                            )
                        }
                    }
                    strength.animateTo(1f, hereAppears)
                    delay(HERE_HOLD_MS)
                    strength.animateTo(0f, hereFades)
                    painter.cancel()
                }
            } finally {
                // Whatever cut it short (the voice starting again, the book closing), nothing is left behind.
                withContext(NonCancellable) { runCatching { n.applyDecorations(emptyList(), HERE) } }
            }
        }
    }

    override fun stop() {
        val wasListening = listen.active
        val elsewhere = listen.offPage
        val spoken = lastSpoken
        val placed = lastPlaced
        lastSpoken = null
        lastPlaced = null
        if (spoken != null) stoppedAt = spoken
        session?.cancel()
        session = null
        mediaSession?.let(ListenService::detach)
        mediaSession = null
        listen.spokenFile = -1
        listen.phrase = ""
        tts?.close()
        tts = null
        listen.active = false
        listen.playing = false
        listen.preparing = false
        listen.offPage = false
        val n = navigator()
        scope.launch {
            // A listener who had gone elsewhere may have had the voice catch up with the page they
            // are on: the page can tell, while the read-along is still laid out on it.
            val inView = !elsewhere || (n != null && lineOnPage(n) != null)
            n?.applyDecorations(emptyList(), GROUP)
            // The page stays as it is. With the sentence the voice stopped on in view, that
            // sentence is marked; otherwise the listener is offered the way back to it.
            if (wasListening && spoken != null) if (inView) markHere(spoken) else onStoppedElsewhere(placed ?: spoken)
        }
    }

    private fun fail(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        stop()
    }

    private companion object {
        const val GROUP = "read-aloud"

        /** Where a sentence is laid out, to tell whether it is on the page in view (never seen). */
        const val PROBE = "start-probe"

        /** The page couldn't say where a sentence is; and how many of those in a row a start passes over before it gives up. */
        const val UNPLACED = 2
        const val MAX_UNPLACED = 3

        /** No start passes over more sentences than this, nor waits longer than this for the voice to move on by one. */
        const val MAX_START_SKIPS = 80
        const val START_SKIP_MS = 1_000L

        /** The longest a tap waits for the voice to be at the tapped block. */
        const val JUMP_GO_MS = 3_000L

        /** Two voices' sentences are the same one if they open with this many of the same characters. */
        const val SAME_SENTENCE_CHARS = SENTENCE_OPENING

        /** How long the page is given to lay the probe out, and how often it is asked meanwhile (about a frame). */
        const val PROBE_MS = 400L
        const val PROBE_POLL_MS = 16L

        /**
         * Where the sentence laid out by [PROBE] is, as { n, at }: n numbers the laying-out (each
         * is one more than the last), and at is -1 wholly before the page in view (on an earlier
         * page, or scrolled off the top), 0 at least partly on it, 1 after it, [UNPLACED] when
         * the page found the sentence nowhere it could draw. Null until the page has laid it
         * out. Called with (scroll, rightToLeft).
         */
        val WHERE_SCRIPT = """
            (function(scroll, rtl) {
              var group = document.querySelector('[data-group="$PROBE"]'), item = group && group.lastElementChild;
              if (!item) return null;
              var w = window.innerWidth, h = window.innerHeight, any = false, here = false, before = true;
              for (var i = 0; i < item.children.length; i++) {
                var r = item.children[i].getBoundingClientRect();
                if (!r.width || !r.height) continue;
                any = true;
                if (r.right > 1 && r.left < w - 1 && r.bottom > 1 && r.top < h - 1) here = true;
                if (!(scroll ? r.bottom <= 1 : (rtl ? r.left >= w - 1 : r.right <= 1))) before = false;
              }
              return { n: parseInt(item.id.slice(item.id.lastIndexOf('-') + 1)) || 0, at: !any ? $UNPLACED : here ? 0 : before ? -1 : 1 };
            })
        """.trimIndent()

        /**
         * The words either side of a tap at (x, y) in the navigator's pixels, as { before, after },
         * running on across the text around it; null if the tap isn't on text (a margin, the
         * space past a line's end, a picture). The tap is taken back to the start of its word.
         */
        val TAPPED_SCRIPT = """
            (function(px, py) {
              var d = window.devicePixelRatio, x = px / d, y = py / d;
              var c = document.caretRangeFromPoint(x, y);
              if (!c || c.startContainer.nodeType !== 3) return null;
              var node = c.startContainer, t = node.textContent, off = c.startOffset;
              if (!t.trim()) return null;
              // On the words, not beside them: a letter next to the caret is under the finger.
              var near = false;
              for (var i = Math.max(0, off - 1); i <= Math.min(off, t.length - 1) && !near; i++) {
                var r = document.createRange();
                r.setStart(node, i);
                r.setEnd(node, i + 1);
                var b = r.getBoundingClientRect();
                if (b.width + b.height > 0 && x > b.left - 32 && x < b.right + 32 && y > b.top - 12 && y < b.bottom + 12) near = true;
              }
              if (!near) return null;
              while (off > 0 && /\S/.test(t[off - 1])) off--;
              var before = t.slice(0, off), after = t.slice(off), w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), m;
              w.currentNode = node;
              while (before.length < 160 && (m = w.previousNode())) before = m.textContent + ' ' + before;
              w.currentNode = node;
              while (after.length < 160 && (m = w.nextNode())) after = after + ' ' + m.textContent;
              return { before: before.slice(-160), after: after.slice(0, 160) };
            })
        """.trimIndent()

        /** The mark left on the sentence where the voice stopped. */
        const val HERE = "here"

        /** Its wash at full strength, the strength it starts from, and the steps its fades are painted in. */
        const val HERE_ALPHA = 0.3f
        const val HERE_FROM = 0.6f
        const val HERE_STEPS = 12

        /** How long it stays before it fades: about two seconds in all. */
        const val HERE_HOLD_MS = 1_800L

        /**
         * The line the voice is on, in the web view's pixels: the first box of the veil over the
         * words still to come (see [UnreadVeil]) that is on this page, widened to the sentence's
         * boxes on the same line; without one (the whole sentence spoken, a voice that doesn't
         * report its words, the rest of the sentence on the next page), the lowest line of the
         * sentence that is on this page.
         */
        val SPOKEN_LINE_SCRIPT = """
            (function() {
              var group = document.querySelector('[data-group="$GROUP"]');
              if (!group) return null;
              var w = window.innerWidth, h = window.innerHeight, d = window.devicePixelRatio;
              function seen(r) { return r.width > 0 && r.height > 0 && r.right > 0 && r.left < w && r.bottom > 0 && r.top < h; }
              var veil = [], sentence = [];
              group.querySelectorAll(':scope > div > *').forEach(function(box) {
                var r = box.getBoundingClientRect();
                if (seen(r)) (box.dataset.jacket === 'unread' ? veil : sentence).push(r);
              });
              var line = veil[0];
              if (!line) {
                for (var i = 0; i < sentence.length; i++) if (!line || sentence[i].bottom > line.bottom) line = sentence[i];
              }
              if (!line) return null;
              var l = line.left, r = line.right;
              for (var j = 0; j < sentence.length; j++) {
                var s = sentence[j];
                if (s.top < line.bottom && s.bottom > line.top) { l = Math.min(l, s.left); r = Math.max(r, s.right); }
              }
              return { l: l * d, t: line.top * d, r: r * d, b: line.bottom * d };
            })()
        """.trimIndent()
    }
}

private fun Int.withAlpha(alpha: Float): Int = (this and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
