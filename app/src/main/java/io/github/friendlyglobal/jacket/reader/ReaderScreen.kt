package io.github.friendlyglobal.jacket.reader

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.outer.OuterDisplay
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.SystemBarsHidden
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.rememberPageColors
import io.github.friendlyglobal.jacket.ui.sharedCover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    var sectionName: (Int) -> String = { "" }
    /** Sections in the same group (e.g. chapter) share a value: the scrubber ticks between groups. */
    var sectionGroup: (Int) -> Int = { it }
    var jumpTo: (Int) -> Unit = {}
    var openToc: (TocItem) -> Unit = {}
    /** Previous/next buttons: by chapter for EPUB, by page or paragraph otherwise. */
    var step: (Int) -> Unit = { jumpTo(section + it) }
    var canStep: (Int) -> Boolean = { section + it in 0 until sectionCount }

    /** Audiobook mode; [listenController] is null for formats that can't be read aloud. */
    val listen = ListenState()
    var listenController by mutableStateOf<ListenController?>(null)
}

typealias ProgressSink = (position: Int, offset: Float, progress: Float, sections: Int) -> Unit

private enum class Sheet { NONE, CONTENTS, TEXT }

@Composable
fun ReaderScreen(
    book: Book,
    settings: Settings,
    outer: OuterDisplay,
    onSettings: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val library = context.library
    val haptics = rememberHaptics()
    // Plain text is read aloud with Jacket's own speech engine; EPUB uses Readium's.
    val speaker = remember(book.id) { if (book.format == BookFormat.TXT) Speaker(context) else null }
    DisposableEffect(speaker) { onDispose { speaker?.shutdown() } }
    LaunchedEffect(speaker, settings.listenRate) { speaker?.setRate(settings.listenRate) }
    val outerAvailable by outer.available.collectAsStateWithLifecycle()
    val outerActive by outer.active.collectAsStateWithLifecycle()
    val nav = remember(book.id) { ReaderNav() }
    val page = rememberPageColors(settings.pageColor)
    var chrome by rememberSaveable(book.id) { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    // Once you touch the controls, they stay until you dismiss them.
    var touchedChrome by remember(book.id) { mutableStateOf(false) }

    LaunchedEffect(book.id) {
        // After the cover's flight, so the library doesn't reshuffle underneath it.
        delay(800)
        library.markOpened(book.id)
        // Show the controls long enough to be discovered, then get out of the way.
        delay(1_800)
        if (!touchedChrome) chrome = false
    }
    SystemBarsHidden(hidden = !chrome && sheet == Sheet.NONE)
    val noticeTouches = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                touchedChrome = true
            }
        }
    }

    // Predictive back: the page shrinks and rounds as you swipe, then lands in the library.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val onProgress: ProgressSink = { position, offset, progress, sections ->
        library.updatePosition(book.id, position, offset, progress, sections)
    }
    val toggleChrome = {
        touchedChrome = true
        chrome = !chrome
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - 0.08f * backProgress
                scaleX = scale
                scaleY = scale
                shape = RoundedCornerShape((32 * backProgress).dp)
                clip = backProgress > 0f
            }
            .background(page.background),
    ) {
        when (book.format) {
            BookFormat.EPUB -> EpubReader(book, settings, page, nav, haptics, toggleChrome)
            BookFormat.PDF -> PdfReader(book, page, nav, haptics, onProgress, toggleChrome)
            BookFormat.CBZ -> ComicReader(book, page, nav, haptics, onProgress, toggleChrome)
            BookFormat.TXT -> TextReader(book, settings, page, nav, haptics, requireNotNull(speaker), onProgress, toggleChrome)
        }

        AnimatedVisibility(
            visible = !chrome && !nav.listen.active,
            enter = fadeIn(MaterialTheme.motionScheme.slowEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { ReaderFooter(nav, book, page) }

        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { -it } + fadeIn(),
            exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).then(noticeTouches),
        ) {
            ReaderTopBar(
                book = book, nav = nav, outerAvailable = outerAvailable, outerActive = outerActive,
                onOuter = { on -> haptics.toggle(on); outer.toggle() },
                onBack = onBack,
            )
        }

        // While listening, the player stays even when the rest of the controls are hidden.
        AnimatedVisibility(
            visible = chrome || nav.listen.active,
            enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
            exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).then(noticeTouches),
        ) {
            ReaderBottomBar(
                nav = nav, book = book, haptics = haptics,
                showScrubber = chrome,
                rate = settings.listenRate,
                onRate = { rate ->
                    onSettings { it.copy(listenRate = rate) }
                    nav.listenController?.setRate(rate)
                },
                onListen = {
                    haptics.confirm()
                    chrome = false
                    nav.listenController?.start()
                },
                onContents = { sheet = Sheet.CONTENTS },
                onText = { sheet = Sheet.TEXT },
            )
        }

        OpeningCover(book)
    }

    when (sheet) {
        Sheet.CONTENTS -> ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
            ContentsList(nav, haptics) { item ->
                nav.openToc(item)
                sheet = Sheet.NONE
                // You picked where to go: get out of the way and let them read.
                chrome = false
            }
        }
        Sheet.TEXT -> ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
            ReadingControls(
                settings, onSettings,
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            )
        }
        Sheet.NONE -> {}
    }
}

@Composable
private fun ReaderTopBar(
    book: Book,
    nav: ReaderNav,
    outerAvailable: Boolean,
    outerActive: Boolean,
    onOuter: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedContent(
                    targetState = nav.title,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "chapter",
                ) { title ->
                    Text(
                        title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, "Back to library") } },
        actions = {
            if (outerAvailable || outerActive) {
                FilledTonalIconToggleButton(checked = outerActive, onCheckedChange = onOuter) {
                    Icon(JacketIcons.OuterScreen, if (outerActive) "Hide cover on outer screen" else "Show cover on outer screen")
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
                ListenBar(nav.listen, controller, rate, onRate, haptics)
            } else {
                HorizontalFloatingToolbar(
                    expanded = true,
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { haptics.tick(); onText() }) {
                            Icon(JacketIcons.TextFormat, "Text and page settings")
                        }
                    },
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                ) {
                    if (nav.toc.isNotEmpty()) {
                        IconButton(onClick = { haptics.tick(); onContents() }) { Icon(JacketIcons.Contents, "Contents") }
                    }
                    if (controller != null) {
                        IconButton(onClick = onListen) { Icon(JacketIcons.Headphones, "Listen: read aloud") }
                    }
                    val unit = when {
                        nav.toc.isNotEmpty() -> "chapter"
                        book.format == BookFormat.TXT -> "paragraph"
                        else -> "page"
                    }
                    IconButton(
                        onClick = { haptics.tick(); nav.step(-1) },
                        enabled = nav.canStep(-1),
                    ) { Icon(JacketIcons.Previous, "Previous $unit") }
                    IconButton(
                        onClick = { haptics.tick(); nav.step(1) },
                        enabled = nav.canStep(1),
                    ) { Icon(JacketIcons.Next, "Next $unit") }
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
                    if (preview != null) "${target * 100 / last}%" else "${book.percent}%",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Slider(
                state = slider,
                onValueChange = { value ->
                    if (nav.sectionGroup(value.roundToInt()) != nav.sectionGroup(target)) haptics.frequentTick()
                    preview = value
                    slider.value = value
                },
                onValueChangeFinished = {
                    preview?.let { nav.jumpTo(it.roundToInt()) }
                    preview = null
                    haptics.confirm()
                },
            )
        }
    }
}

@Composable
private fun ReaderFooter(nav: ReaderNav, book: Book, page: PageColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            nav.footer, style = MaterialTheme.typography.labelMedium, color = page.secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("${book.percent}%", style = MaterialTheme.typography.labelMedium, color = page.secondary, textAlign = TextAlign.End)
    }
}

@Composable
private fun ContentsList(nav: ReaderNav, haptics: Haptics, onPick: (TocItem) -> Unit) {
    val items = nav.toc
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (nav.tocIndex - 2).coerceAtLeast(0))
    Text(
        "Contents", style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        itemsIndexed(items) { index, item ->
            val current = index == nav.tocIndex
            ListItem(
                onClick = { haptics.tick(); onPick(item) },
                colors = ListItemDefaults.colors(
                    containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
                modifier = Modifier.padding(horizontal = 12.dp).clip(MaterialTheme.shapes.large),
            ) {
                Text(
                    item.title,
                    style = if (item.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.SemiBold else null,
                    modifier = Modifier.padding(start = (16 * item.level.coerceAtMost(3)).dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The cover the book was opened from lands here (shared element), holds for a beat, then
 * dissolves into the first page, like opening a book.
 */
@Composable
private fun BoxScope.OpeningCover(book: Book) {
    var visible by remember(book.id) { mutableStateOf(true) }
    LaunchedEffect(book.id) {
        delay(700)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(420)) + scaleOut(tween(420), targetScale = 1.06f),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CoverImage(
                book,
                Modifier.fillMaxHeight(0.7f).aspectRatio(2f / 3f).sharedCover(book.id).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                // Same size the library decoded, so the flying cover is already in the cache.
                maxHeight = 600,
            )
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
