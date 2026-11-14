package io.github.friendlyglobal.jacket.reader

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.isEmpty
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import io.github.friendlyglobal.jacket.data.LineSpacing
import io.github.friendlyglobal.jacket.data.PageLayout
import io.github.friendlyglobal.jacket.data.TextAlignment
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
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.TransformingContainer
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
    // Books are opened from files on the phone, never from the internet (see NoInternet).
    private val httpClient = NoInternet
    private val assets = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(context, httpClient, assets, pdfFactory = null),
    )

    /** Opens a book file; the caller closes the [Publication] when done. */
    suspend fun open(file: File): Publication = withContext(Dispatchers.IO) {
        val asset = assets.retrieve(file).getOrElse { throw IOException(it.message) }
        // Each page carries the rule that keeps its scripts off the internet (see PageRules).
        opener.open(asset, allowUserInteraction = false, onCreatePublication = { container = TransformingContainer(container, PageRules::addTo) }).getOrElse {
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
fun readerConfiguration(selection: ActionMode.Callback): EpubNavigatorFragment.Configuration = EpubNavigatorFragment.Configuration {
    selectionActionModeCallback = selection
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
                    // !important: Readium's page themes clear element backgrounds. The data
                    // attribute lets the reader ask the page where the voice has got to.
                    "<div data-jacket=\"unread\" style=\"background-color: ${color.toCss()} !important; margin: -1px 0 0 -1px; padding: 0 2px 2px 0;\"></div>"
                },
                // Readium adds a template's stylesheet to every chapter file it shows: the one
                // way there is to add a rule of Jacket's own to the pages.
                stylesheet = TWO_PAGES_CSS,
            ),
        )
        // Where the voice stopped: the sentence's highlight, staying a moment after listening ends.
        set(
            HereMark::class,
            HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOXES,
                width = HtmlDecorationTemplate.Width.WRAP,
                element = { decoration ->
                    val color = (decoration.style as? HereMark)?.color ?: 0
                    "<div style=\"background-color: ${color.toCss()} !important; border-radius: 4px; " +
                        "margin: -1px 0 0 -1px; padding: 0 2px 2px 0; box-sizing: border-box;\"></div>"
                },
            ),
        )
        // Your highlights: a marker-pen wash in their colour, underlined when they carry a note.
        set(
            Marker::class,
            HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOXES,
                width = HtmlDecorationTemplate.Width.WRAP,
                element = { decoration ->
                    val style = decoration.style as? Marker
                    val color = style?.color ?: 0
                    val underline = if (style?.noted == true) "border-bottom: 2px dotted ${color.toCss(alpha = 1.0)};" else ""
                    "<div style=\"background-color: ${color.toCss(alpha = 0.34)} !important; border-radius: 3px; " +
                        "margin: -1px 0 0 -1px; padding: 0 2px 2px 0; box-sizing: border-box; $underline\"></div>"
                },
            ),
        )
    }
}

/**
 * Two pages when two are asked for outright (book posture, see [BookPosture]), whatever the size
 * of the text. Readium's stylesheet means the same by its own rule for "two columns", which lets
 * the column be as wide as it likes; but the column width Jacket sets above is written on the page
 * itself, as Readium writes everything it is given, and outranks that rule. Left to it, a page of
 * large text asked to be two would stay one, right across the fold. So the width is let go here,
 * on the property itself.
 */
private const val TWO_PAGES_CSS =
    ":root[style*=\"--USER__colCount: 2\"], :root[style*=\"--USER__colCount:2\"] " +
        "{ -webkit-column-width: auto !important; column-width: auto !important; }"

/** Decoration style for highlights and search results: a translucent [color] wash. */
class Marker(@ColorInt val color: Int, val noted: Boolean = false) : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(color)
        dest.writeInt(if (noted) 1 else 0)
    }
    override fun equals(other: Any?) = other is Marker && other.color == color && other.noted == noted
    override fun hashCode() = color * 31 + if (noted) 1 else 0

    companion object CREATOR : Parcelable.Creator<Marker> {
        override fun createFromParcel(source: Parcel) = Marker(source.readInt(), source.readInt() == 1)
        override fun newArray(size: Int) = arrayOfNulls<Marker>(size)
    }
}

/**
 * Words selected on a book's page, as the phone's web view tells of them.
 *
 * The web view selects the words and draws their handles; Readium hands its menu for them to
 * this. Jacket's own menu goes by the words instead, the same one as over a PDF's (see
 * [SelectionMenu] and EpubSelection.kt), so the web view's is kept empty: with nothing in it, the
 * phone has no floating menu to show. (Saying no to it altogether would have the web view drop
 * the selection.) What is left is knowing when there is a selection, and ending it.
 */
class WebSelection : ActionMode.Callback {
    /** Words are selected on the page: from when the web view starts its menu for them until it ends it. */
    var active by mutableStateOf(false)
        private set

    /** A finger is on the page (dragging a handle, choosing words, scrolling): Jacket's menu waits for it to lift. */
    var touching by mutableStateOf(false)

    private var mode: ActionMode? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.clear()
        this.mode = mode
        active = true
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (menu.isEmpty()) return false
        menu.clear()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

    override fun onDestroyActionMode(mode: ActionMode) {
        if (this.mode !== mode) return
        this.mode = null
        active = false
    }

    /** Ends the selection's menu; the page clears the words themselves (see [EpubNavigatorFragment.clearSelection]). */
    fun finish() {
        mode?.finish()
    }
}

/**
 * Maps Jacket's reading settings onto Readium preferences. [spread] while the phone is held
 * half-open like a book: the pages are laid out about its fold (see [BookPosture]). [textSize] is
 * the size of the words, the reader's own size with the phone's font size on it (see [BookTextSize]).
 */
internal fun epubPreferences(
    settings: Settings,
    page: PageColors,
    spread: BookSpread? = null,
    textSize: Float = settings.textScale,
): EpubPreferences = EpubPreferences(
    fontFamily = when (settings.font) {
        ReadingFont.LITERATA -> LITERATA
        ReadingFont.HYPERLEGIBLE -> HYPERLEGIBLE
        ReadingFont.OPEN_DYSLEXIC -> FontFamily.OPEN_DYSLEXIC
        ReadingFont.SERIF -> FontFamily.SERIF
        ReadingFont.SANS -> FontFamily.SANS_SERIF
        ReadingFont.ORIGINAL -> null
    },
    fontSize = textSize.toDouble(),
    scroll = settings.layout == PageLayout.SCROLL,
    theme = if (page.background.luminance() < 0.4f) Theme.DARK else Theme.LIGHT,
    backgroundColor = Color(page.background.toArgb()),
    textColor = Color(page.text.toArgb()),
    // Two pages outright where the text is too large for Readium to show two by itself: one page
    // would run across the fold. (Otherwise left to Readium, as ever: nothing is laid out anew.)
    columnCount = if (spread?.twoPages == true && settings.layout == PageLayout.PAGES) ColumnCount.TWO else null,
    pageMargins = spread?.margins ?: settings.margins.readium,
    // Line spacing and alignment override the book's own styles, which Readium only allows with
    // publisher styles off; leaving both on "Book" keeps the publisher's typography.
    publisherStyles = settings.lineSpacing == LineSpacing.BOOK && settings.alignment == TextAlignment.BOOK,
    lineHeight = settings.lineSpacing.lineHeight,
    textAlign = when (settings.alignment) {
        TextAlignment.BOOK -> null
        TextAlignment.START -> ReadiumTextAlign.START
        TextAlignment.JUSTIFY -> ReadiumTextAlign.JUSTIFY
    },
    hyphens = when (settings.alignment) {
        TextAlignment.BOOK -> null
        TextAlignment.START -> false
        TextAlignment.JUSTIFY -> true
    },
)

/**
 * Decoration style for the sentence the voice stopped on: a wash of [color], whose own alpha is how
 * strong it is (the reader fades it out by handing over paler and paler ones).
 */
class HereMark(@ColorInt val color: Int) : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(color)
    override fun equals(other: Any?) = other is HereMark && other.color == color
    override fun hashCode() = color

    companion object CREATOR : Parcelable.Creator<HereMark> {
        override fun createFromParcel(source: Parcel) = HereMark(source.readInt())
        override fun newArray(size: Int) = arrayOfNulls<HereMark>(size)
    }
}

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
