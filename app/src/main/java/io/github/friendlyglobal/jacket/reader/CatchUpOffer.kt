package io.github.friendlyglobal.jacket.reader

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.catchUp
import io.github.friendlyglobal.jacket.data.Annotation
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.BookFormat
import io.github.friendlyglobal.jacket.data.CatchUpRules
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberTimeToAct
import io.github.friendlyglobal.jacket.ui.screenReaderOn
import kotlinx.coroutines.delay

/** How long the offer stays over the page before it gets out of the way by itself. */
private const val OFFER_MS = 20_000L

/** How the offer left, which decides which way it goes. */
private enum class Gone { TAKEN, WAVED }

/**
 * "On Tablet you're on page 212, in Chapter 9. | Go there | ✕": another of the reader's devices
 * is further on in this book (see CatchUp). One tap goes there, and leaves the way back on offer
 * as any jump does; ✕ puts the offer away until that device has read on. Nothing moves until the
 * reader says so.
 *
 * It drops in under the top bar, where the way back from a jump does, and stays out of that
 * one's way. Left alone it tucks itself away after a while, and is there again the next time the
 * book is opened; under a screen reader it waits to be found.
 */
@Composable
internal fun CatchUpOffer(book: Book, nav: ReaderNav, haptics: Haptics, visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val catchUp = context.catchUp
    val offers by catchUp.offers.collectAsStateWithLifecycle()
    val offer = offers[book.id]
    // A book opening is a moment to read what the other devices wrote.
    LaunchedEffect(book.id) { catchUp.look() }
    // Left alone long enough, this offer is not shown again while the book stays open.
    var letGo by remember(book.id) { mutableStateOf<CatchUpRules.Offer?>(null) }
    // Kept while the card leaves, after the offer itself has gone.
    var shown by remember(book.id) { mutableStateOf(offer) }
    if (offer != null) shown = offer
    var gone by remember(book.id) { mutableStateOf(Gone.WAVED) }
    val showing = offer != null && offer != letGo && visible && nav.cameFrom == null
    val timeToAct = rememberTimeToAct()
    LaunchedEffect(offer, showing) {
        if (!showing || offer == null) return@LaunchedEffect
        gone = Gone.WAVED
        // Under a screen reader it is found by going through the screen, which takes as long as it takes.
        if (context.screenReaderOn()) return@LaunchedEffect
        delay(timeToAct(OFFER_MS))
        letGo = offer
    }

    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = showing,
        enter = slideInVertically(motion.defaultSpatialSpec()) { -it } +
            scaleIn(motion.defaultSpatialSpec(), initialScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 0f)) +
            fadeIn(motion.fastEffectsSpec()),
        exit = if (gone == Gone.TAKEN) {
            scaleOut(motion.fastSpatialSpec(), targetScale = 0.85f) + fadeOut(motion.fastEffectsSpec())
        } else {
            slideOutVertically(motion.fastSpatialSpec()) { -it * 2 } + fadeOut(motion.fastEffectsSpec())
        },
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 76.dp, start = 16.dp, end = 16.dp),
    ) {
        val told = shown ?: return@AnimatedVisibility
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Row(Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    catchUpOffer(LocalResources.current, told, book),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    // A screen reader says the offer as it appears: nothing else tells that it has.
                    modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = {
                        haptics.confirm()
                        gone = Gone.TAKEN
                        catchUp.answer(book.id)
                        nav.catchUpTo(told, book)
                    },
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text(stringResource(R.string.catch_up_go), maxLines = 1) }
                IconButton(
                    onClick = {
                        haptics.tick()
                        gone = Gone.WAVED
                        catchUp.answer(book.id)
                    },
                ) { Icon(JacketIcons.Close, stringResource(R.string.common_dismiss), Modifier.size(20.dp)) }
            }
        }
    }
}

/**
 * Takes the open book to the place it was offered, as a jump to a bookmark goes: the page left is
 * remembered, and the way back to it is on offer for a while.
 */
private fun ReaderNav.catchUpTo(offer: CatchUpRules.Offer, book: Book) {
    val place = offer.place
    when {
        // A text file's place is its paragraph, which is the same on every device; the pages
        // made of it on the other device needn't be this device's.
        book.format == BookFormat.TXT -> goTo(
            Annotation(
                id = "", bookId = book.id, kind = AnnotationKind.BOOKMARK, section = place.position,
                progress = if (place.sections > 0) place.position.toFloat() / place.sections else place.progress,
            ),
        )
        // A place that came without the exact spot: the step through the book it is on.
        book.format == BookFormat.EPUB && place.locator == null -> jumpTo(place.position)
        else -> goTo(Annotation(id = "", bookId = book.id, kind = AnnotationKind.BOOKMARK, locator = place.locator, section = place.position, progress = place.progress))
    }
}

/**
 * The offer in words. A PDF's and a comic's pages are the same on every device, so the page is
 * named; an EPUB's and a text file's aren't, so it says how far through. With the chapter, where
 * the other device told it.
 */
internal fun catchUpOffer(resources: Resources, offer: CatchUpRules.Offer, book: Book): String {
    val chapter = offer.place.chapter.trim()
    val percent = (offer.place.progress * 100).toInt().coerceIn(0, 100)
    val page = offer.place.position + 1
    return when {
        book.format == BookFormat.PDF || book.format == BookFormat.CBZ ->
            if (chapter.isEmpty()) resources.getString(R.string.catch_up_offer_page, offer.deviceName, page)
            else resources.getString(R.string.catch_up_offer_page_chapter, offer.deviceName, page, chapter)
        chapter.isEmpty() -> resources.getString(R.string.catch_up_offer_percent, offer.deviceName, percent)
        else -> resources.getString(R.string.catch_up_offer_percent_chapter, offer.deviceName, percent, chapter)
    }
}
