package io.github.friendlyglobal.jacket.ui.home

import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.pressScale
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.sharedCover

@Composable
fun HomeScreen(
    state: LibraryState,
    outerAvailable: Boolean,
    importing: Boolean,
    snackbar: SnackbarHostState,
    onImport: (List<Uri>) -> Unit,
    onOpen: (Book) -> Unit,
    onShowOnCover: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onOpenSettings: () -> Unit,
    missing: Set<String> = emptySet(),
    onShelf: (Boolean) -> Unit = {},
    onFinished: (Book, Boolean) -> Unit = { _, _ -> },
) {
    val haptics = rememberHaptics()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    val addBooks = { haptics.tick(); picker.launch(BookImporter.MIME_TYPES) }
    val grid = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { grid.firstVisibleItemIndex == 0 } }
    var query by rememberSaveable { mutableStateOf("") }
    // The hero is the book you're reading, if its file is here (after a restore it may not be yet).
    val hero = state.current?.takeIf { !it.isFinished && it.id !in missing }
        ?: state.books.filter { !it.isFinished && it.id !in missing }.maxByOrNull { maxOf(it.lastOpenedAt, it.addedAt) }
    val finished = state.settings.showFinished
    val finishedCount = state.books.count { it.isFinished }
    // The finished shelf only exists once something is on it.
    val onFinishedShelf = finished && finishedCount > 0
    val shelf = remember(state.books, onFinishedShelf) { state.books.filter { it.isFinished == onFinishedShelf } }
    val shown = remember(shelf, state.settings.sort, query) { sortAndFilter(shelf, state.settings.sort, query) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Brand() },
                actions = {
                    FilledTonalIconButton(onClick = { haptics.tick(); onOpenSettings() }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(JacketIcons.Person, stringResource(R.string.library_profile_and_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.library_add_books)) },
                    icon = { Icon(JacketIcons.Add, null) },
                    onClick = addBooks,
                    expanded = fabExpanded,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (state.books.isEmpty()) {
                EmptyLibrary(Modifier.padding(padding), onAdd = addBooks)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 4.dp,
                        bottom = padding.calculateBottomPadding() + 104.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (hero != null && !onFinishedShelf) {
                        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            ContinueCard(hero, outerAvailable, onOpen)
                        }
                    }
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        LibraryHeader(
                            count = shelf.size,
                            sort = state.settings.sort,
                            onSort = onSort,
                            query = query,
                            onQuery = { query = it },
                            searchable = state.books.size > 6,
                            finishedCount = finishedCount,
                            onFinishedShelf = onFinishedShelf,
                            onShelf = { haptics.tick(); onShelf(it) },
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "nothing", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (query.isBlank()) stringResource(R.string.library_all_finished) else stringResource(R.string.library_no_match, query),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 32.dp),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { book ->
                        BookTile(
                            book = book,
                            onCover = book.id == hero?.id && !onFinishedShelf,
                            missing = book.id in missing,
                            haptics = haptics,
                            onOpen = { if (it.id in missing) addBooks() else onOpen(it) },
                            onShowOnCover = onShowOnCover,
                            onDelete = onDelete,
                            onFinished = onFinished,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (importing) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) { ContainedLoadingIndicator() }
            }
        }
    }
}

private fun sortAndFilter(books: List<Book>, sort: LibrarySort, query: String): List<Book> {
    val q = query.trim()
    val matching = if (q.isEmpty()) books else books.filter { it.title.contains(q, true) || it.author.contains(q, true) }
    fun titleKey(b: Book) = b.title.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")
    return when (sort) {
        LibrarySort.RECENT -> matching.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }
        LibrarySort.TITLE -> matching.sortedBy(::titleKey)
        LibrarySort.AUTHOR -> matching.sortedWith(compareBy({ it.author.ifEmpty { "￿" }.lowercase() }, ::titleKey))
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

/** The book on your cover screen, one tap from where you left off. */
@Composable
private fun ContinueCard(book: Book, outerAvailable: Boolean, onOpen: (Book) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = { onOpen(book) },
        interactionSource = interaction,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().pressScale(interaction, pressed = 0.98f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                book,
                Modifier.width(96.dp).aspectRatio(2f / 3f).sharedCover(book.id).clip(RoundedCornerShape(10.dp)),
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
                LinearWavyProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth(),
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
) {
    Column(Modifier.padding(top = 10.dp)) {
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
                )
            }
            Text(
                pluralStringResource(R.plurals.library_book_count, count, count),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onOpen: (Book) -> Unit,
    onShowOnCover: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onFinished: (Book, Boolean) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .pressScale(interaction)
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClick = { onOpen(book) },
                    onLongClick = { haptics.longPress(); menu = true },
                    onLongClickLabel = stringResource(R.string.library_more_options),
                ),
        ) {
            // The hero card already carries this book's shared cover; keys must be unique on screen.
            CoverImage(book, Modifier.fillMaxSize().sharedCover(book.id, enabled = !onCover), maxHeight = 600)
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
            if (onCover) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(JacketIcons.OuterScreen, stringResource(R.string.library_on_cover_screen), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            if (!book.isNew && !book.isFinished && book.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(book.progress).height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_open)) }, leadingIcon = { Icon(JacketIcons.Book, null) }, onClick = { menu = false; onOpen(book) })
                if (!onCover) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_menu_show_on_cover)) }, leadingIcon = { Icon(JacketIcons.OuterScreen, null) },
                        onClick = { menu = false; haptics.confirm(); onShowOnCover(book) },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            if (book.isFinished) stringResource(R.string.library_menu_mark_unfinished)
                            else stringResource(R.string.library_menu_mark_finished),
                        )
                    },
                    leadingIcon = { Icon(JacketIcons.Check, null) },
                    onClick = { menu = false; haptics.confirm(); onFinished(book, !book.isFinished) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_menu_remove)) }, leadingIcon = { Icon(JacketIcons.Delete, null) },
                    onClick = { menu = false; confirm = true },
                )
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
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.library_remove_title, book.title)) },
            text = { Text(stringResource(R.string.library_remove_message)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; haptics.confirm(); onDelete(book) }) { Text(stringResource(R.string.library_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onAdd: () -> Unit) {
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
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.library_empty_message),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, shapes = ButtonDefaults.shapes()) {
            Icon(JacketIcons.Add, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.library_add_books))
        }
    }
}
