package io.github.friendlyglobal.jacket

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jacket uses the internet for one thing: downloading a natural voice, in voice/VoiceDownload.kt.
 * This fails if any other source file could open a connection: a socket, a URL connection, an
 * HTTP library, Android's download manager, or Readium's own HTTP client (which Jacket hands
 * nothing but NoInternet). So a change that would make Jacket go online anywhere else can't go
 * in by accident; on purpose, it has to change this test, where everyone can see it.
 */
class NetworkUseTest {
    private val sources = listOf("src/main/java", "src/debug/java").map(::File).filter { it.isDirectory }
    private val allowed = "io/github/friendlyglobal/jacket/voice/VoiceDownload.kt"

    /** What would reach the network, as it would be written in Kotlin or Java. */
    private val networkUse = listOf(
        // java.net is fine for taking a URL apart or decoding one; everything else in it connects.
        Regex("""\bjava\.net\.(?!URLDecoder\b|URLEncoder\b|URI\b|IDN\b)\w+"""),
        Regex("""\bjavax\.net\."""),
        Regex("""\b(Http|Https|Jar)?URLConnection\b"""),
        Regex("""\b(openConnection|openStream)\s*\("""),
        Regex("""\.toURL\s*\("""),
        Regex("""\b(Server|SSL|Datagram|Multicast)?Socket(Channel|Factory)?\b"""),
        Regex("""\b(okhttp3?|io\.ktor|java\.net\.http|org\.apache\.http|android\.net\.http|org\.chromium\.net)\b"""),
        Regex("""\bDownloadManager\b"""),
        Regex("""\bDefaultHttpClient\b"""),
        Regex("""\bloadUrl\s*\(\s*"https?:"""),
    )

    @Test
    fun onlyTheVoiceDownloadUsesTheNetwork() {
        assertTrue("No sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        val found = sources.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.mapNotNull { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (path == allowed) return@mapNotNull null
                val code = withoutComments(file.readText())
                networkUse.firstNotNullOfOrNull { it.find(code)?.value }?.let { "$path: $it" }
            }.toList()
        }
        assertTrue("Only $allowed may use the network, but:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theVoiceDownloadIsWhereTheTestThinksItIs() {
        // Moved or renamed, this test would be guarding nothing.
        val file = sources.map { File(it, allowed) }.firstOrNull { it.isFile }
        assertTrue("$allowed is missing", file != null)
        assertTrue(withoutComments(file!!.readText()).contains("HttpsURLConnection"))
    }

    /** [code] without its comments, which may mention all of the above freely. */
    private fun withoutComments(code: String): String =
        code.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), " ")
}
