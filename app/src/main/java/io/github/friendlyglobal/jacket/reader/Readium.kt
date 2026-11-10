package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import io.github.friendlyglobal.jacket.data.PageLayout
import io.github.friendlyglobal.jacket.data.ReadingFont
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.PageColors
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.html.toCss
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * EPUB rendering is done by Readium (https://readium.org), the open-source toolkit behind many
 * reading apps: pagination, publisher CSS, spreads, contents and read-aloud.
 */
class ReadiumBooks(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, httpClient, assets, pdfFactory = null),
    )

    /** Opens a book file; the caller closes the [Publication] when done. */
    suspend fun open(file: File): Publication = withContext(Dispatchers.IO) {
        val asset = assets.retrieve(file).getOrElse { throw IOException(it.message) }
        opener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close()
            throw IOException(it.message)
        }
    }
}

/**
 * Readium's navigator is a Fragment created through a FragmentFactory that knows the open book.
 * The activity installs this before restoring state; the reader screen points [epub] at the
 * current book's factory just before showing it.
 */
object ReaderFragments : FragmentFactory() {
    var epub: FragmentFactory? = null

    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        if (className == EpubNavigatorFragment::class.java.name) {
            // After process death the book isn't open yet: Readium's stand-in, which the activity
            // removes straight away (see [removeRestoredReaders]).
            (epub ?: EpubNavigatorFragment.createDummyFactory()).instantiate(classLoader, className)
        } else {
            super.instantiate(classLoader, className)
        }

    /** Drops readers the system restored on its own; the reader screen makes a fresh one. */
    fun removeRestoredReaders(manager: FragmentManager) {
        val stale = manager.fragments.filterIsInstance<EpubNavigatorFragment>()
        if (stale.isEmpty()) return
        manager.beginTransaction().apply { stale.forEach(::remove) }.commitNow()
    }
}

/** Font families Jacket bundles (assets/fonts), registered with Readium by name. */
private val LITERATA = FontFamily("Literata")
private val HYPERLEGIBLE = FontFamily("Atkinson Hyperlegible")

/**
 * The reader's configuration: bundled fonts, no automatic insets (the screen pads for the system
 * bars itself so pages don't reflow when the bars hide), and the read-along decoration.
 */
fun readerConfiguration(): EpubNavigatorFragment.Configuration = EpubNavigatorFragment.Configuration {
    servedAssets = listOf("fonts/.*")
    shouldApplyInsetsPadding = false
    // Two pages side by side whenever two comfortable columns fit: an unfolded phone, a tablet,
    // landscape. Readium's own threshold (960 CSS px) is wider than an unfolded Pixel Fold.
    readiumCssRsProperties = RsProperties(colCount = ColCount.TWO, colWidth = Length.Rem(22.0))
    addFontFamilyDeclaration(LITERATA) {
        addFontFace {
            addSource("fonts/Literata.ttf")
            setFontStyle(FontStyle.NORMAL)
            setFontWeight(200..900)
        }
        addFontFace {
            addSource("fonts/Literata-Italic.ttf")
            setFontStyle(FontStyle.ITALIC)
            setFontWeight(200..900)
        }
    }
    addFontFamilyDeclaration(HYPERLEGIBLE) {
        for ((file, style, weight) in listOf(
            Triple("Regular", FontStyle.NORMAL, FontWeight.NORMAL),
            Triple("Bold", FontStyle.NORMAL, FontWeight.BOLD),
            Triple("Italic", FontStyle.ITALIC, FontWeight.NORMAL),
            Triple("BoldItalic", FontStyle.ITALIC, FontWeight.BOLD),
        )) {
            addFontFace {
                addSource("fonts/AtkinsonHyperlegible-$file.ttf")
                setFontStyle(style)
                setFontWeight(weight)
            }
        }
    }
    // Read-along: a soft highlight on the sentence being spoken, and the words the voice hasn't
    // reached yet under a veil of the page color.
    decorationTemplates = HtmlDecorationTemplates.defaultTemplates(alpha = 0.18, cornerRadius = 4).apply {
        set(
            UnreadVeil::class,
            HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOXES,
                width = HtmlDecorationTemplate.Width.WRAP,
                element = { decoration ->
                    val color = (decoration.style as? UnreadVeil)?.color ?: 0
                    // !important: Readium's page themes clear element backgrounds.
                    "<div style=\"background-color: ${color.toCss()} !important; margin: -1px 0 0 -1px; padding: 0 2px 2px 0;\"></div>"
                },
            ),
        )
    }
}

/** Maps Jacket's reading settings onto Readium preferences. */
fun epubPreferences(settings: Settings, page: PageColors): EpubPreferences = EpubPreferences(
    fontFamily = when (settings.font) {
        ReadingFont.LITERATA -> LITERATA
        ReadingFont.HYPERLEGIBLE -> HYPERLEGIBLE
        ReadingFont.OPEN_DYSLEXIC -> FontFamily.OPEN_DYSLEXIC
        ReadingFont.SERIF -> FontFamily.SERIF
        ReadingFont.SANS -> FontFamily.SANS_SERIF
        ReadingFont.ORIGINAL -> null
    },
    fontSize = settings.textScale.toDouble(),
    scroll = settings.layout == PageLayout.SCROLL,
    theme = if (page.background.luminance() < 0.4f) Theme.DARK else Theme.LIGHT,
    backgroundColor = Color(page.background.toArgb()),
    textColor = Color(page.text.toArgb()),
)

/** Decoration style for read-along: a translucent veil in the page color over unread words. */
class UnreadVeil(@ColorInt val color: Int) : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(color)
    override fun equals(other: Any?) = other is UnreadVeil && other.color == color
    override fun hashCode() = color

    companion object CREATOR : Parcelable.Creator<UnreadVeil> {
        override fun createFromParcel(source: Parcel) = UnreadVeil(source.readInt())
        override fun newArray(size: Int) = arrayOfNulls<UnreadVeil>(size)
    }
}
