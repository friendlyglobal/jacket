package io.github.friendlyglobal.jacket.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.JacketIcons

private val ContentMaxWidth = 720.dp

@Composable
fun HomeScreen(
    state: LibraryState,
    outerAvailable: Boolean,
    importing: Boolean,
    snackbar: SnackbarHostState,
    onImport: (List<Uri>) -> Unit,
    onOpen: (Book) -> Unit,
    onSelect: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    val addBooks = { picker.launch(BookImporter.MIME_TYPES) }
    val current = state.current

    Scaffold(
        topBar = { TopAppBar(title = { Brand() }) },
        floatingActionButton = {
            if (state.books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = addBooks,
                    icon = { Icon(JacketIcons.Add, null) },
                    text = { Text("Add books") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (state.books.isEmpty()) {
                EmptyLibrary(Modifier.padding(padding), onAdd = addBooks)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
                ) {
                    val itemModifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
                    if (current != null) item(key = "now") { NowWearing(current, onOpen, itemModifier) }
                    item(key = "carousel") { CoverCarousel(state.books, current, onSelect, itemModifier) }
                    item(key = "settings") { CoverScreenCard(state.settings, outerAvailable, onSettings, itemModifier) }
                    item(key = "library-header") {
                        Text(
                            "Library · ${state.books.size}", style = MaterialTheme.typography.titleMedium,
                            modifier = itemModifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }
                    items(state.books.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) }, key = { it.id }) { book ->
                        LibraryRow(book, book.id == current?.id, onOpen, onSelect, onDelete, itemModifier.animateItem())
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

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Text("Jacket", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun NowWearing(book: Book, onOpen: (Book) -> Unit, modifier: Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            "NOW WEARING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            book.title, style = MaterialTheme.typography.headlineMediumEmphasized,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(book.author, book.positionLabel, "${book.percent}%").filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onOpen(book) }) {
                Icon(JacketIcons.Play, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (book.progress > 0f) "Continue reading" else "Start reading")
            }
            Spacer(Modifier.width(16.dp))
            LinearWavyProgressIndicator(progress = { book.progress }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CoverCarousel(books: List<Book>, current: Book?, onSelect: (Book) -> Unit, modifier: Modifier) {
    val count by rememberUpdatedState(books.size)
    val carouselState = rememberCarouselState { count }
    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = 172.dp,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.height(256.dp).padding(vertical = 10.dp),
    ) { index ->
        val book = books[index]
        Box(
            Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .clickable(onClickLabel = "Show on cover") { onSelect(book) },
        ) {
            CoverImage(book, Modifier.fillMaxSize())
            if (book.id == current?.id) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(30.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(JacketIcons.Check, "On the cover", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun CoverScreenCard(settings: Settings, outerAvailable: Boolean, onSettings: ((Settings) -> Settings) -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cover screen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (outerAvailable) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("Outer screen ready", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Widget style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                val styles = CoverStyle.entries
                styles.forEachIndexed { index, style ->
                    val checked = settings.coverStyle == style
                    ToggleButton(
                        checked = checked,
                        onCheckedChange = { onSettings { it.copy(coverStyle = style) } },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            styles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) {
                        if (checked) {
                            Icon(JacketIcons.Check, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(style.label, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            SettingRow("Reading progress", "Progress bar and percentage on the cover", settings.showProgress) { v ->
                onSettings { it.copy(showProgress = v) }
            }
            SettingRow("Ambient dim", "The outer screen dims after 30 s untouched", settings.ambientDim) { v ->
                onSettings { it.copy(ambientDim = v) }
            }
            SettingRow("Cover while reading", "Book-style foldables: put the cover on the outer screen when you unfold", settings.autoOuterScreen) { v ->
                onSettings { it.copy(autoOuterScreen = v) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Galaxy Z Flip: add “Jacket cover” under Settings › Cover screen › Widgets. " +
                    "Motorola Razr: open Jacket from the external display.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                { Icon(JacketIcons.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
            } else null,
        )
    }
}

@Composable
private fun LibraryRow(
    book: Book,
    isCurrent: Boolean,
    onOpen: (Book) -> Unit,
    onSelect: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        onClick = { onOpen(book) },
        modifier = modifier.padding(horizontal = 4.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        supportingContent = {
            val status = if (book.progress > 0f) "${book.percent}%" else "New"
            Text(
                listOf(book.author, book.format.label, status).filter { it.isNotEmpty() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            CoverImage(book, Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)), maxHeight = 200)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(JacketIcons.More, "More options for ${book.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!isCurrent) {
                        DropdownMenuItem(
                            text = { Text("Show on cover") }, leadingIcon = { Icon(JacketIcons.Pin, null) },
                            onClick = { menu = false; onSelect(book) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove") }, leadingIcon = { Icon(JacketIcons.Delete, null) },
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        },
    ) {
        Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove “${book.title}”?") },
            text = { Text("Jacket deletes its own copy and your reading position. The original file stays where it was.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete(book) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
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
        Text(
            "Let your foldable wear your book", style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Add an EPUB, PDF, CBZ comic or text file. Jacket keeps a private copy and puts its cover " +
                "on your outer screen, or in a cover-screen widget, while you read.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(JacketIcons.Add, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Add books")
        }
    }
}
