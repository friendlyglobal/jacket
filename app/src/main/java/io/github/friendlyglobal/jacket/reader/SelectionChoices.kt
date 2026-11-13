package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.HighlightColor
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons

/*
 * The menu over selected words, the same in every book.
 *
 * A PDF's words are chosen by Jacket itself, on the picture of its page (see PdfHighlights.kt);
 * an EPUB's, a text book's and an FB2's by the phone's web view, which draws its own handles (see
 * EpubSelection.kt). Either way the menu that comes up by the words is this one: the same choices,
 * in the same order, with the same look and words, so a reader who has learnt one has learnt both.
 */

/** What the menu over selected words offers. */
internal sealed interface SelectionChoice {
    class Highlight(val color: HighlightColor) : SelectionChoice
    data object Note : SelectionChoice
    data object Copy : SelectionChoice
    data object Share : SelectionChoice
    data object Say : SelectionChoice
    data object Listen : SelectionChoice

    /** One of the phone's own text actions (Translate, and the like). */
    class Process(val activity: ResolveInfo) : SelectionChoice
}

/**
 * The menu over selected words: the five colours to highlight them in, Note, Copy, Share, "Say it
 * this way", "Read aloud from here", and the phone's own text actions (Translate, and the like).
 *
 * It is put by the words ([selection] is where they are, in the window): above them where there
 * is room, else below them and their handles ([handleRoom] is how far those hang under the last
 * line), else beside them. It is not focusable: a finger outside it still reaches the page, and
 * the handles under it.
 */
@Composable
internal fun SelectionMenu(selection: IntRect, haptics: Haptics, handleRoom: Dp, choose: (SelectionChoice) -> Unit) {
    val context = LocalContext.current
    val processors = remember { textProcessors(context) }
    val density = LocalDensity.current
    val position = remember(selection, density, handleRoom) {
        with(density) { BySelection(selection, margin = 8.dp.roundToPx(), handle = handleRoom.roundToPx() + 6.dp.roundToPx()) }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        val motion = MaterialTheme.motionScheme
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(6.dp).widthIn(min = 200.dp, max = 320.dp).width(IntrinsicSize.Max),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    val highlight = stringResource(R.string.selection_highlight)
                    Text(
                        highlight, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    // Each colour highlights at once, in that colour: a dot with a name, and a full-size target around it.
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        HighlightColor.entries.forEach { color ->
                            val name = stringResource(color.label)
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .clickable(onClickLabel = highlight, role = Role.Button) { choose(SelectionChoice.Highlight(color)) }
                                    .semantics { contentDescription = name },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(Color(color.argb))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                )
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Choice(JacketIcons.Note, stringResource(R.string.selection_note)) { choose(SelectionChoice.Note) }
                    Choice(JacketIcons.Copy, stringResource(android.R.string.copy)) {
                        haptics.tick()
                        choose(SelectionChoice.Copy)
                    }
                    Choice(JacketIcons.Share, stringResource(R.string.selection_share)) { choose(SelectionChoice.Share) }
                    Choice(JacketIcons.Voice, stringResource(R.string.say_title)) { choose(SelectionChoice.Say) }
                    Choice(JacketIcons.Play, stringResource(R.string.selection_read_from_here)) { choose(SelectionChoice.Listen) }
                    processors.forEach { processor ->
                        val label = remember(processor) { processor.loadLabel(context.packageManager).toString() }
                        Choice(null, label) { choose(SelectionChoice.Process(processor)) }
                    }
                }
            }
        }
    }
}

/** The phone's own text actions (Translate, Define and the like), other apps' only, as many as the menu lists. */
private fun textProcessors(context: Context): List<ResolveInfo> =
    runCatching { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0) }
        .getOrDefault(emptyList()).filter { it.activityInfo.packageName != context.packageName }.take(MOST_PROCESSORS)

@Composable
private fun Choice(icon: ImageVector?, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = if (icon != null) { { Icon(icon, null) } } else null,
        onClick = onClick,
    )
}

/**
 * Puts the menu by the [selection] (in the window): above it where there is room, else below it
 * and its handles, else beside it; and failing all that at the foot of the window, over it.
 */
private class BySelection(private val selection: IntRect, private val margin: Int, private val handle: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val centred = (selection.center.x - width / 2).coerceIn(margin, (windowSize.width - width - margin).coerceAtLeast(margin))
        val above = selection.top - margin - height
        val below = selection.bottom + handle + margin
        val level = (selection.center.y - height / 2).coerceIn(margin, (windowSize.height - height - margin).coerceAtLeast(margin))
        return when {
            above >= margin -> IntOffset(centred, above)
            below + height <= windowSize.height - margin -> IntOffset(centred, below)
            selection.right + margin + width <= windowSize.width - margin -> IntOffset(selection.right + margin, level)
            selection.left - margin - width >= margin -> IntOffset(selection.left - margin - width, level)
            else -> IntOffset(centred, (windowSize.height - height - margin).coerceAtLeast(margin))
        }
    }
}

/** No more of the phone's own text actions than this are listed. */
private const val MOST_PROCESSORS = 3
