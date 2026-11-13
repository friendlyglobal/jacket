package io.github.friendlyglobal.jacket.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp

/*
 * Book text that follows the phone's font size.
 *
 * Someone who has made the phone's text larger wants larger words everywhere, the book's above
 * all. Jacket's menus grow with that setting by themselves; the words of a book don't, because
 * Readium sets the page's text size outright from the reader's own size (the web view's text
 * zoom), and that leaves out the phone's. So the size handed to the pages is the reader's own
 * size times the phone's, worked out here. The reader's saved size stays what they chose: a book
 * read on a phone with larger text is not the reader asking for a larger size, and the setting
 * means the same on every phone. It is scaled here once, and nowhere else: Readium's text zoom
 * replaces the web view's own (which would have followed the phone's size by itself), so nothing
 * is scaled twice.
 *
 * PDFs and comics are pictures of pages, and are left alone.
 */
internal object BookTextSize {
    /**
     * The size of text the pages are given: the reader's own [textScale] times the phone's
     * [phoneScale], never more than [MAX_TEXT_SCALE]. The largest reader size is already as large
     * as words can be and still leave a line worth reading on a folded phone; a larger phone font
     * on top of it would leave a word or two to a line.
     */
    fun of(textScale: Float, phoneScale: Float): Float {
        val phone = if (phoneScale.isFinite() && phoneScale > 0f) phoneScale else 1f
        return (textScale * phone).coerceAtMost(maxOf(MAX_TEXT_SCALE, textScale))
    }

    /** Body text's usual size, as the phone's font size is measured at for a book. */
    const val BODY_SP = 16f
}

/**
 * How much larger the phone makes body text than its usual size: 1 at the phone's normal font
 * size, 1.3 at "large". It is measured on text of a book's size rather than read from the bare
 * setting, since Android grows large text less than small (from Android 14), and a book's text is
 * meant to grow as the phone's own body text does.
 */
@Composable
internal fun phoneTextScale(): Float = with(LocalDensity.current) { BookTextSize.BODY_SP.sp.toPx() / (BookTextSize.BODY_SP * density) }
