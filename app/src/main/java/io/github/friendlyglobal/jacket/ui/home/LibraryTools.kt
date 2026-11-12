package io.github.friendlyglobal.jacket.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookCollection
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.CoverSpot
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.pressScale
import io.github.friendlyglobal.jacket.ui.sharedCover
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import io.github.friendlyglobal.jacket.ui.asHeading

/** The library's own icons, as path data like [JacketIcons]. */
internal object LibraryIcons {
    val Pin = icon("M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z")
    val Folder = icon("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    val Edit = icon("M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z")
    val More = icon("M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z")
    val Image = icon("M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z")
    val Palette = icon("M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z")

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}

/** What a book's menu can do; the organizing items only show when [organize] is on. */
internal class BookActions(
    val onOpen: (Book) -> Unit,
    val onShowOnCover: (Book) -> Unit,
    val onFinished: (Book, Boolean) -> Unit,
    val onRemove: (Book) -> Unit,
    val onPin: (Book, Boolean) -> Unit,
    val onCollections: (Book) -> Unit,
    val onEdit: (Book) -> Unit,
    val organize: Boolean,
)

/** The items of a book's menu, for the grid's long press and the list's ⋮ alike. */
@Composable
internal fun BookMenuItems(book: Book, onCover: Boolean, haptics: Haptics, actions: BookActions, close: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_open)) }, leadingIcon = { Icon(JacketIcons.Book, null) }, onClick = { close(); actions.onOpen(book) })
    if (actions.organize) {
        DropdownMenuItem(
            text = { Text(stringResource(if (book.isPinned) R.string.library_unpin else R.string.library_pin)) },
            leadingIcon = { Icon(LibraryIcons.Pin, null) },
            onClick = { close(); haptics.toggle(!book.isPinned); actions.onPin(book, !book.isPinned) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_add_to_collection)) }, leadingIcon = { Icon(LibraryIcons.Folder, null) },
            onClick = { close(); actions.onCollections(book) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_edit_book)) }, leadingIcon = { Icon(LibraryIcons.Edit, null) },
            onClick = { close(); actions.onEdit(book) },
        )
    }
    if (!onCover) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_menu_show_on_cover)) }, leadingIcon = { Icon(JacketIcons.OuterScreen, null) },
            onClick = { close(); haptics.confirm(); actions.onShowOnCover(book) },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(if (book.isFinished) R.string.library_menu_mark_unfinished else R.string.library_menu_mark_finished)) },
        leadingIcon = { Icon(JacketIcons.Check, null) },
        onClick = { close(); haptics.confirm(); actions.onFinished(book, !book.isFinished) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.library_menu_remove)) }, leadingIcon = { Icon(JacketIcons.Delete, null) },
        onClick = { close(); actions.onRemove(book) },
    )
}

/**
 * The same things [BookMenuItems] offers, as actions of the book itself: a screen reader or a
 * switch reaches them from the book, without the long press that opens the menu.
 */
@Composable
internal fun bookActionsFor(book: Book, onCover: Boolean, haptics: Haptics, actions: BookActions): List<CustomAccessibilityAction> {
    val pin = stringResource(if (book.isPinned) R.string.library_unpin else R.string.library_pin)
    val collections = stringResource(R.string.library_add_to_collection)
    val edit = stringResource(R.string.library_edit_book)
    val cover = stringResource(R.string.library_menu_show_on_cover)
    val finished = stringResource(if (book.isFinished) R.string.library_menu_mark_unfinished else R.string.library_menu_mark_finished)
    val remove = stringResource(R.string.library_menu_remove)
    return buildList {
        if (actions.organize) {
            add(CustomAccessibilityAction(pin) { haptics.toggle(!book.isPinned); actions.onPin(book, !book.isPinned); true })
            add(CustomAccessibilityAction(collections) { actions.onCollections(book); true })
            add(CustomAccessibilityAction(edit) { actions.onEdit(book); true })
        }
        if (!onCover) add(CustomAccessibilityAction(cover) { haptics.confirm(); actions.onShowOnCover(book); true })
        add(CustomAccessibilityAction(finished) { haptics.confirm(); actions.onFinished(book, !book.isFinished); true })
        add(CustomAccessibilityAction(remove) { actions.onRemove(book); true })
    }
}

/** A pin in a cookie, on a pinned book's cover. */
@Composable
internal fun PinBadge(modifier: Modifier = Modifier, size: Int = 28) {
    Box(
        modifier.size(size.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(LibraryIcons.Pin, stringResource(R.string.library_pinned), Modifier.size((size * 0.55f).dp), tint = MaterialTheme.colorScheme.onSecondary)
    }
}

/**
 * The library's collections as chips: All, each collection, and a new one. Tapping the chosen
 * collection again offers to rename or delete it.
 */
@Composable
internal fun CollectionChips(
    collections: List<BookCollection>,
    selected: String?,
    haptics: Haptics,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onRename: (BookCollection) -> Unit,
    onDelete: (BookCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { haptics.tick(); onSelect(null) },
            label = { Text(stringResource(R.string.library_collection_all)) },
        )
        collections.forEach { collection ->
            val chosen = collection.id == selected
            var menu by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = chosen,
                    onClick = { haptics.tick(); if (chosen) menu = true else onSelect(collection.id) },
                    label = { Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(LibraryIcons.Folder, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    trailingIcon = if (chosen) {
                        { Icon(JacketIcons.ExpandMore, stringResource(R.string.library_collection_options), Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_collection_rename)) }, leadingIcon = { Icon(LibraryIcons.Edit, null) },
                        onClick = { menu = false; onRename(collection) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_collection_delete)) }, leadingIcon = { Icon(JacketIcons.Delete, null) },
                        onClick = { menu = false; onDelete(collection) },
                    )
                }
            }
        }
        AssistChip(
            onClick = { haptics.tick(); onNew() },
            label = { Text(stringResource(R.string.library_collection_new)) },
            leadingIcon = { Icon(JacketIcons.Add, null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }
}

/** Which collections [book] is in, each a tick away; and a new one, which it joins. */
@Composable
internal fun CollectionsSheet(
    book: Book,
    collections: List<BookCollection>,
    haptics: Haptics,
    onChange: (Set<String>) -> Unit,
    onCreate: (String) -> String,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.library_collections_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp).asHeading(),
            )
            Text(
                book.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (collections.isEmpty()) {
                Text(
                    stringResource(R.string.library_collections_none), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            collections.forEach { collection ->
                val inIt = collection.id in book.collections
                ListItem(
                    headlineContent = { Text(collection.name) },
                    leadingContent = { Icon(LibraryIcons.Folder, null) },
                    trailingContent = { Checkbox(checked = inIt, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    // A tick box, ticked or not: as a plain row it never said whether the book was in.
                    modifier = Modifier.toggleable(inIt, role = Role.Checkbox) { on ->
                        haptics.toggle(on)
                        onChange(if (on) book.collections + collection.id else book.collections - collection.id)
                    }.padding(horizontal = 8.dp),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.library_collection_new)) },
                leadingContent = { Icon(JacketIcons.Add, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { haptics.tick(); naming = true }.padding(horizontal = 8.dp),
            )
        }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.library_collection_new),
            initial = "",
            confirm = stringResource(R.string.common_create),
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                haptics.confirm()
                val id = onCreate(name)
                onChange(book.collections + id)
            },
        )
    }
}

/** Asks for a collection's name. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text(stringResource(R.string.library_collection_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Makes sure about deleting a collection: its books stay. */
@Composable
internal fun DeleteCollectionDialog(collection: BookCollection, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_collection_delete_title, collection.name)) },
        text = { Text(stringResource(R.string.library_collection_delete_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.library_collection_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
