package io.github.friendlyglobal.jacket.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.outer.CoverFace
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.theme.DEFAULT_SEED
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme
import kotlinx.coroutines.delay

/**
 * What the cover screen will look like: a folded phone showing the outer display, beside the
 * widget in the chosen style. Both follow the switches below as you flip them, and walk through
 * the stages a book goes through (reading, listening, the last stretch, finished, and ambient
 * when that's on), each named above them.
 */
@Composable
internal fun CoverScreenPreview(book: Book?, settings: Settings, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.settings_preview_description)
    val stages = remember(book, settings.ambientDim) { previewStages(book, settings.ambientDim) }
    var index by remember(stages) { mutableIntStateOf(0) }
    LaunchedEffect(stages) {
        // With animations removed the preview holds still, on the book as it is now.
        if (stages.size < 2 || motionRemoved()) return@LaunchedEffect
        while (true) {
            delay(if (stages[index].ambient) 4_200 else 3_000)
            index = (index + 1) % stages.size
        }
    }
    val stage = stages.getOrNull(index)
    // One name and one picture for TalkBack: the miniatures inside are for the eye.
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        if (stage != null) StageName(stage)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OuterPhone(book, stage, settings, height = 216.dp)
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
                        WidgetStyle(book, settings.coverStyle, settings.showProgress, scale = 1f, status = stage?.status)
                    }
                }
                Caption(stringResource(R.string.settings_preview_widget))
            }
        }
    }
}

/** One stop on the preview's walk: a stage of the book, lit or ambient. */
private class PreviewStage(val status: CoverStatus, val ambient: Boolean = false)

/**
 * The stages to show, made from the current book: its own cover, title and place, with the
 * chapter and the later stages filled in as they would look.
 */
private fun previewStages(book: Book?, ambientDim: Boolean): List<PreviewStage> {
    book ?: return emptyList()
    val place = if (book.progress in 0.12f..0.85f) book.progress else 0.42f
    val reading = CoverStatus(
        CoverPhase.READING, place, (place * 100).toInt(),
        chapter = book.title, chapterProgress = 0.64f,
    )
    return buildList {
        add(PreviewStage(reading))
        add(PreviewStage(reading.copy(phase = CoverPhase.LISTENING, playing = true)))
        add(PreviewStage(reading.copy(phase = CoverPhase.ALMOST_DONE, progress = 0.96f, percent = 96, chapterProgress = 0.8f)))
        add(PreviewStage(CoverStatus(CoverPhase.FINISHED, 1f, 100, finishedAt = book.finishedAt.takeIf { it > 0L } ?: System.currentTimeMillis())))
        if (ambientDim) add(PreviewStage(reading, ambient = true))
    }
}

/** The stage on show, as a small tag that rolls over to the next. */
@Composable
private fun StageName(stage: PreviewStage) {
    val motion = MaterialTheme.motionScheme
    val name = when {
        stage.ambient -> stringResource(R.string.settings_ambient_dim)
        stage.status.phase == CoverPhase.NEW -> stringResource(R.string.library_new_badge)
        stage.status.phase == CoverPhase.READING -> stringResource(R.string.widget_reading)
        stage.status.phase == CoverPhase.LISTENING -> stringResource(R.string.cover_listening)
        stage.status.phase == CoverPhase.ALMOST_DONE -> stringResource(R.string.cover_almost_done)
        else -> stringResource(R.string.book_status_finished)
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        AnimatedContent(
            targetState = name,
            transitionSpec = {
                (slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()))
                    .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            label = "stage",
        ) { text ->
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
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
                    // As the book stands right now. A picture of the style: its name below is what is read out.
                    Box(Modifier.clearAndSetSemantics {}) {
                        WidgetStyle(book, style, settings.showProgress, scale = 0.72f, status = book?.let { CoverStatus.of(it, null) })
                    }
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

/**
 * The outer display, drawn by the outer screen's own [CoverFace] in miniature, so the preview
 * can't drift from the real thing.
 */
@Composable
private fun OuterPhone(book: Book?, stage: PreviewStage?, settings: Settings, height: Dp) {
    Box(
        Modifier
            .height(height)
            .aspectRatio(0.47f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF101213))
            .padding(5.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
            if (book == null || stage == null) {
                PreviewCover(book, Modifier.fillMaxSize())
            } else {
                // The outer screen is always dark and tinted by the book, whatever the app wears.
                JacketTheme(seed = book.seedColor ?: DEFAULT_SEED, darkTheme = true, animated = false) {
                    Miniature(screenWidth = 400.dp) {
                        CoverFace(
                            book, stage.status, showProgress = settings.showProgress, ambient = stage.ambient,
                            label = stringResource(R.string.book_status_percent_read, stage.status.percent),
                        )
                    }
                }
            }
        }
        // The camera punch-hole, for a sense of scale.
        Box(Modifier.align(Alignment.TopCenter).padding(top = 9.dp).size(6.dp).clip(CircleShape).background(Color.Black))
    }
}

/** Lays [content] out as on a screen [screenWidth] wide and shows it shrunk to the space there is. */
@Composable
private fun Miniature(screenWidth: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val shrunk = remember(density, maxWidth) { Density(density.density * (maxWidth / screenWidth), density.fontScale) }
        CompositionLocalProvider(LocalDensity provides shrunk, content = content)
    }
}

/**
 * One widget style, drawn the way the Glance widget lays it out, at [scale] and in the widget's
 * own colors (always dark, tinted by the book). [status] is the stage to show.
 */
@Composable
private fun WidgetStyle(book: Book?, style: CoverStyle, showProgress: Boolean, scale: Float, status: CoverStatus?) {
    JacketTheme(seed = book?.seedColor ?: DEFAULT_SEED, darkTheme = true, animated = false) {
        WidgetFace(book, style, showProgress, scale, status ?: CoverStatus(CoverPhase.READING, 0.42f, 42))
    }
}

@Composable
private fun WidgetFace(book: Book?, style: CoverStyle, showProgress: Boolean, scale: Float, status: CoverStatus) {
    val percent = stringResource(R.string.percent, status.percent)
    val progress = status.progress.coerceIn(0.05f, 1f)
    val finished = status.phase == CoverPhase.FINISHED
    val surface = MaterialTheme.colorScheme.surface
    // The last stretch wears the accent, as on the widget.
    val accent by animateColorAsState(
        if (status.phase == CoverPhase.ALMOST_DONE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        label = "accent",
    )
    val track = MaterialTheme.colorScheme.secondaryContainer
    when (style) {
        CoverStyle.JACKET -> Box(Modifier.fillMaxSize()) {
            PreviewCover(book, Modifier.fillMaxSize())
            if (showProgress) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = (8 * scale).dp, vertical = (6 * scale).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StageBadge(status.phase, (13 * scale).dp, Modifier.padding(end = (5 * scale).dp))
                    if (finished) {
                        Text(
                            stringResource(R.string.book_status_finished), color = Color.White, fontSize = (8 * scale).sp,
                            lineHeight = (10 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Bar(progress, accent, Color.White.copy(alpha = 0.3f), scale)
                    }
                }
            }
        }
        CoverStyle.SHELF -> Column(Modifier.fillMaxSize().background(surface).padding((8 * scale).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewCover(book, Modifier.width((26 * scale).dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(3.dp)))
                Spacer(Modifier.width((6 * scale).dp))
                if (showProgress && !finished) {
                    Text(percent, color = accent, fontSize = (20 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width((4 * scale).dp))
                }
                if (showProgress) StageBadge(status.phase, ((if (finished) 24 else 14) * scale).dp)
            }
            Spacer(Modifier.height((5 * scale).dp))
            Text(
                book?.title.orEmpty(), fontSize = (9 * scale).sp, lineHeight = (11 * scale).sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (showProgress && !finished) Bar(progress, accent, track, scale)
        }
        CoverStyle.GLANCE -> Column(
            Modifier.fillMaxSize().background(surface).padding((6 * scale).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PreviewCover(book, Modifier.fillMaxHeight(0.58f).aspectRatio(2f / 3f).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.height((4 * scale).dp))
            if (showProgress) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StageBadge(status.phase, (12 * scale).dp, Modifier.padding(end = (4 * scale).dp))
                    Text(
                        if (finished) stringResource(R.string.book_status_finished) else percent,
                        color = if (finished) MaterialTheme.colorScheme.onSurface else accent,
                        fontSize = ((if (finished) 9 else 13) * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                }
                if (!finished) Box(Modifier.fillMaxWidth(0.6f)) { Bar(progress, accent, track, scale) }
            } else {
                Text(
                    book?.title.orEmpty(), fontSize = (8 * scale).sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The badge the widget gives a stage: a check in a burst when finished, headphones while listening. */
@Composable
private fun StageBadge(phase: CoverPhase, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    when (phase) {
        CoverPhase.FINISHED -> MorphBadge(MaterialShapes.SoftBurst, scheme.tertiary, modifier.size(size)) {
            Icon(JacketIcons.Check, null, Modifier.size(size * 0.56f), tint = scheme.onTertiary)
        }
        CoverPhase.LISTENING -> MorphBadge(MaterialShapes.Cookie9Sided, scheme.primary, modifier.size(size)) {
            Icon(JacketIcons.Headphones, null, Modifier.size(size * 0.56f), tint = scheme.onPrimary)
        }
        else -> {}
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
