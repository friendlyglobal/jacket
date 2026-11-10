package io.github.friendlyglobal.jacket.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.data.PageColor
import io.github.friendlyglobal.jacket.data.PageLayout
import io.github.friendlyglobal.jacket.data.ReadingFont
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.ColorSwatch
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.rememberReadingFontFamily
import io.github.friendlyglobal.jacket.ui.swatch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import io.github.friendlyglobal.jacket.data.LineSpacing
import io.github.friendlyglobal.jacket.data.PageMargins
import io.github.friendlyglobal.jacket.data.TextAlignment

private const val MIN_SCALE = 0.7f
private const val MAX_SCALE = 2f

/** Text size, typeface, page color and layout. Used in the reader's sheet and in Settings. */
@Composable
fun ReadingControls(
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
    forEpub: Boolean = true,
) {
    val haptics = rememberHaptics()
    Column(modifier) {
        Label("Text size")
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { haptics.tick(); onSettings { it.copy(textScale = step(it.textScale, -1)) } },
                enabled = settings.textScale > MIN_SCALE,
            ) { Text("A", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            val size = remember {
                SliderState(settings.textScale, ((MAX_SCALE - MIN_SCALE) * 10).roundToInt() - 1, MIN_SCALE..MAX_SCALE)
            }
            // Follow changes made elsewhere (the A buttons, the other screen).
            LaunchedEffect(settings.textScale) { size.value = settings.textScale }
            Slider(
                state = size,
                onValueChange = { value ->
                    size.value = value
                    val snapped = (value * 10).roundToInt() / 10f
                    if (snapped != settings.textScale) {
                        haptics.frequentTick()
                        onSettings { it.copy(textScale = snapped) }
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            FilledTonalIconButton(
                onClick = { haptics.tick(); onSettings { it.copy(textScale = step(it.textScale, 1)) } },
                enabled = settings.textScale < MAX_SCALE,
            ) { Text("A", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        Text(
            "${(settings.textScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Label("Typeface")
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            val fonts = ReadingFont.entries
            fonts.forEachIndexed { index, font ->
                val selected = settings.font == font
                SegmentedListItem(
                    selected = selected,
                    onClick = {
                        if (!selected) {
                            haptics.tick()
                            onSettings { it.copy(font = font) }
                        }
                    },
                    shapes = ListItemDefaults.segmentedShapes(index, fonts.size),
                    leadingContent = {
                        // Each option previews itself in its own typeface.
                        Text(
                            "Aa", fontFamily = rememberReadingFontFamily(font), fontSize = 24.sp,
                            modifier = Modifier.widthIn(min = 40.dp),
                        )
                    },
                    supportingContent = { Text(font.description) },
                    trailingContent = if (selected) {
                        { Icon(JacketIcons.Check, "Selected") }
                    } else null,
                ) {
                    Text(font.label)
                }
            }
        }

        Label("Page")
        val bookSurface = MaterialTheme.colorScheme.surface
        ConnectedChoice(
            options = PageColor.entries,
            selected = settings.pageColor,
            label = { it.label },
            onSelect = { choice -> onSettings { it.copy(pageColor = choice) } },
            leading = { ColorSwatch(it.swatch(bookSurface)) },
        )

        Label("Layout")
        ConnectedChoice(
            options = PageLayout.entries,
            selected = settings.layout,
            label = { it.label },
            onSelect = { choice -> onSettings { it.copy(layout = choice) } },
        )
        Text(
            "Pages turn like a book: swipe, or tap the edges. Unfolded, you get a two-page spread.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )

        if (forEpub) {
            Label("Line spacing")
            ConnectedChoice(
                options = LineSpacing.entries,
                selected = settings.lineSpacing,
                label = {
                    when (it) {
                        LineSpacing.BOOK -> "Book"
                        LineSpacing.TIGHT -> "Tight"
                        LineSpacing.NORMAL -> "Normal"
                        LineSpacing.LOOSE -> "Loose"
                    }
                },
                onSelect = { choice -> onSettings { it.copy(lineSpacing = choice) } },
            )

            Label("Margins")
            ConnectedChoice(
                options = PageMargins.entries,
                selected = settings.margins,
                label = {
                    when (it) {
                        PageMargins.NARROW -> "Narrow"
                        PageMargins.NORMAL -> "Normal"
                        PageMargins.WIDE -> "Wide"
                    }
                },
                onSelect = { choice -> onSettings { it.copy(margins = choice) } },
            )

            Label("Alignment")
            ConnectedChoice(
                options = TextAlignment.entries,
                selected = settings.alignment,
                label = {
                    when (it) {
                        TextAlignment.BOOK -> "Book"
                        TextAlignment.START -> "Left"
                        TextAlignment.JUSTIFY -> "Justified"
                    }
                },
                onSelect = { choice -> onSettings { it.copy(alignment = choice) } },
            )
            Text(
                "Justified text is hyphenated. “Book” keeps the publisher's own spacing and alignment.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }

        Label("Buttons")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .toggleable(settings.volumeKeys, role = Role.Switch) { on -> onSettings { it.copy(volumeKeys = on) } }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(JacketIcons.Volume, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("Turn pages with volume keys", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Volume down for the next page, up for the previous. Not while listening.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.volumeKeys, onCheckedChange = null)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun step(scale: Float, direction: Int) =
    ((scale * 10).roundToInt() + direction).div(10f).coerceIn(MIN_SCALE, MAX_SCALE)

@Composable
private fun Label(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
    )
}
