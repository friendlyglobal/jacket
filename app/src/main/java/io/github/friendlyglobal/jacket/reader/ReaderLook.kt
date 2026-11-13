package io.github.friendlyglobal.jacket.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.friendlyglobal.jacket.data.PageColor
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.findActivity

/*
 * The reader's controls dress like its page.
 *
 * The library follows the phone's theme. A book's page needn't: Dark and Black pages are for
 * reading at night, Paper for a cream page in daylight, whatever the phone is set to. Controls in
 * the phone's theme over such a page glare: light bars, a light player and a light settings sheet
 * over a black page in a dark room. So while a book is open, the theme its controls are drawn in
 * (the bars, the chapter pill, the player, the sheets, and the status and navigation bars' icons)
 * is the one that goes with its page: dark over Dark and Black pages, light over Paper, and the
 * phone's own over Book pages, which take their colours from the theme in the first place.
 */

/** The page colour a book is shown in: the reader's choice, except that "Dark pages at night" shows Paper as Dark while [phoneDark]. */
internal fun shownPageColor(settings: Settings, phoneDark: Boolean): PageColor =
    if (settings.nightPages && settings.pageColor == PageColor.PAPER && phoneDark) PageColor.DARK else settings.pageColor

/** Whether the reader's controls are drawn dark over [page]: they go with the page (see above). */
internal fun controlsDark(page: PageColor, phoneDark: Boolean): Boolean = when (page) {
    PageColor.BOOK -> phoneDark
    PageColor.PAPER -> false
    PageColor.DARK, PageColor.BLACK -> true
}

/** Whether the reader's controls are drawn dark, for the reader's [settings] while the phone is [phoneDark] or not. */
internal fun readerControlsDark(settings: Settings, phoneDark: Boolean): Boolean = controlsDark(shownPageColor(settings, phoneDark), phoneDark)

/**
 * The status and navigation bars' icons, light over [dark] controls and dark over light ones, for
 * as long as the reader is on screen; the library's (the phone's theme) again when it goes.
 */
@Composable
internal fun ReaderSystemBars(dark: Boolean) {
    val view = LocalView.current
    val phoneDark = isSystemInDarkTheme()
    DisposableEffect(view, dark, phoneDark) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
        onDispose {
            // As the activity set them up for the library, which follows the phone.
            bars.isAppearanceLightStatusBars = !phoneDark
            bars.isAppearanceLightNavigationBars = !phoneDark
        }
    }
}
