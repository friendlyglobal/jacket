package io.github.friendlyglobal.jacket.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import io.github.friendlyglobal.jacket.ui.spoken
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.asHeading
import io.github.friendlyglobal.jacket.ui.pressScale
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** One book in Jacket's free collection: classics whose copyright has run out everywhere. */
data class FreeBook(
    /** The app language it's shelved under ("pt-BR", "ja"). */
    val language: String,
    val title: String,
    val author: String,
    val year: Int?,
    /** Its file on the collection's GitHub release. */
    val file: String,
    val bytes: Long,
    val source: String,
    val licence: String,
    /** Its cover in the app's assets (free-books/covers/), or null to draw one. */
    val cover: String?,
)

/**
 * The collection as it ships with the app (assets/free-books/catalog.json): its books, and the
 * GitHub release their files are on. The files themselves are downloaded by the browser.
 */
class FreeShelf(val release: String, val books: List<FreeBook>) {
    fun downloadUrl(book: FreeBook): String =
        release.replace("/releases/tag/", "/releases/download/") + "/" + android.net.Uri.encode(book.file)

    companion object {
        fun load(context: Context): FreeShelf? = runCatching {
            parse(context.assets.open("free-books/catalog.json").use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrNull()

        fun parse(text: String): FreeShelf {
            val root = JSONObject(text)
            val list = root.getJSONArray("books")
            val books = (0 until list.length()).mapNotNull { i ->
                runCatching {
                    val b = list.getJSONObject(i)
                    FreeBook(
                        language = b.getString("language"),
                        title = b.getString("title"),
                        author = b.optString("author"),
                        year = b.optInt("year", 0).takeIf { it > 0 },
                        file = b.getString("file"),
                        bytes = b.optLong("bytes"),
                        source = b.optString("source"),
                        licence = b.optString("licence"),
                        cover = if (b.isNull("cover")) null else b.optString("cover").takeIf { it.isNotEmpty() },
                    )
                }.getOrNull()
            }
            return FreeShelf(root.optString("release").ifEmpty { FreeBooks.URL }, books)
        }
    }
}

/** The shelf language for the app's language: "pt-BR" as itself, "en-US" as "en", Chinese by its script. */
internal fun shelfLanguage(locale: Locale, available: Collection<String>): String? {
    val tag = locale.toLanguageTag()
    val chinese = when {
        locale.language != "zh" -> null
        locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "zh-TW"
        else -> "zh-CN"
    }
    return listOfNotNull(chinese, tag, "${locale.language}-${locale.country}", locale.language, if (locale.language == "in") "id" else null)
        .firstOrNull { it in available }
}

/** The book in the library that came from [free], if it's been downloaded and added. */
internal fun inLibrary(free: FreeBook, state: LibraryState): Book? {
    val stem = free.file.substringBeforeLast('.')
    return state.books.firstOrNull { book ->
        // The browser keeps the file's name ("…-dom-casmurro.epub", or "… (1).epub" the second time).
        book.sourceName == free.file || book.sourceName.substringBeforeLast('.').substringBefore(" (") == stem ||
            (book.title.equals(free.title, ignoreCase = true) && book.author.equals(free.author, ignoreCase = true))
    }
}

/**
 * Free books, inside Jacket: the collection's covers by language, the reader's own first. A book's
 * sheet hands its download to the browser (Jacket downloads nothing but voices); once added, it opens here.
 */
@Composable
fun FreeBooksScreen(state: LibraryState, onBack: () -> Unit, onOpen: (Book) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    BackHandler(onBack = onBack)
    val shelf = remember { FreeShelf.load(context) }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val available = remember(shelf) { shelf?.books?.map { it.language }?.distinct().orEmpty() }
    val mine = remember(available, locale) { shelfLanguage(locale, available) }
    // The reader's language first, then the rest by their own names.
    val languages = remember(available, mine) { listOfNotNull(mine) + (available - setOfNotNull(mine)).sortedBy { nativeName(it).lowercase() } }
    var language by rememberSaveable { mutableStateOf(mine) }
    var chosen by remember { mutableStateOf<FreeBook?>(null) }
    val shown = remember(shelf, language) { shelf?.books.orEmpty().filter { language == null || it.language == language } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.free_books_title), modifier = Modifier.asHeading()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = { haptics.tick(); FreeBooks.open(context) }) {
                        Icon(JacketIcons.Language, stringResource(R.string.free_books_see_online))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.free_books_intro),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (languages.size > 1) {
                item(key = "languages", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languages.forEach { code ->
                            FilterChip(
                                selected = language == code,
                                onClick = { haptics.tick(); language = code },
                                label = { Text(nativeName(code)) },
                            )
                        }
                        FilterChip(
                            selected = language == null,
                            onClick = { haptics.tick(); language = null },
                            label = { Text(stringResource(R.string.free_books_all)) },
                        )
                    }
                }
            }
            items(shown, key = { it.file }) { book ->
                FreeBookTile(book, inLibrary(book, state) != null, haptics, Modifier.animateItem()) { chosen = book }
            }
            item(key = "online", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { haptics.tick(); FreeBooks.open(context) }) {
                        Icon(JacketIcons.Language, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.free_books_see_online))
                    }
                }
            }
        }
    }

    chosen?.let { book ->
        val added = inLibrary(book, state)
        FreeBookSheet(
            book = book,
            added = added != null,
            haptics = haptics,
            onDismiss = { chosen = null },
            onDownload = {
                haptics.confirm()
                download(context, shelf?.downloadUrl(book) ?: FreeBooks.URL)
            },
            onOpen = { chosen = null; added?.let(onOpen) },
        )
    }
}

/** A language's name in itself, as a title: "Português (Brasil)", "日本語". */
private fun nativeName(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

private fun download(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.free_books_no_browser, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun FreeBookTile(book: FreeBook, added: Boolean, haptics: Haptics, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // One thing to a screen reader, as a book in the library is: its title, its author, and
    // whether it is in the library already.
    val said = spoken(
        stringResource(R.string.list_separator), book.title, book.author,
        stringResource(R.string.free_books_in_library).takeIf { added },
    )
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = said
            role = Role.Button
            onClick {
                onClick()
                true
            }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .pressScale(interaction)
                .clip(MaterialTheme.shapes.large)
                .clickable(interactionSource = interaction, indication = ripple()) { haptics.tick(); onClick() },
        ) {
            FreeCover(book, Modifier.fillMaxSize())
            if (added) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(28.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(JacketIcons.Check, stringResource(R.string.free_books_in_library), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotEmpty()) {
            Text(
                book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A free book's details, and the way to get it: the browser downloads it, Jacket opens it. */
@Composable
private fun FreeBookSheet(book: FreeBook, added: Boolean, haptics: Haptics, onDismiss: () -> Unit, onDownload: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                FreeCover(book, Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotEmpty()) Text(book.author, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    val details = listOfNotNull(
                        book.year?.toString(),
                        nativeName(book.language),
                        book.bytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                    ).joinToString(stringResource(R.string.separator))
                    Text(details, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOf(book.source, book.licence).filter { it.isNotBlank() }.joinToString(stringResource(R.string.separator)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            if (added) {
                Button(onClick = { haptics.tick(); onOpen() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(JacketIcons.Book, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.library_menu_open))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.free_books_in_library), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Button(onClick = onDownload, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(JacketIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.free_books_download))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.free_books_download_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Covers already loaded or drawn, by file: the shelf scrolls without redrawing them. */
private val covers = LruCache<String, ImageBitmap>(64)

/**
 * A free book's cover: its own, shipped small with the app; or, for a book that has none, one
 * drawn the way Jacket's cover designer draws them, its colour and shape picked from the title.
 */
@Composable
private fun FreeCover(book: FreeBook, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState(covers.get(book.file), book.file) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            val own = book.cover?.let { name ->
                runCatching { context.assets.open("free-books/covers/$name").use(BitmapFactory::decodeStream) }.getOrNull()
            }
            val bitmap = own ?: run {
                val seed = abs(book.title.hashCode())
                val design = CoverDesign(
                    color = CoverDesigns.colors[seed % CoverDesigns.colors.size],
                    shape = (seed / 7) % CoverDesigns.shapes.size,
                    dark = seed % 3 == 0,
                )
                CoverDesigns.render(design, book.title, book.author, width = 300)
            }
            bitmap?.asImageBitmap()?.also { covers.put(book.file, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        image?.let {
            Image(it, stringResource(R.string.cover_of, book.title), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
