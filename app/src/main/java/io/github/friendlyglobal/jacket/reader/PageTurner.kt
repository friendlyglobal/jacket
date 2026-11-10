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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.core.graphics.createBitmap
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
 * the page you're on lifts off and slides away over the next one (or the previous page slides
 * back in over it), casting a soft shadow, and settles with a spring.
 *
 * It works with snapshots: the page on screen is copied (PixelCopy), Readium jumps to the
 * neighbouring page instantly, and the copy and the live page are moved against each other.
 */
@Stable
class PageTurner(private val scope: CoroutineScope) {
    var navigator: EpubNavigatorFragment? = null
    /** Off in scroll mode and while the controls are showing. */
    var enabled: () -> Boolean = { true }
    /** Called once a turn completes (for the haptic tick). */
    var onTurned: () -> Unit = {}

    /** A turn is under way: Readium's own page-change ticks should stay quiet. */
    var busy by mutableStateOf(false)
        private set

    /** The page you were on, sliding off to the left (turning towards the right-hand page). */
    internal var above by mutableStateOf<ImageBitmap?>(null)
    /** The page you were on, lying still while the left-hand page slides back over it. */
    internal var below by mutableStateOf<ImageBitmap?>(null)
    internal val aboveX = Animatable(0f)
    internal val liveX = Animatable(0f)
    internal var width = 1f

    private var side = 0 // +1: the right-hand page comes up from under; -1: the left-hand page comes over
    private var ready = false
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
        if (!ready) return
        scope.launch { applyDrag() }
    }

    internal fun release(velocity: Float, commit: Boolean? = null) {
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
            val spec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
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
        }
        above = null
        below = null
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

/** The page copy beneath the live page (drawn before it). */
@Composable
internal fun BoxScope.PageBelow(turner: PageTurner) {
    turner.below?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds) }
}

/** Moves the live page while the previous one slides in over the copy. */
internal fun Modifier.livePage(turner: PageTurner): Modifier = graphicsLayer { translationX = turner.liveX.value }

/** The copy sliding off, with its shadow, and the shadow of the live page sliding in. */
@Composable
internal fun BoxScope.PageAbove(turner: PageTurner) {
    val above = turner.above
    if (above != null) {
        // The page underneath sits slightly in shade until the top page is out of the way.
        val covered = 1f + turner.aboveX.value / turner.width
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.10f * covered)))
        Box(Modifier.matchParentSize().graphicsLayer { translationX = turner.aboveX.value }) {
            Image(above, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            EdgeShadow(Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = size.width })
        }
    }
    if (turner.below != null) {
        val covered = 1f + turner.liveX.value / turner.width
        Box(
            Modifier.matchParentSize().graphicsLayer {
                translationX = turner.liveX.value + turner.width
                alpha = 1f
            },
        ) {
            // Shade on the old page, just right of the incoming page's edge.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f * (1f - covered))))
            EdgeShadow(Modifier.align(Alignment.CenterStart))
        }
    }
}

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
