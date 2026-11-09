package io.github.friendlyglobal.jacket.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.importer.Epub
import io.github.friendlyglobal.jacket.library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

private const val HOST = "appassets.androidplatform.net"
private const val PREFIX = "/book/"

@Composable
fun EpubReader(book: Book, textScale: Float, nav: ReaderNav, onProgress: ProgressSink) {
    val library = LocalContext.current.library
    val dir = remember(book.id) { library.unpackedDirOf(book) }
    val pkg by produceState<Result<Epub.Package>?>(null, book.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val marker = File(dir, ".jacket-unpacked")
                if (!marker.exists()) {
                    dir.deleteRecursively()
                    Epub.unpack(library.fileOf(book), dir)
                    marker.createNewFile()
                }
                Epub.readPackage(dir)
            }
        }
    }
    val result = pkg
    when {
        result == null -> ReaderLoading()
        result.isFailure -> ReaderError("This EPUB couldn't be opened.")
        result.getOrThrow().spine.isEmpty() -> ReaderError("This EPUB has no readable chapters.")
        else -> EpubView(book, result.getOrThrow(), dir, textScale, nav, onProgress)
    }
}

/** Mutable bits the WebView callbacks need, kept outside Compose state on purpose. */
private class EpubSession(var pendingOffset: Float) {
    var loadedChapter = -1
    var lastFraction = pendingOffset
    /** `#anchor` to land on once the next chapter loads (footnotes, table-of-contents links). */
    var pendingFragment: String? = null
    var appliedStyle = ""
    var style = ""
    var crashes = 0
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubView(
    book: Book,
    pkg: Epub.Package,
    dir: File,
    textScale: Float,
    nav: ReaderNav,
    onProgress: ProgressSink,
) {
    val spine = pkg.spine
    var chapter by rememberSaveable(book.id) { mutableIntStateOf(book.position.coerceIn(0, spine.lastIndex)) }
    val session = remember(book.id) { EpubSession(book.positionOffset) }
    // Bumped when the WebView renderer dies, so a fresh WebView replaces the unusable one.
    var generation by remember { mutableIntStateOf(0) }
    val progressSink by rememberUpdatedState(onProgress)
    val colors = MaterialTheme.colorScheme
    // Both are read inside AndroidView's update block, so text-size and theme changes re-run it.
    val style by rememberUpdatedState(
        remember(colors.surface, colors.onSurface, colors.primary, textScale) {
            styleScript(colors.surface, colors.onSurface, colors.primary, textScale)
        },
    )
    val background by rememberUpdatedState(colors.surface)

    SideEffect {
        nav.canPrevious = chapter > 0
        nav.canNext = chapter < spine.lastIndex
        nav.label = "${chapter + 1} / ${spine.size}"
        nav.onPrevious = { if (chapter > 0) { session.pendingOffset = 0f; chapter-- } }
        nav.onNext = { if (chapter < spine.lastIndex) { session.pendingOffset = 0f; chapter++ } }
    }

    if (generation > 3) {
        ReaderError("This chapter keeps crashing the web view, so Jacket stopped retrying.")
        return
    }

    key(generation) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val loader = WebViewAssetLoader.Builder()
                    .setDomain(HOST)
                    .addPathHandler(PREFIX, WebViewAssetLoader.InternalStoragePathHandler(context, dir))
                    .build()
                val main = Handler(Looper.getMainLooper())
                WebView(context).apply {
                    alpha = 0f
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.textZoom = 100
                    isVerticalScrollBarEnabled = false
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onScroll(fraction: Double) {
                            // Book scripts can call this too; ignore anything that isn't a real fraction.
                            if (!fraction.isFinite()) return
                            main.post {
                                val c = session.loadedChapter.coerceAtLeast(0)
                                val f = fraction.toFloat().coerceIn(0f, 1f)
                                session.lastFraction = f
                                progressSink(c, f, (c + f) / spine.size, spine.size)
                            }
                        }
                    }, "Jacket")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)
                                // Books never reach the network: anything else gets an empty response.
                                ?: WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.host == HOST) {
                                val target = spine.indexOf(url.path.orEmpty().removePrefix(PREFIX))
                                val fragment = url.fragment
                                when {
                                    target == session.loadedChapter ->
                                        fragment?.let { view.evaluateJavascript(scrollToFragment(it), null) }
                                    target >= 0 -> {
                                        session.pendingOffset = 0f
                                        session.pendingFragment = fragment
                                        chapter = target
                                    }
                                    // Other files in the book (images, the nav document): stay on the page.
                                }
                                return true
                            }
                            try {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {
                            }
                            return true
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            // Crashed or reclaimed for memory: rebuild and return to the same spot.
                            session.pendingOffset = session.lastFraction
                            session.loadedChapter = -1
                            session.appliedStyle = ""
                            main.post { generation++ }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            view.alpha = 0f
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            session.appliedStyle = session.style
                            val land = session.pendingFragment?.let(::scrollToFragment)
                                ?: ("(function(){var m=document.documentElement.scrollHeight-innerHeight;" +
                                    "window.scrollTo(0,Math.max(0,m*${session.pendingOffset}));})();")
                            // Each saved position is used once, for the load it was meant for.
                            session.pendingFragment = null
                            session.pendingOffset = 0f
                            view.evaluateJavascript(session.style + LISTENER + land + REPORT) {
                                view.animate().alpha(1f).setDuration(160).start()
                            }
                        }
                    }
                }
            },
            update = { view ->
                session.style = style
                view.setBackgroundColor(background.toArgb())
                if (session.loadedChapter != chapter) {
                    session.loadedChapter = chapter
                    view.loadUrl("https://$HOST$PREFIX" + spine[chapter].split('/').joinToString("/") { Uri.encode(it) })
                } else if (session.appliedStyle != session.style) {
                    session.appliedStyle = session.style
                    view.evaluateJavascript(session.style, null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private fun scrollToFragment(fragment: String): String =
    "(function(){var id=${JSONObject.quote(fragment)};var e=document.getElementById(id)||document.getElementsByName(id)[0];" +
        "if(e)e.scrollIntoView();})();"

private const val REPORT = "window.__jacketReport&&window.__jacketReport();"

/** Reports the scroll position (0..1) back to the app, throttled. */
private const val LISTENER = "(function(){if(window.__jacket)return;window.__jacket=1;var t=0;" +
    "window.__jacketReport=function(){var m=document.documentElement.scrollHeight-innerHeight;" +
    "Jacket.onScroll(m>0?Math.min(1,Math.max(0,scrollY/m)):1);};" +
    "window.addEventListener('scroll',function(){if(t)return;t=setTimeout(function(){t=0;window.__jacketReport();},150);},{passive:true});})();"

/** Re-themes the chapter to match the app: book colors, serif text, comfortable measure. */
private fun styleScript(background: Color, text: Color, link: Color, scale: Float): String {
    fun Color.css() = "rgb(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()})"
    val css = "html,body{background:${background.css()} !important;color:${text.css()} !important;}" +
        "body{font-family:serif;line-height:1.62;font-size:${(scale * 100).toInt()}% !important;max-width:40em;" +
        "margin:0 auto !important;padding:20px 22px 150px !important;overflow-wrap:break-word;-webkit-text-size-adjust:none;}" +
        "body *:not(a){color:inherit !important;background-color:transparent !important;}" +
        "a{color:${link.css()} !important;}img,svg,video{max-width:100% !important;height:auto !important;}p{hyphens:auto;}"
    return "(function(){var s=document.getElementById('jacket-style');if(!s){s=document.createElement('style');" +
        "s.id='jacket-style';(document.head||document.documentElement).appendChild(s);}s.textContent=${JSONObject.quote(css)};})();"
}
