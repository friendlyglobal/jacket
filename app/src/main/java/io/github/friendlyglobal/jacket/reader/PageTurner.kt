package io.github.friendlyglobal.jacket.reader

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateTo
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.core.graphics.createBitmap
import io.github.friendlyglobal.jacket.data.PageTurn
import io.github.friendlyglobal.jacket.ui.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.ReadingProgression
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Page turns that feel like paper. Readium scrolls a strip of columns under your finger; instead,
 * the page you're on is a sheet of its own, which leaves in the chosen [style]:
 *
 * - [PageTurn.PAPER]: it lifts off and slides away over the next page (or the previous page
 *   slides back in over it), casting a soft shadow.
 * - [PageTurn.CURL]: it folds over with its edge in your fingers, showing its blank back, the way
 *   a leaf of a book turns: by a corner if you take it low or high, straight if you take it in
 *   the middle, leaning as your finger moves up or down (see [Fold]).
 * - [PageTurn.CARDS]: it becomes a card, corners rounding as it tilts away, while the next page
 *   comes up from behind on a spring.
 *
 * ([PageTurn.SLIDE] leaves the turn to Readium's own strip: see [enabled].)
 *
 * It works with snapshots: the page on screen is copied (PixelCopy), Readium jumps to the
 * neighbouring page instantly, and the copy and the live page are moved against each other.
 * Everything is a function of a few numbers read while drawing, so a turn recomposes nothing.
 *
 * The sheet is the finger's from the moment it moves: the page is copied as the finger comes
 * down, the sheet goes where the finger does with nothing in between, and on letting go it
 * carries on at the finger's speed. A flick flies; a sheet set down gently eases into place.
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

    /** The page you were on, on top: sliding off to the left (turning towards the right-hand page). */
    internal var above by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The page you were on, lying still while the left-hand page comes back over it. */
    internal var below by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Where the sheet on top has slid to: 0 at rest, -[width] when it has gone. */
    internal var aboveX by mutableFloatStateOf(0f)
        private set
    /** Where the live page is as it slides back in: 0 in place, -[width] (and its shadow's) out of sight. */
    internal var liveX by mutableFloatStateOf(0f)
        private set
    /** At the book's first or last page the sheet only gives a little, and springs back. */
    internal var nudge by mutableFloatStateOf(0f)
        private set
    /** For a curling leaf: where the point of its edge that is held has got to (see [Fold]). */
    internal var leafX by mutableFloatStateOf(0f)
        private set
    internal var leafY by mutableFloatStateOf(0f)
        private set
    /** For a curling leaf: how far down its edge the held point is. */
    internal var heldY by mutableFloatStateOf(0f)
        private set
    internal var width = 1f
    internal var height = 1f

    /** How far the sheet on top has left, 0..1 (a spring may carry it a little past). */
    internal val leaving: Float get() = -aboveX / width

    /** How far the sheet coming back has arrived, 0..1. */
    internal val arriving: Float get() = 1f + liveX / width

    private var side = 0 // +1: the right-hand page comes up from under; -1: the left-hand page comes over
    /** The copy is in place and the live page has been sent to its neighbour: the sheet can move. */
    private var ready = false
    private var blocked = false
    /** A finger has the sheet. */
    private var held = false
    /** Let go, and going through (rather than back). */
    private var landing = false
    /** Called off: the live page is on its way back, out of sight under the copy (see [cover]). */
    private var covering = false
    /** What was last drawn isn't the page at rest: a copy of the screen has to wait for the next. */
    private var unsettled = false
    /** Let go before the copy was ready: whether to go through, and how fast the finger was. */
    private var pendingCommit: Boolean? = null
    private var pendingVelocity = Velocity.Zero
    private var job: Job? = null
    private var coverJob: Job? = null

    /** Where the finger took hold, and where it is now, in the page's own pixels. */
    private var grabX = 0f
    private var grabY = 0f
    private var fingerX = 0f
    private var fingerY = 0f

    /**
     * The sheet never jumps to the finger. Where the two start apart (by the little the finger
     * moves before a drag is a drag; by the way from a leaf's edge to the finger), the sheet moves
     * with the finger from the first, and takes up the [gap] between them on a quick spring:
     * [slack] is how much of it is left, 1 down to 0.
     */
    private var slack = 0f
    private var slackSpeed = 0f
    private var gap = 0f
    private var takeUp: Job? = null
    /** How a curling leaf's fold leans for the height it was taken at (see [hold]). */
    private var lean = 0f
    /** The width of the shadow at a sliding sheet's edge: it has to be out of sight too. */
    private var rim = 0f

    /** A copy of the page taken as a finger came down, for the turn it may be about to make. */
    private var spare: ImageBitmap? = null
    /** A copy that was never shown, kept to be copied into next time: every touch takes one, and a page is megabytes. */
    private var unused: android.graphics.Bitmap? = null
    private var spareAt = 0L
    private var spareJob: Job? = null

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
        // No finger to follow. A leaf is flicked up by its corner, so it still turns corner first.
        finish(commit = true, if (style == PageTurn.CURL) Velocity(0f, -width * TAP_FLICK) else Velocity.Zero)
    }

    /**
     * A finger has come down on the page. The page is copied there and then, while nothing moves:
     * copying takes a frame or two, and a drag that had to wait for it would start late, then jump.
     */
    internal fun touched() {
        settleNow()
        dropSpare()
        // Still busy: the copy of a turn just called off lies over the page, and will do.
        if (busy) return
        val view = navigator?.view ?: return
        spareJob = scope.launch {
            spare = capture(view) ?: return@launch
            spareAt = SystemClock.uptimeMillis()
        }
    }

    /**
     * That finger left without dragging. If it was a tap on the page's edge, the turn it asks for
     * reaches us a moment later: the copy is kept for it, briefly.
     */
    internal fun untouched() {
        val taking = spareJob ?: return
        scope.launch {
            taking.join()
            delay(SPARE_MILLIS)
            if (spareJob === taking) dropSpare()
        }
    }

    /**
     * A horizontal drag began: the finger came down at [from] and has got to [to]. [toRight] when
     * it is moving left (revealing the right-hand page).
     */
    internal fun startDrag(toRight: Boolean, from: Offset, to: Offset) {
        grabX = from.x
        grabY = from.y
        fingerX = to.x
        fingerY = to.y
        start(if (toRight) +1 else -1, dragging = true)
    }

    /** The finger is at [to] now. */
    internal fun drag(to: Offset) {
        if (!held) return
        fingerX = to.x
        fingerY = to.y
        if (blocked) {
            // Rubber band: a fraction of the pull, and only the way that was asked.
            val give = ((fingerX - grabX) * 0.22f).coerceIn(-width * 0.12f, width * 0.12f)
            nudge = if (side > 0) give.coerceAtMost(0f) else give.coerceAtLeast(0f)
        } else if (ready) {
            hold()
        }
    }

    /**
     * The finger let go, moving at [velocity]. The turn goes through or falls back as [commit]
     * says, or else as the finger left it (see [decide]); either way the sheet carries on at the
     * speed it was let go at.
     */
    internal fun release(velocity: Velocity, commit: Boolean? = null) {
        // Another turn (a key, the voice reaching the end of the page) took over from this drag.
        if (!held) return
        finish(commit ?: decide(velocity.x), velocity)
    }

    private fun finish(commit: Boolean, velocity: Velocity) {
        held = false
        if (blocked) {
            blocked = false
            val previous = job
            job = scope.launch {
                previous?.join()
                animate(nudge, 0f, velocity.x * 0.22f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) { value, _ -> nudge = value }
                busy = false
            }
            return
        }
        if (!ready) {
            pendingCommit = commit
            pendingVelocity = velocity
            return
        }
        settle(commit, velocity)
    }

    /**
     * Whether a drag let go at [speed] turns the page. A flick decides it, either way, however
     * short; a sheet let go at rest goes through once it is a quarter of the way.
     */
    private fun decide(speed: Float): Boolean {
        val pull = (grabX - fingerX) * side
        val flick = -speed * side
        return when {
            flick > FLICK -> true
            flick < -FLICK -> false
            else -> pull > width * 0.25f
        }
    }

    private fun start(newSide: Int, dragging: Boolean) {
        settleNow()
        val n = navigator ?: return
        val view = n.view ?: return
        // The copy of this page, if there is one already: still lying over a turn that was called
        // off, or taken as the finger came down.
        val had = if (covering) above else takeSpare()
        coverJob?.cancel()
        covering = false
        side = newSide
        held = dragging
        ready = false
        landing = false
        pendingCommit = null
        busy = true
        width = view.width.toFloat().coerceAtLeast(1f)
        height = view.height.toFloat().coerceAtLeast(1f)
        rim = 18f * view.resources.displayMetrics.density
        val rtl = n.overflow.value.readingProgression == ReadingProgression.RTL
        if (!canTurn(if ((newSide > 0) != rtl) 1 else -1)) {
            // The cover of the book, front or back: nothing to turn to. The page gives a little
            // under the finger (or by itself, for a tap) and springs back.
            above = null
            blocked = true
            onBlocked()
            job = if (dragging) null else scope.launch {
                animate(nudge, -newSide * width * 0.035f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { value, _ -> nudge = value }
            }
            return
        }
        if (had != null) {
            begin(n, had)
            return
        }
        val taking = spareJob
        job = scope.launch {
            // A copy on its way (the finger moved off before it was done) is the quickest to wait for.
            taking?.join()
            val shot = takeSpare() ?: capture(view)
            if (shot == null) {
                // No snapshot (rare): fall back to Readium's own turn.
                cleanUp()
                if (!dragging) if (newSide > 0) n.goRightCompat() else n.goLeftCompat()
                return@launch
            }
            if (begin(n, shot)) pendingCommit?.let { settle(it, pendingVelocity) }
        }
    }

    /**
     * The copy is in hand. It takes the page's place, the live page jumps to its neighbour behind
     * it, and from here the sheet is the finger's to move.
     */
    private fun begin(n: EpubNavigatorFragment, shot: ImageBitmap): Boolean {
        if (side > 0) {
            // The copy of this page covers the screen; underneath, the live view jumps ahead.
            below = null
            above = shot
            if (!n.goRightCompat()) return false.also { cleanUp() }
        } else {
            // The copy lies beneath; the live view jumps back and comes in from the left.
            if (!n.goLeftCompat()) return false.also { cleanUp() }
            above = null
            below = shot
        }
        ready = true
        // Where a leaf's edge lies before the turn: flat on the right, or turned over on the left.
        val rest = if (side > 0) width else -width
        takeUp?.cancel()
        if (!held) {
            // No finger: the sheet sets off from where it lies. A leaf goes by its bottom corner.
            slack = 0f
            slackSpeed = 0f
            gap = 0f
            aboveX = 0f
            liveX = if (side > 0) 0f else -width - rim
            heldY = height
            leafX = rest
            leafY = heldY
            return true
        }
        // A sliding sheet starts under the finger, less the little the finger has already moved.
        // A leaf's edge starts where it lies, and comes to the finger.
        gap = if (style == PageTurn.CURL) rest - fingerX else grabX - fingerX
        slack = 1f
        slackSpeed = 0f
        lean = ((grabY / height - 0.5f) * 2.5f).coerceIn(-1f, 1f) * GRAB_LEAN
        hold()
        // A leaf coming back has the whole page to cross to reach the finger: it is given longer.
        val quick = if (style == PageTurn.CURL && side < 0) 700f else 1600f
        takeUp = scope.launch {
            animate(1f, 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, quick, 0.0005f)) { value, speed ->
                slack = value
                slackSpeed = speed
                hold()
            }
        }
        return true
    }

    /** Puts the sheet where the finger has it (less whatever slack is still to be taken up). */
    private fun hold() {
        val give = slack * gap
        if (style != PageTurn.CURL) {
            val x = fingerX - grabX + give
            // A page coming back sets off from out of sight, its shadow too: that leads it in.
            if (side > 0) aboveX = x.coerceIn(-width, 0f) else liveX = (x - width - rim).coerceIn(-width - rim, 0f)
            return
        }
        // The leaf's edge is at the finger. Which point of the edge decides how the fold leans:
        // one level with the finger folds it straight, one below lifts the bottom corner first.
        // It is the point the leaf was taken at (a finger moving up from there leans the fold
        // one way, down the other), and lower or higher still for a leaf taken low or high.
        val x = fingerX + give
        val reach = (width - x).coerceAtLeast(1f)
        var slope = lean + (grabY - fingerY) / reach
        // Only so far. A fold that leans lifts everything between it and the corner it leans
        // towards: far from that corner, a little pull would turn up a great wedge of the page
        // at once. So it leans as far as the leaf has come, for the way there is to the corner.
        val corner = (if (slope > 0f) height - fingerY else fingerY).coerceAtLeast(1f)
        val most = min(MAX_LEAN, reach / corner)
        slope = most * tanh(slope / most)
        // And not at all once it is nearly over: the binding it turns on is straight.
        slope *= ((x + width) / width).coerceIn(0f, 1f)
        leafX = x
        leafY = fingerY
        heldY = (fingerY + slope * reach).coerceIn(0f, height)
    }

    private fun settle(commit: Boolean, velocity: Velocity) {
        held = false
        landing = commit
        takeUp?.cancel()
        // The sheet carries on as it was going: at the finger's speed, and still catching up
        // with the finger if it hadn't quite.
        val speed = velocity.x + slackSpeed * gap
        val forward = side > 0
        val previous = job
        job = scope.launch {
            previous?.join()
            when (style) {
                PageTurn.CURL -> {
                    // The leaf ends up turned over, its edge off to the left, or lying flat again.
                    val over = forward == commit
                    val end = if (over) -width else width
                    val room = abs(end - leafX).coerceIn(1f, width * 0.3f)
                    // Aimed past where it lands, like a sheet leaving the screen (see glide), so
                    // that it comes down at speed; its edge has twice as far to go, and is aimed
                    // twice as far past.
                    AnimationState(Offset.VectorConverter, Offset(leafX, leafY), Offset(speed, velocity.y))
                        .animateTo(Offset(end * (1f + 2f * PAST), heldY), LeafSpring) {
                            val left = if (over) value.x - end else end - value.x
                            leafX = value.x
                            // What lean is left is pressed out of the leaf as it comes down.
                            leafY = heldY + (value.y - heldY) * (left / room).coerceIn(0f, 1f)
                            if (left <= 0f) cancelAnimation()
                        }
                }
                // A card may bounce: it is the next page's spring that shows.
                PageTurn.CARDS -> AnimationState(if (forward) aboveX else liveX, speed)
                    .animateTo(if (forward == commit) -width else 0f, CardSpring) { if (forward) aboveX = value else liveX = value }
                else -> {
                    // Out of sight, shadow and all.
                    val gone = -width - rim
                    if (forward) glide(aboveX, if (commit) gone else 0f, speed) { aboveX = it.coerceAtMost(0f) }
                    else glide(liveX, if (commit) 0f else gone, speed) { liveX = it.coerceAtMost(0f) }
                }
            }
            if (commit) {
                onTurned()
                cleanUp()
            } else {
                cover()
            }
        }
    }

    /**
     * Springs a sheet [from] where it is [to] where it belongs, setting off at [speed]. Paper
     * lands without a bounce, so it stops dead on getting there; and a sheet on its way off the
     * screen is aimed beyond the edge, so that it is still moving as it goes, not drawing up.
     */
    private suspend fun glide(from: Float, to: Float, speed: Float, set: (Float) -> Unit) {
        val leftwards = to < from
        val aim = if (to < 0f) to - width * PAST else to
        AnimationState(from, speed).animateTo(aim, PaperSpring) {
            val there = if (leftwards) value <= to else value >= to
            set(if (there) to else value)
            if (there) cancelAnimation()
        }
    }

    /**
     * A turn that isn't to be. The live page is sent back, which takes it a few frames, so the
     * copy lies over it at rest meanwhile: nothing shows. And Readium's word that the page has
     * changed, which comes a tenth of a second late, finds a turn still under way and stays quiet.
     */
    private fun cover() {
        val shot = above ?: below
        val n = navigator
        if (shot == null || n == null || !ready) return cleanUp()
        if (side > 0) n.goLeftCompat() else n.goRightCompat()
        ready = false
        held = false
        covering = true
        below = null
        above = shot
        aboveX = 0f
        liveX = 0f
        leafX = width
        leafY = heldY
        coverJob = scope.launch {
            delay(COVER_MILLIS)
            cleanUp()
        }
    }

    /**
     * A new touch or turn while one is still under way: that one is brought to its end at once.
     * If it was going through, it is through; if not, it goes back under its copy (see [cover]).
     */
    private fun settleNow() {
        if (!busy || covering) return
        job?.cancel()
        takeUp?.cancel()
        when {
            blocked -> {
                blocked = false
                held = false
                nudge = 0f
                busy = false
                unsettled = true
            }
            ready && !landing -> cover()
            else -> {
                if (ready) {
                    unsettled = true
                    onTurned()
                }
                cleanUp()
            }
        }
    }

    private fun cleanUp() {
        above = null
        below = null
        aboveX = 0f
        liveX = 0f
        busy = false
        ready = false
        held = false
        covering = false
    }

    /** The copy taken as the finger came down, unless it has been lying about too long to trust. */
    private fun takeSpare(): ImageBitmap? {
        val shot = spare ?: return null
        spare = null
        if (SystemClock.uptimeMillis() - spareAt <= SPARE_MILLIS) return shot
        keep(shot.asAndroidBitmap())
        return null
    }

    /** Keeps a copy that was never shown for the next one to be made in; one is enough. */
    private fun keep(bitmap: android.graphics.Bitmap) {
        if (unused == null && !bitmap.isRecycled) unused = bitmap else bitmap.recycle()
    }

    private fun dropSpare() {
        spareJob?.cancel()
        spareJob = null
        // Never shown, so the next copy can be made in it.
        spare?.asAndroidBitmap()?.let(::keep)
        spare = null
    }

    /** Copies the page on screen. Must run before anything is drawn over it. */
    private suspend fun capture(view: View): ImageBitmap? {
        if (unsettled) {
            // A turn was cut short a moment ago: the page at rest has to reach the screen first.
            awaitFrame()
            awaitFrame()
            unsettled = false
        }
        return suspendCancellableCoroutine { cont ->
            val window = view.context.findActivity()?.window
            if (window == null || view.width == 0 || view.height == 0 || !view.isAttachedToWindow) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val at = IntArray(2).also(view::getLocationInWindow)
            // One made for another size (the phone has folded or turned since) is no use any more.
            val kept = unused.also { unused = null }
            val bitmap = kept?.takeIf { it.width == view.width && it.height == view.height }
                ?: createBitmap(view.width, view.height).also { kept?.recycle() }
            runCatching {
                PixelCopy.request(
                    window, Rect(at[0], at[1], at[0] + view.width, at[1] + view.height), bitmap,
                    { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null)
                        else keep(bitmap) // No longer wanted.
                    },
                    Handler(Looper.getMainLooper()),
                )
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}

/** A finger moving faster than this (pixels a second) as it lets go has flicked the page. */
private const val FLICK = 900f

/** How far past its place a sheet leaving the screen is aimed, as a share of the page's width. */
private const val PAST = 0.15f

/** How much the fold of a leaf leans when it is taken right at the top or bottom (a slope: run over rise). */
private const val GRAB_LEAN = 0.5f

/** The most a fold leans, however the finger pulls. */
private const val MAX_LEAN = 1.2f

/** How fast a tap flicks a leaf's corner up, in page widths a second. */
private const val TAP_FLICK = 6f

/** How long the copy lies over a page that is going back: longer than Readium takes to say so. */
private const val COVER_MILLIS = 200L

/** How long a copy taken at a touch can be trusted to still be the page. */
private const val SPARE_MILLIS = 1000L

/** Paper lands without a bounce. A card may: it is the next page's spring that shows. */
private val PaperSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val LeafSpring = spring<Offset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)
private val CardSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)

private fun EpubNavigatorFragment.goRightCompat(animated: Boolean = false): Boolean =
    if (overflow.value.readingProgression == ReadingProgression.RTL) goBackward(animated) else goForward(animated)

private fun EpubNavigatorFragment.goLeftCompat(animated: Boolean = false): Boolean =
    if (overflow.value.readingProgression == ReadingProgression.RTL) goForward(animated) else goBackward(animated)

/** The page copy beneath the live page (drawn before it): the page you're leaving, turning back. */
@Composable
internal fun BoxScope.PageBelow(turner: PageTurner) {
    val below = turner.below ?: return
    // Under a leaf curling back, the copy is drawn over the live page instead (see Leaf).
    if (turner.style == PageTurn.CURL) return
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
 * Moves the live page: sliding back in over the copy (paper), or dealt back as a card; and, under
 * a card being dealt away, rising to meet it. Under a curling leaf it lies still.
 */
internal fun Modifier.livePage(turner: PageTurner): Modifier = graphicsLayer {
    val rest = turner.nudge
    val returning = turner.below != null
    when (turner.style) {
        PageTurn.CARDS -> if (returning) {
            card(1f - turner.arriving.coerceIn(0f, 1f), turner.liveX)
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
        else -> translationX = turner.liveX + rest
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
    if (style == PageTurn.CURL) {
        // The copy is the leaf going over, or the page a leaf is coming back down on.
        (above ?: turner.below)?.let { Leaf(turner, it, leaving = above != null) }
        return
    }
    if (above != null) {
        // The page underneath sits slightly in shade until the top page is out of the way.
        Box(Modifier.matchParentSize().drawBehind { drawRect(Color.Black, alpha = 0.10f * (1f - turner.leaving).coerceIn(0f, 1f)) })
        if (style == PageTurn.CARDS) {
            Image(
                above, null,
                Modifier.matchParentSize().graphicsLayer { card(turner.leaving.coerceIn(0f, 1f), turner.aboveX) },
                contentScale = ContentScale.FillBounds,
            )
        } else {
            Box(Modifier.matchParentSize().graphicsLayer { translationX = turner.aboveX }) {
                Image(above, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                EdgeShadow(Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = size.width })
            }
        }
    }
    if (turner.below != null && style == PageTurn.PAPER) {
        Box(Modifier.matchParentSize().graphicsLayer { translationX = turner.liveX + turner.width }) {
            // Shade on the old page, just right of the incoming page's edge. It comes up as that
            // page comes in and is gone as it lands: never there, or not, all at once.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val arriving = turner.arriving.coerceIn(0f, 1f)
                    drawRect(Color.Black, alpha = 0.10f * (1f - arriving) * (arriving * 12f).coerceAtMost(1f))
                },
            )
            EdgeShadow(Modifier.align(Alignment.CenterStart))
        }
    }
}

/**
 * Where a leaf folds, for [PageTurn.CURL]. The leaf is bound down the left of the page, and a
 * point of its free edge is held: one that lies [set]'s `edgeY` down the right-hand side when the
 * leaf is flat. Paper doesn't stretch, so with that point held anywhere else the leaf folds along
 * the line halfway between where the point lies at rest and where it is held; everything beyond
 * the line is turned over, back up, mirrored across it. Held level with where it rests, the fold
 * is upright; held higher or lower it leans, and a corner comes up before the rest.
 */
internal class Fold {
    /** A point on the fold, and the way straight across it towards the part turned over. */
    var midX = 0f
    var midY = 0f
    var acrossX = 1f
    var acrossY = 0f
    /** How far the part turned over reaches back from the fold; 0 when the leaf lies flat. */
    var reach = 0f
    /** How far over the leaf is: 0 lying flat, 1 turned right over. */
    var turned = 0f

    /** The leaf's held point is at ([x], [y]), on a page [width] by [height]. */
    fun set(x: Float, y: Float, edgeY: Float, width: Float, height: Float) {
        // The binding holds: no point of the leaf gets further from either end of it than it is
        // on the flat page. A finger that pulls further has the paper slip from under it, and
        // the leaf stays as near the finger as it can: drawn in towards one end of the binding
        // or the other, or else as far over (or back) as it goes.
        var px = x
        var py = y
        val topReach = hypot(width, edgeY)
        val bottomReach = hypot(width, height - edgeY)
        val top = min(1f, topReach / hypot(x, y).coerceAtLeast(0.001f))
        val bottom = min(1f, bottomReach / hypot(x, y - height).coerceAtLeast(0.001f))
        if (top < 1f || bottom < 1f) {
            when {
                hypot(x * top, y * top - height) <= bottomReach + 0.01f -> {
                    px = x * top
                    py = y * top
                }
                hypot(x * bottom, height + (y - height) * bottom) <= topReach + 0.01f -> {
                    px = x * bottom
                    py = height + (y - height) * bottom
                }
                else -> {
                    px = if (x < 0f) -width else width
                    py = edgeY
                }
            }
        }
        val pull = hypot(width - px, edgeY - py)
        if (pull < 0.5f) {
            // Where it rests: no fold, the whole leaf lies flat.
            midX = width
            midY = edgeY
            acrossX = 1f
            acrossY = 0f
            reach = 0f
            turned = 0f
            return
        }
        acrossX = (width - px) / pull
        acrossY = (edgeY - py) / pull
        midX = (width + px) / 2f
        midY = (edgeY + py) / 2f
        // The corner of the page furthest beyond the fold.
        reach = max(max(beyond(0f, 0f), beyond(width, 0f)), max(beyond(width, height), beyond(0f, height))).coerceAtLeast(0f)
        turned = (pull / (2f * width)).coerceIn(0f, 1f)
    }

    /** How far past the fold a point of the flat page is: more than 0 on the part turned over. */
    fun beyond(x: Float, y: Float): Float = (x - midX) * acrossX + (y - midY) * acrossY

    /**
     * Cuts a page [width] by [height] along the fold: [lying] is the part still flat, [lifted]
     * the part that has left the page, and [back] where that part lies now, turned over.
     */
    fun cut(width: Float, height: Float, lying: Path, lifted: Path, back: Path) {
        lying.rewind()
        lifted.rewind()
        back.rewind()
        var lyingOpen = false
        var liftedOpen = false
        // Round the page corner by corner, crossing the fold wherever an edge does.
        for (i in 0 until 4) {
            val ax = if (i == 1 || i == 2) width else 0f
            val ay = if (i >= 2) height else 0f
            val bx = if (i <= 1) width else 0f
            val by = if (i == 1 || i == 2) height else 0f
            val a = beyond(ax, ay)
            val b = beyond(bx, by)
            if (a <= 0f) {
                lying.point(ax, ay, lyingOpen)
                lyingOpen = true
            }
            if (a >= 0f) {
                lifted.point(ax, ay, liftedOpen)
                back.point(ax - 2f * a * acrossX, ay - 2f * a * acrossY, liftedOpen)
                liftedOpen = true
            }
            if ((a < 0f && b > 0f) || (a > 0f && b < 0f)) {
                val t = a / (a - b)
                val x = ax + (bx - ax) * t
                val y = ay + (by - ay) * t
                lying.point(x, y, lyingOpen)
                lifted.point(x, y, liftedOpen)
                // A point on the fold is its own mirror image.
                back.point(x, y, liftedOpen)
                lyingOpen = true
                liftedOpen = true
            }
        }
        if (lyingOpen) lying.close()
        if (liftedOpen) {
            lifted.close()
            back.close()
        }
    }

    private fun Path.point(x: Float, y: Float, open: Boolean) = if (open) lineTo(x, y) else moveTo(x, y)
}

/**
 * The leaf in mid-turn, for [PageTurn.CURL], drawn over the live page from the copy ([page]) and
 * the [Fold]. Going over ([leaving]), the copy is the leaf itself: it shows where the leaf still
 * lies flat, and the next page is uncovered beyond the fold. Coming back, the copy is the page
 * being covered: it shows beyond the fold, and the live page is the leaf lying flat. Either way
 * the part past the fold lies back over the page as its blank reverse, darker into the fold and
 * at its free edge, with the shadows it throws on both pages.
 */
@Composable
private fun BoxScope.Leaf(turner: PageTurner, page: ImageBitmap, leaving: Boolean) {
    Box(
        Modifier.matchParentSize().drawWithCache {
            val fold = Fold()
            val lying = Path()
            val lifted = Path()
            val back = Path()
            val whole = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val cast = 48.dp.toPx()
            val rim = 18.dp.toPx()
            // Longer than any line across the page.
            val far = size.width + size.height
            onDrawBehind {
                fold.set(turner.leafX, turner.leafY, turner.heldY, size.width, size.height)
                fold.cut(size.width, size.height, lying, lifted, back)
                // The page under the leaf sits slightly in shade until the leaf is out of the way.
                val shade = 0.10f * (1f - fold.turned)
                if (leaving) {
                    drawRect(Color.Black, alpha = shade)
                    clipPath(lying) { drawImage(page, dstSize = whole) }
                } else {
                    clipPath(lifted) {
                        drawImage(page, dstSize = whole)
                        drawRect(Color.Black, alpha = shade)
                    }
                }
                if (fold.reach <= 0f) return@onDrawBehind
                // How far the leaf stands off the page: its shadows come up as it lifts, and are
                // gone as it lands on either side.
                val lift = sqrt(1f - (2f * fold.turned - 1f) * (2f * fold.turned - 1f))
                val angle = atan2(fold.acrossY, fold.acrossX) * (180f / PI.toFloat())
                // One end of the fold, well off the page: bands run from there along it.
                val endX = fold.midX + far * fold.acrossY
                val endY = fold.midY - far * fold.acrossX
                clipRect {
                    // Onto the page beyond the fold, falling away from it.
                    band(LeafCast, endX, endY, angle, min(cast, fold.reach), 2f * far, lift)
                    // Onto the page it lies on, along its free edges (darkest at the edge). They
                    // are the page's own edges, seen in the mirror of the fold.
                    clipPath(lying) {
                        withTransform({
                            translate(fold.midX, fold.midY)
                            rotate(angle, Offset.Zero)
                            scale(-1f, 1f, Offset.Zero)
                            rotate(-angle, Offset.Zero)
                            translate(-fold.midX, -fold.midY)
                        }) {
                            band(LeafCast, size.width, 0f, 0f, rim, size.height, 0.7f * lift)
                            band(LeafCast, 0f, 0f, -90f, rim, size.width, 0.7f * lift)
                            band(LeafCast, size.width, size.height, 90f, rim, size.width, 0.7f * lift)
                        }
                    }
                    drawPath(back, turner.paper)
                    // The sheet's curve: light across the middle, falling off into the fold.
                    clipPath(back) {
                        band(LeafCurve, endX - fold.reach * fold.acrossX, endY - fold.reach * fold.acrossY, angle, fold.reach, 2f * far, 1f)
                    }
                }
            }
        },
    )
}

/**
 * Draws a one-unit-wide [brush] as a band starting at ([x], [y]): [width] across, the way
 * [degrees] points (0 is rightwards, 90 down), and [length] long, a quarter turn on from that.
 */
private fun DrawScope.band(brush: Brush, x: Float, y: Float, degrees: Float, width: Float, length: Float, alpha: Float) {
    withTransform({
        translate(x, y)
        rotate(degrees, Offset.Zero)
        scale(width, length, Offset.Zero)
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
    turner.touched()
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    // Fed every sample the finger sent, not just one a frame, and it knows a finger that stopped
    // before lifting from one that left at speed.
    val tracker = VelocityTracker().apply { addPointerInputChange(down) }
    var dragging = false
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            // A pinch or second finger: not a page turn.
            if (event.changes.size > 1) break
            val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
            tracker.addPointerInputChange(change)
            if (!change.pressed) {
                if (dragging) {
                    // Taken from us (the system's back swipe, say) rather than let go: no turn.
                    val taken = change.isConsumed
                    change.consume()
                    turner.release(tracker.calculateVelocity(), commit = if (taken) false else null)
                }
                break
            }
            val total = change.position - down.position
            if (!dragging) {
                // Taken already, by the swipes along the page's edges.
                if (change.isConsumed) break
                // Held still past the long-press time: that's text selection, not ours.
                if (change.uptimeMillis - down.uptimeMillis > longPress && total.getDistance() < slop * 2) break
                // Sideways, or on a slant: a leaf is as often taken by a corner and drawn up across the page.
                if (abs(total.x) > slop && abs(total.x) > abs(total.y) * 0.7f) {
                    dragging = true
                    turner.startDrag(toRight = total.x < 0, from = down.position, to = change.position)
                } else if (abs(total.y) > slop * 2) {
                    break
                }
            }
            if (dragging) {
                change.consume()
                turner.drag(change.position)
            }
        }
    } finally {
        // However the gesture ended: a drag still held falls back, a touch that never became one is forgotten.
        if (dragging) turner.release(Velocity.Zero, commit = false) else turner.untouched()
    }
}
