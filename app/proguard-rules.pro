# The EPUB reader talks to its WebView through a JavaScript interface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
