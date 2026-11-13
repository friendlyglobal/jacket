package io.github.friendlyglobal.jacket.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * The only code in Jacket that uses the internet. A test (NetworkUseTest) fails the build if any
 * other source file opens a connection, so this file is the whole of what to read to know what
 * Jacket does online.
 *
 * It downloads a natural voice's files, after the listener has said yes, from the GitHub release
 * Jacket publishes them in (see assets/voices/catalog.json), and nothing else:
 * - an HTTPS GET, nothing else: nothing is uploaded, and nothing is sent but the request for the
 *   file. No account, no cookie, no identifier; and a plain "Jacket" in place of the user agent
 *   Android would send, which names the phone's model. GitHub sees the phone's IP address, as it
 *   does for any download.
 * - only to GitHub: github.com, which answers with a redirect to the host its release files are
 *   served from. A redirect anywhere else, or to anything but HTTPS, is refused.
 * - into a partial file, carrying on from where that file ends (an HTTP Range request), so a
 *   download cut short, or stopped by Android closing Jacket, picks up where it was.
 *
 * What arrives is checked by [VoiceStore] (its size and SHA-256 against the catalog) before it is
 * used, so a file that has been tampered with on the way, or damaged, is thrown away.
 */
internal object VoiceDownload {
    /**
     * Where it may connect. GitHub answers a release download on github.com with a redirect to
     * release-assets.githubusercontent.com (seen in November 2026); objects.githubusercontent.com
     * is where it sent them before, and is kept in case it does again.
     */
    val HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    /** How many redirects it follows; GitHub uses one. */
    private const val MOST_REDIRECTS = 4
    private const val CONNECT_MS = 20_000
    private const val READ_MS = 30_000

    /** Whether [url] is one it may connect to: HTTPS, on the usual port, to one of [HOSTS], with no name and password in it. */
    fun allowed(url: URL): Boolean =
        url.protocol == "https" && url.host.lowercase(Locale.ROOT) in HOSTS && (url.port == -1 || url.port == 443) && url.userInfo == null

    /**
     * Downloads [url] into [part] until it holds [bytes] bytes, carrying on from what is in it
     * already. [progress] is told how many bytes [part] holds as they arrive; it may throw to
     * stop the download. Throws [IOException] when the file can't be had (no connection, GitHub
     * out of reach, an answer that isn't the file). Stops when its coroutine is cancelled, the
     * connection with it.
     */
    suspend fun fetch(url: String, part: File, bytes: Long, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        var startedOver = false
        while (true) {
            var have = part.length()
            if (have > bytes) {
                part.delete()
                have = 0L
            }
            if (have == bytes) return@withContext
            val connection = connect(URL(url), have)
            try {
                val append = when (connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range = ContentRange.parse(connection.getHeaderField("Content-Range"))
                        if (range == null || range.start != have || (range.total != null && range.total != bytes)) {
                            throw IOException("The answer isn't the rest of the file: ${connection.getHeaderField("Content-Range")}")
                        }
                        true
                    }
                    // The whole file, from the start: whatever was here goes.
                    HttpURLConnection.HTTP_OK -> {
                        val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                        if (length != null && length != bytes) throw IOException("The file isn't the size it should be: $length")
                        false
                    }
                    // Asked for a part past the end: what is here can't be the file. Once, it starts again.
                    REQUESTED_RANGE_NOT_SATISFIABLE -> {
                        if (startedOver) throw IOException("GitHub won't send the rest of the file")
                        startedOver = true
                        part.delete()
                        continue
                    }
                    else -> throw IOException("GitHub answered ${connection.responseCode}")
                }
                copy(connection, part, append, bytes, progress)
                return@withContext
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Opens [url] for the file from byte [from] on, following GitHub's redirect: only ever to [HOSTS], only ever over HTTPS. */
    private fun connect(url: URL, from: Long): HttpsURLConnection {
        var at = url
        repeat(MOST_REDIRECTS + 1) {
            if (!allowed(at)) throw IOException("Not a place Jacket downloads voices from: ${at.protocol}://${at.host}")
            val connection = at.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.connectTimeout = CONNECT_MS
            connection.readTimeout = READ_MS
            connection.setRequestProperty("User-Agent", "Jacket")
            // As it is stored, so its size is the file's and a range is a range of the file.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
            val code = connection.responseCode
            if (code !in REDIRECTS) return connection
            val to = connection.getHeaderField("Location")
            connection.disconnect()
            at = URL(at, to ?: throw IOException("A redirect to nowhere"))
        }
        throw IOException("Too many redirects")
    }

    /** Writes the body of [connection] to [part] (after what is there, if [append]), until it holds [bytes]. */
    private suspend fun copy(connection: HttpsURLConnection, part: File, append: Boolean, bytes: Long, progress: (Long) -> Unit) = coroutineScope {
        // Cancelled, the connection is closed: a read waiting on a slow network ends at once.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            var have = if (append) part.length() else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (have + n > bytes) throw IOException("The file is longer than it should be")
                        output.write(buffer, 0, n)
                        have += n
                        progress(have)
                    }
                    // On the disk, not only on its way there: a partial file is what a download carries on from.
                    output.fd.sync()
                }
            }
            ensureActive()
            if (have != bytes) throw IOException("The download stopped early, at $have of $bytes bytes")
        } finally {
            closer.cancel()
        }
    }

    private const val REQUESTED_RANGE_NOT_SATISFIABLE = 416
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
}

/** An HTTP Content-Range ("bytes 100-199/968"): where the part starts, and the whole file's size if it is given. */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?) {
    companion object {
        private val FORM = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

        fun parse(header: String?): ContentRange? {
            val match = header?.trim()?.let { FORM.matchEntire(it) } ?: return null
            val (start, end, total) = match.destructured
            return ContentRange(start.toLong(), end.toLong(), total.toLongOrNull()).takeIf { it.end >= it.start }
        }
    }
}
