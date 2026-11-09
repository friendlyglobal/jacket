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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.friendlyglobal.jacket.data.PageLayout
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.Epub
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.PageColors
import io.github.friendlyglobal.jacket.ui.ReaderFontCss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

private const val HOST = "appassets.androidplatform.net"
private const val BOOK_PREFIX = "/book/"
private const val ASSET_PREFIX = "/assets/"

/** The package plus each chapter's size, so progress follows the text rather than the file count. */
private class OpenedEpub(val pkg: Epub.Package, sizes: List<Long>) {
    val weights: LongArray = sizes.map { it.coerceAtLeast(1) }.toLongArray()
    val before: LongArray = LongArray(weights.size).also { acc -> for (i in 1 until weights.size) acc[i] = acc[i - 1] + weights[i - 1] }
    val total: Long = weights.sum()

    fun progress(chapter: Int, within: Float): Float =
        ((before[chapter] + weights[chapter] * within.coerceIn(0f, 1f)) / total.toDouble()).toFloat()
}

@Composable
fun EpubReader(
    book: Book,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    speaker: Speaker,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
) {
    val library = LocalContext.current.library
    val dir = remember(book.id) { library.unpackedDirOf(book) }
    val opened by produceState<Result<OpenedEpub>?>(null, book.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val marker = File(dir, ".jacket-unpacked")
                if (!marker.exists()) {
                    dir.deleteRecursively()
                    Epub.unpack(library.fileOf(book), dir)
                    marker.createNewFile()
                }
                val pkg = Epub.readPackage(dir)
                OpenedEpub(pkg, pkg.spine.map { File(dir, it).length() })
            }
        }
    }
    val result = opened
    when {
        result == null -> ReaderLoading()
        result.isFailure -> ReaderError("This EPUB couldn't be opened.")
        result.getOrThrow().pkg.spine.isEmpty() -> ReaderError("This EPUB has no readable chapters.")
        else -> EpubView(book, result.getOrThrow(), dir, settings, page, nav, haptics, speaker, onProgress, onCenterTap)
    }
}

/** Mutable bits the WebView callbacks need, kept outside Compose state on purpose. */
private class EpubSession(var pendingOffset: Float) {
    var webView: WebView? = null
    var loadedChapter = -1
    /** Start of the current page (or scroll position) as a fraction of the chapter. */
    var lastFraction = pendingOffset
    var lastPage = -1
    /** `#anchor` to land on once the next chapter loads (contents, footnotes). */
    var pendingFragment: String? = null
    /** Contents entries inside the loaded chapter, in order; their anchors are page markers. */
    var marks: List<TocItem> = emptyList()
    var appliedStyle = ""
    var style = ""

    // Read aloud. Utterance ids are "token:paragraph:offset:flags"; bumping the token makes
    // callbacks from anything spoken before a pause, skip or stop harmless.
    var ttsToken = 0
    var ttsBlock = 0
    var ttsChar = 0
    /** No position spoken yet in this chapter: (re)start from the top of the visible page. */
    var ttsFresh = true
    /** Keep reading once the chapter has loaded. */
    var continueTts = false
    /** The chapter has loaded and been laid out (set by the page's onReady). */
    var ready = false
    /** A real page load is under way, as opposed to an in-page navigation. */
    var loadPending = false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EpubView(
    book: Book,
    epub: OpenedEpub,
    dir: File,
    settings: Settings,
    page: PageColors,
    nav: ReaderNav,
    haptics: Haptics,
    speaker: Speaker,
    onProgress: ProgressSink,
    onCenterTap: () -> Unit,
) {
    val spine = epub.pkg.spine
    var chapter by rememberSaveable(book.id) { mutableIntStateOf(book.position.coerceIn(0, spine.lastIndex)) }
    val session = remember(book.id) { EpubSession(book.positionOffset) }
    val listen = nav.listen
    val js: (String) -> Unit = { session.webView?.evaluateJavascript(it, null) }

    val controller = remember(book.id, speaker) {
        object : ListenController {
            /** Speaks from [script] once the chapter is ready, or as soon as it becomes ready. */
            fun play(script: String) {
                if (!speaker.acquire()) {
                    // A call or another app holds the audio: show the player, paused.
                    listen.active = true
                    listen.playing = false
                    return
                }
                listen.active = true
                listen.playing = true
                session.ttsToken++
                if (session.ready) js(script.replace("TOKEN", session.ttsToken.toString())) else session.continueTts = true
            }

            override fun start() {
                speaker.setLanguage(epub.pkg.language)
                play("__jkt.start(false,TOKEN)")
            }

            override fun toggle() = if (listen.playing) pause() else resume()

            fun pause() {
                session.ttsToken++
                speaker.stop()
                speaker.release()
                listen.playing = false
            }

            fun resume() = play(
                if (session.ttsFresh) "__jkt.start(false,TOKEN)"
                else "__jkt.speak(${session.ttsBlock},${session.ttsChar},false,TOKEN)",
            )

            override fun skip(delta: Int) {
                speaker.stop()
                play(
                    if (session.ttsFresh) "__jkt.start(false,TOKEN)"
                    else "__jkt.speak(Math.max(0,__jkt.cur+($delta)),0,false,TOKEN)",
                )
            }

            override fun restart() {
                if (listen.playing) resume()
            }

            override fun stop() {
                session.ttsToken++
                session.continueTts = false
                speaker.stop()
                speaker.release()
                listen.active = false
                listen.playing = false
                js("window.__jkt&&__jkt.end()")
            }
        }
    }

    DisposableEffect(speaker, controller) {
        fun current(u: Utterance) = u.token == session.ttsToken
        speaker.listener = object : Speaker.Listener {
            override fun onStart(utterance: Utterance) {
                if (!current(utterance)) return
                session.ttsBlock = utterance.paragraph
                session.ttsChar = utterance.offset
                session.ttsFresh = false
                // The first piece of a paragraph also queues the next paragraph.
                js(
                    if (utterance.first) "__jkt.started(${utterance.paragraph},${utterance.offset})"
                    else "__jkt.word(${utterance.paragraph},${utterance.offset},${utterance.offset})",
                )
            }

            override fun onRange(utterance: Utterance, start: Int, end: Int) {
                if (!current(utterance)) return
                session.ttsBlock = utterance.paragraph
                session.ttsChar = utterance.offset + start
                js("__jkt.word(${utterance.paragraph},${utterance.offset + start},${utterance.offset + end})")
            }

            override fun onDone(utterance: Utterance) {
                if (current(utterance) && utterance.last) js("__jkt.done(${utterance.paragraph})")
            }

            override fun onFailed() {
                if (listen.playing) controller.toggle()
            }

            override fun onInterrupted() {
                if (listen.playing) controller.toggle()
            }
        }
        nav.listenController = controller
        onDispose {
            speaker.listener = null
            nav.listenController = null
        }
    }
    // Bumped when the WebView renderer dies, so a fresh WebView replaces the unusable one.
    var generation by remember { mutableIntStateOf(0) }
    val sink by rememberUpdatedState(onProgress)
    val centerTap by rememberUpdatedState(onCenterTap)
    val paged = settings.layout == PageLayout.PAGES

    val toc = remember(epub) {
        epub.pkg.toc.mapNotNull { entry ->
            spine.indexOf(entry.path).takeIf { it >= 0 }?.let { TocItem(entry.title, entry.level, it, entry.fragment) }
        }
    }
    // A name for every chapter file: its first contents entry, else the one before it.
    val chapterTitles = remember(epub, toc) {
        var last = ""
        spine.indices.map { i ->
            toc.firstOrNull { it.section == i }?.title?.also { last = it } ?: last.ifEmpty { "Beginning" }
        }
    }

    // Read inside AndroidView's update block, so text, font, page color and layout changes re-run it.
    val style by rememberUpdatedState(
        remember(page, settings.textScale, settings.font, paged) { styleScript(page, settings, paged) },
    )
    val background by rememberUpdatedState(page.background)
    val mode by rememberUpdatedState(if (paged) "pages" else "scroll")

    SideEffect {
        nav.sectionCount = spine.size
        nav.section = chapter
        nav.toc = toc
        nav.sectionName = { chapterTitles.getOrElse(it) { "" } }
        nav.jumpTo = { target ->
            if (target in spine.indices && target != chapter) {
                session.pendingOffset = 0f
                chapter = target
            }
        }
        nav.openToc = { item ->
            if (item.section == chapter) {
                session.webView?.evaluateJavascript(
                    item.fragment?.let { "__jk.toFragment(${JSONObject.quote(it)})" } ?: "__jk.toFraction(0)", null,
                )
            } else {
                session.pendingOffset = 0f
                session.pendingFragment = item.fragment
                chapter = item.section
            }
        }
        if (nav.title.isEmpty()) nav.title = chapterTitles[chapter]
    }

    val chapterChanged: (Int, String) -> Unit = { c, title ->
        nav.section = c
        nav.title = title
        nav.tocIndex = toc.indexOfLast { it.section < c || (it.section == c && it.fragment == null) }
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
                    .addPathHandler(BOOK_PREFIX, WebViewAssetLoader.InternalStoragePathHandler(context, dir))
                    .addPathHandler(ASSET_PREFIX, WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                val main = Handler(Looper.getMainLooper())
                WebView(context).apply {
                    alpha = 0f
                    session.webView = this
                    // `this.settings`: the WebView's own settings, not the reader's [Settings] parameter.
                    this.settings.javaScriptEnabled = true
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = false
                    this.settings.textZoom = 100
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onPage(pageIndex: Int, count: Int, mark: Int, left: Int) = main.post {
                            val c = session.loadedChapter.coerceAtLeast(0)
                            val pages = count.coerceAtLeast(1)
                            val p = pageIndex.coerceIn(0, pages - 1)
                            // Tick on page turns you make, not on ones the voice makes.
                            if (!listen.playing && session.lastPage >= 0 && p != session.lastPage) haptics.tick()
                            session.lastPage = p
                            session.lastFraction = p / pages.toFloat()
                            sink(c, session.lastFraction, epub.progress(c, (p + 1f) / pages), spine.size)
                            val marker = session.marks.getOrNull(mark)
                            if (marker != null) {
                                nav.title = marker.title
                                nav.tocIndex = toc.indexOf(marker)
                            } else {
                                chapterChanged(c, chapterTitles[c])
                            }
                            nav.footer = when (left) {
                                0 -> "Last page in chapter"
                                1 -> "1 page left in chapter"
                                else -> "$left pages left in chapter"
                            }
                        }

                        @JavascriptInterface
                        fun onScroll(fraction: Double) {
                            // Book scripts can call this too; ignore anything that isn't a real fraction.
                            if (!fraction.isFinite()) return
                            main.post {
                                val c = session.loadedChapter.coerceAtLeast(0)
                                val f = fraction.toFloat().coerceIn(0f, 1f)
                                session.lastFraction = f
                                sink(c, f, epub.progress(c, f), spine.size)
                                chapterChanged(c, chapterTitles[c])
                                nav.footer = ""
                            }
                        }

                        @JavascriptInterface
                        fun onEdge(direction: Int) = main.post {
                            val c = session.loadedChapter
                            when {
                                direction > 0 && c < spine.lastIndex -> {
                                    haptics.threshold()
                                    session.pendingOffset = 0f
                                    chapter = c + 1
                                }
                                direction < 0 && c > 0 -> {
                                    haptics.threshold()
                                    session.pendingOffset = 1f // land on the last page of the previous chapter
                                    chapter = c - 1
                                }
                                else -> haptics.reject() // first or last page of the book
                            }
                        }

                        @JavascriptInterface
                        fun onTap() = main.post { centerTap() }

                        @JavascriptInterface
                        fun onReady() = main.post {
                            session.ready = true
                            session.webView?.animate()?.alpha(1f)?.setDuration(180)?.start()
                            if (session.continueTts) {
                                session.continueTts = false
                                if (listen.playing) js("__jkt.start(false,${session.ttsToken})")
                            }
                        }

                        /** A paragraph to read aloud; [token] guards against stale requests. */
                        @JavascriptInterface
                        fun ttsSpeak(token: Int, index: Int, from: Int, text: String, queue: Boolean) = main.post {
                            if (listen.playing && token == session.ttsToken) speaker.speak(token, index, from, text, queue)
                        }

                        @JavascriptInterface
                        fun ttsChapterEnd(token: Int) = main.post {
                            if (!listen.playing || token != session.ttsToken) return@post
                            val c = session.loadedChapter
                            if (c < spine.lastIndex) {
                                haptics.threshold()
                                session.pendingOffset = 0f
                                chapter = c + 1 // keeps reading once it loads (see update)
                            } else {
                                haptics.confirm() // the end of the book
                                controller.stop()
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
                                val target = spine.indexOf(url.path.orEmpty().removePrefix(BOOK_PREFIX))
                                val fragment = url.fragment
                                when {
                                    target == session.loadedChapter -> view.evaluateJavascript(
                                        fragment?.let { "__jk.toFragment(${JSONObject.quote(it)})" } ?: "__jk.toFraction(0)", null,
                                    )
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
                            // In-page navigations can report "finished" too; only real loads land.
                            if (!session.loadPending) return
                            session.loadPending = false
                            session.appliedStyle = session.style
                            session.lastPage = -1
                            val land = session.pendingFragment?.let { "__jk.toFragment(${JSONObject.quote(it)})" }
                                ?: "__jk.toFraction(${session.pendingOffset})"
                            // Each saved position is used once, for the load it was meant for.
                            session.pendingFragment = null
                            session.pendingOffset = 0f
                            val marks = JSONArray(session.marks.map { it.fragment.orEmpty() }).toString()
                            view.evaluateJavascript(
                                session.style + ENGINE + READ_ALOUD +
                                    "__jk.setMode('$mode');__jk.marks=$marks;" +
                                    // Measure only once fonts have loaded, or page counts shift.
                                    "document.fonts.ready.then(function(){requestAnimationFrame(function(){$land;Jacket.onReady();});});",
                                null,
                            )
                        }
                    }
                }
            },
            update = { view ->
                session.style = style
                view.setBackgroundColor(background.toArgb())
                if (session.loadedChapter != chapter) {
                    session.loadedChapter = chapter
                    session.marks = toc.filter { it.section == chapter }
                    session.ready = false
                    session.loadPending = true
                    // A spoken position belongs to its chapter.
                    session.ttsBlock = 0
                    session.ttsChar = 0
                    session.ttsFresh = true
                    if (listen.playing) {
                        // Whatever is queued belongs to the old chapter; pick up again once the new one loads.
                        session.ttsToken++
                        speaker.stop()
                        session.continueTts = true
                    }
                    chapterChanged(chapter, chapterTitles[chapter])
                    view.loadUrl("https://$HOST$BOOK_PREFIX" + spine[chapter].split('/').joinToString("/") { Uri.encode(it) })
                } else if (session.appliedStyle != session.style) {
                    session.appliedStyle = session.style
                    // Keep the reading position through text-size, font and layout changes.
                    view.evaluateJavascript(
                        "var __f=window.__jk?__jk.fraction():0;" + session.style +
                            "if(window.__jk){__jk.setMode('$mode');setTimeout(function(){__jk.mp=null;__jk.toFraction(__f);},60);}",
                        null,
                    )
                }
            },
            onRelease = {
                if (session.webView === it) session.webView = null
                it.destroy()
            },
        )
    }
}

/**
 * Paging engine injected into every chapter.
 *
 * Pages: the body is laid out in CSS columns exactly one screen wide (two when unfolded), and the
 * document scrolls sideways one screen per page. A drag follows the finger and snaps to the
 * nearest page; edge taps turn pages; a center tap asks the app to show its controls.
 * Scroll: plain vertical reading with the same tap zones.
 */
private val ENGINE = """
(function(){
if (window.__jk) return;
var se = document.scrollingElement || document.documentElement;
var jk = window.__jk = { page: 0, mode: 'pages', marks: [], mp: null, lf: 0 };
jk.setMode = function(m){ jk.mode = m; jk.mp = null; };
jk.count = function(){ return Math.max(1, Math.round(se.scrollWidth / innerWidth)); };
jk.fraction = function(){
  if (jk.mode === 'pages') return jk.lf;
  var m = se.scrollHeight - innerHeight; return m > 0 ? se.scrollTop / m : 0;
};
jk.pageOf = function(e){ return Math.floor((e.getBoundingClientRect().left + se.scrollLeft + 1) / innerWidth); };
jk.find = function(id){ return document.getElementById(id) || document.getElementsByName(id)[0]; };
jk.report = function(){
  if (jk.mode !== 'pages') {
    var m = se.scrollHeight - innerHeight;
    Jacket.onScroll(m > 0 ? Math.min(1, Math.max(0, se.scrollTop / m)) : 1); return;
  }
  var c = jk.count(), p = jk.page;
  if (!jk.mp) jk.mp = jk.marks.map(function(id){ if (!id) return 0; var e = jk.find(id); return e ? jk.pageOf(e) : -1; });
  var mi = -1, next = c;
  for (var i = 0; i < jk.mp.length; i++) { var q = jk.mp[i]; if (q < 0) continue; if (q <= p) mi = i; else { next = q; break; } }
  Jacket.onPage(p, c, mi, Math.max(0, next - p - 1));
};
jk.go = function(p, smooth){
  var c = jk.count(); jk.page = Math.max(0, Math.min(c - 1, p)); jk.lf = jk.page / c;
  se.scrollTo({ left: jk.page * innerWidth, top: 0, behavior: smooth ? 'smooth' : 'instant' });
  jk.report();
};
jk.next = function(){
  if (jk.mode === 'pages') { if (jk.page < jk.count() - 1) jk.go(jk.page + 1, true); else Jacket.onEdge(1); }
  else if (se.scrollTop + innerHeight >= se.scrollHeight - 2) Jacket.onEdge(1);
  else se.scrollBy({ top: innerHeight * 0.9, behavior: 'smooth' });
};
jk.prev = function(){
  if (jk.mode === 'pages') { if (jk.page > 0) jk.go(jk.page - 1, true); else Jacket.onEdge(-1); }
  else if (se.scrollTop <= 1) Jacket.onEdge(-1);
  else se.scrollBy({ top: -innerHeight * 0.9, behavior: 'smooth' });
};
jk.toFraction = function(f){
  if (jk.mode === 'pages') { var c = jk.count(); jk.go(f >= 1 ? c - 1 : Math.floor(f * c + 0.0001), false); }
  else { var m = se.scrollHeight - innerHeight; se.scrollTo(0, Math.max(0, m * f)); jk.report(); }
};
jk.toFragment = function(id){
  var e = jk.find(id); if (!e) { jk.toFraction(0); return; }
  if (jk.mode === 'pages') jk.go(jk.pageOf(e), false); else { e.scrollIntoView(); jk.report(); }
};
var sx = 0, sy = 0, st = 0, drag = false, moved = false;
addEventListener('touchstart', function(e){
  if (e.touches.length !== 1) return;
  sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now(); drag = false; moved = false;
}, { passive: true });
addEventListener('touchmove', function(e){
  if (e.touches.length !== 1) return;
  var dx = e.touches[0].clientX - sx, dy = e.touches[0].clientY - sy;
  if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
  if (jk.mode !== 'pages') return;
  if (!drag && Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy)) drag = true;
  if (drag) { e.preventDefault(); se.scrollTo({ left: jk.page * innerWidth - dx, top: 0, behavior: 'instant' }); }
}, { passive: false });
addEventListener('touchend', function(e){
  var t = e.changedTouches[0], dx = t.clientX - sx;
  if (drag) {
    drag = false;
    var v = Math.abs(dx) / Math.max(1, Date.now() - st);
    if (dx < -innerWidth * 0.18 || (dx < -30 && v > 0.4)) jk.next();
    else if (dx > innerWidth * 0.18 || (dx > 30 && v > 0.4)) jk.prev();
    else jk.go(jk.page, true);
    return;
  }
  if (moved || Date.now() - st > 450) return;
  if (e.target && e.target.closest && e.target.closest('a[href]')) return;
  if (window.getSelection && String(getSelection()).length) return;
  var x = t.clientX / innerWidth;
  if (x < 0.28) jk.prev(); else if (x > 0.72) jk.next(); else Jacket.onTap();
}, { passive: true });
var sc = 0;
addEventListener('scroll', function(){
  if (jk.mode === 'pages' || sc) return;
  sc = setTimeout(function(){ sc = 0; jk.report(); }, 150);
}, { passive: true });
// Links within this chapter (footnotes, "back to text") land on a page boundary instead of
// letting the browser scroll to an arbitrary offset.
document.addEventListener('click', function(e){
  var a = e.target && e.target.closest && e.target.closest('a[href]'); if (!a) return;
  var u; try { u = new URL(a.href); } catch (err) { return; }
  if (u.hash && u.origin === location.origin && u.pathname === location.pathname) {
    e.preventDefault(); jk.mp = null; jk.toFragment(decodeURIComponent(u.hash.slice(1)));
  }
}, true);
var rz = 0;
addEventListener('resize', function(){
  var f = jk.fraction(); clearTimeout(rz);
  rz = setTimeout(function(){ jk.mp = null; jk.toFraction(f); }, 80);
});
})();
""".trimIndent()

/**
 * Read aloud, injected with the engine. The chapter is split into paragraphs (blocks) that are
 * handed to Android's text-to-speech one at a time, with the next one queued ahead so there are
 * no gaps. As each word is spoken, everything up to it is "read": unread text is dimmed and read
 * text returns to full contrast, using the CSS Highlight API, so the book's DOM is never modified.
 * The page turns itself to follow the voice.
 */
private val READ_ALOUD = """
(function(){
if (window.__jkt) return;
var jk = window.__jk, se = document.scrollingElement || document.documentElement;
var t = window.__jkt = { blocks: null, cur: -1, token: 0, hl: !!(window.CSS && CSS.highlights && window.Highlight) };
var BLOCK = 'p,h1,h2,h3,h4,h5,h6,li,blockquote,figcaption,pre,dd,dt,td,th,div,section,article,aside';
t.build = function(){
  var blocks = [], byEl = new Map();
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, { acceptNode: function(n){
    if (!n.nodeValue.trim()) return NodeFilter.FILTER_SKIP;
    var p = n.parentElement;
    if (!p || p.closest('script,style,noscript,rt,rp')) return NodeFilter.FILTER_SKIP;
    return NodeFilter.FILTER_ACCEPT;
  } });
  var n;
  while ((n = walker.nextNode())) {
    var el = n.parentElement.closest(BLOCK) || document.body;
    var b = byEl.get(el);
    if (!b) { b = { nodes: [], text: '' }; byEl.set(el, b); blocks.push(b); }
    b.nodes.push({ node: n, at: b.text.length });
    b.text += n.nodeValue.replace(/[\n\r\t]/g, ' ');
  }
  t.blocks = blocks;
  if (t.hl) document.documentElement.classList.add('jk-tts');
};
t.locate = function(b, offset){
  var i = 0;
  while (i + 1 < b.nodes.length && b.nodes[i + 1].at <= offset) i++;
  var nd = b.nodes[i];
  return { node: nd.node, off: Math.max(0, Math.min(nd.node.nodeValue.length, offset - nd.at)) };
};
t.firstVisible = function(){
  for (var i = 0; i < t.blocks.length; i++) {
    var r = document.createRange(); r.selectNodeContents(t.blocks[i].nodes[0].node);
    var rc = r.getBoundingClientRect();
    if (jk.mode === 'pages') { if (Math.floor((rc.left + se.scrollLeft + 1) / innerWidth) >= jk.page) return i; }
    else if (rc.bottom > 0) return i;
  }
  return 0;
};
t.start = function(fromTop, token){ t.build(); t.speak(fromTop ? 0 : t.firstVisible(), 0, false, token); };
t.speak = function(i, from, queue, token){
  if (token !== undefined) t.token = token;
  if (!t.blocks) t.build();
  i = Math.max(0, i);
  if (i >= t.blocks.length) { if (!queue) Jacket.ttsChapterEnd(t.token); return; }
  var text = t.blocks[i].text.substring(from);
  if (!text.trim()) { if (!queue) t.speak(i + 1, 0, false); return; }
  Jacket.ttsSpeak(t.token, i, from, text, queue);
};
t.started = function(i, from){ t.cur = i; t.mark(i, from, from); t.speak(i + 1, 0, true); };
t.word = function(i, start, end){ t.cur = i; t.mark(i, start, end); };
t.mark = function(i, start, end){
  var b = t.blocks && t.blocks[i]; if (!b) return;
  var s = t.locate(b, start), e = t.locate(b, Math.max(start, end));
  var word = document.createRange(); word.setStart(s.node, s.off); word.setEnd(e.node, e.off);
  if (t.hl) {
    var read = document.createRange(); read.setStart(document.body, 0); read.setEnd(e.node, e.off);
    CSS.highlights.set('jk-read', new Highlight(read));
    if (end > start) CSS.highlights.set('jk-word', new Highlight(word)); else CSS.highlights.delete('jk-word');
  }
  var target = word;
  if (end <= start) { target = document.createRange(); target.selectNodeContents(s.node); }
  t.follow(target);
};
t.follow = function(r){
  var rc = r.getBoundingClientRect(); if (!rc.width && !rc.height) return;
  if (jk.mode === 'pages') {
    var p = Math.floor((rc.left + se.scrollLeft + 1) / innerWidth);
    if (p !== jk.page) jk.go(p, true);
  } else if (rc.top < innerHeight * 0.12 || rc.bottom > innerHeight * 0.8) {
    se.scrollBy({ top: rc.top - innerHeight * 0.3, behavior: 'smooth' });
  }
};
t.done = function(i){ if (t.blocks && i >= t.blocks.length - 1) Jacket.ttsChapterEnd(t.token); };
t.end = function(){
  document.documentElement.classList.remove('jk-tts');
  if (t.hl) { CSS.highlights.delete('jk-read'); CSS.highlights.delete('jk-word'); }
  t.cur = -1; t.blocks = null;
};
})();
""".trimIndent()

private const val MARGIN = 28

/** Re-themes the chapter: page colors, chosen font and size, and either paged or scrolling layout. */
private fun styleScript(page: PageColors, settings: Settings, paged: Boolean): String {
    fun Color.css(alpha: Float = 1f) =
        "rgba(${(red * 255).roundToInt()},${(green * 255).roundToInt()},${(blue * 255).roundToInt()},$alpha)"
    val base = "html{background:${page.background.css()} !important;-webkit-text-size-adjust:none;}" +
        "body{background:${page.background.css()} !important;color:${page.text.css()} !important;" +
        "font-size:${(settings.textScale * 100).roundToInt()}% !important;line-height:1.6 !important;" +
        "overflow-wrap:break-word;max-width:none !important;}" +
        "body *:not(a){color:inherit !important;background-color:transparent !important;}" +
        "a{color:${page.link.css()} !important;}" +
        "p{hyphens:auto;-webkit-hyphens:auto;orphans:2;widows:2;}" +
        "h1,h2,h3,h4{line-height:1.25 !important;break-after:avoid;}" +
        "img,svg,video{max-width:100% !important;height:auto !important;}" +
        ReaderFontCss.of(settings.font) +
        // Read aloud: text ahead of the voice is soft, text it has read is full ink, and the
        // current word sits on a light marker in the book's accent color.
        "html.jk-tts body{color:${page.text.css(0.4f)} !important;}" +
        "::highlight(jk-read){color:${page.text.css()};}" +
        "::highlight(jk-word){color:${page.text.css()};background-color:${page.link.css(0.24f)};}"
    val layout = if (paged) {
        "html{height:100vh !important;overflow:hidden !important;margin:0 !important;padding:0 !important;}" +
            "body{height:100vh !important;margin:0 !important;box-sizing:border-box !important;" +
            "padding:44px ${MARGIN}px 52px !important;column-count:1;column-gap:${2 * MARGIN}px;column-fill:auto;}" +
            // Unfolded: a two-page spread whose gutter sits on the hinge.
            "@media (min-width:720px) and (min-aspect-ratio:1/1){body{column-count:2;}}" +
            "img,svg,video{max-height:calc(100vh - 110px) !important;object-fit:contain;break-inside:avoid;}"
    } else {
        "html{height:auto !important;overflow:visible !important;}" +
            "body{height:auto !important;column-count:auto !important;margin:0 auto !important;" +
            "max-width:40em !important;padding:28px 22px 160px !important;}"
    }
    return "(function(){var s=document.getElementById('jacket-style');if(!s){s=document.createElement('style');" +
        "s.id='jacket-style';(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=${JSONObject.quote(base + layout)};})();"
}
