package io.github.friendlyglobal.jacket.outer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.CoverPhase
import io.github.friendlyglobal.jacket.data.CoverStatus
import io.github.friendlyglobal.jacket.data.ReadingMoment
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.rememberCover
import io.github.friendlyglobal.jacket.ui.rememberTurn
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Root of the outer-display window, and of the cover a folded phone wears: always dark
 * (OLED-friendly), tinted by the book. [onTap] hears taps on the cover (each one wakes it first);
 * [keepLit] holds the ambient state off while something is showing over it.
 */
@Composable
fun OuterCoverHost(onTap: (() -> Unit)? = null, keepLit: Boolean = false, onDimmed: ((Boolean) -> Unit)? = null) {
    val library = LocalContext.current.library
    val state by library.state.collectAsStateWithLifecycle()
    val moment by library.moment.collectAsStateWithLifecycle()
    val book = state.current
    // "Highlights on the dimmed cover": what you marked in this book, short enough to read at a glance.
    val quotes = remember(state.annotations, book?.id, state.settings.ambientQuote) {
        if (!state.settings.ambientQuote || book == null) emptyList() else {
            state.annotations
                .filter { it.bookId == book.id && it.kind == AnnotationKind.HIGHLIGHT }
                .map { it.text.trim() }
                .filter { it.length in 3..220 }
        }
    }
    JacketTheme(seed = book?.seedColor, darkTheme = true) {
        OuterCover(book, state.settings, moment, onTap, keepLit, quotes, onDimmed)
    }
}

/**
 * The cover, edge to edge, like album art on a lock screen, with the stage the book is at under
 * it (see [CoverFace]). Left alone it steps back into an ambient state; a tap brings it forward.
 */
@Composable
fun OuterCover(
    book: Book?,
    settings: Settings,
    moment: ReadingMoment? = null,
    onTap: (() -> Unit)? = null,
    keepLit: Boolean = false,
    quotes: List<String> = emptyList(),
    /** Told as the cover goes ambient and wakes, for whatever is drawn over it. */
    onDimmed: ((Boolean) -> Unit)? = null,
) {
    var wakeCount by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(dimmed) { onDimmed?.invoke(dimmed) }
    val status = book?.let { CoverStatus.of(it, moment) }
    val tapped by rememberUpdatedState(onTap)
    // A tap wakes it. So does a new stage (starting to listen, finishing), which is worth showing;
    // pages turning are not.
    LaunchedEffect(wakeCount, settings.ambientDim, book?.id, status?.phase, status?.playing, keepLit) {
        dimmed = false
        if (settings.ambientDim && !keepLit) {
            delay(AMBIENT_AFTER_MS)
            dimmed = true
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures {
                wakeCount++
                tapped?.invoke()
            }
        },
    ) {
        if (book == null || status == null) {
            val brightness by animateFloatAsState(if (dimmed) AMBIENT_BRIGHTNESS else 1f, tween(AMBIENT_FADE_MS), label = "ambient")
            Text(
                stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = brightness },
            )
        } else {
            CoverFace(book, status, showProgress = settings.showProgress, ambient = dimmed, quotes = quotes)
        }
    }
}

/**
 * One book on the cover screen, in one of two faces.
 *
 * Lit: the cover fills the screen, and a strip along the bottom tells the stage the book is at,
 * each with its own shape and motion ([CoverPhase]).
 *
 * Ambient: the cover steps back into a small, dim card on black (far fewer lit pixels on an OLED
 * than the whole screen at low brightness), with a ring and a number under it. Nothing moves
 * while ambient, apart from a slow drift that keeps the pixels from wearing unevenly.
 *
 * With [showProgress] off, both are the cover and nothing else.
 */
@Composable
internal fun CoverFace(
    book: Book,
    status: CoverStatus,
    showProgress: Boolean,
    ambient: Boolean,
    modifier: Modifier = Modifier,
    /** The book's status in words, e.g. "42% read". */
    label: String = book.statusLabel(LocalResources.current),
    /** Highlights from the book, one of which the ambient face shows under the ring. */
    quotes: List<String> = emptyList(),
) {
    val motion = MaterialTheme.motionScheme
    // The lights go down slowly and evenly; waking, the cover comes forward again on a spring.
    val dim = animateFloatAsState(
        targetValue = if (ambient) 1f else 0f,
        animationSpec = if (ambient) tween(AMBIENT_FADE_MS, easing = EaseInOutCubic) else motion.slowSpatialSpec(),
        label = "ambient",
    )
    val drift = rememberAmbientDrift(ambient)
    // Only the face that can be seen is composed, so nothing animates behind the other.
    val lit by remember { derivedStateOf { dim.value < 0.5f } }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        CoverArt(book, dim, drift)
        AnimatedVisibility(visible = showProgress, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize()) {
                if (lit) {
                    LitStatus(
                        status, label,
                        Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = (1f - dim.value * 2f).coerceIn(0f, 1f) },
                    )
                } else {
                    AmbientStatus(
                        status, quotes,
                        Modifier.graphicsLayer {
                            alpha = (dim.value * 2f - 1f).coerceIn(0f, 1f)
                            translationX = drift.value.x
                            translationY = drift.value.y
                        },
                    )
                }
            }
        }
    }
}

/** How long the cover stays lit after a touch or a change of stage. */
private const val AMBIENT_AFTER_MS = 30_000L
private const val AMBIENT_FADE_MS = 1_400

/** The ambient cover's brightness, against full. */
private const val AMBIENT_BRIGHTNESS = 0.42f
private val AmbientCorner = 18.dp

/**
 * Where the cover rests while ambient: whole (2:3) rather than cropped, a little above the
 * middle, with room under it for the ring.
 */
private fun ambientCard(width: Int, height: Int): IntRect {
    val cardWidth = min(width * 0.56f, height * 0.44f * 2f / 3f)
    val cardHeight = cardWidth * 1.5f
    val left = (width - cardWidth) / 2f
    val top = (height - cardHeight) * 0.38f
    return IntRect(left.roundToInt(), top.roundToInt(), (left + cardWidth).roundToInt(), (top + cardHeight).roundToInt())
}

/**
 * A few pixels' drift, once a minute, while ambient: a still image at the same spot for hours is
 * how OLED panels wear unevenly.
 */
@Composable
private fun rememberAmbientDrift(ambient: Boolean): State<Offset> {
    val step = with(LocalDensity.current) { 5.dp.toPx() }
    val drift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(ambient, step) {
        if (!ambient) {
            drift.animateTo(Offset.Zero)
            return@LaunchedEffect
        }
        var index = 0
        while (true) {
            delay(60_000)
            index = (index + 1) % DriftPath.size
            drift.animateTo(DriftPath[index] * step, tween(4_000))
        }
    }
    return drift.asState()
}

private val DriftPath = listOf(Offset.Zero, Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f), Offset(-1f, -1f))

/** The cover itself: filling the screen when lit, and shrinking into the ambient card as [dim] goes to 1. */
@Composable
private fun CoverArt(book: Book, dim: State<Float>, drift: State<Offset>) {
    val cover = rememberCover(book)
    val corner = with(LocalDensity.current) { AmbientCorner.toPx() }
    Box(
        Modifier
            // Sized and placed in layout, not scaled: cropped to the screen it shows the middle of
            // the cover, and the rest of it comes into view as the card takes its own shape.
            .layout { measurable, constraints ->
                val stepped = dim.value.coerceIn(0f, 1f)
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val card = ambientCard(width, height)
                val placeable = measurable.measure(
                    Constraints.fixed(lerp(width, card.width, stepped), lerp(height, card.height, stepped)),
                )
                layout(width, height) { placeable.place(lerp(0, card.left, stepped), lerp(0, card.top, stepped)) }
            }
            .graphicsLayer {
                val stepped = dim.value.coerceIn(0f, 1f)
                alpha = 1f - (1f - AMBIENT_BRIGHTNESS) * stepped
                translationX = drift.value.x * stepped
                translationY = drift.value.y * stepped
                clip = stepped > 0f
                shape = if (stepped > 0f) RoundedCornerShape(corner * stepped) else RectangleShape
            },
    ) {
        Crossfade(targetState = cover, animationSpec = tween(500), label = "cover") { image ->
            if (image != null) {
                Image(
                    image, stringResource(R.string.cover_of, book.title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Until the image is decoded: the book's own color, not a black flash.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lit: one strip per stage of the book
// ---------------------------------------------------------------------------------------------

/**
 * The strip along the bottom of the lit cover, over a soft fade so the cover still owns the
 * screen. Stages change places on a spring; reading and "almost done" are one strip that gains an
 * accent, so the wave carries on through.
 */
@Composable
private fun LitStatus(status: CoverStatus, label: String, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    Box(
        modifier
            .fillMaxWidth()
            // Dark enough behind the words to read them over any cover, its own lettering included.
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Black.copy(alpha = 0.74f), 1f to Color.Black.copy(alpha = 0.94f)))
            .padding(start = 28.dp, end = 28.dp, top = 104.dp, bottom = 36.dp),
    ) {
        AnimatedContent(
            targetState = status.phase,
            contentKey = { if (it == CoverPhase.ALMOST_DONE) CoverPhase.READING else it },
            transitionSpec = {
                (slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.BottomStart,
            label = "stage",
        ) { phase ->
            when (phase) {
                CoverPhase.NEW -> NewStatus(status)
                CoverPhase.READING, CoverPhase.ALMOST_DONE -> ReadingStatus(status, label)
                CoverPhase.LISTENING -> ListeningStatus(status, label)
                CoverPhase.FINISHED -> FinishedStatus(status)
            }
        }
    }
}

/** Just opened: a "New" tag, and the chapter you're starting on if the book names it. */
@Composable
private fun NewStatus(status: CoverStatus) {
    Column {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary) {
            Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(JacketIcons.Sparkle, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.library_new_badge), style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
        if (status.chapter.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            ChapterTitle(status.chapter)
        }
    }
}

/**
 * Reading: the chapter and how far through it (the wave), over the whole book (the line). In the
 * last stretch a badge joins in, growing more points the closer the end comes.
 */
@Composable
private fun ReadingStatus(status: CoverStatus, label: String) {
    val motion = MaterialTheme.motionScheme
    val almost = status.phase == CoverPhase.ALMOST_DONE
    Column {
        AnimatedVisibility(
            visible = almost,
            enter = expandVertically(motion.defaultSpatialSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                MorphBadge(almostShape(status.percent), MaterialTheme.colorScheme.tertiary, Modifier.size(56.dp)) {
                    Icon(JacketIcons.Sparkle, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onTertiary)
                }
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.cover_almost_done), style = MaterialTheme.typography.headlineSmallEmphasized, color = Color.White)
            }
        }
        val accent = if (almost) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        if (status.chapter.isNotEmpty()) {
            // The chapter by name, and the place in the book as a figure to read from across the room.
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f).padding(bottom = 6.dp)) { ChapterTitle(status.chapter) }
                Numeral(status.percent, accent)
            }
            Spacer(Modifier.height(10.dp))
        }
        Scales(status, accent)
        // The figure already says "60%": the line under it is for what it doesn't (a page count).
        if (status.chapter.isEmpty() || !label.startsWith(status.percent.toString())) {
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = Soft)
        }
    }
}

/** The closer the end, the more points on the badge; each step morphs into the next. */
private fun almostShape(percent: Int): RoundedPolygon = when {
    percent >= 99 -> MaterialShapes.SoftBurst
    percent >= 97 -> MaterialShapes.Cookie12Sided
    percent >= 95 -> MaterialShapes.Cookie9Sided
    percent >= 93 -> MaterialShapes.Cookie7Sided
    else -> MaterialShapes.Cookie6Sided
}

/**
 * Being read aloud: the reader's own play button, in small (a cookie turning while the voice
 * speaks, a square at rest when paused), and a wave that runs with the voice and stops with it.
 */
@Composable
private fun ListeningStatus(status: CoverStatus, label: String) {
    val turn = rememberTurn(status.playing)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MorphBadge(
                shape = if (status.playing) MaterialShapes.Cookie9Sided else MaterialShapes.Square,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(60.dp),
                rotation = { turn.value },
            ) {
                Icon(JacketIcons.Headphones, stringResource(R.string.cover_listening), Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                ChapterTitle(status.chapter.ifEmpty { stringResource(R.string.cover_listening) })
                Text(stringResource(R.string.cover_listening), style = MaterialTheme.typography.titleMedium, color = Soft)
            }
            Numeral(status.percent, MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Scales(status, MaterialTheme.colorScheme.primary, moving = status.playing, level = if (status.playing) 1f else 0.4f)
    }
}

/** Finished: a check in a burst that opens out of a circle, and the day you got there. */
@Composable
private fun FinishedStatus(status: CoverStatus) {
    var shape by remember { mutableStateOf(MaterialShapes.Circle) }
    LaunchedEffect(Unit) { shape = MaterialShapes.SoftBurst }
    val locale = LocalConfiguration.current.locales[0]
    val day = remember(status.finishedAt, locale) {
        if (status.finishedAt > 0L) DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(status.finishedAt)) else ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MorphBadge(shape, MaterialTheme.colorScheme.tertiary, Modifier.size(76.dp)) {
            Icon(JacketIcons.Check, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onTertiary)
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(stringResource(R.string.book_status_finished), style = MaterialTheme.typography.headlineMediumEmphasized, color = Color.White)
            if (day.isNotEmpty()) Text(day, style = MaterialTheme.typography.titleMedium, color = Soft)
        }
    }
}

/** How far through the book, as one big figure; each new percent rolls up into place. */
@Composable
private fun Numeral(percent: Int, color: Color) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = percent,
        transitionSpec = {
            (slideInVertically(motion.defaultSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false))
        },
        label = "percent",
    ) { value ->
        Text(stringResource(R.string.percent, value), style = MaterialTheme.typography.displaySmallEmphasized, color = color, maxLines = 1)
    }
}

/** The chapter's name, big enough to read from across a table; a new chapter rolls up into place. */
@Composable
private fun ChapterTitle(chapter: String) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = chapter,
        transitionSpec = {
            (slideInVertically(motion.defaultSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false))
        },
        label = "chapter",
    ) { title ->
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Two scales: the chapter as a wave over a thinner line for the whole book, or the book alone as
 * the wave when that is all that's known. The wave is drawn still; it moves when its progress
 * does (a page turned), and runs only while [moving].
 */
@Composable
private fun Scales(status: CoverStatus, color: Color, moving: Boolean = false, level: Float = 1f) {
    val spec = WavyProgressIndicatorDefaults.ProgressAnimationSpec
    val twoScales = status.hasChapterScale
    val book = animateFloatAsState(status.progress, spec, label = "book")
    val wave = animateFloatAsState(if (twoScales) status.chapterProgress ?: 0f else status.progress, spec, label = "wave")
    val thickness = with(LocalDensity.current) { 7.dp.toPx() }
    val stroke = remember(thickness) { Stroke(width = thickness, cap = StrokeCap.Round) }
    Column {
        LinearWavyProgressIndicator(
            progress = { wave.value },
            modifier = Modifier.fillMaxWidth().height(20.dp),
            color = color,
            trackColor = Track,
            stroke = stroke,
            trackStroke = stroke,
            amplitude = { progress -> WavyProgressIndicatorDefaults.indicatorAmplitude(progress) * level },
            wavelength = 44.dp,
            waveSpeed = if (moving) 44.dp else 0.dp,
        )
        AnimatedVisibility(twoScales) {
            if (status.marks.size in 2..MOST_BEADS) {
                ChapterBeads(status.marks, { book.value }, color, Modifier.padding(top = 10.dp).fillMaxWidth().height(12.dp))
            } else {
                LinearProgressIndicator(
                    progress = { book.value },
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp),
                    color = Soft,
                    trackColor = Track,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** Past this many chapters the beads would be too small to tell apart: a plain line serves better. */
private const val MOST_BEADS = 48

/**
 * The whole book as a string of beads, one to a chapter and as long as the chapter is: the ones
 * behind you filled in, the one you're in standing a little taller and filling as you read.
 * [progress] is read while drawing.
 */
@Composable
private fun ChapterBeads(marks: List<Float>, progress: () -> Float, accent: Color, modifier: Modifier = Modifier) {
    val edges = remember(marks) { (listOf(0f) + marks.filter { it > 0.004f && it < 0.996f } + 1f).distinct().sorted() }
    Box(
        modifier.drawBehind {
            val at = progress().coerceIn(0f, 1f)
            val gap = 3.dp.toPx()
            val low = 5.dp.toPx()
            for (index in 0 until edges.lastIndex) {
                val from = edges[index]
                val to = edges[index + 1]
                val left = from * size.width
                val width = ((to - from) * size.width - gap).coerceAtLeast(1.5.dp.toPx())
                val current = at >= from && at < to || (index == edges.lastIndex - 1 && at >= to)
                val tall = if (current) size.height else low
                val top = (size.height - tall) / 2f
                val round = CornerRadius(tall / 2f)
                drawRoundRect(Track, Offset(left, top), Size(width, tall), round)
                val read = ((at - from) / (to - from)).coerceIn(0f, 1f)
                if (read > 0f) {
                    // A bead never fills to less than a dot, so the place always shows.
                    drawRoundRect(if (current) accent else Soft, Offset(left, top), Size((width * read).coerceAtLeast(tall.coerceAtMost(width)), tall), round)
                }
            }
        },
    )
}

/** Secondary text and the book line: white, a step back from the title. */
private val Soft = Color.White.copy(alpha = 0.82f)
private val Track = Color.White.copy(alpha = 0.26f)

// ---------------------------------------------------------------------------------------------
// Ambient: a ring and a number
// ---------------------------------------------------------------------------------------------

/**
 * Under the ambient card: how far through the book, as a ring and a number (a check once it's
 * finished). Drawn once and left alone; the wavy ring holds still.
 */
@Composable
private fun AmbientStatus(status: CoverStatus, quotes: List<String>, modifier: Modifier = Modifier) {
    val finished = status.phase == CoverPhase.FINISHED
    // A book not yet begun has no progress to show: the cover is enough.
    if (status.phase == CoverPhase.NEW) return
    // One highlight at a time, changed every couple of minutes: a step, not an animation.
    val turn by produceState(0, quotes) {
        while (quotes.size > 1) {
            delay(QUOTE_STAYS_MS)
            value++
        }
    }
    val quote = quotes.getOrNull(turn % quotes.size.coerceAtLeast(1))
    val accent = when (status.phase) {
        CoverPhase.ALMOST_DONE, CoverPhase.FINISHED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }.copy(alpha = 0.72f)
    Column(
        modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val card = ambientCard(constraints.maxWidth, constraints.maxHeight)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((constraints.maxWidth - placeable.width) / 2, card.bottom + 28.dp.roundToPx())
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (finished) {
                MorphBadge(MaterialShapes.SoftBurst, accent, Modifier.size(48.dp)) {
                    Icon(JacketIcons.Check, null, Modifier.size(24.dp), tint = Color.Black)
                }
            } else {
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.size(52.dp),
                        color = accent,
                        trackColor = Color.White.copy(alpha = 0.14f),
                        waveSpeed = 0.dp,
                    )
                    if (status.phase == CoverPhase.LISTENING) {
                        Icon(JacketIcons.Headphones, stringResource(R.string.cover_listening), Modifier.size(22.dp), tint = accent)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                if (finished) stringResource(R.string.book_status_finished) else stringResource(R.string.percent, status.percent),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        if (quote != null) {
            Text(
                stringResource(R.string.notes_highlight_quote, quote),
                style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.5f), maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 28.dp).widthIn(max = 300.dp),
            )
        }
    }
}

/** How long one highlight stays on the ambient cover before the next. */
private const val QUOTE_STAYS_MS = 120_000L
