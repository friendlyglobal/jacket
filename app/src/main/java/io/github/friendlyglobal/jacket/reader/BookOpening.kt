package io.github.friendlyglobal.jacket.reader

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.ui.CoverCorner
import io.github.friendlyglobal.jacket.ui.CoverImage
import io.github.friendlyglobal.jacket.ui.LocalCoverSpot
import io.github.friendlyglobal.jacket.ui.LocalCoverTransition
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.sharedCover
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Opening a book, and closing it again.
 *
 * The cover flies in from the library (a shared element) and lands in the middle of the screen, on
 * a backdrop in the book's color. It waits there, breathing, until the first page has rendered.
 * Then it swings open on its spine while the page underneath grows from the size of the book to
 * fill the screen. Closing plays the same thing backwards and hands the closed cover back to the
 * library.
 *
 * All of it is a function of one number, [open] (0 = a closed book, 1 = the page filling the
 * screen), which is only read while drawing: nothing recomposes as it moves.
 */
@Stable
internal class BookOpening {
    val open = Animatable(0f)

    /** The cover's slow breath while it waits for the page (0..1). */
    val breath = Animatable(0f)

    /** Reading: the cover is out of the way and touches reach the page. */
    var settled by mutableStateOf(false)
        private set

    /** Closing, or closed and about to fly home. */
    var folding by mutableStateOf(false)
        private set

    /** The reader's size, which the cover's own layer can't see. */
    var container by mutableStateOf(IntSize.Zero)

    /** Something alive to look at while the page is still on its way. */
    suspend fun breathe() {
        if (motionRemoved()) return
        breath.animateTo(1f, infiniteRepeatable(tween(BREATH_MS, easing = EaseInOutSine), RepeatMode.Reverse))
    }

    /** Swings the cover open and grows the page to fill the screen. */
    suspend fun reveal() = coroutineScope {
        folding = false
        launch { breath.animateTo(0f, tween(OPEN_MS / 3)) }
        open.animateTo(1f, tween(((1f - open.value) * OPEN_MS).roundToInt(), easing = LinearEasing))
        settled = true
    }

    /** Folds the page back under the cover, from wherever it is (a back swipe may have begun it). */
    suspend fun fold() = coroutineScope {
        settled = false
        folding = true
        launch { breath.animateTo(0f, tween(FOLD_MS / 2)) }
        // Straight to where the page visibly starts to shrink: no pause after the back press.
        if (open.value > FOLD_FROM) open.snapTo(FOLD_FROM)
        open.animateTo(0f, tween((open.value / FOLD_FROM * FOLD_MS).roundToInt(), easing = LinearEasing))
    }

    /** A back swipe in progress: the page shrinks towards the book, stopping short of the cover. */
    suspend fun peek(progress: Float) {
        open.snapTo(1f - PEEK_DEPTH * LinearOutSlowInEasing.transform(progress.coerceIn(0f, 1f)))
    }

    /** The back swipe was abandoned: flat again. */
    suspend fun unpeek() {
        open.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
    }

    companion object {
        /** The point in [open] from which the page reads as revealed: the controls may arrive. */
        const val REVEALED = 0.8f
    }
}

private const val OPEN_MS = 600
private const val FOLD_MS = 300

/** Above this the page is within a hair of full size (its growth eases out). */
private const val FOLD_FROM = 0.85f

/** One breath in (or out), and how much the cover swells with it. */
private const val BREATH_MS = 1300
private const val BREATH_SCALE = 0.022f

/** The cover has swung out of sight by this point in the opening; the page keeps growing after it. */
private const val SWING_END = 0.55f

/** The page starts to grow once the cover has lifted clear of it. */
private const val GROW_START = 0.2f

/** How far a back swipe folds the page: not so far that the cover comes back into view. */
private const val PEEK_DEPTH = 0.42f

/** The cover peels up slowly, then swings away. */
private val SwingEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

/** How far the cover has swung open at [open]: 0 flat on the book, 1 edge-on and gone. */
private fun swing(open: Float): Float = SwingEasing.transform((open / SWING_END).coerceIn(0f, 1f))

/** How far the page has grown at [open]: 0 the size of the book, 1 the size of the screen. */
private fun growth(open: Float): Float =
    FastOutSlowInEasing.transform(((open - GROW_START) / (1f - GROW_START)).coerceIn(0f, 1f))

/** A 2:3 cover, 70% of the reader's height or as tall as its width leaves room for (a folded phone). */
private fun restingCoverHeight(width: Float, height: Float): Float = min(height * 0.70f, width * 0.80f * 1.5f)

/**
 * Scales the reader to the width of the window the backdrop leaves open (see [OpeningBackdrop]),
 * so the page is whole at every size: book-sized under the cover, then growing to the screen.
 */
internal fun Modifier.openingPage(opening: BookOpening): Modifier = graphicsLayer {
    val coverWidth = restingCoverHeight(size.width, size.height) * 2f / 3f
    val scale = (coverWidth + (size.width - coverWidth) * growth(opening.open.value)) / size.width
    scaleX = scale
    scaleY = scale
}

/**
 * The backdrop the closed book lies on, drawn over the reader with a page-shaped window in it:
 * the window is the size of the book while it is shut and widens to the whole screen as it opens.
 * Until the book is open it also keeps touches from the page.
 */
@Composable
internal fun BoxScope.OpeningBackdrop(opening: BookOpening, color: Color) {
    val corner = remember { Path() }
    // The shadow the lifting cover throws on the page beside its free edge. One unit wide,
    // stretched into place as it is drawn.
    val shade = remember {
        Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent, startX = 0f, endX = 1f)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shield = if (opening.settled) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    Box(
        Modifier.matchParentSize().then(shield).drawBehind {
            val open = opening.open.value
            if (open >= 1f) return@drawBehind
            if (open <= 0f) {
                drawRect(color)
                return@drawBehind
            }
            val grown = growth(open)
            val coverHeight = restingCoverHeight(size.width, size.height)
            // A hair inside the cover's edge while the book is nearly shut, so no page shows around
            // it. Whole pixels, so the four bands below meet without seams.
            val tuck = (1f - grown) * 1.dp.toPx()
            val left = ceil((size.width - coverHeight * 2f / 3f) / 2f * (1f - grown) + tuck)
            val top = ceil((size.height - coverHeight) / 2f * (1f - grown) + tuck)
            val right = size.width - left
            val bottom = size.height - top
            val swung = swing(open)
            if (swung < 1f) {
                // Where the cover's free edge appears, seen through the same camera as the cover.
                val coverWidth = coverHeight * 2f / 3f
                val angle = swung * (PI / 2).toFloat()
                val edge = coverWidth * cos(angle) * CAMERA_WIDTHS / (CAMERA_WIDTHS - sin(angle))
                val reach = coverWidth * (0.05f + 0.3f * sin(angle))
                withTransform({
                    translate(if (rtl) right - edge else left + edge, top)
                    scale(if (rtl) -reach else reach, bottom - top, pivot = Offset.Zero)
                }) { drawRect(shade, size = Size(1f, 1f), alpha = 1f - swung) }
            }
            // Plain fills around the window rather than a clip: a full-screen clip redrawn every
            // frame is the expensive way to make a hole.
            drawRect(color, size = Size(size.width, top))
            drawRect(color, Offset(0f, bottom), Size(size.width, top))
            drawRect(color, Offset(0f, top), Size(left, bottom - top))
            drawRect(color, Offset(right, top), Size(left, bottom - top))
            val radius = CoverCorner.toPx() * (1f - grown)
            if (radius >= 0.5f) {
                roundOff(corner, left, top, radius, 1, 1, color)
                roundOff(corner, right, top, radius, -1, 1, color)
                roundOff(corner, left, bottom, radius, 1, -1, color)
                roundOff(corner, right, bottom, radius, -1, -1, color)
            }
        },
    )
}

/**
 * Fills the sliver between the square corner at ([x], [y]) and a quarter circle of [radius] set
 * into it; [dx] and [dy] point from the corner into the window. [scratch] is reused, so drawing
 * allocates nothing.
 */
private fun DrawScope.roundOff(scratch: Path, x: Float, y: Float, radius: Float, dx: Int, dy: Int, color: Color) {
    val centerX = x + dx * radius
    val centerY = y + dy * radius
    scratch.rewind()
    scratch.asAndroidPath().apply {
        moveTo(x, y)
        lineTo(centerX, y)
        arcTo(centerX - radius, centerY - radius, centerX + radius, centerY + radius, if (dy > 0) 270f else 90f, -90f * dx * dy, false)
        close()
    }
    drawPath(scratch, color)
}

/**
 * The book's cover: where the shared cover from the library lands, and what swings open, towards
 * you, on its spine. The spine stays on the edge of the growing page (the right edge in
 * right-to-left layouts).
 */
@Composable
internal fun BoxScope.OpeningCover(book: Book, opening: BookOpening) {
    val flight = LocalCoverTransition.current?.visibility?.transition
    // The shadow grows as the cover lifts off the shelf.
    val lift = flight?.animateFloat({ tween(240) }, label = "cover-lift") { if (it == EnterExitState.Visible) 1f else 0f }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = remember { RoundedCornerShape(CoverCorner) }
    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
        CoverImage(
            book,
            Modifier
                .layout { measurable, constraints ->
                    opening.container = IntSize(constraints.maxWidth, constraints.maxHeight)
                    val height = restingCoverHeight(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    val placeable = measurable.measure(Constraints.fixed((height * 2f / 3f).roundToInt(), height.roundToInt()))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                // No clip in flight: this cover brings its own shape and shadow.
                .sharedCover(book.id, LocalCoverSpot.current, clipInFlight = false)
                .graphicsLayer {
                    val open = opening.open.value
                    val swung = swing(open)
                    val breath = 1f + BREATH_SCALE * opening.breath.value
                    val side = if (rtl) -1f else 1f
                    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                    scaleX = breath
                    scaleY = breath
                    // The spine follows the page's edge outwards; breathing swells from the middle.
                    val margin = (opening.container.width - size.width) / 2f
                    translationX = -side * (margin * growth(open) + size.width * (breath - 1f) / 2f)
                    rotationY = -side * 90f * swung
                    // Far enough back that the swinging edge looms without turning inside out
                    // (the camera's unit is 72 pixels).
                    cameraDistance = size.width * CAMERA_WIDTHS / 72f
                    // Gone early in the swing: a shadow thrown from a steep angle streaks across
                    // the screen. Closing, the cover comes down flat, as it will fly home.
                    val raised = if (opening.folding) 0f else (lift?.value ?: 1f) * (1f - 3f * swung).coerceAtLeast(0f)
                    shadowElevation = CoverElevation.toPx() * raised
                    this.shape = shape
                    clip = true
                    alpha = if (swung >= 1f) 0f else 1f
                }
                .drawWithContent {
                    drawContent()
                    // Turning away from the light.
                    val swung = swing(opening.open.value)
                    if (swung > 0f) drawRect(Color.Black, alpha = 0.3f * swung)
                },
            contentScale = ContentScale.Crop,
            // Same size the library decoded, so the flying cover is already in the cache.
            maxHeight = 600,
        )
    }
}

private val CoverElevation = 14.dp

/** The camera's distance from the cover, in cover widths. */
private const val CAMERA_WIDTHS = 4.5f
