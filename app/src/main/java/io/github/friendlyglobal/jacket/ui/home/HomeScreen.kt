package io.github.friendlyglobal.jacket.ui.home

import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.LibrarySort
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.readingStreak
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.CoverSpot
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.pressScale
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.sharedCover
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import io.github.friendlyglobal.jacket.data.BookFormat
import androidx.compose.runtime.rememberCoroutineScope
import io.github.friendlyglobal.jacket.data.BookCollection
import io.github.friendlyglobal.jacket.data.LibraryOrganizer
import io.github.friendlyglobal.jacket.data.arrangeShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import io.github.friendlyglobal.jacket.ui.asHeading
import io.github.friendlyglobal.jacket.ui.decorative
import io.github.friendlyglobal.jacket.ui.fitOnOneLine
import io.github.friendlyglobal.jacket.ui.spoken

@Composable
fun HomeScreen(
    state: LibraryState,
    outerAvailable: Boolean,
    importing: Boolean,
    snackbar: SnackbarHostState,
    onImport: (List<Uri>) -> Unit,
    onOpen: (Book, CoverSpot) -> Unit,
    onShowOnCover: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onOpenSettings: () -> Unit,
    missing: Set<String> = emptySet(),
    onShelf: (Boolean) -> Unit = {},
    onFinished: (Book, Boolean) -> Unit = { _, _ -> },
    grid: LazyGridState = rememberLazyGridState(),
    /** This library is coming back into view from Settings (its gear has a turn to undo). */
    backFromSettings: Boolean = false,
    /** Opens the book that comes with the app, offered while the library is empty. */
    onSample: () -> Unit = {},
    /** The phone folds: the empty library speaks of its outer screen (see Foldables). */
    foldable: Boolean = false,
    /** Opens the book and starts reading it aloud; offered on the wide layout's "Continue reading" pane. */
    onListen: ((Book) -> Unit)? = null,
    /** Pins, collections, a book's details and cover, the grid or the list; without it, none are offered. */
    organizer: LibraryOrganizer? = null,
    /** Jacket's shelf of free classics; the + menu and the empty library offer it. */
    onFreeBooks: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    val addBooks = { haptics.tick(); picker.launch(BookImporter.MIME_TYPES) }
    var fabMenu by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val hero = state.heroBook(missing)
    val finishedCount = state.books.count { it.isFinished }
    val onFinishedShelf = state.onFinishedShelf
    val shelf = remember(state.books, onFinishedShelf) { state.books.filter { it.isFinished == onFinishedShelf } }
    // Reading time today and the streak, if the reader wants them shown.
    val stats = state.settings.readingStats
    val today = remember(state.reading) { java.time.LocalDate.now() }
    val readToday = if (stats) ((state.reading[today.toString()] ?: 0L) / 60_000L).toInt() else 0
    val streak = if (stats) remember(state.reading, today) { readingStreak(state.reading, today) } else 0
    // A collection shows all its books, finished or not; the shelves split the rest.
    val collection = state.settings.collection?.let { id -> state.collections.firstOrNull { it.id == id } }
    val source = if (collection != null) state.books else shelf
    val shown = remember(source, state.settings.sort, query, collection) { arrangeShelf(source, state.settings.sort, query, collection?.id) }
    val scope = rememberCoroutineScope()

    // What's being asked about: a book to remove, to sort into collections or to edit; a collection to name or delete.
    var removing by remember { mutableStateOf<Book?>(null) }
    var collectionsFor by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<BookCollection?>(null) }
    var newCollection by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BookCollection?>(null) }
    val actions = BookActions(
        onOpen = { if (it.id in missing) addBooks() else onOpen(it, CoverSpot.TILE) },
        onShowOnCover = onShowOnCover,
        onFinished = onFinished,
        onRemove = { removing = it },
        onPin = { book, pinned -> organizer?.setPinned(book.id, pinned) },
        onCollections = { collectionsFor = it.id },
        onEdit = { editing = it.id },
        organize = organizer != null,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Brand() },
                actions = { SettingsButton(haptics, backFromSettings, onOpenSettings) },
            )
        },
        floatingActionButton = {
            if (state.books.isNotEmpty()) {
                // The + opens into Material's FAB menu, turning into a ✕: books from your files, or free ones.
                BackHandler(enabled = fabMenu) { fabMenu = false }
                val label = stringResource(R.string.library_add_books)
                FloatingActionButtonMenu(
                    expanded = fabMenu,
                    button = {
                        ToggleFloatingActionButton(
                            checked = fabMenu,
                            onCheckedChange = { haptics.tick(); fabMenu = it },
                            // A button that opens a menu, to a screen reader: "Add books, collapsed",
                            // not a tick box that is "not checked".
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = label
                                role = Role.Button
                                onClick {
                                    fabMenu = !fabMenu
                                    true
                                }
                                if (fabMenu) {
                                    collapse {
                                        fabMenu = false
                                        true
                                    }
                                } else {
                                    expand {
                                        fabMenu = true
                                        true
                                    }
                                }
                            },
                        ) {
                            val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) JacketIcons.Close else JacketIcons.Add } }
                            Icon(icon, null, with(ToggleFloatingActionButtonDefaults) { Modifier.animateIcon({ checkedProgress }) })
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = { fabMenu = false; addBooks() },
                        icon = { Icon(LibraryIcons.Folder, null) },
                        text = { Text(stringResource(R.string.library_add_from_files)) },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = { fabMenu = false; haptics.tick(); onFreeBooks() },
                        icon = { Icon(FreeBooks.Icon, null) },
                        text = { Text(stringResource(R.string.free_books_find)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The shelves, with the "Continue reading" card at their head when it doesn't have a pane of its own.
        val shelves: @Composable (Modifier, Boolean) -> Unit = { gridModifier, inlineHero ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                state = grid,
                modifier = gridModifier,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 104.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (inlineHero && hero != null && !onFinishedShelf) {
                    item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                        // The shelves change under it: it moves over rather than jumping.
                        ContinueCard(hero, outerAvailable, onOpen = { onOpen(it, CoverSpot.HERO) }, modifier = Modifier.animateItem())
                    }
                }
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    LibraryHeader(
                        count = if (collection != null) source.count { collection.id in it.collections } else shelf.size,
                        sort = state.settings.sort,
                        onSort = onSort,
                        query = query,
                        onQuery = { query = it },
                        searchable = state.books.size > 6,
                        // Inside a collection there's no shelf to switch: it shows all its books.
                        finishedCount = if (collection != null) 0 else finishedCount,
                        onFinishedShelf = onFinishedShelf && collection == null,
                        onShelf = { haptics.tick(); onShelf(it) },
                        modifier = Modifier.animateItem(),
                        // Beside the pane, today's reading is told there instead.
                        readToday = if (inlineHero) readToday else 0,
                        streak = if (inlineHero) streak else 0,
                        organize = if (organizer == null) null else {
                            {
                                // Once there's something to sort: a few books, or a collection already.
                                if (state.collections.isNotEmpty() || state.books.size >= 3) {
                                    CollectionChips(
                                        collections = state.collections,
                                        selected = collection?.id,
                                        haptics = haptics,
                                        onSelect = { organizer.showCollection(it) },
                                        onNew = { newCollection = true },
                                        onRename = { naming = it },
                                        onDelete = { deleting = it },
                                    )
                                }
                            }
                        },
                    )
                }
                if (shown.isEmpty()) {
                    item(key = "nothing", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            when {
                                query.isNotBlank() -> stringResource(R.string.library_no_match, query)
                                collection != null -> stringResource(R.string.library_collection_empty, collection.name)
                                else -> stringResource(R.string.library_all_finished)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.animateItem().padding(vertical = 32.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { book ->
                    BookTile(
                        book = book,
                        onCover = book.id == hero?.id && !onFinishedShelf,
                        missing = book.id in missing,
                        haptics = haptics,
                        actions = actions,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        // Wide (unfolded, a tablet, landscape): the book you're reading gets a pane of its own beside
        // the shelves, its cover large, instead of a card stretched across the whole width.
        val paneWidth = libraryPaneWidth()
        Box(Modifier.fillMaxSize()) {
            if (state.books.isEmpty()) {
                EmptyLibrary(
                    Modifier.padding(padding), foldable, onAdd = addBooks, onSample = { haptics.confirm(); onSample() },
                    onFreeBooks = { haptics.tick(); onFreeBooks() },
                )
            } else if (paneWidth != null && hero != null) {
                Row(Modifier.fillMaxSize()) {
                    NowReadingPane(
                        book = hero,
                        outerAvailable = outerAvailable,
                        readToday = readToday,
                        streak = streak,
                        haptics = haptics,
                        onOpen = { onOpen(it, CoverSpot.HERO) },
                        onListen = onListen,
                        // Side by side, each is read through before the other: not a line of one,
                        // then whatever of the other happens to be level with it.
                        modifier = Modifier
                            .width(paneWidth)
                            .fillMaxHeight()
                            .semantics { isTraversalGroup = true }
                            .padding(start = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                    )
                    shelves(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true }, false)
                }
            } else {
                shelves(Modifier.fillMaxSize(), true)
            }
            // The library dims while books come in, and clears as they land.
            AnimatedVisibility(visible = importing, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) { ContainedLoadingIndicator() }
            }
        }
    }

    removing?.let { book ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.library_remove_title, book.title)) },
            text = { Text(stringResource(R.string.library_remove_message)) },
            confirmButton = {
                TextButton(onClick = { removing = null; haptics.confirm(); onDelete(book) }) { Text(stringResource(R.string.library_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (organizer != null) {
        // Looked up afresh, so the ticks follow each change.
        state.find(collectionsFor)?.let { book ->
            CollectionsSheet(
                book = book,
                collections = state.collections,
                haptics = haptics,
                onChange = { organizer.setCollections(book.id, it) },
                onCreate = { organizer.createCollection(it) },
                onDismiss = { collectionsFor = null },
            )
        }
        state.find(editing)?.let { book ->
            EditBookSheet(
                book = book,
                haptics = haptics,
                onDismiss = { editing = null },
                onSave = { title, author, cover ->
                    editing = null
                    if (title != book.title || author != book.author) organizer.editDetails(book.id, title, author)
                    when (cover) {
                        CoverChange.Original -> organizer.setCover(book.id, null)
                        is CoverChange.Photo -> organizer.setCover(book.id, cover.image)
                        // Drawn at full size with the title and author as saved.
                        is CoverChange.Design -> scope.launch {
                            val image = withContext(Dispatchers.Default) { CoverDesigns.render(cover.design, title, author) }
                            organizer.setCover(book.id, image, cover.design.color)
                        }
                        null -> Unit
                    }
                },
            )
        }
        if (newCollection) {
            NameDialog(
                title = stringResource(R.string.library_collection_new),
                initial = "",
                confirm = stringResource(R.string.common_create),
                onDismiss = { newCollection = false },
                onConfirm = { name ->
                    newCollection = false
                    haptics.confirm()
                    organizer.showCollection(organizer.createCollection(name))
                },
            )
        }
        naming?.let { collection ->
            NameDialog(
                title = stringResource(R.string.library_collection_rename),
                initial = collection.name,
                confirm = stringResource(R.string.common_save),
                onDismiss = { naming = null },
                onConfirm = { name -> naming = null; organizer.renameCollection(collection.id, name) },
            )
        }
        deleting?.let { collection ->
            DeleteCollectionDialog(
                collection = collection,
                onDismiss = { deleting = null },
                onConfirm = { deleting = null; haptics.confirm(); organizer.deleteCollection(collection.id) },
            )
        }
    }
}

/**
 * The book on the "Continue reading" card: the one you're reading, if its file is here (after a
 * restore it may not be yet).
 */
internal fun LibraryState.heroBook(missing: Set<String>): Book? =
    current?.takeIf { !it.isFinished && it.id !in missing }
        ?: books.filter { !it.isFinished && it.id !in missing }.maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }

/** Showing the finished shelf, which only exists once something is on it (and has no hero card). */
internal val LibraryState.onFinishedShelf: Boolean get() = settings.showFinished && books.any { it.isFinished }

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The app icon's mark, in the current book's colour.
        Icon(
            painterResource(R.drawable.ic_brand_mark), null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(26.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

/**
 * The way into Settings: a gear that turns a notch under your thumb, a quarter turn as it takes
 * you there, and back again when you return. A quarter turn of an eight-toothed gear looks like
 * where it started, so it can rest at either end.
 */
@Composable
private fun SettingsButton(haptics: Haptics, backFromSettings: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var leaving by remember { mutableStateOf(false) }
    val turn = remember { Animatable(if (backFromSettings) GEAR_TURN else 0f) }
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(pressed, leaving) {
        turn.animateTo(if (leaving) GEAR_TURN else if (pressed) GEAR_TURN / 3f else 0f, spring)
    }
    FilledTonalIconButton(
        onClick = {
            haptics.tick()
            leaving = true
            onClick()
        },
        interactionSource = interaction,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Icon(
            JacketIcons.Settings, stringResource(R.string.library_profile_and_settings),
            Modifier.graphicsLayer { rotationZ = turn.value },
        )
    }
}

private const val GEAR_TURN = 90f

/** The book on your cover screen, one tap from where you left off. */
@Composable
private fun ContinueCard(book: Book, outerAvailable: Boolean, onOpen: (Book) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    // Back from reading, the line runs on to where you got to.
    val progress = animateFloatAsState(book.progress, WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "progress")
    Card(
        onClick = { onOpen(book) },
        interactionSource = interaction,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth().pressScale(interaction, pressed = 0.98f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                book,
                // The clip stays outside the shared cover: in flight it is drawn scaled, corners and all.
                // (The card says the title in words: "Cover of" the same title isn't said as well.)
                Modifier.width(96.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).sharedCover(book.id, CoverSpot.HERO).decorative(),
                maxHeight = 600,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (book.isNew) stringResource(R.string.library_start_reading) else stringResource(R.string.library_continue_reading),
                    style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(book.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotEmpty()) {
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                // The line is the words under it ("42% read") for the eye: it isn't read out too.
                LinearWavyProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.fillMaxWidth().decorative(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(book.statusLabel(LocalResources.current), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    AnimatedVisibility(outerAvailable) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(JacketIcons.OuterScreen, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.library_on_your_cover), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Windows at least this wide put the book you're reading in a pane beside the shelves. */
private val TWO_PANE_FROM = 700.dp

/**
 * The width of the "Continue reading" pane beside the shelves when the window is wide enough for
 * one (an unfolded foldable, a tablet, a phone on its side); null when it isn't.
 */
@Composable
internal fun libraryPaneWidth(): Dp? {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return if (width >= TWO_PANE_FROM) (width * 0.38f).coerceIn(300.dp, 440.dp) else null
}

/**
 * The book you're reading, given a pane of its own on a wide screen: its cover as large as the
 * pane allows, where you are in it, and a button each to read on or to listen.
 */
@Composable
private fun NowReadingPane(
    book: Book,
    outerAvailable: Boolean,
    readToday: Int,
    streak: Int,
    haptics: Haptics,
    onOpen: (Book) -> Unit,
    onListen: ((Book) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    // Back from reading, the line runs on to where you got to.
    val progress = animateFloatAsState(book.progress, WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "progress")
    BoxWithConstraints(modifier) {
        // As big as the pane is wide, unless that would push the words and buttons out of a short window.
        val coverWidth = minOf(maxWidth * 0.62f, ((maxHeight - 330.dp) * (2f / 3f)).coerceAtLeast(110.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Not a button itself, so Read and Listen stay buttons of their own; the cover opens the book too.
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (book.isNew) stringResource(R.string.library_start_reading) else stringResource(R.string.library_continue_reading),
                        style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))
                    CoverImage(
                        book,
                        // The clip stays outside the shared cover: in flight it is drawn scaled, corners and all.
                        // (To a screen reader the title and the Read button below are this cover, in words.)
                        Modifier.width(coverWidth).aspectRatio(2f / 3f)
                            .pressScale(interaction, pressed = 0.96f)
                            .clip(RoundedCornerShape(14.dp))
                            .decorative()
                            .clickable(interactionSource = interaction, indication = ripple(), onClickLabel = stringResource(R.string.library_read)) { onOpen(book) }
                            .sharedCover(book.id, CoverSpot.HERO),
                        maxHeight = 900,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        book.title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    if (book.author.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            book.author, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    LinearWavyProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier.fillMaxWidth().decorative(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(book.statusLabel(LocalResources.current), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        AnimatedVisibility(outerAvailable) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(JacketIcons.OuterScreen, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.library_on_your_cover), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpen(book) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(JacketIcons.Book, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.library_read), maxLines = 1, autoSize = fitOnOneLine(MaterialTheme.typography.labelLarge))
                        }
                        // Formats that can be read aloud.
                        if (onListen != null && (book.format == BookFormat.EPUB || book.format == BookFormat.TXT)) {
                            FilledTonalButton(
                                onClick = { haptics.tick(); onListen(book) },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Icon(JacketIcons.Headphones, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.library_listen), maxLines = 1, autoSize = fitOnOneLine(MaterialTheme.typography.labelLarge))
                            }
                        }
                    }
                }
            }
            // "24 min today · Day 5", under the book rather than over the shelves.
            if (readToday > 0 || streak > 1) {
                Row(Modifier.padding(start = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(JacketIcons.Clock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(
                            stringResource(R.string.stats_today, readToday).takeIf { readToday > 0 },
                            stringResource(R.string.stats_streak, streak).takeIf { streak > 1 },
                        ).joinToString(stringResource(R.string.separator)),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    count: Int,
    sort: LibrarySort,
    onSort: (LibrarySort) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    searchable: Boolean,
    finishedCount: Int,
    onFinishedShelf: Boolean,
    onShelf: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Minutes read today, and the run of days read; both 0 when the reader has those switched off. */
    readToday: Int = 0,
    streak: Int = 0,
    /** The collections' chips, when the library can be organized. */
    organize: (@Composable () -> Unit)? = null,
) {
    Column(modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedContent(
                targetState = onFinishedShelf,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "shelf-title",
                modifier = Modifier.weight(1f),
            ) { done ->
                Text(
                    if (done) stringResource(R.string.library_title_finished) else stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.asHeading(),
                )
            }
            Text(
                pluralStringResource(R.plurals.library_book_count, count, count),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // "24 min today · Day 5": only once there is something to say.
        AnimatedVisibility(visible = readToday > 0 || streak > 0) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(JacketIcons.Clock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
                Text(
                    listOfNotNull(
                        stringResource(R.string.stats_today, readToday).takeIf { readToday > 0 },
                        stringResource(R.string.stats_streak, streak).takeIf { streak > 1 },
                    ).joinToString(stringResource(R.string.separator)),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (organize != null) {
            Spacer(Modifier.height(12.dp))
            organize()
        }
        if (finishedCount > 0) {
            Spacer(Modifier.height(12.dp))
            ConnectedChoice(
                options = listOf(false, true),
                selected = onFinishedShelf,
                label = {
                    if (it) stringResource(R.string.library_shelf_finished_count, finishedCount)
                    else stringResource(R.string.library_shelf_reading)
                },
                onSelect = onShelf,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        ConnectedChoice(
            options = LibrarySort.entries, selected = sort, label = { stringResource(it.label) }, onSelect = onSort,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (searchable || query.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                leadingIcon = { Icon(JacketIcons.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQuery("") }) { Icon(JacketIcons.Close, stringResource(R.string.library_clear_search)) } }
                } else null,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BookTile(
    book: Book,
    onCover: Boolean,
    missing: Boolean,
    haptics: Haptics,
    actions: BookActions,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    // To a screen reader a book is one thing with one name: its title, who wrote it, how far
    // through it you are, and what its badges say; not a "Cover of" the title, the title again
    // and the author, each a stop of its own. What its menu offers is offered as its actions.
    val resources = LocalResources.current
    val separator = stringResource(R.string.list_separator)
    val said = spoken(
        separator,
        book.title,
        book.author,
        if (missing) stringResource(R.string.library_add_file_again) else book.statusLabel(resources),
        stringResource(R.string.library_pinned).takeIf { book.isPinned },
        stringResource(R.string.library_on_cover_screen).takeIf { onCover },
    )
    val openLabel = stringResource(R.string.library_menu_open)
    val menuLabel = stringResource(R.string.library_more_options)
    val doing = bookActionsFor(book, onCover, haptics, actions)
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = said
            onClick(label = openLabel) {
                actions.onOpen(book)
                true
            }
            onLongClick(label = menuLabel) {
                menu = true
                true
            }
            customActions = doing
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .pressScale(interaction, held = menu)
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = { actions.onOpen(book) },
                    onLongClick = { haptics.longPress(); menu = true },
                    onLongClickLabel = stringResource(R.string.library_more_options),
                ),
        ) {
            // The hero card may show this book too: each spot has its own key (see CoverSpot).
            CoverImage(book, Modifier.fillMaxSize().sharedCover(book.id, CoverSpot.TILE), maxHeight = 600)
            if (missing) {
                // Restored from a backup without the file: tap to add it again.
                Box(
                    Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                        Icon(JacketIcons.Upload, null, tint = Color.White)
                        Text(
                            stringResource(R.string.library_add_file_again), style = MaterialTheme.typography.labelMedium, color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else if (book.isFinished) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(28.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(JacketIcons.Check, stringResource(R.string.library_finished_badge), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiary)
                }
            }
            if (book.isNew && !missing) {
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text(
                        stringResource(R.string.library_new_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (book.isPinned) PinBadge()
                if (onCover) {
                    Box(
                        Modifier.size(28.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(JacketIcons.OuterScreen, stringResource(R.string.library_on_cover_screen), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            if (!book.isNew && !book.isFinished && book.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(book.progress).height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                BookMenuItems(book, onCover, haptics, actions, close = { menu = false })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotEmpty()) {
            Text(
                book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, foldable: Boolean, onAdd: () -> Unit, onSample: () -> Unit, onFreeBooks: () -> Unit) {
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "rotation",
    )
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(148.dp)
                .graphicsLayer { rotationZ = spin }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                JacketIcons.Book, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp).graphicsLayer { rotationZ = -spin },
            )
        }
        Spacer(Modifier.height(28.dp))
        // A phone that folds is told about the cover on its outer screen; any other, about reading and listening.
        Text(
            stringResource(if (foldable) R.string.library_empty_title else R.string.library_empty_title_phone),
            style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (foldable) R.string.library_empty_message else R.string.library_empty_message_phone),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, shapes = ButtonDefaults.shapes()) {
            Icon(JacketIcons.Add, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.library_add_books))
        }
        Spacer(Modifier.height(4.dp))
        // Nothing to add yet? A classic comes with the app, and more are free to download.
        TextButton(onClick = onSample) { Text(stringResource(R.string.library_try_sample)) }
        TextButton(onClick = onFreeBooks) {
            Icon(FreeBooks.Icon, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.free_books_find))
        }
    }
}
