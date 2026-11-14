package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.map

/*
 * Books never reach the internet. Jacket itself may, for one thing: downloading a natural voice
 * (see voice/VoiceDownload.kt). But a book's pages are drawn by web views, and Android lets a web
 * view in an app that may use the internet fetch anything a page asks for: a picture, a font or a
 * script from some server, which would tell that server the book is being read; and a book's own
 * scripts could open connections of their own. Before Jacket could use the internet, Android
 * blocked all of that by itself. Now it is blocked here, three ways over:
 * - the web views are made with their network loads blocked ([OfflinePages]); and Readium answers
 *   every web address a page asks for itself, from the book's file, so none of those leave the phone;
 * - each page carries a rule the web view enforces, that its scripts may open no connection of
 *   their own (a Content Security Policy, see [PageRules]): no WebSocket, to anywhere;
 * - and before a page's own scripts run, the ways a script could talk to a server without a web
 *   address (WebSocket, and WebRTC, which no such rule covers) are taken away ([NO_SOCKETS]).
 */

/**
 * Makes the web views Readium draws a book's pages in, with their network loads blocked before
 * they load anything. Everything a page shows still comes from the book's own file: Readium
 * answers those requests itself, from the file, and only the rest is refused. Every other view is
 * left to [next] (AppCompat's, which makes the rest of the activity's views as it always did).
 * Set on the activity's layout inflater, which the reader's fragments inflate their views with.
 *
 * Any web view in a layout is made here, not only the two Readium has now: one a later Readium
 * adds is sealed all the same. (Readium keeps its web views to itself, so they are made by name.)
 */
internal class OfflinePages(private val next: (parent: View?, name: String, context: Context, attrs: AttributeSet) -> View?) : LayoutInflater.Factory2 {
    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val type = webViewType(name, context) ?: return next(parent, name, context, attrs)
        val page = type.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(context, attrs)
        seal(page)
        return page
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)

    /** The class [name] stands for in a layout, if it is a web view. */
    private fun webViewType(name: String, context: Context): Class<out WebView>? {
        val full = if ('.' in name) name else if (name == "WebView") WebView::class.java.name else return null
        val type = runCatching { Class.forName(full, false, context.classLoader) }.getOrNull() ?: return null
        return if (WebView::class.java.isAssignableFrom(type)) type.asSubclass(WebView::class.java) else null
    }

    companion object {
        /** Blocks [page] from the network; and, once, the service workers a page could start. */
        fun seal(page: WebView) {
            page.settings.blockNetworkLoads = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(page, NO_SOCKETS, setOf("*"))
            }
            if (!workersSealed && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)
            ) {
                runCatching { ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings.blockNetworkLoads = true }
                workersSealed = true
            }
        }

        private var workersSealed = false
    }
}

/**
 * Run in every page of a book (and every frame in it) before the page's own scripts: takes away
 * the ways a script could reach a server that a web address doesn't go through (WebSocket, and
 * WebRTC, which the page's rule can't cover). A page can make frames of its own, each a fresh
 * window with those ways back in it, so every frame is cleared too: those in the book's own
 * markup as soon as they are there (before the next of its scripts runs), and those a script adds
 * as it adds them, before it can reach into them. Readium's own scripts use none of these.
 */
internal const val NO_SOCKETS = """
(function () {
  var gone = ['WebSocket', 'WebTransport', 'RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel', 'RTCIceTransport'];
  function strip(w) {
    try {
      gone.forEach(function (name) { try { delete w[name]; } catch (e) {} });
      for (var i = 0; i < w.length; i++) strip(w[i]);
    } catch (e) {}
  }
  strip(window);
  new MutationObserver(function () { strip(window); }).observe(document, { childList: true, subtree: true });
  // A frame sent somewhere new is a new window too: cleared as it loads, before the page hears it has.
  document.addEventListener('load', function () { strip(window); }, true);
  function after(proto, names) {
    if (!proto) return;
    names.forEach(function (name) {
      var d = Object.getOwnPropertyDescriptor(proto, name);
      if (!d) return;
      if (typeof d.value === 'function') {
        var f = d.value;
        d.value = function () { try { return f.apply(this, arguments); } finally { strip(window); } };
      } else if (d.set) {
        var set = d.set;
        d.set = function (v) { try { set.call(this, v); } finally { strip(window); } };
      } else {
        return;
      }
      Object.defineProperty(proto, name, d);
    });
  }
  after(Node.prototype, ['appendChild', 'insertBefore', 'replaceChild']);
  after(Element.prototype, ['append', 'prepend', 'before', 'after', 'replaceWith', 'replaceChildren',
    'insertAdjacentElement', 'insertAdjacentHTML', 'innerHTML', 'outerHTML', 'setHTMLUnsafe']);
  after(DocumentFragment.prototype, ['append', 'prepend', 'replaceChildren']);
  after(window.ShadowRoot && ShadowRoot.prototype, ['innerHTML', 'setHTMLUnsafe']);
  after(Document.prototype, ['write', 'writeln']);
  after(Range.prototype, ['insertNode']);
})();
"""

/**
 * A rule each of a book's pages carries, which the web view enforces (a Content Security Policy):
 * a page's scripts may fetch only web addresses, which Readium answers from the book's file, and
 * may open no connection of any other kind (a WebSocket, to anywhere). Added to the page as
 * Readium reads it from the book, before anything of the book's own in it.
 */
internal object PageRules {
    /** "https:" lets Readium's own scripts read the book's parts (it answers those addresses itself); it doesn't cover "wss:". */
    const val POLICY = "connect-src 'self' https: data: blob:"

    private val PAGE = Regex("""\.(x?html?|xht)$""", RegexOption.IGNORE_CASE)
    private val HEAD = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

    /** [resource], at [url] in the book, with the rule added if it is a page. */
    fun addTo(url: Url, resource: Resource): Resource {
        return if (url.path?.let(PAGE::containsMatchIn) == true) resource.map { Try.success(withRule(it)) } else resource
    }

    /**
     * The page [html] with the rule just after its opening head tag. A page in UTF-16, or with no
     * head, is left as it is (the other two guards still hold for it).
     */
    fun withRule(html: ByteArray): ByteArray {
        if (html.size >= 2 && ((html[0] == 0xFE.toByte() && html[1] == 0xFF.toByte()) || (html[0] == 0xFF.toByte() && html[1] == 0xFE.toByte()))) return html
        // Read a byte to a character, so where the tag is in the text is where it is in the bytes.
        val text = String(html, Charsets.ISO_8859_1)
        val head = HEAD.find(text) ?: return html
        val at = head.range.last + 1
        val rule = "<meta http-equiv=\"Content-Security-Policy\" content=\"$POLICY\"/>".toByteArray(Charsets.US_ASCII)
        return html.copyOfRange(0, at) + rule + html.copyOfRange(at, html.size)
    }
}

/**
 * What Readium is given to fetch things over the internet with: nothing. Readium can open a book
 * from a web address, and parts of a book from one; Jacket only ever opens files on the phone, and
 * any request Readium makes is refused here, without a connection being tried.
 */
internal object NoInternet : HttpClient {
    override suspend fun stream(request: HttpRequest): Try<HttpStreamResponse, HttpError> =
        Try.failure(HttpError.Unreachable(DebugError("Jacket doesn't fetch books from the internet: ${request.url}")))
}
