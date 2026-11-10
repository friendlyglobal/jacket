package io.github.friendlyglobal.jacket.reader

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.pressScale
import io.github.friendlyglobal.jacket.ui.rememberTurn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Book
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.outer.OuterCoverHost
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberReadingFontFamily
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme
import kotlinx.coroutines.delay

/**
 * The book's cover over the whole reader, while the phone is folded shut on an open book and
 * "Cover when closed" is on: the same cover, stages and ambient state as the outer screen shows
 * while you read inside.
 *
 * It is never a dead end. A tap brings up what can be done from here: read on this screen after
 * all, have the book read aloud, or go back to the library; and, while it is being read aloud,
 * the sentence being spoken and the player, so listening works with the phone shut. Another tap,
 * or a while untouched, gives the screen back to the cover. Back closes the panel, then the book.
 */
@Composable
internal fun BoxScope.ClosedCover(
    visible: Boolean,
    book: Book,
    nav: ReaderNav,
    settings: Settings,
    onRate: (Float) -> Unit,
    haptics: Haptics,
    onReadHere: () -> Unit,
    onLeave: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.slowEffectsSpec()),
        exit = fadeOut(motion.defaultEffectsSpec()),
        modifier = Modifier.matchParentSize(),
    ) {
        val controller = nav.listenController
        val listening = nav.listen.active && controller != null
        var panel by remember { mutableStateOf(false) }
        var dimmed by remember { mutableStateOf(false) }
        // Back steps out the way it came in: the panel first, then the book.
        BackHandler { if (panel) panel = false else onLeave() }
        // When the panel was last touched (uptime): it bows out after a while left alone.
        val touched = remember { longArrayOf(0L) }
        LaunchedEffect(panel) {
            if (!panel) return@LaunchedEffect
            touched[0] = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - touched[0] < PANEL_STAYS_MS) delay(1_000)
            panel = false
        }
        Box(Modifier.fillMaxSize()) {
            OuterCoverHost(
                onTap = {
                    haptics.tick()
                    panel = !panel
                },
                // The cover stays lit behind the panel; it dims again once that has gone.
                keepLit = panel,
                onDimmed = { dimmed = it },
            )
            // Listening with the phone shut (in a pocket, at the gym): play and pause sit on the
            // cover itself, big enough to hit without looking. They go dark with the cover.
            JacketTheme(seed = book.seedColor, darkTheme = true, animated = false) {
                AnimatedVisibility(
                    visible = listening && !dimmed && !panel,
                    enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.7f) + fadeIn(motion.defaultEffectsSpec()),
                    exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.85f) + fadeOut(motion.fastEffectsSpec()),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    if (controller != null) Medallion(nav, controller, haptics)
                }
            }
            // The panel wears the cover's colors: dark, from the book.
            JacketTheme(seed = book.seedColor, darkTheme = true, animated = false) {
                AnimatedVisibility(
                    visible = panel,
                    enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
                    exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                    touched[0] = SystemClock.uptimeMillis()
                                }
                            }
                        },
                    ) {
                        Column(
                            Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // The player and the plain choices change places as listening starts and stops.
                            AnimatedContent(
                                targetState = listening,
                                transitionSpec = {
                                    (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())).using(SizeTransform(clip = false))
                                },
                                label = "closed-cover-panel",
                            ) { playing ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (playing && controller != null) {
                                        SpokenPhrase(nav.listen, settings)
                                        Spacer(Modifier.height(24.dp))
                                        ListenBar(
                                            nav.listen, controller, settings.listenRate, onRate, haptics,
                                            progress = nav.section.toFloat() / (nav.sectionCount - 1).coerceAtLeast(1),
                                            hasChapters = nav.toc.isNotEmpty(),
                                        )
                                        Spacer(Modifier.height(12.dp))
                                    } else if (controller != null) {
                                        Button(
                                            onClick = {
                                                haptics.confirm()
                                                controller.start()
                                            },
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                        ) {
                                            Icon(JacketIcons.Headphones, null, Modifier.size(ButtonDefaults.IconSize))
                                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                            Text(stringResource(R.string.reader_listen))
                                        }
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    // Always there: read on this screen after all, or leave the book.
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                haptics.tick()
                                                onReadHere()
                                            },
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Icon(JacketIcons.Book, null, Modifier.size(ButtonDefaults.IconSize))
                                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                            Text(stringResource(R.string.library_menu_open), maxLines = 1)
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                haptics.tick()
                                                onLeave()
                                            },
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Icon(JacketIcons.Back, null, Modifier.size(ButtonDefaults.IconSize))
                                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                            Text(stringResource(R.string.library_title), maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The player on the cover: the play button (a cookie turning while the voice speaks, a square at
 * rest) inside a ring that is the chapter, wavy and running while it plays, with a sentence back
 * and forward on either side. Over a soft shadow, so it reads on any cover.
 */
@Composable
private fun Medallion(nav: ReaderNav, controller: ListenController, haptics: Haptics) {
    val playing = nav.listen.playing
    val turn = rememberTurn(playing)
    val press = remember { MutableInteractionSource() }
    val thickness = with(LocalDensity.current) { 9.dp.toPx() }
    val stroke = remember(thickness) { Stroke(width = thickness, cap = StrokeCap.Round) }
    val place = animateFloatAsState(
        if (nav.chapterProgress >= 0f) nav.chapterProgress else nav.section.toFloat() / (nav.sectionCount - 1).coerceAtLeast(1),
        WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "place",
    )
    Row(
        Modifier.drawBehind {
            drawCircle(
                Brush.radialGradient(0f to Color.Black.copy(alpha = 0.62f), 0.7f to Color.Black.copy(alpha = 0.4f), 1f to Color.Transparent),
                radius = size.minDimension * 0.9f,
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { haptics.tick(); controller.skip(-1) }, modifier = Modifier.size(64.dp)) {
            Icon(JacketIcons.SkipBack, stringResource(controller.previousLabel), Modifier.size(30.dp))
        }
        Spacer(Modifier.width(16.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(
                progress = { place.value },
                modifier = Modifier.size(208.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.24f),
                stroke = stroke,
                trackStroke = stroke,
                amplitude = { if (playing) 1f else 0.35f },
                wavelength = 34.dp,
                waveSpeed = if (playing) 34.dp else 0.dp,
            )
            MorphBadge(
                shape = if (playing) MaterialShapes.Cookie9Sided else MaterialShapes.Square,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(132.dp)
                    .pressScale(press, pressed = 0.9f)
                    .clip(CircleShape)
                    .clickable(interactionSource = press, indication = null, role = Role.Button) {
                        haptics.toggle(!playing)
                        controller.toggle()
                    },
                rotation = { turn.value },
            ) {
                Icon(
                    if (playing) JacketIcons.Pause else JacketIcons.Play,
                    if (playing) stringResource(R.string.listen_pause) else stringResource(R.string.listen_play),
                    Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        FilledTonalIconButton(onClick = { haptics.tick(); controller.skip(1) }, modifier = Modifier.size(64.dp)) {
            Icon(JacketIcons.SkipForward, stringResource(controller.nextLabel), Modifier.size(30.dp))
        }
    }
}

/** How long the panel stays over the cover after its last touch. */
private const val PANEL_STAYS_MS = 12_000L

/**
 * The sentence being read, in the book's own typeface, its words turning from soft to full as
 * they're spoken (as they do on the page). One sentence gives way to the next with a fade.
 */
@Composable
internal fun SpokenPhrase(listen: ListenState, settings: Settings) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val family = rememberReadingFontFamily(settings.font)
    AnimatedContent(
        targetState = listen.phrase,
        transitionSpec = { (fadeIn(effects) togetherWith fadeOut(effects)).using(SizeTransform(clip = false)) },
        modifier = Modifier.fillMaxWidth(),
        label = "phrase",
    ) { phrase ->
        // A sentence on its way out is wholly spoken.
        val spoken = if (phrase == listen.phrase) listen.spoken.coerceIn(0, phrase.length) else phrase.length
        val unread = SpanStyle(color = LocalContentColor.current.copy(alpha = 0.45f))
        Text(
            buildAnnotatedString {
                append(phrase)
                if (spoken < phrase.length) addStyle(unread, spoken, phrase.length)
            },
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = family),
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
