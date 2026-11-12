package io.github.friendlyglobal.jacket.ui

import android.app.Activity
import androidx.activity.compose.PredictiveBackHandler
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.graphics.shapes.Morph
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import io.github.friendlyglobal.jacket.data.PageColor
import io.github.friendlyglobal.jacket.data.ReadingFont
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

// ---------------------------------------------------------------------------------------------
// Haptics: one vocabulary for the whole app, mapped onto the platform's expressive feedback types.
// ---------------------------------------------------------------------------------------------

class Haptics(private val feedback: HapticFeedback) {
    /** A discrete step: page turn, choosing an option. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** Fine-grained steps, e.g. dragging a slider through positions. */
    fun frequentTick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /** Crossing a boundary, e.g. into the next chapter. */
    fun threshold() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

// ---------------------------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------------------------

/** Scopes for the cover's shared-element flight between the library and the reader. */
class CoverTransition(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalCoverTransition = compositionLocalOf<CoverTransition?> { null }

/** Corner radius of a cover in flight, and of the book lying closed in the reader. */
val CoverCorner = 12.dp

/**
 * The places a book's cover can sit in the library. The book you're reading has both at once, so
 * each has its own key, and the reader's cover is told which one it flies from and back to.
 */
enum class CoverSpot { HERO, TILE }

/** The library spot the open book's cover came from, or is about to fly home to. */
val LocalCoverSpot = compositionLocalOf { CoverSpot.HERO }

/**
 * Marks a book cover so it flies between screens, on the theme's spatial spring: in the library,
 * the cover at [spot]; in the reader, the cover bound for it.
 *
 * In flight the cover is drawn scaled, which would scale any rounding inside it too: keep the
 * resting clip outside this modifier and let [clipInFlight] round the flying cover instead.
 */
@Composable
fun Modifier.sharedCover(bookId: String, spot: CoverSpot, clipInFlight: Boolean = true): Modifier {
    val transition = LocalCoverTransition.current ?: return this
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val bounds = remember(spatial) {
        // The same spring, but done once the cover is within a pixel: left to itself it keeps
        // "moving" invisibly for another third of a second, and the book waits for it to land.
        val landing = (spatial as? SpringSpec<Rect>)
            ?.let { spring(it.dampingRatio, it.stiffness, visibilityThreshold = Rect(1f, 1f, 1f, 1f)) }
            ?: spatial
        BoundsTransform { _, _ -> landing }
    }
    return with(transition.shared) {
        val state = rememberSharedContentState("cover-$spot-$bookId")
        if (clipInFlight) {
            val clip = remember(this) { OverlayClip(RoundedCornerShape(CoverCorner)) }
            this@sharedCover.sharedElement(state, transition.visibility, bounds, clipInOverlayDuringTransition = clip)
        } else {
            this@sharedCover.sharedElement(state, transition.visibility, bounds)
        }
    }
}

/**
 * Android's "Remove animations" setting (animator duration scale 0). Compose already snaps its
 * animations then; choreography built on delays should not wait around either.
 */
suspend fun motionRemoved(): Boolean = coroutineContext[MotionDurationScale]?.scaleFactor == 0f

/**
 * Squash-and-spring on press, with the theme's expressive spatial spring. [held] keeps it
 * squashed after the finger has gone: a cover stays picked up while its menu is open.
 */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.94f, held: Boolean = false): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed || held) pressed else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Predictive back for a whole screen: it shrinks and rounds its corners under the swipe. Carried
 * through, [onBack] takes over; let go, the screen springs back to full size.
 */
@Composable
fun Modifier.predictiveBack(onBack: () -> Unit): Modifier {
    val swiped = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler { events ->
        try {
            events.collect { swiped.snapTo(it.progress) }
            back()
        } catch (e: CancellationException) {
            // This coroutine is on its way out; the spring back needs one that isn't.
            scope.launch { swiped.animateTo(0f, spring) }
            throw e
        }
    }
    return graphicsLayer {
        val progress = swiped.value
        val scale = 1f - 0.08f * progress
        scaleX = scale
        scaleY = scale
        clip = progress > 0f
        shape = if (progress > 0f) RoundedCornerShape((32 * progress).dp) else RectangleShape
    }
}

/**
 * A badge in one of the [androidx.compose.material3.MaterialShapes], which morphs on the theme's
 * spatial spring whenever it is handed another. [rotation] (degrees) is read while drawing, so a
 * turning badge redraws without recomposing.
 */
@Composable
fun MorphBadge(
    shape: RoundedPolygon,
    color: Color,
    modifier: Modifier = Modifier,
    rotation: () -> Float = { 0f },
    content: @Composable BoxScope.() -> Unit = {},
) {
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val outline = remember { MorphingOutline(shape) }
    LaunchedEffect(shape) { outline.morphTo(shape, spec) }
    Box(modifier.drawBehind { outline.draw(this, color, rotation()) }, contentAlignment = Alignment.Center, content = content)
}

/** The outline of a [MorphBadge]: a path that is reused, so morphing and turning allocate nothing. */
private class MorphingOutline(initial: RoundedPolygon) {
    private var target = initial
    private var morph: Morph? = null
    private val progress = Animatable(1f)
    private val path = Path()
    private val matrix = android.graphics.Matrix()

    suspend fun morphTo(shape: RoundedPolygon, spec: AnimationSpec<Float>) {
        if (shape === target) return
        // A morph cut short starts over from the shape it was heading for.
        morph = Morph(target, shape)
        target = shape
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
        morph = null
    }

    fun draw(scope: DrawScope, color: Color, degrees: Float) = with(scope) {
        val outline = path.asAndroidPath()
        outline.rewind()
        // Read every time, so a morph starting redraws this. The spring may carry past the target
        // shape and back: that is its bounce.
        val morphed = progress.value
        morph?.toPath(morphed, outline) ?: target.toPath(outline)
        matrix.setScale(size.width, size.height)
        if (degrees != 0f) matrix.postRotate(degrees, size.width / 2f, size.height / 2f)
        outline.transform(matrix)
        drawPath(path, color)
    }
}

/**
 * An angle (degrees) that turns steadily while [turning], once every [periodMillis]. Stopping, it
 * runs on to the next quarter turn and rests there, which is where a square (or any shape with
 * its symmetry) looks the way it started. Nothing runs while it rests, and it never starts with
 * animations removed.
 */
@Composable
fun rememberTurn(turning: Boolean, periodMillis: Int = 16_000): State<Float> {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(turning) {
        if (!turning) {
            angle.animateTo(ceil(angle.value / 90f) * 90f, spring(stiffness = Spring.StiffnessLow))
            angle.snapTo(0f)
            return@LaunchedEffect
        }
        if (motionRemoved()) return@LaunchedEffect
        while (true) {
            angle.snapTo(angle.value % 360f)
            angle.animateTo(angle.value + 360f, tween(periodMillis, easing = LinearEasing))
        }
    }
    return angle.asState()
}

// ---------------------------------------------------------------------------------------------
// Connected button group (single choice)
// ---------------------------------------------------------------------------------------------

@Composable
fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable (T) -> Unit)? = null,
    /** The check on the chosen option. Four words in a row on a folded phone need its room; the fill and shape still say which. */
    check: Boolean = true,
) {
    val haptics = rememberHaptics()
    // One choice out of these: a screen reader counts them ("2 of 4") and knows picking one unpicks the rest.
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (!checked) {
                        haptics.tick()
                        onSelect(option)
                    }
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                if (checked && check) {
                    Icon(JacketIcons.Check, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(6.dp))
                } else if (leading != null && !checked) {
                    leading(option)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
fun ColorSwatch(color: Color) {
    Box(
        Modifier.size(14.dp).clip(CircleShape).background(color).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

// ---------------------------------------------------------------------------------------------
// Reading: page colors and fonts
// ---------------------------------------------------------------------------------------------

@Immutable
data class PageColors(val background: Color, val text: Color, val secondary: Color, val link: Color)

@Composable
fun rememberPageColors(choice: PageColor): PageColors {
    val scheme = MaterialTheme.colorScheme
    return when (choice) {
        PageColor.BOOK -> PageColors(scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, scheme.primary)
        PageColor.PAPER -> PageColors(Color(0xFFF4ECDB), Color(0xFF3A2F23), Color(0xFF7B6B57), Color(0xFF8C5A2B))
        PageColor.DARK -> PageColors(Color(0xFF1E1D1B), Color(0xFFDDD6CA), Color(0xFF9D968B), Color(0xFFE3B86C))
        PageColor.BLACK -> PageColors(Color.Black, Color(0xFFCAC5BD), Color(0xFF86817A), Color(0xFFE3B86C))
    }
}

/** Swatch shown for each page color in pickers. */
fun PageColor.swatch(book: Color): Color = when (this) {
    PageColor.BOOK -> book
    PageColor.PAPER -> Color(0xFFF4ECDB)
    PageColor.DARK -> Color(0xFF1E1D1B)
    PageColor.BLACK -> Color.Black
}

/** Compose font families for the bundled reading fonts (files in assets). */
@Composable
fun rememberReadingFontFamily(font: ReadingFont): FontFamily {
    val assets = LocalContext.current.assets
    return remember(font) {
        when (font) {
            ReadingFont.LITERATA -> FontFamily(
                Font("fonts/Literata.ttf", assets, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
                Font("fonts/Literata.ttf", assets, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
                Font("fonts/Literata-Italic.ttf", assets, FontWeight.Normal, FontStyle.Italic, FontVariation.Settings(FontVariation.weight(400))),
            )
            ReadingFont.HYPERLEGIBLE -> FontFamily(
                Font("fonts/AtkinsonHyperlegible-Regular.ttf", assets, FontWeight.Normal),
                Font("fonts/AtkinsonHyperlegible-Bold.ttf", assets, FontWeight.Bold),
                Font("fonts/AtkinsonHyperlegible-Italic.ttf", assets, FontWeight.Normal, FontStyle.Italic),
            )
            // Bundled with Readium, which serves it to EPUB pages too.
            ReadingFont.OPEN_DYSLEXIC -> FontFamily(Font("readium/fonts/OpenDyslexic-Regular.otf", assets))
            ReadingFont.SERIF, ReadingFont.ORIGINAL -> FontFamily.Serif
            ReadingFont.SANS -> FontFamily.SansSerif
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Window: immersive reading
// ---------------------------------------------------------------------------------------------

/** Hides the status and navigation bars while [hidden]; a swipe from the edge reveals them briefly. */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden, view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller?.hide(WindowInsetsCompat.Type.systemBars()) else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Whether this phone folds: it has a hinge to measure. */
fun Context.hasHinge(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

/**
 * True while a foldable is folded shut, going by its hinge; always false on a phone without one.
 * The hinge is only listened to while [watch]: it reports when it moves, and costs nothing still.
 */
@Composable
fun rememberFoldedShut(watch: Boolean): State<Boolean> {
    val context = LocalContext.current
    val shut = remember { mutableStateOf(false) }
    DisposableEffect(context, watch) {
        val sensors = context.getSystemService(SensorManager::class.java)
        // Phones differ on whether the hinge is a wake-up sensor: take whichever there is.
        val hinge = if (watch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sensors != null) {
            sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
                ?: sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, true)
                ?: sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, false)
        } else {
            null
        }
        if (sensors == null || hinge == null) {
            shut.value = false
            return@DisposableEffect onDispose {}
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                shut.value = event.values[0] < SHUT_BELOW_DEGREES
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensors.registerListener(listener, hinge, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensors.unregisterListener(listener) }
    }
    return shut
}

/**
 * The fold's bounds in the window while the phone sits half-open with its fold lying flat (on a
 * table, like a laptop); null in any other posture, on other phones, or while not [watch]ed.
 */
@Composable
fun rememberTabletopFold(watch: Boolean): State<IntRect?> {
    val activity = LocalContext.current.findActivity()
    return produceState<IntRect?>(null, activity, watch) {
        value = null
        if (activity == null || !watch) return@produceState
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layout ->
            val fold = layout.displayFeatures.firstNotNullOfOrNull { it as? FoldingFeature }
            value = fold
                ?.takeIf { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL }
                ?.bounds?.let { IntRect(it.left, it.top, it.right, it.bottom) }
        }
    }
}

/** A hinge this far closed is shut (they don't all read exactly zero). */
private const val SHUT_BELOW_DEGREES = 20f

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
