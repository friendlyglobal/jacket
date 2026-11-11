package io.github.friendlyglobal.jacket.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.use

/**
 * Links tapped on an EPUB page that lead to a note: the note is shown over the page instead of the
 * page jumping to it.
 *
 * Readium asks before it follows a link inside the book (`shouldFollowInternalLink`). For a link
 * marked exactly `epub:type="noteref"` it hands over the note's text as well; for every other link
 * it says nothing more than where the link leads. So each link to a place within a file (one with
 * a fragment) is held back for a moment while the page is asked which link it was and the file it
 * leads into is read (see [findNote]). A note is shown; anything else is followed as it always was.
 */
internal class NoteLinks(
    private val publication: Publication,
    private val navigator: () -> EpubNavigatorFragment?,
    private val scope: CoroutineScope,
) {
    /** A note was found for a tapped link: show it. The link is the way to the note in its place. */
    var onNote: (Note, Link) -> Unit = { _, _ -> }

    /** The file notes were last read from: a book's notes are mostly in one, tapped one after another. */
    private var read: Pair<String, NoteFile>? = null
    private val reading = Mutex()

    /**
     * Called as Readium is about to follow [link]. True if the link is taken care of here: shown
     * as a note, or followed once it is known not to be one.
     */
    fun take(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
        val url = link.url()
        val fragment = url.fragment?.takeIf { it.isNotEmpty() } ?: return false
        val known = (context as? HyperlinkNavigator.FootnoteContext)?.noteContent
        scope.launch {
            val note = runCatching { noteFor(url, fragment, known) }.getOrNull()
            if (note != null) onNote(note, link) else navigator()?.go(link, animated = false)
        }
        return true
    }

    private suspend fun noteFor(url: Url, fragment: String, known: String?): Note? {
        val tapped = navigator()?.let { tappedLink(it, url, fragment) }
        val marked = known != null || tapped?.marked == true
        val file = url.removeFragment()
        val note = withContext(Dispatchers.Default) {
            reading.withLock {
                val name = file.toString()
                val notes = read?.takeIf { it.first == name }?.second ?: run {
                    val bytes = publication.get(file)?.use { it.read().getOrNull() } ?: return@withLock null
                    // A link that looks nothing like a note's, into a file that marks no notes: no need to read it through.
                    if (!marked && tapped?.text?.let(::looksLikeNoteMark) != true && NOTE_WORDS.none { bytes.contains(it) }) return@withLock null
                    readNotes(bytes).also { read = name to it }
                }
                if (!marked && !notes.marksNotes && tapped?.text?.let(::looksLikeNoteMark) != true) null else findNote(notes, fragment, tapped, marked)
            }
        }
        // Readium found a note where Jacket's own reading of the file didn't: its text will do.
        return note ?: known?.let { noteOf(it, tapped) }
    }

    /** Asks the page which link leading to [fragment] of [url] was tapped; null if it can't say. */
    private suspend fun tappedLink(n: EpubNavigatorFragment, url: Url, fragment: String): TappedLink? = runCatching {
        val path = url.removeFragment().path.orEmpty()
        val answer = n.evaluateJavascript("$TAPPED_LINK_SCRIPT(${JSONObject.quote(path)}, ${JSONObject.quote(fragment)})")?.let(::JSONObject) ?: return null
        val ids = answer.optJSONArray("ids")
        TappedLink(
            types = answer.optString("type"),
            role = answer.optString("role"),
            text = answer.optString("text"),
            ids = (0 until (ids?.length() ?: 0)).map { ids!!.optString(it) }.filter { it.isNotEmpty() },
        )
    }.getOrNull()

    private companion object {
        /** A file that marks any of its notes has one of these words in it. */
        val NOTE_WORDS = listOf("footnote", "endnote", "rearnote").map { it.toByteArray(Charsets.US_ASCII) }

        /**
         * The link on the page that leads to (path, fragment), the one in view if there are several:
         * what the book says of it and of what it sits in (`epub:type`, `role`), its words, and the
         * ids around it that a note may link back to. Null if no link on the page leads there.
         */
        val TAPPED_LINK_SCRIPT = """
            (function(path, fragment) {
              var w = window.innerWidth, h = window.innerHeight, found = null, links = document.querySelectorAll('a[href]');
              function plain(s) { try { return decodeURIComponent(s); } catch (e) { return s; } }
              for (var i = 0; i < links.length; i++) {
                var a = links[i], u;
                try { u = new URL(a.href); } catch (e) { continue; }
                if (plain(u.hash.slice(1)) !== fragment) continue;
                var file = plain(u.pathname);
                if (path && file !== path && !file.endsWith('/' + path)) continue;
                var r = a.getBoundingClientRect();
                var seen = r.width > 0 && r.height > 0 && r.right > 0 && r.left < w && r.bottom > 0 && r.top < h;
                if (!found || seen) found = a;
                if (seen) break;
              }
              if (!found) return null;
              function type(e) { return e ? (e.getAttributeNS('http://www.idpf.org/2007/ops', 'type') || e.getAttribute('epub:type') || '') : ''; }
              function role(e) { return e ? (e.getAttribute('role') || '') : ''; }
              var ids = [], up = found.parentElement;
              for (var e = found, n = 0; e && n < 3 && e !== document.body; e = e.parentElement, n++) if (e.id) ids.push(e.id);
              if (found.getAttribute('name')) ids.push(found.getAttribute('name'));
              var inner = found.querySelectorAll('[id]');
              for (var j = 0; j < inner.length && j < 3; j++) ids.push(inner[j].id);
              // An empty anchor just before the link is often what the note links back to.
              var before = found.previousElementSibling;
              if (before && before.id && !before.textContent.trim()) ids.push(before.id);
              return { type: type(found) + ' ' + type(up), role: role(found) + ' ' + role(up), text: (found.textContent || '').trim().slice(0, 40), ids: ids };
            })
        """.trimIndent()
    }
}

/** Whether [this] holds the bytes of [word] somewhere. */
private fun ByteArray.contains(word: ByteArray): Boolean {
    if (word.isEmpty()) return true
    outer@ for (start in 0..size - word.size) {
        for (i in word.indices) if (this[start + i] != word[i]) continue@outer
        return true
    }
    return false
}
