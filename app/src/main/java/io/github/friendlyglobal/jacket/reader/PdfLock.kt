package io.github.friendlyglobal.jacket.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Library
import io.github.friendlyglobal.jacket.importer.PdfDocument
import io.github.friendlyglobal.jacket.importer.PdfLocked
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons

/*
 * A PDF locked with a password, in its reader.
 *
 * Android opens such a PDF when it is given the password (see Pdf.open). The reader asks for it
 * in a small dialog, says so when it is wrong, and, if asked to, remembers it for that book on
 * this phone (see PdfPasswords): the book then opens without asking, and can be read aloud with
 * no reader open. A phone whose Android can't open locked PDFs says so instead.
 */

/** A password the reader typed for a PDF, and whether it is to be remembered on this phone. */
internal class PdfAttempt(val password: String, val remember: Boolean)

/** What Jacket knows of a PDF's password, as far as opening the PDF goes (see [PdfOpening]). */
internal interface PdfKeys {
    /** The password Jacket has for the book, if any. */
    fun known(): String?

    /** A password that was typed opened the book. */
    fun opened(attempt: PdfAttempt)

    /** The password Jacket had no longer opens the book. */
    fun stale()

    /** The book turned out to be locked. */
    fun locked()
}

/** Opening a PDF that may be locked: which password it is tried with, and what is kept of how that went. Apart from Android, so it can be tested. */
internal object PdfOpening {
    /**
     * Opens a PDF with [open], which throws [PdfLocked] for a locked one it wasn't given the
     * password of. It is tried with what the reader [typed]; before they have typed anything,
     * with the password Jacket has for it, if it has one.
     *
     * A typed password that opens it is handed to [keys] to hold, and to remember if that was
     * asked. One that Jacket had and that no longer opens the file (the file was changed for
     * another) is dropped. Throws [PdfLocked] when the PDF stays locked: `wrong` only if a
     * password the reader typed just now was refused, which is when they are to be told so.
     */
    fun <T> open(typed: PdfAttempt?, keys: PdfKeys, open: (password: String?) -> T): T {
        val known = if (typed == null) keys.known() else null
        try {
            val opened = open(typed?.password ?: known)
            if (typed != null) keys.opened(typed)
            return opened
        } catch (e: PdfLocked) {
            if (known != null) keys.stale()
            keys.locked()
            throw PdfLocked(wrong = typed != null)
        }
    }
}

/** [book]'s PDF, opened to be read (see [PdfOpening.open]), with Jacket's own keeping of passwords. */
internal fun Library.openPdf(book: Book, typed: PdfAttempt?): PdfDocument {
    val keys = object : PdfKeys {
        override fun known() = passwords.of(book.id)
        override fun opened(attempt: PdfAttempt) = passwords.opened(book.id, attempt.password, attempt.remember)
        override fun stale() = passwords.forget(book.id)
        override fun locked() = passwords.locked(book.id)
    }
    return PdfOpening.open(typed, keys) { password -> PdfDocument(fileOf(book), password) }
}

/**
 * The page of a PDF that is still locked: it says so, and asks for the password (once the book
 * has opened on it, and again from its button if the dialog was put away). [wrong] when the
 * password just typed wasn't the one.
 */
@Composable
internal fun LockedPdf(wrong: Boolean, haptics: Haptics, onOpen: (PdfAttempt) -> Unit) {
    val nav = LocalReaderNav.current
    // Nothing more is coming: the cover may open on this page.
    SideEffect { nav?.contentReady = true }
    var asking by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { nav?.toggleControls?.invoke() }.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(JacketIcons.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.pdf_locked_message), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = { haptics.tick(); asking = true }) { Text(stringResource(R.string.pdf_locked_enter)) }
    }
    // Asked once the cover has swung out of the way, not over it.
    if (asking && nav?.opened != false) {
        PdfPasswordDialog(wrong, haptics, onOpen = onOpen, onDismiss = { asking = false })
    }
}

@Composable
private fun PdfPasswordDialog(wrong: Boolean, haptics: Haptics, onOpen: (PdfAttempt) -> Unit, onDismiss: () -> Unit) {
    // (Not saved with the screen's state: a password is kept nowhere it wasn't asked to be.)
    var password by remember { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    var keep by rememberSaveable { mutableStateOf(false) }
    // The password that didn't work is said to be wrong until another is begun.
    var refused by remember { mutableStateOf(wrong) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        if (password.isNotEmpty()) {
            haptics.confirm()
            onOpen(PdfAttempt(password, keep))
        }
    }
    val wrongMessage = stringResource(R.string.pdf_password_wrong)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(JacketIcons.Lock, null) },
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        refused = false
                    },
                    label = { Text(stringResource(R.string.pdf_password_label)) },
                    isError = refused,
                    supportingText = if (refused) {
                        { Text(wrongMessage, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    } else {
                        null
                    },
                    singleLine = true,
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).then(if (refused) Modifier.semantics { error(wrongMessage) } else Modifier),
                )
                CheckRow(shown, stringResource(R.string.pdf_password_show)) { on ->
                    haptics.toggle(on)
                    shown = on
                }
                CheckRow(keep, stringResource(R.string.pdf_password_remember), stringResource(R.string.pdf_password_remember_note)) { on ->
                    haptics.toggle(on)
                    keep = on
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = password.isNotEmpty()) { Text(stringResource(R.string.library_menu_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** A box to tick with its words beside it, the whole row the thing to tap. */
@Composable
private fun CheckRow(checked: Boolean, label: String, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
