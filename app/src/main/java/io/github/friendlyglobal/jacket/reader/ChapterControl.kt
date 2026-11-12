package io.github.friendlyglobal.jacket.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.ui.FullHeightThumb
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.motionRemoved
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The chapter control over the foot of the page, in the one form it has at a time: the slider
 * through the book that comes with the full controls, and the steps to the previous and the next
 * chapter that the player needs on a window too narrow to have them on its bar.
 */
internal enum class ChapterForm(val slider: Boolean, val steps: Boolean) {
    /** Nothing: the controls are down, and nothing is being read aloud that needs the steps here. */
    NONE(slider = false, steps = false),

    /** The two steps around the chapter's name: all there is of it while the full controls are down. */
    PILL(slider = false, steps = true),

    /** The slider card by itself: while you read (the reading toolbar has the steps), and where the player's bar has them. */
    CARD(slider = true, steps = false),

    /** The slider card with the two steps in its title row: the full controls, on a narrow window, while listening. */
    CARD_WITH_STEPS(slider = true, steps = true),
}

/**
 * Which form the chapter control takes. The [slider] is up with the full controls (in a book with
 * more than one place to slide to). The steps are wanted here only while [listening], in a book
 * that has [steps] to take, on a window whose player can't hold them ([stepsOnBar]); they then
 * ride in the slider's card when it is up, so there is never a second chapter control beside it.
 */
internal fun chapterForm(slider: Boolean, listening: Boolean, steps: Boolean, stepsOnBar: Boolean): ChapterForm {
    val stepsHere = listening && steps && !stepsOnBar
    return when {
        slider && stepsHere -> ChapterForm.CARD_WITH_STEPS
        slider -> ChapterForm.CARD
        stepsHere -> ChapterForm.PILL
        else -> ChapterForm.NONE
    }
}

/** The pill is never wider than this, however long the chapter's name. */
private val PillWidest = 320.dp

/** The card is never wider than this (its margins included), however wide the window. */
private val CardWidest = 560.dp

/** The card's margin from the window's edges. */
private val CardMargin = 16.dp

/** Room between the control and the toolbar under it: as a pill, and as a card. */
private val PillGap = 10.dp
private val CardGap = 12.dp

/** Inside the card: its sides, above its title, and under its slider. */
private val CardSide = 20.dp
private val CardTop = 14.dp
private val CardFoot = 6.dp

/** The same with the steps in the title row: they are 48dp targets, whose icons then line up with the slider's ends. */
private val SteppedSide = 8.dp
private val SteppedTop = 4.dp

/** Between a step and the words beside it. */
private val WordGap = 4.dp

/** A step's size where it isn't there to be measured. */
private val StepTarget = 48.dp

/** The card floats a little above the page; the pill lies flat. */
private val CardElevation = 3.dp

/** A step arrives at this much of its size. */
private const val STEP_FROM = 0.6f

/** The percent starts to show with the surface this far grown from the pill into the card, and is all there by this far. */
private const val PERCENT_FROM = 0.4f
private const val PERCENT_BY = 0.8f

/** A step's icon, inside its target; and the room beside it at which it is fully there. */
private val StepIcon = 24.dp
private val StepRoom = 8.dp

/** The parts of the control, for laying them out. */
private enum class Part { PREVIOUS, NEXT, PILL_NAME, CARD_NAME, PERCENT, SLIDER }

/**
 * The chapter control, between "Back to the voice" and the toolbar. It is one surface: the pill of
 * chapter steps while the full controls are down, which grows into the slider's card when they
 * come up (the steps move out to the card's corners, the slider unfolds under them) and shrinks
 * back into the pill when they go. So there is one chapter control on the screen, never two.
 *
 * [chapters] are the steps a narrow player needs here; null for a book with none.
 */
@Composable
internal fun ColumnScope.ChapterControl(
    form: ChapterForm,
    nav: ReaderNav,
    book: Book,
    chapters: ChapterSteps?,
    haptics: Haptics,
    /** What a screen reader or a switch can do to the page from the slider: turn it, and send the controls away. */
    pageActions: List<CustomAccessibilityAction>,
) {
    val motion = MaterialTheme.motionScheme
    // Leaving, it goes in the form it had, with the steps it had.
    val last = remember { Last(form, chapters) }
    if (form != ChapterForm.NONE) last.form = form
    if (chapters != null) last.steps = chapters
    val shown = last.form
    // A pill that comes up by itself, onto a toolbar that is already there, follows the player in
    // (as the voice's name does: see PlayerLine). One that the card shrinks into is there at once.
    val up by produceState(form != ChapterForm.NONE, form) {
        if (form == ChapterForm.PILL && !value && !motionRemoved()) delay(2 * ARRIVAL_STAGGER_MS)
        value = form != ChapterForm.NONE
    }
    // By itself the card springs up off the toolbar and drops back onto it, moving and scaling only;
    // the pill, a small thing, unfolds from the toolbar and folds away.
    val enter = if (shown.slider) {
        slideInVertically(motion.fastSpatialSpec()) { it / 3 } +
            scaleIn(motion.fastSpatialSpec(), initialScale = 0.9f, transformOrigin = BottomMiddle) + fadeIn(motion.fastEffectsSpec())
    } else {
        fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.7f) +
            slideInVertically(motion.fastSpatialSpec()) { it / 2 } + expandVertically(motion.defaultSpatialSpec())
    }
    val exit = if (shown.slider) {
        slideOutVertically(motion.fastSpatialSpec()) { it / 3 } +
            scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f, transformOrigin = BottomMiddle) + fadeOut(motion.fastEffectsSpec())
    } else {
        shrinkVertically(motion.fastSpatialSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.7f) + fadeOut(motion.fastEffectsSpec())
    }
    AnimatedVisibility(visible = up && form != ChapterForm.NONE, enter = enter, exit = exit, label = "chapter-control") {
        ChapterSurface(shown, nav, book, last.steps, haptics, pageActions)
    }
}

/** The form the control last had, and its steps: what it is drawn as while it leaves. */
private class Last(var form: ChapterForm, var steps: ChapterSteps?)

/**
 * The surface itself, in [form] (never [ChapterForm.NONE]) or on its way there. Going from one
 * form to another nothing is swapped: the surface, and each thing on it, moves from where it is in
 * the one to where it is in the other on the theme's spring, and what only one of them has fades.
 * Only where things sit and how see-through they are change on the way, so it runs at the phone's
 * full frame rate.
 */
@Composable
private fun ChapterSurface(
    form: ChapterForm,
    nav: ReaderNav,
    book: Book,
    chapters: ChapterSteps?,
    haptics: Haptics,
    pageActions: List<CustomAccessibilityAction>,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    // How far it is a card (0 the pill, 1 the card), and how far the steps are in (0 without, 1 with).
    val grown = animateFloatAsState(if (form.slider) 1f else 0f, motion.defaultSpatialSpec(), label = "chapter-card")
    val stepped = animateFloatAsState(if (form.steps) 1f else 0f, motion.defaultSpatialSpec(), label = "chapter-steps")
    // What only the card has, and what only the steps bring, fade in and out.
    val cardShows = animateFloatAsState(if (form.slider) 1f else 0f, motion.defaultEffectsSpec(), label = "chapter-card-shows")
    val stepsShow = animateFloatAsState(if (form.steps) 1f else 0f, motion.defaultEffectsSpec(), label = "chapter-steps-show")
    val container = animateColorAsState(
        if (form.slider) colors.surfaceContainerHigh else colors.secondaryContainer, motion.defaultEffectsSpec(), label = "chapter-container",
    )
    val content = animateColorAsState(
        if (form.slider) colors.onSurface else colors.onSecondaryContainer, motion.defaultEffectsSpec(), label = "chapter-content",
    )
    // Each part is there while its form is, and until it has left.
    val cardLeaving by remember { derivedStateOf { grown.value > GONE } }
    val stepsLeaving by remember { derivedStateOf { stepped.value > GONE } }
    val hasCard = form.slider || cardLeaving
    val hasSteps = chapters != null && (form.steps || stepsLeaving)

    // Where a drag on the slider would land, while it is being dragged: the card names that place.
    val preview = remember { mutableStateOf<Float?>(null) }
    val pillName = chapters?.title.orEmpty()
    // The pill and the card name the same chapter in most books; in a PDF with an outline the card
    // names the page. The same words stay as they are on the way; different ones fade over.
    val sameName = pillName == nav.title
    val corner = (MaterialTheme.shapes.extraLarge as? CornerBasedShape)?.topStart

    Layout(
        content = {
            CompositionLocalProvider(LocalContentColor provides content.value) {
                // In this order: it is the order a keyboard's Tab takes them in.
                if (hasSteps && chapters != null) {
                    StepButton(chapters, -1, haptics, Modifier.layoutId(Part.PREVIOUS))
                    StepButton(chapters, 1, haptics, Modifier.layoutId(Part.NEXT))
                    if (pillName.isNotBlank()) {
                        Text(
                            pillName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            // In the card the slider says the chapter: it isn't said a second time.
                            modifier = Modifier.layoutId(Part.PILL_NAME).then(if (form.slider) Modifier.clearAndSetSemantics {} else Modifier),
                        )
                    }
                }
                if (hasCard) {
                    CardName(nav, preview, Modifier.layoutId(Part.CARD_NAME))
                    CardPercent(nav, book, preview, Modifier.layoutId(Part.PERCENT))
                    PlaceSlider(nav, book, haptics, pageActions, preview, Modifier.layoutId(Part.SLIDER))
                }
            }
        },
        modifier = Modifier
            // Its parts are read out together, in the order they are in.
            .semantics { isTraversalGroup = true }
            .graphicsLayer {
                val g = grown.value
                val width = size.width - 2 * cardMargin(g)
                val height = size.height - gapBelow(g)
                val round = lerp(height / 2, corner?.toPx(Size(width, height), this) ?: (height / 2), g.coerceIn(0f, 1f))
                shape = SurfaceShape(width, height, round)
                // What is on the card and not on the pill shows only as far as the surface has grown over it.
                clip = true
                shadowElevation = CardElevation.toPx() * g.coerceIn(0f, 1f)
            }
            .drawBehind { drawRect(container.value) },
    ) { measurables, constraints ->
        val g = grown.value
        // (The spring runs a little past where it is going: the steps' room in the card doesn't.)
        val s = stepped.value.coerceIn(0f, 1f)
        val room = constraints.maxWidth
        val target = StepTarget.roundToPx()
        val gap = WordGap.roundToPx()
        val previous = measurables.part(Part.PREVIOUS)?.measure(Constraints())
        val next = measurables.part(Part.NEXT)?.measure(Constraints())
        val previousWidth = previous?.width ?: target
        val nextWidth = next?.width ?: target
        val stepHeight = maxOf(previous?.height ?: target, next?.height ?: target)

        // The pill: the two steps around the name, and no wider than they need. (On the narrowest
        // windows it keeps the card's margin from the edges, rather than run from one to the other.)
        val pillRoom = minOf(PillWidest.roundToPx(), room - 2 * CardMargin.roundToPx())
        val pillWords = measurables.part(Part.PILL_NAME)?.measure(Constraints(maxWidth = (pillRoom - previousWidth - nextWidth - 2 * gap).coerceAtLeast(0)))
        val pillWidth = previousWidth + nextWidth + (pillWords?.let { it.width + 2 * gap } ?: 0)
        val pillHeight = maxOf(stepHeight, pillWords?.height ?: 0)

        // The card: as wide as the window lets it be. Its title row is the name and the percent,
        // between the two steps when it has them, and the slider runs under it.
        val cardWidth = (minOf(CardWidest.roundToPx(), room) - 2 * CardMargin.roundToPx()).coerceAtLeast(0)
        val side = CardSide.roundToPx()
        val percent = measurables.part(Part.PERCENT)?.measure(Constraints())
        val slider = measurables.part(Part.SLIDER)?.measure(Constraints.fixedWidth((cardWidth - 2 * side).coerceAtLeast(0)))
        val percentWidth = percent?.width ?: 0
        val nameStart = lerp(side, SteppedSide.roundToPx() + previousWidth + gap, s)
        val percentEnd = lerp(side, SteppedSide.roundToPx() + nextWidth + gap, s)
        // (Between the steps the name has less room and is cut short more often: it then stops a little short of the percent.)
        val nameEnd = percentEnd + percentWidth + lerp(0, StepRoom.roundToPx(), s)
        val cardWords = measurables.part(Part.CARD_NAME)?.measure(Constraints(maxWidth = (cardWidth - nameStart - nameEnd).coerceAtLeast(0)))
        val words = maxOf(cardWords?.height ?: 0, percent?.height ?: 0)
        val rowTop = lerp(CardTop.roundToPx(), SteppedTop.roundToPx(), s)
        val rowHeight = lerp(words, maxOf(words, stepHeight), s)
        val cardHeight = rowTop + rowHeight + (slider?.height ?: 0) + CardFoot.roundToPx()

        // And wherever it is between the two.
        val margin = cardMargin(g)
        val wide = (lerp(pillWidth.toFloat(), cardWidth.toFloat(), g) + 2 * margin).roundToInt().coerceIn(0, room)
        val high = (lerp(pillHeight.toFloat(), cardHeight.toFloat(), g) + gapBelow(g)).roundToInt().coerceAtLeast(0)
        val width = wide - 2 * margin
        layout(wide, high) {
            val stepInset = lerp(0f, SteppedSide.toPx(), g)
            // Everything in the title row is centred on one line: the pill's own middle, or the row's.
            val middle = lerp(pillHeight / 2f, rowTop + rowHeight / 2f, g)
            val nameAt = margin + lerp((previousWidth + gap).toFloat(), nameStart.toFloat(), g)
            val percentInset = lerp((nextWidth + gap).toFloat(), percentEnd.toFloat(), g)
            // The steps: at the pill's two ends, and out at the card's corners. Arriving in a card
            // that had none, each grows into the room the words beside it make for it, and shows
            // once there is room: never on top of the name or the percent.
            val stepScale = lerp(STEP_FROM, 1f, stepped.value.coerceAtLeast(0f))
            val icon = StepIcon.toPx() / 2 * stepScale
            val previousRoom = ((nameAt - margin - stepInset - previousWidth / 2f - icon) / StepRoom.toPx()).coerceIn(0f, 1f)
            val nextRoom = ((percentInset - stepInset - nextWidth / 2f - icon) / StepRoom.toPx()).coerceIn(0f, 1f)
            previous?.placeRelativeWithLayer((margin + stepInset).roundToInt(), (middle - previous.height / 2f).roundToInt()) {
                alpha = stepsShow.value * previousRoom
                scaleX = stepScale
                scaleY = stepScale
            }
            next?.placeRelativeWithLayer((margin + width - stepInset - nextWidth).roundToInt(), (middle - next.height / 2f).roundToInt()) {
                alpha = stepsShow.value * nextRoom
                scaleX = stepScale
                scaleY = stepScale
            }
            // The name starts beside the first step, wherever that is; the card's own takes over as it grows.
            // (The same words change hands halfway, unseen. Different ones fade through: the one out, then the other in.)
            pillWords?.placeRelativeWithLayer(nameAt.roundToInt(), (middle - pillWords.height / 2f).roundToInt()) {
                alpha = if (sameName) (if (cardShows.value < 0.5f) 1f else 0f) else (1f - 2f * cardShows.value).coerceIn(0f, 1f)
            }
            cardWords?.placeRelativeWithLayer(nameAt.roundToInt(), (middle - cardWords.height / 2f).roundToInt()) {
                alpha = when {
                    pillWords == null -> cardShows.value
                    sameName -> if (cardShows.value < 0.5f) 0f else 1f
                    else -> (2f * cardShows.value - 1f).coerceIn(0f, 1f)
                }
            }
            // The percent keeps beside the last step. In the pill there is no room for it after the
            // name: it shows once the surface has grown wide enough to part the two.
            val percentRoom = ((g - PERCENT_FROM) / (PERCENT_BY - PERCENT_FROM)).coerceIn(0f, 1f)
            percent?.placeRelativeWithLayer(
                (margin + width - percentInset - percentWidth).roundToInt(),
                (middle - percent.height / 2f).roundToInt(),
            ) { alpha = cardShows.value * percentRoom }
            // The slider hangs under the title row: it comes up from behind the pill's bottom edge as the card grows.
            slider?.placeRelativeWithLayer(
                (margin + (width - slider.width) / 2f).roundToInt(),
                lerp(pillHeight.toFloat(), (rowTop + rowHeight).toFloat(), g).roundToInt(),
            ) { alpha = cardShows.value }
        }
    }
}

/** A part counts as gone once it is this near to it. */
private const val GONE = 0.01f

private fun List<Measurable>.part(part: Part): Measurable? = firstOrNull { it.layoutId == part }

/** The card's margin either side, [grown] this far from the pill (which has none). */
private fun Density.cardMargin(grown: Float): Float = CardMargin.toPx() * grown.coerceIn(0f, 1f)

/** The room kept under the surface, [grown] this far from the pill. */
private fun Density.gapBelow(grown: Float): Float = lerp(PillGap.toPx(), CardGap.toPx(), grown.coerceIn(0f, 1f))

/** The surface's outline: [width] by [height] with [round] corners, at the top of its space and in the middle of it across. */
private class SurfaceShape(private val width: Float, private val height: Float, private val round: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = (size.width - width) / 2
        val corner = round.coerceIn(0f, (minOf(width, height) / 2).coerceAtLeast(0f))
        return Outline.Rounded(RoundRect(left, 0f, left + width.coerceAtLeast(0f), height.coerceAtLeast(0f), corner, corner))
    }
}

/** A step to the previous ([by] -1) or the next (+1) chapter. */
@Composable
private fun StepButton(chapters: ChapterSteps, by: Int, haptics: Haptics, modifier: Modifier = Modifier) {
    IconButton(onClick = { haptics.tick(); chapters.onStep(by) }, enabled = chapters.canStep(by), modifier = modifier) {
        Icon(
            if (by < 0) JacketIcons.ChapterBack else JacketIcons.ChapterForward,
            stringResource(if (by < 0) chapters.previousLabel else chapters.nextLabel),
        )
    }
}

/** Where you are, by name, on the card: or where the slider would land, while it is dragged. For the eye (the slider says it). */
@Composable
private fun CardName(nav: ReaderNav, preview: MutableState<Float?>, modifier: Modifier = Modifier) {
    val landing = preview.value?.roundToInt()
    Text(
        if (landing != null) nav.sectionName(landing) else nav.title,
        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** How far through the book that is. For the eye too. */
@Composable
private fun CardPercent(nav: ReaderNav, book: Book, preview: MutableState<Float?>, modifier: Modifier = Modifier) {
    val landing = preview.value?.roundToInt()
    val last = (nav.sectionCount - 1).coerceAtLeast(1)
    Text(
        stringResource(R.string.percent, if (landing != null) landing * 100 / last else book.percent),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** Drag through the book, with a tick per chapter and a preview of where you'll land. */
@Composable
private fun PlaceSlider(
    nav: ReaderNav,
    book: Book,
    haptics: Haptics,
    pageActions: List<CustomAccessibilityAction>,
    preview: MutableState<Float?>,
    modifier: Modifier = Modifier,
) {
    val last = (nav.sectionCount - 1).coerceAtLeast(1)
    val landing = preview.value?.roundToInt()
    val target = landing ?: nav.section
    val slider = remember(nav.sectionCount) {
        SliderState(nav.section.toFloat(), if (nav.sectionCount in 3..60) nav.sectionCount - 2 else 0, 0f..last.toFloat())
    }
    // Follow page turns while not being dragged.
    LaunchedEffect(nav.section) { if (preview.value == null) slider.value = nav.section.toFloat() }
    // Gone mid-drag, it leaves no landing place behind for the card to name.
    DisposableEffect(Unit) { onDispose { preview.value = null } }
    // To a screen reader the slider speaks for the whole card: what it is, then how far through
    // the book and in which chapter ("Place in the book, 42% read, Chapter 3"), not a bare
    // "420". The words above it are the same thing, for the eye.
    val percent = if (landing != null) landing * 100 / last else book.percent
    val chapter = if (landing != null) nav.sectionName(landing) else nav.title
    val place = stringResource(R.string.reader_place_in_book)
    val where = listOf(stringResource(R.string.reader_percent_read, percent), chapter.trim())
        .filter { it.isNotEmpty() }.joinToString(stringResource(R.string.list_separator))
    Box(modifier) {
        val touch = remember { MutableInteractionSource() }
        Slider(
            state = slider,
            interactionSource = touch,
            thumb = { FullHeightThumb(touch) },
            onValueChange = { value ->
                if (nav.sectionGroup(value.roundToInt()) != nav.sectionGroup(target)) haptics.tick()
                preview.value = value
                slider.value = value
            },
            onValueChangeFinished = {
                preview.value?.let { nav.jumpTo(it.roundToInt()) }
                preview.value = null
                haptics.confirm()
            },
            modifier = Modifier.semantics {
                contentDescription = place
                stateDescription = where
                customActions = pageActions
            },
        )
        // Chapter starts, as small marks along the track.
        val marks = nav.marks
        if (marks.size in 2..80) {
            val onActive = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            val onInactive = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            Canvas(Modifier.matchParentSize()) {
                val inset = 8.dp.toPx()
                val radius = 1.5.dp.toPx()
                val current = slider.value / last
                for (mark in marks) {
                    if (mark <= 0.002f || mark >= 0.998f) continue
                    val x = inset + mark * (size.width - 2 * inset)
                    drawCircle(if (mark < current) onActive else onInactive, radius, Offset(x, size.height / 2))
                }
            }
        }
    }
}
