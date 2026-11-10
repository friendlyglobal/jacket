package io.github.friendlyglobal.jacket.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons

/**
 * What the cover screen will look like: a folded phone showing the outer display, beside the
 * widget in the chosen style. Both follow the switches below as you flip them.
 */
@Composable
internal fun CoverScreenPreview(book: Book?, settings: Settings, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings_preview_description)
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OuterPhone(book, settings, height = 196.dp)
            Caption(stringResource(R.string.settings_preview_outer))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A Flex Window is roughly square with rounded corners.
            Box(
                Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF101213))
                    .padding(6.dp),
            ) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))) {
                    WidgetStyle(book, settings.coverStyle, settings.showProgress, scale = 1f)
                }
            }
            Caption(stringResource(R.string.settings_preview_widget))
        }
    }
}

/** A thumbnail of each widget style to pick from, selected one outlined with a cookie check. */
@Composable
internal fun WidgetStylePicker(book: Book?, settings: Settings, haptics: Haptics, onSelect: (CoverStyle) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverStyle.entries.forEach { style ->
            val selected = style == settings.coverStyle
            val ring by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "ring",
            )
            val lift by animateFloatAsState(if (selected) 1f else 0.94f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lift")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .selectable(selected, role = Role.RadioButton) {
                        haptics.tick()
                        onSelect(style)
                    }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer { scaleX = lift; scaleY = lift }
                        .border(if (selected) 3.dp else 1.dp, ring, RoundedCornerShape(18.dp))
                        .padding(5.dp)
                        .clip(RoundedCornerShape(14.dp)),
                ) {
                    WidgetStyle(book, style, settings.showProgress, scale = 0.72f)
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                                .clip(MaterialShapes.Cookie9Sided.toShape())
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(JacketIcons.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(style.label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The outer display: the cover edge to edge, the progress line if on, dimming if ambient dim is on. */
@Composable
private fun OuterPhone(book: Book?, settings: Settings, height: Dp) {
    // Ambient dim, sped up: lit for a moment, then dims, then wakes again.
    val dim = if (settings.ambientDim) {
        val cycle = rememberInfiniteTransition(label = "dim")
        cycle.animateFloat(
            0f, 0f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = 5200
                    0f at 0
                    0f at 2400
                    0.62f at 3200
                    0.62f at 4600
                    0f at 5200
                },
                RepeatMode.Restart,
            ),
            label = "dim-level",
        ).value
    } else {
        0f
    }
    val progressAlpha by animateFloatAsState(if (settings.showProgress) 1f else 0f, label = "progress")
    Box(
        Modifier
            .height(height)
            .aspectRatio(0.47f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF101213))
            .padding(5.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
            PreviewCover(book, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp)
                    .fillMaxWidth().height(4.dp)
                    .graphicsLayer { alpha = progressAlpha }
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.35f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(book?.progress?.coerceIn(0.05f, 1f) ?: 0.42f).background(Color.White))
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Color.Black))
        }
        // The camera punch-hole, for a sense of scale.
        Box(Modifier.align(Alignment.TopCenter).padding(top = 9.dp).size(6.dp).clip(CircleShape).background(Color.Black))
    }
}

/** One widget style, drawn the way the Glance widget lays it out, at [scale]. */
@Composable
private fun WidgetStyle(book: Book?, style: CoverStyle, showProgress: Boolean, scale: Float) {
    val percent = stringResource(R.string.percent, book?.percent ?: 42)
    val progress = book?.progress?.coerceIn(0.05f, 1f) ?: 0.42f
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val primary = MaterialTheme.colorScheme.primary
    when (style) {
        CoverStyle.JACKET -> Box(Modifier.fillMaxSize()) {
            PreviewCover(book, Modifier.fillMaxSize())
            if (showProgress) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = (8 * scale).dp, vertical = (6 * scale).dp),
                ) {
                    Bar(progress, Color.White, Color.White.copy(alpha = 0.3f), scale)
                }
            }
        }
        CoverStyle.SHELF -> Column(Modifier.fillMaxSize().background(surface).padding((8 * scale).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewCover(book, Modifier.width((26 * scale).dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.width((6 * scale).dp))
                Text(
                    if (showProgress) percent else "",
                    color = primary, fontSize = (20 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
            Spacer(Modifier.height((5 * scale).dp))
            Text(
                book?.title.orEmpty(), fontSize = (9 * scale).sp, lineHeight = (11 * scale).sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (showProgress) Bar(progress, primary, MaterialTheme.colorScheme.secondaryContainer, scale)
        }
        CoverStyle.GLANCE -> Column(
            Modifier.fillMaxSize().background(surface).padding((6 * scale).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PreviewCover(book, Modifier.fillMaxHeight(0.58f).aspectRatio(2f / 3f).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.height((4 * scale).dp))
            if (showProgress) {
                Text(percent, color = primary, fontSize = (13 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Box(Modifier.fillMaxWidth(0.6f)) { Bar(progress, primary, MaterialTheme.colorScheme.secondaryContainer, scale) }
            } else {
                Text(
                    book?.title.orEmpty(), fontSize = (8 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Bar(progress: Float, color: Color, track: Color, scale: Float) {
    Box(Modifier.fillMaxWidth().height((3 * scale).dp.coerceAtLeast(2.dp)).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(color))
    }
}

/** The current book's cover, or a calm placeholder before there's a book. */
@Composable
private fun PreviewCover(book: Book?, modifier: Modifier) {
    if (book != null) {
        CoverImage(book, modifier, contentScale = ContentScale.Crop, maxHeight = 600)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(JacketIcons.Book, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
