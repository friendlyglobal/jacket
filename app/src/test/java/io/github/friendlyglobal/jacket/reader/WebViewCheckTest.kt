package io.github.friendlyglobal.jacket.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewCheckTest {
    private val google = "com.google.android.webview"

    /** The user agent a web view of Chromium [version] gives, as Android writes it. */
    private fun agent(version: String, android: String = "8.0.0") =
        "Mozilla/5.0 (Linux; Android $android; Pixel Build/OPR6; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$version Mobile Safari/537.36"

    /** Asked of an app that counts its versions as Chromium does, the web view itself is never asked. */
    private val unasked: () -> String? = { error("The web view was asked what it is, though its version already says") }

    private fun tooOld(packageName: String?, versionName: String?, userAgent: () -> String? = { null }) =
        WebViewCheck.tooOld(WebViewCheck.chromium(packageName, versionName, userAgent))

    @Test
    fun `a version name starts with the Chromium version`() {
        assertEquals(58, WebViewCheck.major("58.0.3029.125"))
        assertEquals(91, WebViewCheck.major("91.0.4472.114"))
        assertEquals(142, WebViewCheck.major(" 142.0.7444.102 "))
        assertEquals(64, WebViewCheck.major("64"))
        assertNull(WebViewCheck.major(null))
        assertNull(WebViewCheck.major(""))
        assertNull(WebViewCheck.major("beta 91"))
        assertNull(WebViewCheck.major("v91.0"))
        // Not a version at all: a build number, a date.
        assertNull(WebViewCheck.major("20261111.1"))
    }

    @Test
    fun `the web view says what it is in its user agent`() {
        assertEquals(58, WebViewCheck.chromiumOf(agent("58.0.3029.125")))
        assertEquals(124, WebViewCheck.chromiumOf(agent("124.0.0.0", android = "14")))
        assertNull(WebViewCheck.chromiumOf(null))
        assertNull(WebViewCheck.chromiumOf(""))
        assertNull(WebViewCheck.chromiumOf("Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0"))
        // "HeadlessChrome/91" and the like are not the word Chrome.
        assertNull(WebViewCheck.chromiumOf("Mozilla/5.0 NotChrome/12.0 Safari/537.36"))
    }

    @Test
    fun `Android 8's own web view is too old, and the first with ResizeObserver is not`() {
        assertTrue(tooOld(google, "58.0.3029.125", unasked))
        assertTrue(tooOld(google, "61.0.3163.98", unasked))
        assertTrue(tooOld(google, "63.0.3239.111", unasked))
        assertFalse(tooOld(google, "64.0.3282.137", unasked))
        assertFalse(tooOld(google, "69.0.3497.100", unasked))
        assertFalse(tooOld(google, "91.0.4472.114", unasked))
        assertEquals(64, WebViewCheck.OLDEST)
    }

    @Test
    fun `Chrome standing in as the web view is read the same way`() {
        assertTrue(tooOld("com.android.chrome", "58.0.3029.83", unasked))
        assertFalse(tooOld("com.android.chrome", "138.0.7204.63", unasked))
        assertTrue(tooOld("com.android.webview", "58.0.3029.125", unasked))
        assertFalse(tooOld("com.chrome.beta", "143.0.7499.10", unasked))
    }

    @Test
    fun `a modern phone is never turned away`() {
        for (version in listOf("64.0.3282.137", "83.0.4103.106", "100.0.4896.127", "119.0.6045.193", "142.0.7444.102", "999.0.0.0")) {
            assertFalse(version, tooOld(google, version, unasked))
            assertFalse(version, tooOld("com.android.chrome", version, unasked))
        }
        // Four digits, should Chromium ever get there.
        assertFalse(tooOld(google, "1000.0.1.2", unasked))
    }

    @Test
    fun `another maker's web view is asked what it is, not judged by its own version`() {
        // Huawei's counts 12, 13, 14…; underneath it is a recent Chromium.
        assertFalse(tooOld("com.huawei.webview", "14.0.1.331") { agent("114.0.5735.196", android = "12") })
        assertFalse(tooOld("com.amazon.webview.chromium", "118-5993-tv.5993.155.51") { agent("118.0.5993.155", android = "11") })
        // And an old one of theirs is still old.
        assertTrue(tooOld("com.huawei.webview", "9.1.0.300") { agent("58.0.3029.125") })
    }

    @Test
    fun `a web view that can't be told is let through`() {
        assertFalse(tooOld(null, null))
        assertFalse(tooOld(google, null))
        assertFalse(tooOld(google, ""))
        assertFalse(tooOld(google, "unknown"))
        assertFalse(tooOld("com.huawei.webview", "14.0.1.331"))
        assertFalse(tooOld("com.example.webview", "3.2") { "SomethingElse/1.0" })
        assertFalse(tooOld("com.example.webview", "3.2") { null })
        // A version of nought is no version.
        assertFalse(tooOld(google, "0.0.0.0"))
        assertFalse(WebViewCheck.tooOld(null))
        assertFalse(WebViewCheck.tooOld(0))
        assertFalse(WebViewCheck.tooOld(-1))
    }

    @Test
    fun `a version that says nothing falls back on the web view's own word`() {
        assertTrue(tooOld(google, "unknown") { agent("58.0.3029.125") })
        assertFalse(tooOld(google, null) { agent("120.0.6099.230") })
    }

    @Test
    fun `the Play Store is opened on the app that provides the web view`() {
        assertEquals("com.google.android.webview", WebViewCheck.listingFor("com.google.android.webview"))
        assertEquals("com.android.chrome", WebViewCheck.listingFor("com.android.chrome"))
        // Not on Play under their own names: Android System WebView is what to install.
        assertEquals("com.google.android.webview", WebViewCheck.listingFor("com.android.webview"))
        assertEquals("com.google.android.webview", WebViewCheck.listingFor("com.huawei.webview"))
        assertEquals("com.google.android.webview", WebViewCheck.listingFor(null))
    }
}
