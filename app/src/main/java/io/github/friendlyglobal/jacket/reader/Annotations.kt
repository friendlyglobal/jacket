package io.github.friendlyglobal.jacket.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.HighlightColor
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/** The page on screen, as a bookmark would remember it. */
data class Place(
    val locator: String?,
    val section: Int,
    val progress: Float,
    val chapter: String,
    val snippet: String,
)

/** A search result: where it is and the words around it. */
data class SearchHit(
    val index: Int,
    val chapter: String,
    val before: String,
    val match: String,
    val after: String,
)

/** Full-text search, for formats that support it. */
interface BookSearch {
    /** Streams results in reading order to [onHits] until the end of the book; cancel to stop. */
    suspend fun search(query: String, onHits: (List<SearchHit>) -> Unit)

    /** Goes to a result and marks it on the page. */
    fun show(hit: SearchHit)

    /** Forgets the last search and its marks. */
    fun clear()
}

private enum class NotesTab { CONTENTS, BOOKMARKS, HIGHLIGHTS }

/**
 * Contents, bookmarks and highlights in one sheet, switched with a connected button group.
 * Opens on Contents when the book has one.
 */
@Composable
fun NotesSheet(
    nav: ReaderNav,
    annotations: List<Annotation>,
    haptics: Haptics,
    onPickToc: (TocItem) -> Unit,
    onPick: (Annotation) -> Unit,
    onEdit: (Annotation) -> Unit,
    onDelete: (Annotation) -> Unit,
    /** Sends every highlight and note to another app, as text. */
    onShare: (() -> Unit)? = null,
    /** Saves them as a file instead. */
    onSave: (() -> Unit)? = null,
    /** Where nothing can be highlighted in this book, why: said in place of how highlights are made. */
    noHighlights: String? = null,
    /** And what to say over highlights that are listed all the same (made on another device), and can't be shown on the page. */
    highlightsNote: String? = null,
) {
    val bookmarks = annotations.filter { it.kind == AnnotationKind.BOOKMARK }.sortedBy { it.progress }
    val highlights = annotations.filter { it.kind == AnnotationKind.HIGHLIGHT }.sortedBy { it.progress }
    val tabs = buildList {
        if (nav.toc.isNotEmpty()) add(NotesTab.CONTENTS)
        add(NotesTab.BOOKMARKS)
        add(NotesTab.HIGHLIGHTS)
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    val roomy = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    if (tab !in tabs) tab = tabs.first()

    Column(Modifier.fillMaxWidth()) {
        ConnectedChoice(
            options = tabs,
            selected = tab,
            label = {
                when (it) {
                    NotesTab.CONTENTS -> stringResource(R.string.notes_tab_contents)
                    // Counts only fit when there's room (unfolded); the lists show them anyway.
                    NotesTab.BOOKMARKS -> if (bookmarks.isEmpty() || !roomy) {
                        stringResource(R.string.notes_tab_bookmarks)
                    } else {
                        stringResource(R.string.notes_tab_bookmarks_count, bookmarks.size)
                    }
                    NotesTab.HIGHLIGHTS -> if (highlights.isEmpty() || !roomy) {
                        stringResource(R.string.notes_tab_highlights)
                    } else {
                        stringResource(R.string.notes_tab_highlights_count, highlights.size)
                    }
                }
            },
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "notes-tab",
        ) { current ->
            when (current) {
                NotesTab.CONTENTS -> ContentsList(nav, haptics, onPickToc)
                NotesTab.BOOKMARKS -> if (bookmarks.isEmpty()) {
                    EmptyNotes(JacketIcons.Bookmark, stringResource(R.string.notes_no_bookmarks), stringResource(R.string.notes_no_bookmarks_hint))
                } else {
                    AnnotationList(bookmarks, haptics, onPick, onEdit = null, onDelete)
                }
                NotesTab.HIGHLIGHTS -> if (highlights.isEmpty()) {
                    EmptyNotes(JacketIcons.Highlight, stringResource(R.string.notes_no_highlights), noHighlights ?: stringResource(R.string.notes_no_highlights_hint))
                } else {
                    AnnotationList(highlights, haptics, onPick, onEdit, onDelete, onShare, onSave, note = highlightsNote)
                }
            }
        }
    }
}

@Composable
private fun ContentsList(nav: ReaderNav, haptics: Haptics, onPick: (TocItem) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (nav.tocIndex - 2).coerceAtLeast(0))
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        itemsIndexed(nav.toc) { index, item ->
            val current = index == nav.tocIndex
            ListItem(
                onClick = { haptics.tick(); onPick(item) },
                colors = ListItemDefaults.colors(
                    containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
                // The chapter you're in is filled in for the eye, and said to be the one for the ear.
                modifier = Modifier.padding(horizontal = 12.dp).clip(MaterialTheme.shapes.large).semantics { selected = current },
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

@Composable
private fun AnnotationList(
    items: List<Annotation>,
    haptics: Haptics,
    onPick: (Annotation) -> Unit,
    onEdit: ((Annotation) -> Unit)?,
    onDelete: (Annotation) -> Unit,
    onShare: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    /** Said above the list, where there is something to say of all of it (highlights that this phone can list and can't show on the page). */
    note: String? = null,
) {
    val resources = LocalResources.current
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (note != null) {
            item(key = "note") {
                Text(
                    note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
        }
        if (onShare != null || onSave != null) {
            // All of them at once, to keep somewhere else: as a file, or sent to another app.
            item(key = "share") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (onSave != null) {
                        FilledTonalButton(onClick = { haptics.tick(); onSave() }) {
                            Icon(JacketIcons.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.highlights_save))
                        }
                    }
                    if (onShare != null) {
                        FilledTonalButton(onClick = { haptics.tick(); onShare() }) {
                            Icon(JacketIcons.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.selection_share))
                        }
                    }
                }
            }
        }
        items(items, key = { it.id }) { item ->
            ListItem(
                onClick = { haptics.tick(); onPick(item) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 12.dp).clip(MaterialTheme.shapes.large).animateItem(),
                leadingContent = {
                    if (item.kind == AnnotationKind.HIGHLIGHT) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(item.color.argb)))
                    } else {
                        Icon(JacketIcons.BookmarkFilled, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                overlineContent = {
                    // (For a highlight in a PDF, its page too.)
                    Text(remember(item, resources) { highlightPlace(resources, item) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = if (item.note.isNotBlank()) {
                    { Text(item.note, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                } else {
                    null
                },
                trailingContent = {
                    Row {
                        if (onEdit != null) {
                            IconButton(onClick = { haptics.tick(); onEdit(item) }) { Icon(JacketIcons.Note, stringResource(R.string.notes_edit)) }
                        }
                        IconButton(onClick = { haptics.confirm(); onDelete(item) }) { Icon(JacketIcons.Delete, stringResource(R.string.notes_delete)) }
                    }
                },
            ) {
                Text(
                    item.text.ifBlank { stringResource(R.string.notes_bookmark_page_at, (item.progress * 100).toInt()) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (item.kind == AnnotationKind.HIGHLIGHT) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyNotes(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.padding(top = 16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Colour and note for one highlight. */
@Composable
fun HighlightEditor(
    annotation: Annotation,
    haptics: Haptics,
    onChange: (Annotation) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var note by remember(annotation.id) { mutableStateOf(annotation.note) }
    // A note typed and then dismissed with a swipe or back is kept, not lost.
    val latestNote by rememberUpdatedState(note)
    val latest by rememberUpdatedState(annotation)
    val save by rememberUpdatedState(onChange)
    DisposableEffect(annotation.id) {
        onDispose {
            val text = latestNote.trim()
            if (text != latest.note) save(latest.copy(note = text))
        }
    }
    // So is one still being written when Jacket is left (to look something up, the screen going
    // dark): Android may close the app from there, and this sheet and its words with it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val text = latestNote.trim()
        if (text != latest.note) save(latest.copy(note = text))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.notes_highlight_quote, annotation.text), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
        // Each colour is a choice with a name, and a full-size target around the dot that shows it.
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            HighlightColor.entries.forEach { color ->
                val selected = color == annotation.color
                val shape = if (selected) MaterialShapes.Cookie4Sided.toShape() else CircleShape
                val name = stringResource(color.label)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            haptics.tick()
                            onChange(annotation.copy(color = color))
                        }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(if (selected) 44.dp else 36.dp)
                            .clip(shape)
                            .background(Color(color.argb))
                            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, shape),
                    )
                }
            }
        }
        val done = {
            haptics.confirm()
            if (note != annotation.note) onChange(annotation.copy(note = note.trim()))
            onDone()
        }
        val focus = LocalFocusManager.current
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.notes_note)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                // With a keyboard alone (a highlight can be made without touching the page: see
                // ReaderNav.highlightHere), a note of several lines kept the keys to itself, and
                // there was no way on to Done. Tab goes on to the buttons, Shift and Tab back to
                // the colours, and Escape is Done: the note is kept.
                .onPreviewKeyEvent { event ->
                    val leaves = (event.key == Key.Tab || event.key == Key.Escape) && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                    if (leaves && event.type == KeyEventType.KeyDown) {
                        if (event.key == Key.Escape) done() else focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    }
                    // (The release is taken too: nothing else acts on half a key press.)
                    leaves
                },
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { haptics.confirm(); onDelete() }) {
                Icon(JacketIcons.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.notes_delete))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = done) { Text(stringResource(R.string.notes_done)) }
        }
    }
}

/** What a highlight's colour is called, for a screen reader: the dots themselves say nothing. */
@get:StringRes
internal val HighlightColor.label: Int
    get() = when (this) {
        HighlightColor.YELLOW -> R.string.color_yellow
        HighlightColor.GREEN -> R.string.color_green
        HighlightColor.BLUE -> R.string.color_blue
        HighlightColor.PINK -> R.string.color_pink
        HighlightColor.PURPLE -> R.string.color_purple
    }

/** Search inside the book: type, see results stream in, tap one to go there. */
@Composable
fun SearchPanel(search: BookSearch, haptics: Haptics, onPick: (SearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember { mutableStateListOf<SearchHit>() }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun run() {
        val q = query.trim()
        if (q.length < 2) return
        job?.cancel()
        hits.clear()
        searched = q
        searching = true
        job = scope.launch {
            try {
                search.search(q) { batch -> hits += batch }
            } finally {
                searching = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(JacketIcons.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = ""; job?.cancel(); hits.clear(); searched = ""; search.clear() }) { Icon(JacketIcons.Close, stringResource(R.string.search_clear)) } }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            when {
                searching -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                searched.isNotEmpty() -> Text(
                    if (hits.isEmpty()) {
                        stringResource(R.string.search_no_results, searched)
                    } else {
                        pluralStringResource(R.plurals.search_result_count, hits.size, hits.size)
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Said once the search is done: how many were found is otherwise only seen.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(hits, key = { it.index }) { hit ->
                ListItem(
                    onClick = { haptics.tick(); onPick(hit) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 12.dp).clip(MaterialTheme.shapes.large),
                    overlineContent = if (hit.chapter.isNotBlank()) {
                        { Text(hit.chapter, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                ) {
                    val highlight = MaterialTheme.colorScheme.primary
                    Text(
                        buildAnnotatedString {
                            append("…")
                            append(hit.before.takeLast(60).trimStart())
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) { append(hit.match) }
                            append(hit.after.take(80).trimEnd())
                            append("…")
                        },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Shown on the last page: a small celebration, and what to do next (mark it finished, open the
 * next book from the library, or go back).
 */
@Composable
fun FinishedCard(
    book: Book,
    next: Book?,
    onFinish: () -> Unit,
    onNext: (Book) -> Unit,
    onLibrary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spin = rememberInfiniteTransition(label = "finished")
    val rotation by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "spin")
    val pulse by spin.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "pulse")
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .graphicsLayer { rotationZ = rotation; scaleX = pulse; scaleY = pulse }
                        .clip(MaterialShapes.SoftBurst.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        JacketIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.graphicsLayer { rotationZ = -rotation },
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (book.isFinished) stringResource(R.string.finished_title_finished) else stringResource(R.string.finished_title_end),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        // It slides in under the last page: a screen reader says that it has.
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(JacketIcons.Close, stringResource(R.string.finished_keep_reading)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!book.isFinished) {
                    Button(onClick = onFinish) {
                        Icon(JacketIcons.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.finished_mark))
                    }
                } else {
                    Button(onClick = onLibrary) { Text(stringResource(R.string.finished_back_to_library)) }
                }
                if (next != null) {
                    FilledTonalButton(onClick = { onNext(next) }, modifier = Modifier.weight(1f, fill = false)) {
                        Text(stringResource(R.string.finished_next, next.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
