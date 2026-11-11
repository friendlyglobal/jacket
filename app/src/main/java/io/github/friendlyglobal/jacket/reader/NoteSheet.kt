package io.github.friendlyglobal.jacket.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberReadingFontFamily

/** A note being shown over the page, and the way to it where it sits in the book. */
internal class ShownNote(val note: Note, val goTo: () -> Unit)

/**
 * A note of the book's, in one of the reader's sheets: read where you are, in the type you read
 * the book in, and dismissed like any sheet. "Go to the note" opens it in its place instead (the
 * reader then offers the way back, as after any jump).
 */
@Composable
internal fun ColumnScope.NoteSheet(note: Note, settings: Settings, haptics: Haptics, onGoTo: () -> Unit) {
    val family = rememberReadingFontFamily(settings.font)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = family, fontSize = 16.sp * settings.textScale, lineHeight = 25.sp * settings.textScale,
    )
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 24.dp).navigationBarsPadding()) {
        Text(
            if (note.mark.isEmpty()) stringResource(R.string.footnote_title) else stringResource(R.string.footnote_title_marked, note.mark),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
        )
        // A long note scrolls under its title; the way to it stays in reach below.
        SelectionContainer(Modifier.weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (paragraph in note.paragraphs) {
                    Text(remember(paragraph) { paragraph.styled() }, style = style)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = { haptics.tick(); onGoTo() }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.footnote_go_to))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Icon(JacketIcons.Next, null, Modifier.size(ButtonDefaults.IconSize))
            }
        }
    }
}

/** The paragraph with its italics and bold as the book set them. */
private fun NoteParagraph.styled(): AnnotatedString = buildAnnotatedString {
    append(text)
    for (range in italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.first, range.last + 1)
    for (range in bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
}
