package io.github.friendlyglobal.jacket.reader

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.compose.AndroidFragment
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.search.isSearchable
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.floor
import kotlin.math.roundToInt

/** Room under the page for the quiet footer ("4 pages left in chapter · 42%"). */
private val FooterSpace = 36.dp

/** Room under the page for the read-aloud player. */
private val ListeningSpace = 104.dp

/** The scrubber's resolution: any book, long or short, is this many steps. */
private const val SCRUB_STEPS = 1000

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
                Opened(publication, savedLocator(book, publication, file))
            }
        }
        value = result
        awaitDispose { result.getOrNull()?.publication?.let(::closeSoon) }
    }
    val result = opened ?: return ReaderLoading()
    val (publication, start) = result.getOrElse {
        return ReaderError(stringResource(R.string.reader_error_open_book, it.message.orEmpty()))
    }
    if (publication.readingOrder.isEmpty()) return ReaderError(stringResource(R.string.reader_error_no_pages))
    EpubPages(book, publication, start, settings, page, nav, haptics, annotations, onCenterTap)
}

private data class Opened(val publication: Publication, val start: Locator?)

/** Closes the book once its pages have been torn down (the fragment goes after composition). */
private fun closeSoon(publication: Publication) {
    Handler(Looper.getMainLooper()).postDelayed({ publication.close() }, 2_000)
}

@Composable
private fun EpubPages(
    book: Book,
    publication: Publication,
    start: Locator?,
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

    val factory = remember(publication) {
        EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = start,
            initialPreferences = preferences,
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) = openLink(context, url)
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    // The chapter on screen has loaded and found its place: the opening cover may lift.
                    // (onPageLoaded can't tell: it also fires for the neighbours Readium preloads.)
                    nav.contentReady = true
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
    }

    LaunchedEffect(publication) { positions = runCatching { publication.positions() }.getOrDefault(emptyList()) }
    // Settings apply live: Readium re-flows the pages and keeps your place.
    LaunchedEffect(navigator, preferences) { navigator?.submitPreferences(preferences) }

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
    DisposableEffect(navigator) {
        val n = navigator ?: return@DisposableEffect onDispose {}
        val minEdge = minEdgePx
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
        n.addInputListener(edges)
        n.addInputListener(center)
        onDispose {
            n.removeInputListener(edges)
            n.removeInputListener(center)
        }
    }

    // Where you are: saved, shown in the chrome, and used for chapter tracking.
    val resize = remember { ResizeKeeper() }
    var current by remember { mutableStateOf<Locator?>(null) }
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
    LaunchedEffect(navigator, spans, scroll) {
        val n = navigator ?: return@LaunchedEffect
        n.currentLocator.collectLatest { locator ->
            current = locator
            // Moving on after a resize (turning a page, say) makes this the place to keep.
            if (SystemClock.uptimeMillis() - resize.restoredAt > 1_500) resize.place = null
            val resource = contents.resourceOf(locator.href)
            val position = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0)
            // Readium's positions are coarse (about a page of compressed text), so progress is
            // measured within the file: smooth even for a book that is one long file.
            val progress = spans?.total(resource, locator.locations.progression ?: 0.0)
                ?: locator.locations.totalProgression ?: 0.0
            nav.sectionCount = SCRUB_STEPS
            nav.section = (progress * (SCRUB_STEPS - 1)).roundToInt()
            library.updateLocator(book.id, locator.toJSON().toString(), progress.toFloat(), position, positions.size)

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
            val position = ((locator.locations.position ?: 1) - 1).coerceAtLeast(0)
            library.updateLocator(book.id, locator.toJSON().toString(), 1f, position, positions.size)
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
        val here = current
        val n = navigator
        if (here != null && n != null) {
            val pages = filePage.count.takeIf { filePage.href == here.href.toString() && it > 0 }
            val place = here.byProgression().onPage(pages, n.overflow.value)
            nav.cameFrom = Origin(nav.section * 100 / (nav.sectionCount - 1).coerceAtLeast(1)) { navigator?.go(place, animated = false) }
        }
    }
    nav.goTo = { mark ->
        mark.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            ?.let {
                markOrigin()
                navigator?.go(it, animated = false)
            }
    }
    nav.turnPage = { delta -> turner.turn(delta) }

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
        ReadAloud(context, publication, book.id, { navigator }, nav.listen, scope) { href -> contents.resourceOf(href) }
    }
    listen.colors = page.link.toArgb() to page.background.toArgb()
    // Closing the player gives the text its room back and the pages re-flow: land on the sentence
    // the voice was reading, not where you were when listening started.
    listen.onStopped = stopped@{ spoken ->
        spoken ?: return@stopped
        resize.place = null
        resize.target = spoken
        resize.resumeAt = spoken
        scope.launch {
            // If the page area didn't change size after all, go there directly.
            delay(900)
            if (resize.resumeAt === spoken) {
                resize.resumeAt = null
                resize.target = null
                navigator?.go(spoken, animated = false)
            }
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
    // Read-aloud lives as long as the book is open, whatever else changes.
    DisposableEffect(listen) {
        nav.listenController = listen
        onDispose {
            listen.stop()
            nav.listenController = null
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
            // While listening, the player floats at the bottom: keep the text clear of it.
            .padding(bottom = if (nav.listen.active) ListeningSpace else FooterSpace)
            // Readium loses your place when the page area changes size (it computes the corrected
            // scroll offset but never applies it): folding, unfolding, rotating, the player
            // appearing. Remember where you were and go back once the pages have re-flowed.
            .pointerInput(turner) { pageTurnGestures(turner) }
            .onSizeChanged { size ->
                val old = resize.size
                resize.size = size
                if (old == IntSize.Zero || old == size) return@onSizeChanged
                // A fold animates through several sizes, and you may fold and unfold again without
                // reading on: keep the place from before the first change.
                if (resize.target == null) resize.target = resize.place ?: current?.byProgression()
                resize.job?.cancel()
                resize.job = scope.launch {
                    val n = navigator ?: return@launch
                    val pages = n.awaitLayout(size)
                    val target = resize.target ?: return@launch
                    resize.target = null
                    resize.resumeAt = null
                    n.go(target.onPage(pages, n.overflow.value), animated = false)
                    resize.place = target
                    resize.restoredAt = SystemClock.uptimeMillis()
                }
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
    }
}

private class ResizeKeeper {
    var size = IntSize.Zero
    /** Where to go once the pages have re-flowed. */
    var target: Locator? = null
    var job: Job? = null
    /** The place the last restore aimed for, until the reader moves on. */
    var place: Locator? = null
    /** Where read-aloud stopped, until the re-flow after it has been restored. */
    var resumeAt: Locator? = null
    var restoredAt = 0L
}

/**
 * The same place, located by its progression through the file only: the text snippet and
 * element id in a live locator point at the start of a Readium position, not at your page.
 */
private fun Locator.byProgression(): Locator =
    copy(text = Locator.Text()).copyWithLocations(fragments = emptyList())

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
    if (pages == null || overflow.scroll || overflow.readingProgression != ReadingProgression.LTR) return this
    val page = floor(progression * pages).coerceAtMost(pages - 1.0)
    return copyWithLocations(progression = (page + 0.25) / pages)
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

/** Last saved place, else the chapter and offset saved by Jacket 0.2, else the start. */
private fun savedLocator(book: Book, publication: Publication, file: File): Locator? {
    book.locator?.let { json ->
        runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()?.let { return it }
    }
    if (book.position <= 0 && book.positionOffset <= 0f) return null
    // 0.2 counted chapters in its own spine, which kept non-linear items (a cover, say) that
    // Readium leaves out: find the chapter by its file, not its number.
    val path = runCatching {
        ZipFile(file).use { zip -> Epub.readPackage { zip.getEntry(it)?.let(zip::getInputStream) }.spine }
    }.getOrNull()?.getOrNull(book.position) ?: return null
    val link = publication.readingOrder.firstOrNull { it.url().path?.removePrefix("/") == path } ?: return null
    return publication.locatorFromLink(link)?.copyWithLocations(progression = book.positionOffset.toDouble())
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

private class ReadAloud(
    private val context: Context,
    private val publication: Publication,
    private val bookId: String,
    private val navigator: () -> EpubNavigatorFragment?,
    private val listen: ListenState,
    private val scope: CoroutineScope,
    /** Which chapter file a locator is in, so the sleep timer knows when one ends. */
    private val fileOf: (org.readium.r2.shared.util.Url) -> Int,
) : ListenController {
    /** The sentence being read when listening stopped (null if it never started): the page stays there. */
    var onStopped: (Locator?) -> Unit = {}
    private var lastSpoken: Locator? = null
    private var mediaSession: androidx.media3.session.MediaSession? = null
    /** Highlight tint and page color, for the read-along decorations. */
    var colors: Pair<Int, Int> = 0 to 0
    private var speed = 1f

    override val previousLabel = R.string.listen_previous_sentence
    override val nextLabel = R.string.listen_next_sentence
    private val factory by lazy { AndroidTtsNavigatorFactory(context.applicationContext as Application, publication) }
    private var tts: AndroidTtsNavigator? = null
    private var session: Job? = null

    override fun start() {
        tts?.let {
            it.play()
            return
        }
        // Still starting up (the voice engine takes a moment): one session at a time.
        if (session?.isActive == true) return
        listen.active = true
        listen.playing = true
        session = scope.launch {
            val factory = factory ?: return@launch fail(context.getString(R.string.listen_no_readable_text))
            // The first block of text on screen; before the page has loaded, wherever you are.
            val from = navigator()?.let { it.firstVisibleElementLocator() ?: it.currentLocator.value }
            val tts = factory.createNavigator(
                listener = object : TtsNavigator.Listener {
                    override fun onStopRequested() = stop()
                },
                initialLocator = from,
                initialPreferences = AndroidTtsPreferences(speed = speed.toDouble()),
            ).getOrElse { return@launch fail(context.getString(R.string.listen_no_voice)) }
            if (!isActive) {
                // Stopped while the engine was starting.
                tts.close()
                return@launch
            }
            this@ReadAloud.tts = tts
            // Lock screen, notification and headphone controls; keeps playing with the screen off.
            mediaSession = ListenService.attach(context.applicationContext, tts.asMedia3Player(), bookId)
            launch {
                tts.playback.collect { playback ->
                    listen.playing = playback.playWhenReady && playback.state is TtsNavigator.State.Ready
                    when (val state = playback.state) {
                        is TtsNavigator.State.Ended -> stop()
                        is TtsNavigator.State.Failure -> fail(context.getString(R.string.listen_stopped_error, state.error.message))
                        else -> {}
                    }
                }
            }
            launch {
                var utterance: Locator? = null
                tts.location.collect { location ->
                    listen.spokenFile = fileOf(location.href)
                    // Paused, the voice reports no word: the sentence keeps the ink it has.
                    val spokenTo = location.range?.last?.plus(1)
                    if (location.utterance != listen.phrase) {
                        listen.phrase = location.utterance
                        listen.spoken = spokenTo ?: 0
                    } else if (spokenTo != null) {
                        listen.spoken = spokenTo
                    }
                    lastSpoken = location.utteranceLocator
                    val n = navigator() ?: return@collect
                    // Turn the page to follow the voice.
                    if (location.utteranceLocator != utterance) {
                        utterance = location.utteranceLocator
                        n.go(location.utteranceLocator, animated = false)
                    }
                    n.applyDecorations(decorationsFor(location), GROUP)
                }
            }
            tts.play()
        }
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
        tts?.submitPreferences(AndroidTtsPreferences(speed = rate.toDouble()))
    }

    override fun stop() {
        val wasListening = listen.active
        val spoken = lastSpoken
        lastSpoken = null
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
        navigator()?.let { n -> scope.launch { n.applyDecorations(emptyList(), GROUP) } }
        if (wasListening) onStopped(spoken)
    }

    private fun fail(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        stop()
    }

    private companion object {
        const val GROUP = "read-aloud"
    }
}

private fun Int.withAlpha(alpha: Float): Int = (this and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
