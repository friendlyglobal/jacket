package io.github.friendlyglobal.jacket.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.pressScale
import io.github.friendlyglobal.jacket.ui.rememberReadingFontFamily
import io.github.friendlyglobal.jacket.ui.rememberTurn
import io.github.friendlyglobal.jacket.ui.theme.JacketTheme

/**
 * The book's cover over the whole reader, while the phone is folded shut on an open book and
 * "Cover when closed" is on: the same cover, stages and ambient state as the outer screen shows
 * while you read inside.
 *
 * While the book is read aloud, the strip along the bottom of the cover is the player, and the
 * only one on it: the chapter, the place in it, and play, pause and a sentence back or forward,
 * big enough to hit without looking (in a pocket, at the gym). See [CoverControls].
 *
 * It is never a dead end. A tap on the cover brings up what can be done from here: read on this
 * screen after all, have the book read aloud, or go back to the library; and, while it is being
 * read aloud, the sentence being spoken and the whole player (speed, sleep timer, stop). The panel
 * stays until it is sent away: by another tap on the cover, by Back, or by choosing to read here
 * or to leave. Back closes the panel, then the book.
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
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.slowEffectsSpec()),
        exit = fadeOut(motion.defaultEffectsSpec()),
        modifier = modifier.matchParentSize(),
    ) {
        val controller = nav.listenController
        val listening = nav.listen.active && controller != null
        var panel by remember { mutableStateOf(false) }
        // Back steps out the way it came in: the panel first, then the book.
        BackHandler { if (panel) panel = false else onLeave() }
        Box(Modifier.fillMaxSize()) {
            OuterCoverHost(
                onTap = {
                    haptics.tick()
                    panel = !panel
                },
                // The cover stays lit behind the panel; it dims again once that has gone.
                keepLit = panel,
                // Listening with the phone shut: the cover's own strip is the player. It goes dark
                // with the cover, and the panel slides up over it.
                controls = controller?.let { player -> { CoverControls(nav.listen, player, haptics, covered = panel) } },
                // This book's cover, whichever the library counts as the one being read.
                bookId = book.id,
            )
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
                        modifier = Modifier.fillMaxWidth(),
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
                                            chapters = nav.chapterSteps(),
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
 * The player's buttons on the cover, under the chapter and its wave (the cover's listening strip
 * lays them out): play and pause, with a sentence back and forward on either side. While the panel
 * is up they are [covered] by it, and out of a screen reader's way: its player is the one in reach.
 */
@Composable
private fun CoverControls(listen: ListenState, controller: ListenController, haptics: Haptics, covered: Boolean) {
    Row(
        if (covered) Modifier.clearAndSetSemantics {} else Modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverSkipButton(JacketIcons.SkipBack, stringResource(controller.previousLabel)) {
            haptics.tick()
            controller.skip(-1)
        }
        CoverPlayButton(listen, controller, haptics)
        CoverSkipButton(JacketIcons.SkipForward, stringResource(controller.nextLabel)) {
            haptics.tick()
            controller.skip(1)
        }
    }
}

/**
 * Play and pause on the cover: the reader's own play button, large. A cookie turning slowly while
 * the voice speaks, a square at rest; a loading shape where the pause bars will be while the voice
 * gets ready. Only this button follows the voice's state, so nothing around it is redrawn with it.
 */
@Composable
private fun CoverPlayButton(listen: ListenState, controller: ListenController, haptics: Haptics) {
    val playing = listen.playing
    val preparing = playing && listen.preparing
    val motion = MaterialTheme.motionScheme
    val turn = rememberTurn(playing)
    val press = remember { MutableInteractionSource() }
    MorphBadge(
        shape = if (playing) MaterialShapes.Cookie9Sided else MaterialShapes.Square,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(CoverPlaySize)
            .pressScale(press, pressed = 0.9f)
            .clickable(interactionSource = press, indication = null, role = Role.Button) {
                haptics.toggle(!playing)
                controller.toggle()
            },
        rotation = { turn.value },
    ) {
        val effects = motion.fastEffectsSpec<Float>()
        val spatial = motion.fastSpatialSpec<Float>()
        AnimatedContent(
            targetState = when {
                preparing -> CoverGlyph.PREPARING
                playing -> CoverGlyph.PAUSE
                else -> CoverGlyph.PLAY
            },
            transitionSpec = { (scaleIn(spatial, initialScale = 0.6f) + fadeIn(effects)) togetherWith (scaleOut(spatial, targetScale = 0.6f) + fadeOut(effects)) },
            contentAlignment = Alignment.Center,
            label = "cover-play",
        ) { glyph ->
            val tint = MaterialTheme.colorScheme.onPrimary
            when (glyph) {
                CoverGlyph.PREPARING -> {
                    val label = stringResource(R.string.listen_preparing)
                    LoadingIndicator(Modifier.size(CoverPlayIcon).semantics { contentDescription = label }, color = tint)
                }
                CoverGlyph.PAUSE -> Icon(JacketIcons.Pause, stringResource(R.string.listen_pause), Modifier.size(CoverPlayIcon), tint = tint)
                CoverGlyph.PLAY -> Icon(JacketIcons.Play, stringResource(R.string.listen_play), Modifier.size(CoverPlayIcon), tint = tint)
            }
        }
    }
}

/** What the cover's play button shows. */
private enum class CoverGlyph { PLAY, PAUSE, PREPARING }

/** A sentence (or paragraph) back or forward: white on a soft disc, like the strip's own words, on any cover. */
@Composable
private fun CoverSkipButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White),
        modifier = Modifier.size(CoverSkipSize),
    ) {
        Icon(icon, label, Modifier.size(28.dp))
    }
}

/** Big enough to hit without looking. */
private val CoverPlaySize = 96.dp
private val CoverPlayIcon = 44.dp
private val CoverSkipSize = 64.dp

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
