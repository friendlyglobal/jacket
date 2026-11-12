package io.github.friendlyglobal.jacket.ui.voices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Saying
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.voice.SayIt
import io.github.friendlyglobal.jacket.voice.VoiceRef
import java.util.UUID

/*
 * "Say it this way": a name the voice gets wrong is written for it the way it sounds. A word is
 * given its new sound in a small dialog (from the menu over selected words, or from the list), and
 * the list of them is where they are looked over, changed and removed.
 */

/** What the dialog is opened on: a saved word to change, or a new one ([word] as selected on the page, or nothing yet). */
class SayItDraft(val saying: Saying? = null, val word: String = "")

/** The saved way of saying [word] that the book [bookId] goes by, if there is one: its own, else the one for every book. */
fun List<Saying>.sayingOf(word: String, bookId: String?): Saying? =
    firstOrNull { it.bookId == bookId && SayIt.sameWord(it.word, word) }
        ?: firstOrNull { it.bookId == null && SayIt.sameWord(it.word, word) }

/**
 * A word, and how it sounds. "Hear it" says it in the voice that reads the book ([voice], or the
 * phone's own when null), in [language]. Kept for the book [bookId], or for every book; with no
 * book (in Settings), for every book.
 */
@Composable
fun SayItDialog(
    draft: SayItDraft,
    bookId: String?,
    voice: VoiceRef?,
    language: String?,
    haptics: Haptics,
    onSave: (Saying) -> Unit,
    onDelete: (Saying) -> Unit,
    onDismiss: () -> Unit,
) {
    val saved = draft.saying
    // A word kept for one book stays that book's, wherever it is changed from.
    val book = saved?.bookId ?: bookId
    var word by rememberSaveable(saved?.id, draft.word) { mutableStateOf(saved?.word ?: draft.word) }
    var say by rememberSaveable(saved?.id, draft.word) { mutableStateOf(saved?.say.orEmpty()) }
    var everyBook by rememberSaveable(saved?.id, draft.word) { mutableStateOf(if (saved != null) saved.bookId == null else book == null) }
    val previewer = rememberVoicePreviewer()
    val hearing = previewer.playing == SAYING
    val ready = SayIt.tidy(word).isNotEmpty() && SayIt.tidy(say).isNotEmpty()
    val save = {
        if (ready) {
            haptics.confirm()
            onSave(
                Saying(
                    id = saved?.id ?: UUID.randomUUID().toString(),
                    word = SayIt.tidy(word),
                    say = SayIt.tidy(say),
                    bookId = if (everyBook) null else book,
                    createdAt = saved?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        }
    }
    // Opened on a word: straight to how it sounds. Opened on nothing: the word first.
    val wordFocus = remember { FocusRequester() }
    val sayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { (if (word.isBlank()) wordFocus else sayFocus).requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(JacketIcons.Voice, null) },
        title = { Text(stringResource(R.string.say_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it.take(SayIt.LONGEST) },
                    label = { Text(stringResource(R.string.say_word)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(wordFocus),
                )
                OutlinedTextField(
                    value = say,
                    onValueChange = { say = it.take(SayIt.LONGEST) },
                    label = { Text(stringResource(R.string.say_sounds)) },
                    supportingText = { Text(stringResource(R.string.say_sounds_example)) },
                    singleLine = true,
                    // As it is typed: capitals are for the part to stress.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(sayFocus),
                )
                // The word as the voice will say it; before anything is written, as it says it now.
                FilledTonalButton(
                    onClick = {
                        haptics.tick()
                        previewer.say(voice, language, SayIt.tidy(say).ifEmpty { SayIt.tidy(word) })
                    },
                    enabled = hearing || SayIt.tidy(word).isNotEmpty() || SayIt.tidy(say).isNotEmpty(),
                ) {
                    Icon(if (hearing) JacketIcons.Stop else JacketIcons.Play, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(if (hearing) R.string.voice_stop_sample else R.string.say_hear))
                }
                if (book != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .toggleable(everyBook, role = Role.Switch) { on ->
                                haptics.toggle(on)
                                everyBook = on
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.say_every_book), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 16.dp))
                        Switch(checked = everyBook, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = ready) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            if (saved != null) {
                TextButton(onClick = { haptics.confirm(); onDelete(saved) }) { Text(stringResource(R.string.notes_delete)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * The words the voice says the listener's way: each with how it sounds, to change with a tap or
 * to remove; and the way to add one. [scope] says where a word applies, for the ones that need
 * saying: "Every book" in a book's own list, the book's name in the list of them all.
 */
@Composable
fun SayItList(
    sayings: List<Saying>,
    scope: (Saying) -> String?,
    haptics: Haptics,
    onAdd: () -> Unit,
    onEdit: (Saying) -> Unit,
    onDelete: (Saying) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            stringResource(R.string.say_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.say_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
        )
        if (sayings.isEmpty()) {
            Text(
                stringResource(R.string.say_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            sayings.forEachIndexed { index, saying ->
                val where = scope(saying)
                SegmentedListItem(
                    onClick = { haptics.tick(); onEdit(saying) },
                    shapes = ListItemDefaults.segmentedShapes(index, sayings.size),
                    supportingContent = {
                        Column {
                            Text(saying.say, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (where != null) {
                                Text(
                                    where, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { haptics.confirm(); onDelete(saying) }) {
                            Icon(JacketIcons.Delete, stringResource(R.string.say_remove, saying.word))
                        }
                    },
                ) { Text(saying.word, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        FilledTonalButton(onClick = { haptics.tick(); onAdd() }, modifier = Modifier.padding(top = 16.dp)) {
            Icon(JacketIcons.Add, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.say_add))
        }
        Spacer(Modifier.height(16.dp))
    }
}
