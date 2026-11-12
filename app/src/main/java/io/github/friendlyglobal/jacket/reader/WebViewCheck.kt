package io.github.friendlyglobal.jacket.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.rememberHaptics

/*
 * A book's pages are drawn by the phone's web view (Android System WebView, or Chrome on some
 * phones with Android 7 to 9), which is an app that is updated like any other. A phone that has
 * never updated it still has the one it was made with, and Readium's pages need a newer one than
 * Android 8.0's and 8.1's: the reader then says so, and how to put it right, instead of showing a
 * chapter that can't be paged through.
 */

/** A web view that is too old for the pages: the app that provides it, by name and by package. */
internal class OutdatedWebView(val name: String?, val packageName: String?)

internal object WebViewCheck {
    /**
     * The oldest web view (its Chromium version) that Readium 3.4 can lay pages out with.
     *
     * Readium names no minimum (its docs and README don't; it supports Android 7, whose own web
     * view is older than this). The number comes from what its page scripts call, read in the
     * 3.4.0 source and in the built `readium-reflowable.js`:
     *  - Their syntax is ES2015 (the bundle is built with Babel; its dependencies aren't
     *    transpiled): Chrome 51.
     *  - `utils.js` and `decorator.js` make a `ResizeObserver` when a chapter has loaded, and the
     *    first sets the page width and snaps to a page from it: Chrome 64. Without it the script
     *    stops at "ResizeObserver is not defined", which is what Android 8.0's web view (58) does:
     *    the first page shows and the chapter can't be paged through.
     *  - `css-selector-generator` 3.6, which names the first element on a page and a tapped image,
     *    calls `Array.prototype.flatMap`: Chrome 69. Jacket has no need of either (read-aloud
     *    finds its place by the page's words first), so 64 to 68 are let through.
     *  - The style sheets (Readium CSS 1.0.0-beta.3, from 2017) need CSS variables and columns
     *    without a prefix: Chrome 50.
     * Jacket's own page scripts are ES5 and ask for nothing newer.
     *
     * Checked on a real web view at 58 (fails) and 91 (works). In between it rests on the reading
     * above and on a current web view with parts taken away by a test book's own script: without
     * `ResizeObserver` alone, a chapter stays on its first page with the error 58 gives; with it,
     * and without everything that came from 66 to 90 (`flatMap` among it), pages turn and
     * read-aloud starts on the page in view. That takes away what the scripts can call, not how an
     * old web view lays a page out: no web view from 64 to 90 itself has been run.
     */
    const val OLDEST = 64

    /**
     * The apps whose version is the Chromium version they are built from ("91.0.4472.114"):
     * Google's web view and Chrome in their channels, and the open-source build. Another maker's
     * web view (Huawei's, Amazon's) counts its versions its own way, and is asked what it is
     * instead (see [chromiumOf]).
     */
    private val CHROMIUM_NUMBERED = setOf(
        "com.google.android.webview", "com.google.android.webview.beta", "com.google.android.webview.dev",
        "com.google.android.webview.canary", "com.android.webview",
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    )

    /** The Play Store's listing for these can be opened to update them. */
    private fun onPlay(packageName: String) = packageName in CHROMIUM_NUMBERED && packageName != "com.android.webview"

    /** The first number of a version name such as "58.0.3029.125"; null if it doesn't start with one. */
    fun major(versionName: String?): Int? =
        versionName?.trim()?.takeWhile { it in '0'..'9' }?.takeIf { it.length in 1..4 }?.toIntOrNull()

    /** The Chromium version a web view says it is in its user agent ("… Chrome/58.0.3029.125 Mobile Safari/537.36"). */
    fun chromiumOf(userAgent: String?): Int? =
        userAgent?.let { Regex("""\bChrome/(\d{1,4})\.""").find(it) }?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The Chromium version of the web view provided by [packageName] at [versionName]: read from
     * the version of an app known to count that way, otherwise from what the web view itself says
     * ([userAgent], only asked for then). Null when neither can tell.
     */
    fun chromium(packageName: String?, versionName: String?, userAgent: () -> String?): Int? =
        (if (packageName in CHROMIUM_NUMBERED) major(versionName) else null) ?: chromiumOf(userAgent())

    /** Too old for the pages. A web view whose version can't be told is never turned away: it is most likely a new one. */
    fun tooOld(chromium: Int?): Boolean = chromium != null && chromium in 1 until OLDEST

    /** Where the Play Store lists the web view to update: the app providing it if Play has it, else Android System WebView. */
    fun listingFor(packageName: String?): String = packageName?.takeIf(::onPlay) ?: "com.google.android.webview"

    /** This phone's web view if it is too old for the pages; null if it will do, or can't be told. */
    fun outdated(context: Context): OutdatedWebView? = runCatching {
        val provider = WebView.getCurrentWebViewPackage()
        val version = chromium(provider?.packageName, provider?.versionName) {
            runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        }
        if (!tooOld(version)) return null
        val name = provider?.applicationInfo?.let { runCatching { context.packageManager.getApplicationLabel(it).toString() }.getOrNull() }
        OutdatedWebView(name?.takeIf { it.isNotBlank() }, provider?.packageName)
    }.getOrNull()

    /**
     * Opens the web view's page in the Play Store app; without that app, in the browser. Jacket
     * itself goes nowhere: it hands the link over. False when nothing on the phone takes it.
     */
    fun openStore(context: Context, packageName: String?): Boolean {
        val id = listingFor(packageName)
        for (link in listOf("market://details?id=$id", "https://play.google.com/store/apps/details?id=$id")) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // No Play Store: the browser next.
            } catch (_: SecurityException) {
                // An app that takes the link but can't be started from here: the same.
            }
        }
        return false
    }
}

/**
 * This phone's web view if it is too old for the pages (see [WebViewCheck]), looked at again each
 * time Jacket comes back to the front: back from updating it, the book opens.
 */
@Composable
internal fun rememberOutdatedWebView(): OutdatedWebView? {
    val context = LocalContext.current
    var outdated by remember { mutableStateOf(WebViewCheck.outdated(context)) }
    LifecycleResumeEffect(Unit) {
        outdated = WebViewCheck.outdated(context)
        onPauseOrDispose {}
    }
    return outdated
}

/** In place of the pages: what they need, and the way to the Play Store to get it. */
@Composable
internal fun WebViewTooOld(webView: OutdatedWebView, page: PageColors) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // Nothing more is coming: the cover may open on the message.
    val nav = LocalReaderNav.current
    SideEffect { nav?.contentReady = true }
    val name = webView.name ?: stringResource(R.string.reader_webview_name)
    Box(
        // A tap brings the controls back, as on any page: the way out to the library is among them.
        Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { nav?.toggleControls?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(32.dp).widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // In the page's own ink, on the page's own paper.
            Text(
                stringResource(R.string.reader_webview_old, name),
                style = MaterialTheme.typography.bodyLarge, color = page.text, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    haptics.tick()
                    if (!WebViewCheck.openStore(context, webView.packageName)) {
                        Toast.makeText(context, R.string.reader_no_app_for_link, Toast.LENGTH_SHORT).show()
                    }
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(JacketIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.reader_webview_update))
            }
        }
    }
}
