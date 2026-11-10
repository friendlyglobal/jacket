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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.R
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
        Label(stringResource(R.string.reading_text_size))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { haptics.tick(); onSettings { it.copy(textScale = step(it.textScale, -1)) } },
                enabled = settings.textScale > MIN_SCALE,
            ) { Text(stringResource(R.string.reading_text_size_sample), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
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
            ) { Text(stringResource(R.string.reading_text_size_sample), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        Text(
            stringResource(R.string.percent, (settings.textScale * 100).roundToInt()),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Label(stringResource(R.string.reading_typeface))
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
                            stringResource(R.string.reading_typeface_sample), fontFamily = rememberReadingFontFamily(font), fontSize = 24.sp,
                            modifier = Modifier.widthIn(min = 40.dp),
                        )
                    },
                    supportingContent = { Text(stringResource(font.description)) },
                    trailingContent = if (selected) {
                        { Icon(JacketIcons.Check, stringResource(R.string.reading_selected)) }
                    } else null,
                ) {
                    Text(stringResource(font.label))
                }
            }
        }

        Label(stringResource(R.string.reading_page))
        val bookSurface = MaterialTheme.colorScheme.surface
        ConnectedChoice(
            options = PageColor.entries,
            selected = settings.pageColor,
            label = { stringResource(it.label) },
            onSelect = { choice -> onSettings { it.copy(pageColor = choice) } },
            leading = { ColorSwatch(it.swatch(bookSurface)) },
        )

        Label(stringResource(R.string.reading_layout))
        ConnectedChoice(
            options = PageLayout.entries,
            selected = settings.layout,
            label = { stringResource(it.label) },
            onSelect = { choice -> onSettings { it.copy(layout = choice) } },
        )
        Text(
            stringResource(R.string.reading_layout_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )

        if (forEpub) {
            Label(stringResource(R.string.reading_line_spacing))
            ConnectedChoice(
                options = LineSpacing.entries,
                selected = settings.lineSpacing,
                label = {
                    when (it) {
                        LineSpacing.BOOK -> stringResource(R.string.reading_line_spacing_book)
                        LineSpacing.TIGHT -> stringResource(R.string.reading_line_spacing_tight)
                        LineSpacing.NORMAL -> stringResource(R.string.reading_line_spacing_normal)
                        LineSpacing.LOOSE -> stringResource(R.string.reading_line_spacing_loose)
                    }
                },
                onSelect = { choice -> onSettings { it.copy(lineSpacing = choice) } },
            )

            Label(stringResource(R.string.reading_margins))
            ConnectedChoice(
                options = PageMargins.entries,
                selected = settings.margins,
                label = {
                    when (it) {
                        PageMargins.NARROW -> stringResource(R.string.reading_margins_narrow)
                        PageMargins.NORMAL -> stringResource(R.string.reading_margins_normal)
                        PageMargins.WIDE -> stringResource(R.string.reading_margins_wide)
                    }
                },
                onSelect = { choice -> onSettings { it.copy(margins = choice) } },
            )

            Label(stringResource(R.string.reading_alignment))
            ConnectedChoice(
                options = TextAlignment.entries,
                selected = settings.alignment,
                label = {
                    when (it) {
                        TextAlignment.BOOK -> stringResource(R.string.reading_alignment_book)
                        TextAlignment.START -> stringResource(R.string.reading_alignment_left)
                        TextAlignment.JUSTIFY -> stringResource(R.string.reading_alignment_justified)
                    }
                },
                onSelect = { choice -> onSettings { it.copy(alignment = choice) } },
            )
            Text(
                stringResource(R.string.reading_alignment_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }

        Label(stringResource(R.string.reading_buttons))
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
                Text(stringResource(R.string.reading_volume_keys), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.reading_volume_keys_description),
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
