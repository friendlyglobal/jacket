package io.github.friendlyglobal.jacket.ui.home

import android.graphics.Bitmap
import android.icu.text.MeasureFormat
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.importer.Bitmaps
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.theme.SeedScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import io.github.friendlyglobal.jacket.ui.asHeading

/** A cover made in Jacket: one of Material's shapes, large, over the book's name, in a color's tones. */
data class CoverDesign(val color: Int, val shape: Int = 0, val dark: Boolean = false)

/** Draws [CoverDesign]s: the same drawing for the preview and the cover that's kept. */
object CoverDesigns {
    val colors: List<Int> = listOf(
        0xFF2E7D8C, 0xFFE0633F, 0xFFF4C76B, 0xFF6750A4, 0xFF386A20,
        0xFFB3261E, 0xFF0B57D0, 0xFF7D5260, 0xFF16424A, 0xFF8B5000,
    ).map { it.toInt() }

    val shapes: List<RoundedPolygon>
        get() = listOf(
            MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Flower,
            MaterialShapes.Arch, MaterialShapes.Gem, MaterialShapes.SoftBurst, MaterialShapes.Heart,
        )

    fun render(design: CoverDesign, title: String, author: String, width: Int = 900): Bitmap {
        val w = width
        val h = width * 3 / 2
        val scheme = if (design.dark) SeedScheme.dark(design.color) else SeedScheme.light(design.color)
        val ink = scheme.onPrimaryContainer.toArgb()
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        canvas.drawColor(scheme.primaryContainer.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // The shape, large, in the upper half; a small tertiary dot overlapping its foot for depth.
        val side = w * 0.74f
        val left = (w - side) / 2f
        val top = h * 0.07f
        val shapes = shapes
        val path = shapes[design.shape.coerceIn(shapes.indices)].toPath()
        path.transform(Matrix().apply { setScale(side, side); postTranslate(left, top) })
        canvas.drawPath(path, paint.apply { color = scheme.primary.toArgb() })
        canvas.drawCircle(left + side * 0.88f, top + side * 0.9f, side * 0.1f, paint.apply { color = scheme.tertiary.toArgb() })

        // The title, as large as fits between the shape and the author, in heavy type.
        val margin = w * 0.08f
        val authorSize = w * 0.052f
        val textTop = top + side + h * 0.045f
        val room = h - margin - authorSize * 2.2f - textTop
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = heavy() }
        val name = title.ifBlank { " " }
        // Large for a word or two, smaller until a long title fits.
        var size = w * 0.17f
        var layout: StaticLayout
        while (true) {
            titlePaint.textSize = size
            layout = StaticLayout.Builder.obtain(name, 0, name.length, titlePaint, (w - margin * 2).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.95f)
                .setMaxLines(4)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            if (layout.height <= room || size <= w * 0.055f) break
            size *= 0.9f
        }
        canvas.withTranslation(margin, textTop) { layout.draw(this) }

        if (author.isNotBlank()) {
            val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                alpha = 210
                textSize = authorSize
                letterSpacing = 0.12f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val line = TextUtils.ellipsize(author.uppercase(), authorPaint, w - margin * 2, TextUtils.TruncateAt.END).toString()
            canvas.drawText(line, margin, h - margin, authorPaint)
        }
        return bitmap
    }

    private fun heavy(): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.SANS_SERIF, 900, false)
        else Typeface.create("sans-serif-black", Typeface.NORMAL)
}

/** Where a book's cover comes from, as chosen in [EditBookSheet]. */
private enum class CoverSource { ORIGINAL, PHOTO, DESIGN }

/** What [EditBookSheet] asks to change about the cover. */
sealed interface CoverChange {
    /** Back to the cover the book came with. */
    data object Original : CoverChange
    data class Photo(val image: Bitmap) : CoverChange
    data class Design(val design: CoverDesign) : CoverChange
}

/**
 * Everything about a book that's the reader's to change: its title and author when the file has
 * them wrong, and its cover, kept as it came, a photo, or one designed here.
 */
@Composable
internal fun EditBookSheet(
    book: Book,
    haptics: Haptics,
    onSave: (title: String, author: String, cover: CoverChange?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(book.title) }
    var author by rememberSaveable { mutableStateOf(book.author) }
    // Nothing chosen while the book wears a cover of the reader's: that one stays unless changed.
    var source by remember { mutableStateOf(if (book.coverCustomized) null else CoverSource.ORIGINAL) }
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var pickFailed by remember { mutableStateOf(false) }
    val swatches = remember(book.seedColor) { listOfNotNull(book.originalSeed ?: book.seedColor) + CoverDesigns.colors }
    var design by remember { mutableStateOf(CoverDesign(color = swatches.first())) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickFailed = false
        picked = runCatching { Bitmaps.decodeCover(context, uri) }.getOrNull()
        if (picked == null) pickFailed = true else source = CoverSource.PHOTO
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val designed by produceState<ImageBitmap?>(null, design, title, author, source) {
        if (source == CoverSource.DESIGN) value = withContext(Dispatchers.Default) { CoverDesigns.render(design, title, author, 450).asImageBitmap() }
    }
    // The cover the book came with, when it's wearing another.
    val original by produceState<ImageBitmap?>(null, book.originalCover) {
        val file = book.originalCover?.let { java.io.File(context.library.coversDir, it) } ?: return@produceState
        value = withContext(Dispatchers.IO) { Bitmaps.decodeFile(file, 600)?.asImageBitmap() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.library_edit_book), style = MaterialTheme.typography.titleLarge, modifier = Modifier.asHeading())
            // The book's own sheet: the time spent with it, read and listened, said quietly under its name.
            val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
            val time = bookTime(book, locale, MeasureFormat.FormatWidth.SHORT, named = true)
            if (time != null) {
                val said = bookTime(book, locale, MeasureFormat.FormatWidth.WIDE, named = true).orEmpty().joinToString(stringResource(R.string.list_separator))
                Text(
                    time.joinToString(stringResource(R.string.separator)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = said },
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                val shown: ImageBitmap? = when (source) {
                    CoverSource.DESIGN -> designed
                    CoverSource.PHOTO -> picked?.asImageBitmap()
                    CoverSource.ORIGINAL -> if (book.coverCustomized) original else null
                    null -> null
                }
                if (shown != null) {
                    Image(shown, stringResource(R.string.cover_of, title), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (!(source == CoverSource.ORIGINAL && book.coverCustomized)) {
                    CoverImage(book, Modifier.fillMaxSize(), maxHeight = 600)
                }
            }
            Spacer(Modifier.height(16.dp))
            ConnectedChoice(
                options = listOf<CoverSource?>(CoverSource.ORIGINAL, CoverSource.PHOTO, CoverSource.DESIGN),
                selected = source,
                label = {
                    stringResource(
                        when (it) {
                            CoverSource.PHOTO -> R.string.cover_photo
                            CoverSource.DESIGN -> R.string.cover_design
                            else -> R.string.cover_original
                        },
                    )
                },
                onSelect = { chosen ->
                    if (chosen == CoverSource.PHOTO && picked == null) pick() else source = chosen
                },
            )
            Spacer(Modifier.height(12.dp))
            AnimatedContent(source, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cover-source") { now ->
                when (now) {
                    CoverSource.ORIGINAL -> Text(
                        stringResource(R.string.cover_original_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CoverSource.PHOTO -> Column {
                        OutlinedButton(onClick = { pick() }, shapes = ButtonDefaults.shapes()) {
                            Icon(LibraryIcons.Image, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.cover_choose_image))
                        }
                    }
                    CoverSource.DESIGN -> Designer(design, swatches, haptics) { design = it }
                    null -> Spacer(Modifier.height(0.dp))
                }
            }
            if (pickFailed) {
                Text(stringResource(R.string.cover_image_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true,
                label = { Text(stringResource(R.string.library_edit_title)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = author, onValueChange = { author = it }, singleLine = true,
                label = { Text(stringResource(R.string.library_edit_author)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        haptics.confirm()
                        val change = when (source) {
                            CoverSource.ORIGINAL -> CoverChange.Original.takeIf { book.coverCustomized }
                            CoverSource.PHOTO -> picked?.let { CoverChange.Photo(it) }
                            CoverSource.DESIGN -> CoverChange.Design(design)
                            null -> null
                        }
                        onSave(title.trim(), author.trim(), change)
                    },
                    enabled = title.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
}

/** A designed cover's choices: a color (the book's own first), a shape, light or dark. */
@Composable
private fun Designer(design: CoverDesign, swatches: List<Int>, haptics: Haptics, onChange: (CoverDesign) -> Unit) {
    Column {
        // One of these, each a full-size target around the dot of colour it shows.
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            swatches.forEachIndexed { index, color ->
                val chosen = color == design.color
                val label = stringResource(R.string.cover_color, index + 1)
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .selectable(selected = chosen, role = Role.RadioButton) { haptics.tick(); onChange(design.copy(color = color)) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(color))
                            .border(if (chosen) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chosen) Icon(JacketIcons.Check, null, Modifier.size(18.dp), tint = Color.White)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val scheme = remember(design.color, design.dark) { if (design.dark) SeedScheme.dark(design.color) else SeedScheme.light(design.color) }
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoverDesigns.shapes.forEachIndexed { index, shape ->
                val chosen = index == design.shape
                val label = stringResource(R.string.cover_shape, index + 1)
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                        .semantics { contentDescription = label }
                        .background(if (chosen) scheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .selectable(selected = chosen, role = Role.RadioButton) { haptics.tick(); onChange(design.copy(shape = index)) }
                        .padding(8.dp),
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(shape.toShape()).background(if (chosen) scheme.primary else MaterialTheme.colorScheme.outline),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(value = design.dark, role = Role.Switch) { haptics.toggle(it); onChange(design.copy(dark = it)) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cover_dark), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = design.dark, onCheckedChange = null)
        }
    }
}
