package io.github.friendlyglobal.jacket.reader

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.ui.Haptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import kotlin.math.roundToInt

/*
 * Words selected on an EPUB's page (and a text book's, an FB2's), and the menu over them.
 *
 * The phone's web view does the selecting: a press and hold on a word, and its own handles to
 * stretch the selection. The menu that then comes up by the words is Jacket's, the one a PDF's
 * words have too (see SelectionChoices.kt): the colours to highlight in, Note, Copy, Share, "Say it
 * this way", "Read aloud from here", and the phone's own text actions. The web view's own menu is
 * kept empty (see [WebSelection]).
 *
 * The web view says when words are selected and when they no longer are, and nothing in between:
 * not where they are, nor when a handle is dragged (its handles are windows of their own, out of
 * Jacket's reach). So where the words are is asked of the page while they are selected, a few
 * times a second, and the menu shows only once they have stayed where they are for a moment:
 * while a handle is dragged, or the page scrolls, it waits, as the menu over a PDF's words does.
 */

/** A finger on the page, watched before the page's own gestures and taking nothing from them: the menu over selected words waits while one is down. */
internal fun Modifier.selectionTouches(selection: WebSelection): Modifier = pointerInput(selection) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            selection.touching = event.changes.any { it.pressed }
        }
    }
}

/**
 * The menu over the words selected on the pages of [navigator], while [selection] says there are
 * some: put above them (see [SelectionMenu]), and following them if the page moves under them.
 * [choose] acts on what is chosen from it.
 */
@Composable
internal fun EpubSelectionMenu(selection: WebSelection, navigator: () -> EpubNavigatorFragment?, haptics: Haptics, choose: (SelectionChoice) -> Unit) {
    // Back lets go of the words, as it does over a PDF's, rather than close the book under them.
    val scope = rememberCoroutineScope()
    BackHandler(enabled = selection.active) {
        scope.launch { navigator()?.clearSelection() }
        selection.finish()
    }
    var at by remember(selection) { mutableStateOf<IntRect?>(null) }
    LaunchedEffect(selection) {
        snapshotFlow { selection.active && !selection.touching }.collectLatest { show ->
            at = null
            if (!show) return@collectLatest
            var last: IntRect? = null
            while (true) {
                delay(SELECTION_POLL_MS)
                val n = navigator() ?: continue
                val now = runCatching { n.currentSelection()?.rect }.getOrNull()?.let { inWindow(n, it) }
                // Still for a moment: the menu comes up there. Moving (a handle dragged, the page
                // scrolling), it waits for the words to come to rest.
                at = if (now != null && now == last) now else null
                last = now
            }
        }
    }
    val rect = at ?: return
    SelectionMenu(rect, haptics, handleRoom = WEB_HANDLE, choose = choose)
}

/** Where [rect], the selection as Readium gives it (in the pixels of the pages' own view), is in the window. */
private fun inWindow(n: EpubNavigatorFragment, rect: RectF): IntRect? {
    val view = n.view ?: return null
    val origin = IntArray(2).also(view::getLocationInWindow)
    if (rect.width() <= 0f && rect.height() <= 0f) return null
    return IntRect(
        origin[0] + rect.left.roundToInt(), origin[1] + rect.top.roundToInt(),
        origin[0] + rect.right.roundToInt(), origin[1] + rect.bottom.roundToInt(),
    )
}

/**
 * Where the words selected begin in a chapter file read aloud as [texts] (Readium's own reading
 * of it, block by block): the block, and how far into it (spaces aside), found by the words
 * either side of where the selection starts. [words] are the selection's own, with a little of
 * what stands before and after them, as Readium gives them. Null if they can't be told apart.
 */
internal fun selectionSpot(texts: List<String>, words: Locator.Text): TextSpot? =
    tappedAt(texts, words.before.orEmpty(), words.highlight.orEmpty() + words.after.orEmpty())

/** How often the page is asked where the selected words are, while there are some. */
private const val SELECTION_POLL_MS = 120L

/** How far the web view's selection handles hang below the words. */
private val WEB_HANDLE = 26.dp
