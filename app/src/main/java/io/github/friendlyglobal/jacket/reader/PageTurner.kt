package io.github.friendlyglobal.jacket.reader

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.core.graphics.createBitmap
import io.github.friendlyglobal.jacket.data.PageTurn
import io.github.friendlyglobal.jacket.ui.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Page turns that feel like paper. Readium scrolls a strip of columns under your finger; instead,
 * the page you're on is a sheet of its own, which leaves in the chosen [style]:
 *
 * - [PageTurn.PAPER]: it lifts off and slides away over the next page (or the previous page
 *   slides back in over it), casting a soft shadow.
 * - [PageTurn.CURL]: it folds over along a line that follows your finger, showing its blank back,
 *   the way a leaf of a book turns.
 * - [PageTurn.CARDS]: it becomes a card, corners rounding as it tilts away, while the next page
 *   comes up from behind on a spring.
 *
 * ([PageTurn.SLIDE] leaves the turn to Readium's own strip: see [enabled].)
 *
 * It works with snapshots: the page on screen is copied (PixelCopy), Readium jumps to the
 * neighbouring page instantly, and the copy and the live page are moved against each other.
 * Everything is a function of two offsets read while drawing, so a turn recomposes nothing.
 */
@Stable
class PageTurner(private val scope: CoroutineScope) {
    var navigator: EpubNavigatorFragment? = null
    /** Off in scroll mode, while the controls are showing, and for [PageTurn.SLIDE]. */
    var enabled: () -> Boolean = { true }
    /** Called once a turn completes (for the haptic tick). */
    var onTurned: () -> Unit = {}
    /** Whether there is a page that way: +1 forward, -1 back, in reading order. */
    var canTurn: (direction: Int) -> Boolean = { true }
    /** Called when a turn meets the first or last page of the book. */
    var onBlocked: () -> Unit = {}
    var style by mutableStateOf(PageTurn.PAPER)
    /** The page's own color, for the back of a curling leaf. */
    var paper by mutableStateOf(Color.White)

    /** A turn is under way: Readium's own page-change ticks should stay quiet. */
    var busy by mutableStateOf(false)
        private set

    /** The page you were on, sliding off to the left (turning towards the right-hand page). */
    internal var above by mutableStateOf<ImageBitmap?>(null)
    /** The page you were on, lying still while the left-hand page slides back over it. */
    internal var below by mutableStateOf<ImageBitmap?>(null)
    internal val aboveX = Animatable(0f)
    internal val liveX = Animatable(0f)
    /** At the book's first or last page the sheet only gives a little, and springs back. */
    internal val nudge = Animatable(0f)
    internal var width = 1f

    /** How far the sheet on top has left, 0..1 (a spring may carry it a little past). */
    internal val leaving: Float get() = -aboveX.value / width

    /** How far the sheet coming back has arrived, 0..1. */
    internal val arriving: Float get() = 1f + liveX.value / width

    private var side = 0 // +1: the right-hand page comes up from under; -1: the left-hand page comes over
    private var ready = false
    private var blocked = false
    private var dragX = 0f
    private var pendingRelease: Float? = null
    private var job: Job? = null

    /** Turns one page with the animation: +1 forward, -1 back (in reading order). */
    fun turn(direction: Int) {
        val rtl = navigator?.overflow?.value?.readingProgression == ReadingProgression.RTL
        turnToward(toRight = (direction > 0) != rtl)
    }

    /** Turns towards the page on the right or left (edge taps, arrow keys), animated when possible. */
    fun turnToward(toRight: Boolean) {
        val n = navigator ?: return
        if (!enabled()) {
            // Controls showing: let Readium slide the strip itself.
            if (toRight) n.goRightCompat(animated = true) else n.goLeftCompat(animated = true)
            return
        }
        start(if (toRight) +1 else -1, dragging = false)
        release(velocity = 0f, commit = true)
    }

    /** A horizontal drag began: [toRight] when the finger moves left (revealing the right-hand page). */
    internal fun startDrag(toRight: Boolean) = start(if (toRight) +1 else -1, dragging = true)

    internal fun drag(totalX: Float) {
        dragX = totalX
        if (blocked) {
            // Rubber band: a fraction of the pull, and only the way that was asked.
            val give = (totalX * 0.22f).coerceIn(-width * 0.12f, width * 0.12f)
            scope.launch { nudge.snapTo(if (side > 0) give.coerceAtMost(0f) else give.coerceAtLeast(0f)) }
            return
        }
        if (!ready) return
        scope.launch { applyDrag() }
    }

    internal fun release(velocity: Float, commit: Boolean? = null) {
        if (blocked) {
            blocked = false
            val previous = job
            job = scope.launch {
                previous?.join()
                nudge.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
                busy = false
            }
            return
        }
        val decide = commit ?: decide(velocity)
        if (!ready) {
            pendingRelease = if (decide) 1f else 0f
            return
        }
        settle(decide, velocity)
    }

    private fun decide(velocity: Float): Boolean {
        val fast = 900f
        return if (side > 0) dragX < -width * 0.25f || velocity < -fast else dragX > width * 0.25f || velocity > fast
    }

    private fun start(newSide: Int, dragging: Boolean) {
        finishNow()
        val n = navigator ?: return
        val view = n.view ?: return
        side = newSide
        ready = false
        dragX = 0f
        pendingRelease = null
        busy = true
        width = view.width.toFloat().coerceAtLeast(1f)
        val rtl = n.overflow.value.readingProgression == ReadingProgression.RTL
        if (!canTurn(if ((newSide > 0) != rtl) 1 else -1)) {
            // The cover of the book, front or back: nothing to turn to. The page gives a little
            // under the finger (or by itself, for a tap) and springs back.
            blocked = true
            onBlocked()
            job = if (dragging) null else scope.launch {
                nudge.animateTo(-newSide * width * 0.035f, spring(stiffness = Spring.StiffnessMedium))
            }
            return
        }
        job = scope.launch {
            val shot = capture(view)
            if (shot == null) {
                // No snapshot (rare): fall back to Readium's own turn.
                busy = false
                if (!dragging) if (newSide > 0) n.goRightCompat() else n.goLeftCompat()
                return@launch
            }
            if (newSide > 0) {
                // The copy of this page covers the screen; underneath, the live view jumps ahead.
                above = shot
                aboveX.snapTo(dragX.coerceAtMost(0f))
                if (!n.goRightCompat()) return@launch cleanUp()
            } else {
                // The copy lies beneath; the live view jumps back and slides in from the left.
                if (!n.goLeftCompat()) return@launch cleanUp()
                below = shot
                liveX.snapTo(-width + dragX.coerceAtLeast(0f))
            }
            ready = true
            pendingRelease?.let { settle(it > 0f, 0f) }
        }
    }

    private suspend fun applyDrag() {
        if (side > 0) aboveX.snapTo(dragX.coerceIn(-width, 0f))
        else liveX.snapTo((-width + dragX).coerceIn(-width, 0f))
    }

    private fun settle(commit: Boolean, velocity: Float) {
        val previous = job
        job = scope.launch {
            previous?.join()
            // Paper lands without a bounce. A card may: it is the next page's spring that shows.
            val spec = when (style) {
                PageTurn.CARDS -> spring<Float>(dampingRatio = 0.72f, stiffness = 320f)
                PageTurn.CURL -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)
                else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            }
            val n = navigator
            if (side > 0) {
                if (commit) {
                    aboveX.animateTo(-width, spec, velocity.coerceAtMost(0f))
                } else {
                    aboveX.animateTo(0f, spec, velocity.coerceAtLeast(0f))
                    n?.goLeftCompat()
                    awaitFrame()
                }
            } else {
                if (commit) {
                    liveX.animateTo(0f, spec, velocity.coerceAtLeast(0f))
                } else {
                    liveX.animateTo(-width, spec, velocity.coerceAtMost(0f))
                    n?.goRightCompat()
                    liveX.snapTo(0f)
                    awaitFrame()
                }
            }
            if (commit) onTurned()
            cleanUp()
        }
    }

    /** A new turn while one is still settling: jump to its end first. */
    private fun finishNow() {
        if (!busy) return
        job?.cancel()
        scope.launch {
            aboveX.snapTo(0f)
            liveX.snapTo(0f)
            nudge.snapTo(0f)
        }
        above = null
        below = null
        blocked = false
        busy = false
    }

    private suspend fun cleanUp() {
        above = null
        below = null
        aboveX.snapTo(0f)
        liveX.snapTo(0f)
        busy = false
        ready = false
    }

    /** Copies the page on screen. Must run before anything is drawn over it. */
    private suspend fun capture(view: View): ImageBitmap? = suspendCancellableCoroutine { cont ->
        val window = view.context.findActivity()?.window
        if (window == null || view.width == 0 || view.height == 0 || !view.isAttachedToWindow) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val at = IntArray(2).also(view::getLocationInWindow)
        val bitmap = createBitmap(view.width, view.height)
        runCatching {
            PixelCopy.request(
                window, Rect(at[0], at[1], at[0] + view.width, at[1] + view.height), bitmap,
                { result -> if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null) },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }
}

private fun EpubNavigatorFragment.goRightCompat(animated: Boolean = false): Boolean =
    if (overflow.value.readingProgression == ReadingProgression.RTL) goBackward(animated) else goForward(animated)

private fun EpubNavigatorFragment.goLeftCompat(animated: Boolean = false): Boolean =
    if (overflow.value.readingProgression == ReadingProgression.RTL) goForward(animated) else goBackward(animated)

/** The page copy beneath the live page (drawn before it): the page you're leaving, turning back. */
@Composable
internal fun BoxScope.PageBelow(turner: PageTurner) {
    val below = turner.below ?: return
    Image(
        below, null,
        Modifier.matchParentSize().graphicsLayer {
            if (turner.style == PageTurn.CARDS) {
                // It sinks back as the returning card comes down over it.
                val scale = 1f - CARD_DEPTH * turner.arriving.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
            }
        },
        contentScale = ContentScale.FillBounds,
    )
}

/**
 * Moves the live page: sliding back in over the copy (paper), laid down up to the fold (curl), or
 * dealt back as a card; and, under a card being dealt away, rising to meet it.
 */
internal fun Modifier.livePage(turner: PageTurner): Modifier = this
    .graphicsLayer {
        val rest = turner.nudge.value
        val returning = turner.below != null
        when (turner.style) {
            PageTurn.CARDS -> if (returning) {
                card(1f - turner.arriving.coerceIn(0f, 1f), turner.liveX.value)
            } else {
                translationX = rest
                if (turner.above != null) {
                    // The spring carries this a hair past full size and back: the bounce you see.
                    val scale = 1f - CARD_DEPTH * (1f - turner.leaving)
                    scaleX = scale
                    scaleY = scale
                }
            }
            PageTurn.CURL -> translationX = rest
            else -> translationX = turner.liveX.value + rest
        }
    }
    .drawWithContent {
        // A leaf curling back into place covers the old page only up to its fold.
        if (turner.style == PageTurn.CURL && turner.below != null) {
            clipRect(right = size.width * turner.arriving.coerceIn(0f, 1f)) { this@drawWithContent.drawContent() }
        } else {
            drawContent()
        }
    }

/** How far back the page underneath sits while a card is over it. */
private const val CARD_DEPTH = 0.07f

/** A page as a card, [away] (0..1) of the way off: tilted, a little smaller, its corners rounded. */
private fun GraphicsLayerScope.card(away: Float, x: Float) {
    translationX = x
    transformOrigin = TransformOrigin(0.5f, 1f)
    rotationZ = -5f * away
    val scale = 1f - 0.05f * away
    scaleX = scale
    scaleY = scale
    shadowElevation = 16.dp.toPx() * (away * 8f).coerceAtMost(1f)
    // Square at rest, so a settled page is exactly the page.
    val corner = 28.dp.toPx() * (away * 6f).coerceAtMost(1f)
    shape = if (corner > 0f) RoundedCornerShape(corner) else RectangleShape
    clip = corner > 0f
}

/** What lies over the live page: the copy leaving, the shade it casts, and the turning leaf. */
@Composable
internal fun BoxScope.PageAbove(turner: PageTurner) {
    val above = turner.above
    val style = turner.style
    if (above != null) {
        // The page underneath sits slightly in shade until the top page is out of the way.
        Box(Modifier.matchParentSize().drawBehind { drawRect(Color.Black, alpha = 0.10f * (1f - turner.leaving).coerceIn(0f, 1f)) })
        when (style) {
            PageTurn.CURL -> Image(
                above, null,
                Modifier.matchParentSize().drawWithContent {
                    clipRect(right = size.width * (1f - turner.leaving).coerceIn(0f, 1f)) { this@drawWithContent.drawContent() }
                },
                contentScale = ContentScale.FillBounds,
            )
            PageTurn.CARDS -> Image(
                above, null,
                Modifier.matchParentSize().graphicsLayer { card(turner.leaving.coerceIn(0f, 1f), turner.aboveX.value) },
                contentScale = ContentScale.FillBounds,
            )
            else -> Box(Modifier.matchParentSize().graphicsLayer { translationX = turner.aboveX.value }) {
                Image(above, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                EdgeShadow(Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = size.width })
            }
        }
    }
    if (turner.below != null && style == PageTurn.PAPER) {
        Box(Modifier.matchParentSize().graphicsLayer { translationX = turner.liveX.value + turner.width }) {
            // Shade on the old page, just right of the incoming page's edge.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = 0.10f * (1f - turner.arriving).coerceIn(0f, 1f)) })
            EdgeShadow(Modifier.align(Alignment.CenterStart))
        }
    }
    if (style == PageTurn.CURL && (above != null || turner.below != null)) Leaf(turner)
}

/**
 * The leaf in mid-turn, for [PageTurn.CURL]: the part of the sheet past the fold, lying back over
 * the page as its blank reverse, darker into the fold and at its free edge, with the shadows it
 * throws on the page it leaves and the page it uncovers. The fold is where the sheet on top
 * ends; the leaf reaches as far back from it as there is sheet beyond it.
 */
@Composable
private fun BoxScope.Leaf(turner: PageTurner) {
    Box(
        Modifier.matchParentSize().drawBehind {
            val flat = if (turner.above != null) 1f - turner.leaving else turner.arriving
            val fold = size.width * flat.coerceIn(0f, 1f)
            val edge = (2f * fold - size.width)
            val left = edge.coerceAtLeast(0f)
            if (fold <= 0f || fold >= size.width) return@drawBehind
            // Onto the page being uncovered, right of the fold.
            band(LeafCast, fold, (size.width - fold).coerceAtMost(48.dp.toPx()))
            // Onto the page it lies on, left of its free edge (mirrored: darkest at the edge).
            if (edge > 0f) band(LeafCast, edge, -18.dp.toPx(), alpha = 0.7f)
            drawRect(turner.paper, Offset(left, 0f), Size(fold - left, size.height))
            // The sheet's curve: light across the middle, falling off into the fold.
            band(LeafCurve, edge, fold - edge)
        },
    )
}

/** Draws a one-unit-wide [brush] stretched from [x] over [width] (negative: leftwards), full height. */
private fun DrawScope.band(brush: Brush, x: Float, width: Float, alpha: Float = 1f) {
    withTransform({
        translate(x, 0f)
        scale(width, size.height, pivot = Offset.Zero)
    }) { drawRect(brush, size = Size(1f, 1f), alpha = alpha) }
}

/** The shadow a lifted leaf throws: one unit wide, stretched into place as it is drawn. */
private val LeafCast = Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.26f), 1f to Color.Transparent, startX = 0f, endX = 1f)

/** Shading across the back of the leaf, from its free edge (0) to the fold (1). */
private val LeafCurve = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.16f),
    0.06f to Color.Black.copy(alpha = 0.04f),
    0.45f to Color.White.copy(alpha = 0.10f),
    0.8f to Color.Black.copy(alpha = 0.06f),
    1f to Color.Black.copy(alpha = 0.24f),
    startX = 0f, endX = 1f,
)

@Composable
private fun EdgeShadow(modifier: Modifier) {
    Box(
        modifier
            .width(18.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent))),
    )
}

/**
 * Watches touches on the page: horizontal drags become page turns (Readium gets a cancel, so it
 * doesn't scroll its strip); taps, long-press selection and vertical moves are left alone.
 */
internal suspend fun PointerInputScope.pageTurnGestures(turner: PageTurner) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (!turner.enabled()) return@awaitEachGesture
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    var total = Offset.Zero
    var dragging = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) {
            // A pinch or second finger: not a page turn.
            if (dragging) turner.release(0f, commit = false)
            return@awaitEachGesture
        }
        val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            if (dragging) {
                change.consume()
                turner.release(tracker.calculateVelocity().x)
            }
            break
        }
        tracker.addPosition(change.uptimeMillis, change.position)
        total += change.positionChange()
        if (!dragging) {
            // Held still past the long-press time: that's text selection, not ours.
            if (change.uptimeMillis - down.uptimeMillis > longPress && total.getDistance() < slop * 2) break
            if (abs(total.x) > slop && abs(total.x) > abs(total.y) * 1.2f) {
                dragging = true
                turner.startDrag(toRight = total.x < 0)
            } else if (abs(total.y) > slop * 2) {
                break
            }
        }
        if (dragging) {
            change.consume()
            turner.drag(total.x)
        }
    }
}
