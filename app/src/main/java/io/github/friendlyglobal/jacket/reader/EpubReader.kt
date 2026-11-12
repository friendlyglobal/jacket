package io.github.friendlyglobal.jacket.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntRect
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
import io.github.friendlyglobal.jacket.listening
import io.github.friendlyglobal.jacket.readium
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.readableVeil
import io.github.friendlyglobal.jacket.importer.Epub
import io.github.friendlyglobal.jacket.importer.NothingToRead
import io.github.friendlyglobal.jacket.importer.TextLayout
import io.github.friendlyglobal.jacket.data.TextPages
import io.github.friendlyglobal.jacket.voice.Respelling
import io.github.friendlyglobal.jacket.voice.SayIt
import io.github.friendlyglobal.jacket.voices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
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
internal const val FOLLOW_SETTLE_MS = 900L

/** Nor is one this soon after the page was put back from a fold, an unfold or a turn of the phone. */
private const val RESIZE_SETTLE_MS = 1_500L

/** A page move this soon after the reader's own swipe, page turn or jump is theirs. */
internal const val LISTENER_MOVE_MS = 2_000L

/** How long Readium is given to report the page it was sent back to, and how many times it is sent. */
internal const val ARRIVAL_MS = 1_500L
private const val PUT_BACK_TRIES = 3

/** Scrolling, how long Readium takes to report where a move has left it. */
private const val SCROLL_SETTLE_MS = 300L

/** How long a page jumped to is given to be drawn before its veil lifts. */
private const val JUMP_DRAWN_MS = 60L

/** Joining a voice that is already reading, how often the pages are asked whether they have settled where they opened. */
private const val JOIN_POLL_MS = 50L

/** How long a navigator just made is waited on for its first report of a page. */
private const val FIRST_PAGE_MS = 10_000L

/** While the voice speaks its place is saved this often, and whenever it pauses or the screen is left. */
internal const val SPOKEN_SAVE_MS = 20_000L

/**
 * EPUB, rendered by Readium: real pagination (or continuous scroll), publisher styles, spreads
 * when unfolded, and read-aloud. Jacket supplies the chrome around it.
 *
 * A text file is read here too, through the pages made of it (see TextBook.kt): it is laid out,
 * read aloud, highlighted and searched like any other book.
 */
@Composable
fun EpubReader(
    book: Book,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    annotations: List<Annotation>,
    /** The words the voice is to say the listener's way in this book ("Say it this way"). */
    respellings: List<Respelling>,
    /** The fold, while the phone is held half-open like a book and the fold is the spine between two pages (see [BookPosture]). */
    spine: IntRect?,
    onCenterTap: () -> Unit,
) {
    val context = LocalContext.current
    // The pages are the phone's web view's to draw. One too old for them can't page through a
    // chapter: the reader says so, and how to update it, rather than open the book broken.
    rememberOutdatedWebView()?.let { return WebViewTooOld(it, page) }
    val listening = context.listening
    val opened by produceState<Result<Opened>?>(null, book.id) {
        val result = runCatching {
            // Not cancellable: backing out mid-open must still hand the book over to be closed.
            withContext(NonCancellable) {
                // The one opening of the book that read-aloud shares: a voice reading it already
                // (started with no reader open) has it open, and goes on reading from it.
                val open = listening.books.acquire(book)
                // That voice's sentence is the book's place right now: it opens there. Otherwise,
                // at the place it was left at.
                val reading = listening.sentenceOf(book.id)
                val left = if (reading != null) null else withContext(Dispatchers.IO) { open.left(book) }
                Opened(open, reading ?: left?.place, oldCount = left?.oldCount == true)
            }
        }
        value = result
        awaitDispose { result.getOrNull()?.open?.let(listening.books::releaseSoon) }
    }
    val result = opened ?: return ReaderLoading()
    val (open, start, oldCount) = result.getOrElse {
        // A text file with nothing in it (one added before Jacket turned those away) says so.
        return ReaderError(if (it is NothingToRead) stringResource(R.string.reader_error_text) else stringResource(R.string.reader_error_open_book, it.message.orEmpty()))
    }
    val publication = open.publication
    // A copy-protected book (Adobe's DRM, Readium LCP: a shop's or a library's) opens, but locked,
    // and Readium's pages refuse a locked book by throwing: Jacket would close under the reader.
    if (publication.isRestricted) return ReaderError(stringResource(R.string.reader_error_protected))
    if (publication.readingOrder.isEmpty()) return ReaderError(stringResource(R.string.reader_error_no_pages))
    EpubPages(book, open, start, oldCount, open.text?.layout, settings, page, nav, haptics, annotations, respellings, spine, onCenterTap)
}

/** A book opened (see [OpenBook]), where to start it, and whether that place is a sentence Jacket 1.0.0 saved by its own count (see [OpenBook.Left]). */
private data class Opened(val open: OpenBook, val start: Locator?, val oldCount: Boolean)

@Composable
private fun EpubPages(
    book: Book,
    /** The book as Readium has it open, which read-aloud shares (see [OpenBook]). */
    open: OpenBook,
    start: Locator?,
    /** [start] is a sentence Jacket 1.0.0 saved by a count of its own: the book's progress is counted over once it is seen on its page. */
    oldCount: Boolean,
    /** For a text file: where its paragraphs are in these pages. Its place is kept as a paragraph too (see [Library.updateLocator]). */
    textFile: TextLayout?,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    annotations: List<Annotation>,
    respellings: List<Respelling>,
    spine: IntRect?,
    onCenterTap: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val library = context.library
    val publication = open.publication
    val selectionMenu = remember(publication) { SelectionMenu(context) }
    val scope = rememberCoroutineScope()
    val contents = remember(publication) { Contents(publication) }
    val scroll = settings.layout == PageLayout.SCROLL
    // Held half-open like a book, the two pages are laid out about the fold: two of them whatever
    // the size of the text, and their margins wide enough to keep the words clear of it. (For most
    // readers that is how the pages are already, and nothing is laid out anew.) Scrolling, there
    // are no pages to part.
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val spread = remember(spine, scroll, settings.textScale, settings.margins, windowWidth, density) {
        spine?.takeIf { !scroll }?.let { fold ->
            with(density) { BookPosture.spread(windowWidth.toDp().value, fold.width.toDp().value, settings.textScale, settings.margins.readium) }
        }
    }
    val preferences = epubPreferences(settings, page, spread)
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
            recount = oldCount && sentence != null
        }
    }
    // Whether the reader has gone back through the book, which is all that lets its progress fall
    // (see PagePlace): told from the pages Readium reports.
    val pagePlace = remember { PagePlace() }

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
    // A tap on a note's number shows the note over the page, rather than jump to it (see NoteLinks).
    val notes = remember(publication) { NoteLinks(publication, { navigator }, scope) }

    /** Readium's navigator for this book, opening at [from] (the first page if null). */
    fun navigatorAt(from: Locator?) = navigators.createFragmentFactory(
        initialLocator = from,
        initialPreferences = settingsNow,
        listener = object : EpubNavigatorFragment.Listener {
            override fun onExternalLinkActivated(url: AbsoluteUrl) = openLink(context, url)

            // A link to a note is shown as one; any other link inside the book is followed as ever.
            override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean =
                !notes.take(link, context)
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
                // New pages: the first one seen is the book's place shown again, not a page turned to.
                pagePlace.unsettled()
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
    val minEdgePx = with(density) { 80.dp.toPx() }

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
            // Keys never get as far as the page: the activity takes them first (see ReaderKeys).
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
    // In a text book: the paragraphs that start on the page in view (asked only when a bookmark needs to know).
    var shownParagraphs by remember { mutableStateOf(IntRange.EMPTY) }
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
    /** How many pages Readium counted in the file when it reported [place]; null if it hasn't said. */
    fun pagesAt(place: Locator): Int? = filePage.takeIf { it.count > 0 && it.href == place.href.toString() }?.count

    /** The page Readium reports at [page], as [PagePlace] tells pages apart ([pages] where Readium has yet to say how many its file has). */
    fun seen(page: Locator, pages: Int? = pagesAt(page)): PageSeen =
        PageSeen(contents.resourceOf(page.href), page.locations.progression ?: 0.0, pages.takeIf { !navigatorScrolls(navigator) })

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
        val here = seen(locator)
        // A book left on a sentence, and still on the page that sentence is on, keeps the
        // sentence as its place: saved over with this page's first words, it would be taken up
        // again (by the voice, started with the book shut) from the top of the page.
        if (voiceAt.sentence != null && pagePlace.isOn(here) && !pagePlace.jumping(SystemClock.uptimeMillis())) return
        // Saved with the first words on the page, as a bookmark is. How far down the file a page
        // starts shifts with every layout (folded or not, a text size, a blank page Readium adds
        // at the end): by that alone the book reopens a page out. The words are where they are.
        val n = navigator
        val top = n?.let { topOf(it) }
        val place = if (n != null) locator.byFirstWords(top) else locator
        // Asking the page took a moment, and the pages may have started to re-flow in it.
        if (notTheReaders()) return
        resize.known = locator to place
        // The book's progress only falls when the reader went back through it: to a page before
        // the one its place was counted on, by a jump, or (the voice paused or stopped while they
        // were looking elsewhere) to a page of their own choosing. A page that is the same place
        // shown again, reopened or laid out anew, counts a little differently and takes nothing off.
        val back = pagePlace.wentBackTo(here, SystemClock.uptimeMillis()) || (nav.listen.active && nav.listen.offPage)
        val position = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0)
        if (textFile != null) {
            val paragraph = textFile.pagePlace(top, locator)
            library.updateLocator(book.id, place.toJSON().toString(), progress.toFloat(), paragraph.paragraph, textFile.paragraphs, paragraph.within, back)
        } else {
            library.updateLocator(book.id, place.toJSON().toString(), progress.toFloat(), position, positions.size, back = back)
        }
        // The reader has moved on from where the voice last was.
        voiceAt.sentence = null
        voiceAt.unseen = false
        voiceAt.back = false
    }

    val owner = LocalLifecycleOwner.current

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
        // The last page is all of the book to the voice as it is to the reader, and so is the end
        // of the book once the voice has read to it (see [spokenIsAllOfTheBook]).
        val ended = sentence === voiceAt.ended
        val all = spokenIsAllOfTheBook(ended, onPage = page != null, lastPage = current != null && current === voiceAt.lastPage)
        val progress = held ?: if (all) 1.0 else spans?.total(file, within) ?: return
        // A sentence already saved is saved again only when there is something new to say of it
        // (the voice has turned its page, or read the book to its end): not to be counted over
        // again, another way, as the screen goes dark.
        if (sentence === voiceAt.saved && ((page == null && !ended) || progress == voiceAt.savedProgress)) return
        // The book's progress only falls for it if the listener sent the voice back (the previous
        // sentence, an earlier one tapped, the chapter before). Counted again any other way (the
        // page it is on seen at last, the voice stepping back a sentence by itself after a
        // pause) it is the same place, and takes nothing off. A sentence Jacket 1.0.0 saved by
        // its own count is the one exception: seen on its page, it is counted over, up or down.
        val back = voiceAt.back || (voiceAt.recount && page != null)
        // (With the Readium position it falls in, or its paragraph in a text file, kept beside every saved place.)
        library.saveSpoken(book.id, sentence, progress, positions, textFile, back)
        voiceAt.back = false
        if (page != null) {
            voiceAt.recount = false
            // The page in view is the one the book's place is on: a page turned back from here is the reader going back.
            current?.let { pagePlace.at(seen(it)) }
        }
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
        // Laid out anew, the pages say nothing of where the reader was by where they start: the
        // page they are put back to is the same place, however it counts.
        pagePlace.unsettled()
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
                        pagePlace.at(seen(said, pagesAt(said) ?: pages))
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
            pagePlace.at(seen(opened, reported?.count))
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
        // A text file with no chapters has no "pages left in chapter" to tell: the files it was cut into are not chapters.
        nav.footer = if (textFile != null && !textFile.hasContents) "" else footerFor(resources, scroll, nav.title, filePage, locator, located)
        // A bookmark kept as a paragraph is on this page if its paragraph starts here: the page says which do.
        if (textFile != null && annotations.any { it.kind == AnnotationKind.BOOKMARK && it.locator == null }) {
            shownParagraphs = navigator?.let { paragraphsOn(it) } ?: IntRange.EMPTY
        }
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
        voiceAt.lastPage = locator.takeIf { nav.atEnd }
        if (nav.atEnd) {
            if (nav.chapterProgress >= 0f) nav.chapterProgress = 1f
            savePage(locator, 1.0)
            // That save is the reader's. While the voice is speaking the place is the voice's, and
            // it has just come onto the last page: its count is told the same.
            if (voiceHoldsThePlace(nav.listen.active, nav.listen.playing)) saveSpoken()
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
                        section = textFile?.pagePlace(null, selection.locator)?.paragraph ?: 0,
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
                // "Say it this way": how the voice is to say the word, or the name, selected.
                SelectionMenu.Action.SAY -> SayIt.wordFrom(text).takeIf { it.isNotEmpty() }?.let {
                    haptics.tick()
                    nav.sayWord = it
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
        val top = topOf(n)
        val quote = top?.quote.orEmpty()
        val marked = if (quote.isNotBlank()) {
            locator.copy(text = Locator.Text(before = top?.before, highlight = quote))
        } else {
            locator.byProgression()
        }
        val resource = contents.resourceOf(locator.href)
        Place(
            locator = marked.toJSON().toString(),
            // In a text file, the paragraph the page starts in: what a bookmark there has always kept.
            section = textFile?.pagePlace(top, locator)?.paragraph ?: ((locator.locations.position ?: 1) - 1).coerceAtLeast(0),
            progress = (spans?.total(resource, locator.locations.progression ?: 0.0) ?: 0.0).toFloat(),
            chapter = nav.title,
            snippet = top?.text.orEmpty(),
        )
    }
    nav.onScreen = onScreen@{ mark ->
        val here = current ?: return@onScreen false
        // A text book's bookmark from before it had pages names a paragraph, not a page.
        if (textFile != null && mark.locator == null) return@onScreen textFile.marked(mark.section, mark.progress) in shownParagraphs
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
    // The jump being seen to its place (see jumpTo).
    val jump = remember { arrayOfNulls<Job>(1) }

    /**
     * Takes the pages to [place]: a jump the reader asked for (the contents, a bookmark, a search
     * result, the way back from one).
     *
     * Sent to a place in a chapter file other than the one in view, Readium can stop short of it.
     * It keeps the files either side of the open one laid out, and one of those, on becoming the
     * open one, is put at its first page or its last (as a page turned into it would find it),
     * whatever place in it was asked for. So the place is sent again once that file is the one in
     * view, and the page waits under its veil meanwhile, as it does while pages re-flow.
     */
    fun jumpTo(place: Locator) {
        val n = navigator ?: return
        val from = n.currentLocator.value
        // The reader's own doing: wherever it lands is their place, back through the book or on.
        pagePlace.jumped(SystemClock.uptimeMillis())
        n.go(place, animated = false)
        if (from.href.removeFragment().isEquivalent(place.href.removeFragment())) return
        jump[0]?.cancel()
        jump[0] = scope.launch {
            launch { reflowVeil.animateTo(1f, veilOn) }
            try {
                // Readium says where it is a moment after it gets there.
                withTimeoutOrNull(ARRIVAL_MS) { n.currentLocator.first { it !== from } }
                delay(SCROLL_SETTLE_MS)
                if (navigator === n) {
                    pagePlace.jumped(SystemClock.uptimeMillis())
                    n.go(place, animated = false)
                }
                // A moment for the page gone to to be drawn.
                delay(JUMP_DRAWN_MS)
            } finally {
                // Cut short by another jump, the veil stays for that one to lift.
                if (isActive) launch { reflowVeil.animateTo(0f, veilOff) }
            }
        }
    }

    // A jump remembers the page it left from, so the reader can offer the way back.
    val markOrigin = {
        nav.movedAt = SystemClock.uptimeMillis()
        pagePlace.jumped(nav.movedAt)
        val here = current
        val n = navigator
        if (here != null && n != null) {
            val pages = filePage.count.takeIf { filePage.href == here.href.toString() && it > 0 }
            val place = here.byProgression().onPage(pages, n.overflow.value)
            nav.cameFrom = Origin(
                percent = nav.section * 100 / (nav.sectionCount - 1).coerceAtLeast(1),
                chapter = nav.title.trim(),
            ) { jumpTo(place) }
        }
    }
    notes.onNote = { note, link ->
        haptics.tick()
        nav.note = ShownNote(note) {
            markOrigin()
            publication.locatorFromLink(link)?.let(::jumpTo) ?: navigator?.go(link, animated = false)
        }
    }
    nav.goTo = { mark ->
        val to = if (textFile != null && mark.locator == null) {
            // Kept as a paragraph: to the top of that paragraph.
            publication.locatorOfParagraph(textFile, textFile.marked(mark.section, mark.progress))
        } else {
            mark.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        }
        to?.let {
            markOrigin()
            jumpTo(it)
        }
    }
    nav.turnPage = { delta ->
        nav.movedAt = SystemClock.uptimeMillis()
        turner.turn(delta)
    }
    // For the keys and the mouse wheel: which way the book reads, and whether it has pages to turn.
    LaunchedEffect(navigator) {
        navigator?.overflow?.collect {
            nav.rightToLeft = it.readingProgression == ReadingProgression.RTL
            nav.paged = !it.scroll
        }
    }

    // Search the whole book; results stream in as Readium works through it.
    val search = remember(publication) {
        if (publication.isSearchable) EpubSearch(publication, contents, { navigator }, scope) else null
    }
    search?.tint = page.link.toArgb()
    search?.beforeShow = markOrigin
    search?.go = ::jumpTo
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

    // The voice is the app's, not this screen's (see Listening): the one already reading this book
    // (started from a headphone button, the notification, the widget or the car, with no reader
    // open), or one made now, which waits to be started. Either way it is handed these pages: to
    // turn as it reads, to mark the sentence it is on, and to ask where the listener is.
    val listening = context.listening
    val listen = remember(publication) { listening.voiceFor(book, open, nav.listen) }
    val voicePage = remember(listen) {
        VoicePage(
            navigator = { navigator },
            scope = scope,
            resizing = { resize.job?.isActive == true || SystemClock.uptimeMillis() - resize.restoredAt < RESIZE_SETTLE_MS },
            movedAt = { nav.movedAt },
            inSight = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            chapter = { nav.tocIndex },
        )
    }
    voicePage.colors = page.link.toArgb() to page.background.toArgb()
    voicePage.veil = readableVeil(page.text.toArgb(), page.background.toArgb())
    // "Say it this way": the words written for the voice the way they sound, and the numbers of
    // notes passed over. Both take effect with the next sentence the voice is given.
    listen.edits.respellings = respellings
    listen.skipNotes = settings.skipNoteNumbers

    // Highlights without selecting anything, for a screen reader and a keyboard, which can't
    // press and hold on the page: the sentence being read aloud, or else the first paragraph on
    // the page. Either is found in Readium's own reading of the file (which is where the voice
    // reads from, and what a highlight is drawn by), not by asking the page to select it.
    suspend fun firstParagraph(n: EpubNavigatorFragment): Pair<Locator, String>? {
        val here = n.currentLocator.value
        val top = topOf(n)?.text
        val file = open.spoken.of(here.href, here.mediaType)
        val texts = file.blocks.map { it.text }
        val block = firstParagraphOn(texts, texts.indices.map(file.places::of), top, here.locations.progression) {
            file.blocks[it].role is Content.TextElement.Role.Heading
        } ?: return null
        val paragraph = file.blocks[block]
        return paragraph.locator.copy(text = Locator.Text(highlight = paragraph.text)) to paragraph.text
    }
    // (Offered once, for as long as the book is open here: the controls are told it is there.)
    val spansNow by rememberUpdatedState(spans)
    DisposableEffect(listen) {
        nav.highlightHere = here@{ note ->
            val n = navigator ?: return@here Marked.NOTHING
            val (found, said) = (if (nav.listen.active) listen.sentenceNow() else null) ?: firstParagraph(n) ?: return@here Marked.NOTHING
            val text = highlightWords(said)
            if (text.isEmpty()) return@here Marked.NOTHING
            // Told where it is in its file the pages' way (see SpokenFiles.placed), as every sentence of the voice's is.
            val at = open.spoken.placed(found)
            val resource = contents.resourceOf(at.href)
            // Marked already: that highlight is opened, to be given a note, another colour, or taken off.
            val marked = library.state.value.annotations.firstOrNull { old ->
                old.bookId == book.id && old.kind == AnnotationKind.HIGHLIGHT && old.text == text &&
                    old.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }?.let { contents.resourceOf(it.href) == resource } == true
            }
            if (marked != null) {
                nav.editing = marked.id
                return@here Marked.ALREADY
            }
            val highlight = Annotation(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                kind = AnnotationKind.HIGHLIGHT,
                locator = at.toJSON().toString(),
                section = textFile?.pagePlace(null, at)?.paragraph ?: 0,
                progress = (spansNow?.total(resource, at.locations.progression ?: 0.0) ?: 0.0).toFloat(),
                // (The voice may be in another chapter than the page, if the listener has gone to look elsewhere.)
                chapter = contents.titleOf(contents.chapterAt(resource)).takeIf { nav.listen.active && nav.listen.offPage } ?: nav.title,
                text = text,
                color = library.state.value.annotations.lastOrNull { it.bookId == book.id && it.kind == AnnotationKind.HIGHLIGHT }?.color ?: HighlightColor.YELLOW,
                createdAt = System.currentTimeMillis(),
            )
            library.addAnnotation(highlight)
            if (note) nav.editing = highlight.id
            Marked.DONE
        }
        onDispose { nav.highlightHere = null }
    }

    // While the voice is speaking, the book's place is the sentence it is on (see saveSpoken).
    voicePage.onSpoken = { sentence, back ->
        voiceAt.sentence = sentence
        if (back) voiceAt.back = true
        // Out of sight, the page can't follow (unless the listener had left it to look elsewhere).
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !nav.listen.offPage) voiceAt.unseen = true
        // Often enough that little is heard twice if the app is closed under the voice, and no
        // oftener: every save writes the library and redraws the widgets.
        if (SystemClock.uptimeMillis() - voiceAt.savedAt >= SPOKEN_SAVE_MS) saveSpoken()
    }
    // Read to the end of the book: all of it has been heard, and is saved as that there and then
    // (the page may be shut in a pocket, with nothing on it to say so).
    voicePage.onEnded = {
        voiceAt.ended = voiceAt.sentence
        saveSpoken()
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
                // (Sent after the voice: the page it lands on is the book's place, not one the reader turned to.)
                pagePlace.unsettled()
                navigator?.go(sentence, animated = false)
                // An unfolding phone shows its pages a moment before it changes their size. Until
                // Readium is seen at the sentence, that is still the place to put them back at.
                resize.place = sentence
                resize.restoredAt = SystemClock.uptimeMillis()
            }
        }
        // Out of sight, the words weren't inked in one by one (nobody was looking): now they are.
        listen.seen()
    }

    // The voice follows the book's language by itself. A book that gives the wrong one can be
    // told another, and is then read in that whatever its pages claim.
    val voices = context.voices
    val said = publication.metadata.language?.code
    nav.bookLanguage = said
    LaunchedEffect(book.id, said) { if (book.language == null && said != null) library.setLanguage(book.id, said) }
    // A text file whose language couldn't be told from its words is read in the phone's, as it always was.
    val phoneLanguage = LocalConfiguration.current.locales[0]?.toLanguageTag()
    // (Worked out as a voice started with no reader open works it out: see [voicing].)
    val voicing = voicing(book.language, said, phoneLanguage, text = textFile != null)
    val readIn = voicing.readIn
    val voice = remember(settings.voice, settings.voices, readIn) { voices.choose(readIn, settings) }
    val chosenLanguage = voicing.chosen
    listen.voice = voice
    listen.language = chosenLanguage
    listen.phoneVoiceLanguage = voicing.phoneVoice
    LaunchedEffect(listen, voice, chosenLanguage) { listen.retune() }
    // Closing the player moves nothing: the page followed the voice, so the sentence it stopped on
    // is in front of you, and is marked for a moment (see ReadAloud.markHere). Only a listener who
    // had gone to look elsewhere is not there: the page they chose stays, and the way back to
    // where the voice stopped is offered like the way back from any jump.
    voicePage.hereAppears = MaterialTheme.motionScheme.defaultEffectsSpec()
    voicePage.hereFades = MaterialTheme.motionScheme.slowEffectsSpec()
    voicePage.onStoppedElsewhere = { spoken ->
        // The page the listener chose is theirs: once it is saved as the book's place (at their
        // next page turn), the progress is what that page counts, not what the voice had reached.
        pagePlace.choseAPage()
        val resource = contents.resourceOf(spoken.href)
        val progress = spoken.locations.progression?.let { spans?.total(resource, it) } ?: spoken.locations.totalProgression
        nav.cameFrom = Origin(
            percent = progress?.let { (it * 100).toInt().coerceIn(0, 100) } ?: (nav.section * 100 / (nav.sectionCount - 1).coerceAtLeast(1)),
            chapter = contents.titleOf(contents.chapterAt(resource)).orEmpty(),
        ) {
            jumpTo(spoken)
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
                publication.locatorFromLink(it.link)?.let(::jumpTo) ?: navigator?.go(it.link, animated = false)
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
                // While the book is read aloud the voice goes to the chapter too, and reads on from there.
                if (nav.listen.active) {
                    listen.readChapter(it)
                } else {
                    markOrigin()
                    publication.locatorFromLink(it)?.let(::jumpTo) ?: navigator?.go(it, animated = false)
                }
            }
        }
        nav.canStep = { delta -> contents.step(nav.tocIndex, delta) != null }
        onDispose {}
    }
    // The voice has these pages for as long as the book is open here, whatever else changes.
    // Closing the book stops it (see ReaderScreen), and another book opened over this one (from a
    // widget, a shortcut) ends it mid-sentence: that sentence is the place. A screen that goes any
    // other way (Android clearing it away while the voice reads on behind another app) only takes
    // the pages with it: the voice reads on, and keeps the book's place itself from there.
    val saveLastSpoken by rememberUpdatedState { saveSpoken() }
    DisposableEffect(listen) {
        nav.listenController = listen
        listening.install(listen)
        onDispose {
            saveLastSpoken()
            listen.detach(voicePage)
            listening.uninstall(listen)
            nav.listenController = null
        }
    }
    // A voice that was reading this book before it was opened here is joined once the book is open
    // on its page: until then the pages are still finding the place they open at, and sent after
    // the voice meanwhile they would be put back and sent again. (The book opened at the sentence
    // the voice was on; joined, the page goes to the one it has reached, and follows.)
    LaunchedEffect(listen, voicePage) {
        if (nav.listen.active) {
            while (!(nav.contentReady && navigator != null && resize.target == null)) delay(JOIN_POLL_MS)
        }
        listen.attach(voicePage)
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
    /** The sentence the voice read the book to its end on, until it speaks another. */
    var ended: Locator? = null
    /** The page in front of the reader, as Readium reported it, when it is the last of the book. */
    var lastPage: Locator? = null
    /** The listener has sent the voice back since its place was last saved: what lets the book's progress fall for it. */
    var back = false
    /** The sentence the book was left on was saved by Jacket 1.0.0, by a count of its own: counted over once it is seen on its page. */
    var recount = false
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
private fun Locator.byFirstWords(top: TopText?): Locator {
    val words = top?.quote.orEmpty()
    if (words.trim().length < FIRST_WORDS_ENOUGH) return byProgression()
    return byProgression().copy(text = Locator.Text(before = top?.before, highlight = words))
}

/** The first words on the page [navigator] shows (see [TOP_TEXT_SCRIPT]); null if the page has none, or can't say. */
private suspend fun topOf(navigator: EpubNavigatorFragment): TopText? =
    runCatching { navigator.evaluateJavascript(TOP_TEXT_SCRIPT)?.let(::JSONObject) }.getOrNull()?.let { top ->
        TopText(top.optString("before"), top.optString("quote"), top.optString("text"), top.optString("id").takeIf { it.isNotEmpty() }, top.optInt("at", 0))
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
internal suspend fun savedLocator(book: Book, publication: Publication, file: File, text: TextPages? = null): Locator? {
    val saved = book.locator?.let { json -> runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull() }
    val files = publication.readingOrder
    val usable = saved != null && files.any { it.url().removeFragment().isEquivalent(saved.href.removeFragment()) }
    // A text file's place is kept as a paragraph as well. That is all Jacket 1.1 and earlier kept
    // of it, and it is what there is to go by when a place names pages this book no longer has.
    if (text != null && !usable) return text.saved?.let(publication::locatorOf)
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

internal class TocEntry(val title: String, val level: Int, val link: Link, val resource: Int, val fragment: String?)

/**
 * Where on the page the reader is, relative to the chapter anchors in [resource]: the chapter
 * you're in, pages until the next one, and where each anchored chapter starts (0..1 in the file).
 */
internal class Located(val resource: Int, val chapter: Int, val pagesToNext: Int?, val starts: List<Pair<Int, Double>>)

/** Where each file of the book starts, as a fraction of the whole (from Readium's positions). */
internal class Spans(positions: List<Locator>, contents: Contents) {
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

internal class Contents(private val publication: Publication) {
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
 * short exact quote to find them again, and a tidy snippet for the bookmark list. And the name of
 * the element they are in, with how far into its text they start: in a text file's pages that is
 * the paragraph (see [pagePlace]).
 */
internal val TOP_TEXT_SCRIPT = """
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
      var held = node.parentElement && node.parentElement.closest('[id]');
      return { before: t.slice(Math.max(0, off - 40), off), quote: quote, text: text.replace(/\s+/g, ' ').trim().slice(0, 160), id: held ? held.id : '', at: off };
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
    /** Takes the pages to a result (see the reader's own way of jumping). */
    var go: (Locator) -> Unit = { navigator()?.go(it, animated = false) }
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
        go(locator)
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
