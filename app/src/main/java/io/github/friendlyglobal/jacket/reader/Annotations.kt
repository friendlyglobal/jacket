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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.HighlightColor
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
) {
    val bookmarks = annotations.filter { it.kind == AnnotationKind.BOOKMARK }.sortedBy { it.progress }
    val highlights = annotations.filter { it.kind == AnnotationKind.HIGHLIGHT }.sortedBy { it.progress }
    val tabs = buildList {
        if (nav.toc.isNotEmpty()) add(NotesTab.CONTENTS)
        add(NotesTab.BOOKMARKS)
        add(NotesTab.HIGHLIGHTS)
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    if (tab !in tabs) tab = tabs.first()

    Column(Modifier.fillMaxWidth()) {
        ConnectedChoice(
            options = tabs,
            selected = tab,
            label = {
                when (it) {
                    NotesTab.CONTENTS -> "Contents"
                    NotesTab.BOOKMARKS -> if (bookmarks.isEmpty()) "Bookmarks" else "Bookmarks · ${bookmarks.size}"
                    NotesTab.HIGHLIGHTS -> if (highlights.isEmpty()) "Highlights" else "Highlights · ${highlights.size}"
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
                    EmptyNotes(JacketIcons.Bookmark, "No bookmarks yet", "Tap the ribbon at the top of a page to keep your place there.")
                } else {
                    AnnotationList(bookmarks, haptics, onPick, onEdit = null, onDelete)
                }
                NotesTab.HIGHLIGHTS -> if (highlights.isEmpty()) {
                    EmptyNotes(JacketIcons.Highlight, "No highlights yet", "Press and hold on the text, then choose Highlight or Note.")
                } else {
                    AnnotationList(highlights, haptics, onPick, onEdit, onDelete)
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

@Composable
private fun AnnotationList(
    items: List<Annotation>,
    haptics: Haptics,
    onPick: (Annotation) -> Unit,
    onEdit: ((Annotation) -> Unit)?,
    onDelete: (Annotation) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
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
                    Text(
                        listOf(item.chapter, "${(item.progress * 100).toInt()}%").filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = if (item.note.isNotBlank()) {
                    { Text(item.note, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                } else {
                    null
                },
                trailingContent = {
                    Row {
                        if (onEdit != null) {
                            IconButton(onClick = { haptics.tick(); onEdit(item) }) { Icon(JacketIcons.Note, "Edit note and colour") }
                        }
                        IconButton(onClick = { haptics.confirm(); onDelete(item) }) { Icon(JacketIcons.Delete, "Delete") }
                    }
                },
            ) {
                Text(
                    item.text.ifBlank { "Page at ${(item.progress * 100).toInt()}%" },
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
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "“${annotation.text}”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HighlightColor.entries.forEach { color ->
                val selected = color == annotation.color
                val shape = if (selected) MaterialShapes.Cookie4Sided.toShape() else CircleShape
                Box(
                    Modifier
                        .size(if (selected) 44.dp else 36.dp)
                        .clip(shape)
                        .background(Color(color.argb))
                        .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, shape)
                        .clickable {
                            haptics.tick()
                            onChange(annotation.copy(color = color))
                        },
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { haptics.confirm(); onDelete() }) {
                Icon(JacketIcons.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text("Delete")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                haptics.confirm()
                if (note != annotation.note) onChange(annotation.copy(note = note.trim()))
                onDone()
            }) { Text("Done") }
        }
    }
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
            placeholder = { Text("Search this book") },
            leadingIcon = { Icon(JacketIcons.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = ""; job?.cancel(); hits.clear(); searched = ""; search.clear() }) { Icon(JacketIcons.Close, "Clear") } }
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
                    when (hits.size) {
                        0 -> "No results for “$searched”"
                        1 -> "1 result"
                        else -> "${hits.size} results"
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        JacketIcons.Sparkle, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.graphicsLayer { rotationZ = -rotation },
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (book.isFinished) "Finished" else "The end",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(JacketIcons.Close, "Keep reading") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!book.isFinished) {
                    Button(onClick = onFinish) {
                        Icon(JacketIcons.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mark as finished")
                    }
                } else {
                    Button(onClick = onLibrary) { Text("Back to library") }
                }
                if (next != null) {
                    FilledTonalButton(onClick = { onNext(next) }, modifier = Modifier.weight(1f, fill = false)) {
                        Text("Next: ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
