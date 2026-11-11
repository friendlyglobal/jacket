package io.github.friendlyglobal.jacket.ui.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.PageColor
import io.github.friendlyglobal.jacket.data.PageTurn
import io.github.friendlyglobal.jacket.data.ReadingFont
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.ui.ColorSwatch
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.ui.rememberPageColors
import io.github.friendlyglobal.jacket.ui.rememberReadingFontFamily
import io.github.friendlyglobal.jacket.ui.rememberTurn
import io.github.friendlyglobal.jacket.ui.swatch
import io.github.friendlyglobal.jacket.ui.voices.VoicePicker
import io.github.friendlyglobal.jacket.ui.voices.rememberVoicePreviewer
import io.github.friendlyglobal.jacket.ui.voices.voiceLabel
import io.github.friendlyglobal.jacket.voices
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** The welcome's pages. Which of them a phone sees, and in what order, depends on whether it folds. */
private enum class Welcome { COVER, READ, LISTEN, YOURS, START }

/**
 * The welcome, shown once before the first book. A phone that folds hears about the cover on its
 * outer screen first; any other phone starts with reading and listening, and is never told about
 * screens it doesn't have. Then a few choices to make it yours (page, typeface, page turn, voice),
 * and the way in: your own books, or a sample to try straight away. Four pages at most, each with
 * a small moving picture, all of it skippable.
 */
@Composable
fun Onboarding(
    foldable: Boolean,
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onSample: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = rememberHaptics()
    val pages = remember(foldable) {
        if (foldable) listOf(Welcome.COVER, Welcome.READ, Welcome.YOURS, Welcome.START)
        else listOf(Welcome.READ, Welcome.LISTEN, Welcome.YOURS, Welcome.START)
    }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    // Done once the picker comes back, with books or without: leaving sooner would lose its answer.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
        onDone()
    }
    // Back steps back a page before it leaves the app.
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
    // A tick as each page settles into place.
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { haptics.tick() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { haptics.tick(); onDone() }) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            when (val page = pages[index]) {
                Welcome.YOURS -> YoursPage(index, pager, settings, onSettings)
                else -> WelcomePage(page, index, pager, foldable)
            }
        }
        // The foot of the page changes its mind on the last one: from moving on to letting you in.
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = pager.currentPage == pages.lastIndex,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            label = "welcome-action",
        ) { last ->
            if (last) {
                // Two ways in, side by side where there's room and one above the other where there isn't.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { haptics.confirm(); onSample() }, shapes = ButtonDefaults.shapes()) {
                        Icon(JacketIcons.Book, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.library_try_sample))
                    }
                    Button(onClick = { haptics.tick(); picker.launch(BookImporter.MIME_TYPES) }, shapes = ButtonDefaults.shapes()) {
                        Icon(JacketIcons.Add, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.library_add_books))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PageDots(pager, pages.size)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
        }
    }
}

/** A picture that travels a little further than its words as pages change places. */
private fun Modifier.parallax(index: Int, pager: PagerState): Modifier = graphicsLayer {
    val away = (pager.currentPage - index) + pager.currentPageOffsetFraction
    translationX = -away * 96.dp.toPx()
    val near = 1f - abs(away).coerceAtMost(1f)
    alpha = near
    scaleX = 0.85f + 0.15f * near
    scaleY = 0.85f + 0.15f * near
}

@Composable
private fun WelcomePage(page: Welcome, index: Int, pager: PagerState, foldable: Boolean) {
    // The picture plays while its page is the one in front.
    val active = pager.currentPage == index
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(260.dp, 240.dp).parallax(index, pager), contentAlignment = Alignment.Center) {
            when (page) {
                Welcome.COVER -> WearPicture(active)
                Welcome.READ -> TurnPicture(active)
                Welcome.LISTEN -> ListenPicture(active)
                else -> AddPicture(active)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(
                when (page) {
                    Welcome.COVER -> R.string.library_empty_title
                    Welcome.READ -> R.string.onboarding_read_title
                    Welcome.LISTEN -> R.string.onboarding_listen_title
                    else -> R.string.onboarding_start_title
                },
            ),
            style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                when (page) {
                    Welcome.COVER -> R.string.onboarding_cover_body
                    // A phone that folds has had its first page on the cover: reading and listening share this one.
                    Welcome.READ -> if (foldable) R.string.onboarding_read_listen_body else R.string.onboarding_read_body
                    Welcome.LISTEN -> R.string.onboarding_listen_body
                    else -> R.string.onboarding_private
                },
            ),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(20.dp))
        // What the page is about, in a word or two each.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            when (page) {
                Welcome.COVER -> {
                    Pill(JacketIcons.OuterScreen, stringResource(R.string.onboarding_cover_outside))
                    Pill(JacketIcons.Book, stringResource(R.string.settings_cover_when_closed))
                    Pill(JacketIcons.TextFormat, stringResource(R.string.settings_tabletop))
                }
                Welcome.READ -> {
                    PageTurn.entries.filter { it != PageTurn.SLIDE }.forEach { Pill(null, stringResource(it.label)) }
                    Pill(JacketIcons.Highlight, stringResource(R.string.selection_highlight))
                    Pill(JacketIcons.Note, stringResource(R.string.selection_note))
                }
                Welcome.LISTEN -> {
                    Pill(JacketIcons.Voice, stringResource(R.string.voice_natural))
                    Pill(JacketIcons.Bedtime, stringResource(R.string.listen_sleep_timer))
                }
                else -> Pill(JacketIcons.Widgets, stringResource(R.string.onboarding_widget))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A word or two on what a page offers, with a small icon. */
@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * "Make it yours": the page's colour, the typeface, how pages turn and the voice, each a tap, with
 * a line of text that wears the choices as they're made. Nothing here has to be touched.
 */
@Composable
private fun YoursPage(index: Int, pager: PagerState, settings: Settings, onSettings: ((Settings) -> Settings) -> Unit) {
    val haptics = rememberHaptics()
    val page = rememberPageColors(settings.pageColor)
    val previewer = rememberVoicePreviewer()
    val voices = LocalContext.current.voices
    val language = LocalConfiguration.current.locales[0]?.toLanguageTag()
    var choosingVoice by remember { mutableStateOf(false) }
    // Leaving the page hushes a voice being tried.
    LaunchedEffect(pager.currentPage) { if (pager.currentPage != index) previewer.stop() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp)) {
            // The page as it will look: it takes each choice as it's made.
            val paper by animateColorAsState(page.background, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "paper")
            val ink by animateColorAsState(page.text, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "ink")
            Surface(
                color = paper, shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().parallax(index, pager),
            ) {
                Text(
                    stringResource(R.string.onboarding_yours_sample),
                    fontFamily = rememberReadingFontFamily(settings.font), fontSize = 22.sp, lineHeight = 32.sp, color = ink,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.onboarding_yours_title), style = MaterialTheme.typography.headlineMediumEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onboarding_yours_body),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            YoursLabel(stringResource(R.string.reading_page))
            val bookSurface = MaterialTheme.colorScheme.surface
            ConnectedChoice(
                options = PageColor.entries,
                selected = settings.pageColor,
                label = { stringResource(it.label) },
                onSelect = { choice -> onSettings { it.copy(pageColor = choice) } },
                leading = { ColorSwatch(it.swatch(bookSurface)) },
                check = false,
            )

            YoursLabel(stringResource(R.string.reading_typeface))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingFont.entries.forEach { font ->
                    val chosen = settings.font == font
                    ToggleButton(
                        checked = chosen,
                        onCheckedChange = {
                            if (!chosen) {
                                haptics.tick()
                                onSettings { it.copy(font = font) }
                            }
                        },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    ) { Text(stringResource(font.label), fontFamily = rememberReadingFontFamily(font), maxLines = 1) }
                }
            }

            YoursLabel(stringResource(R.string.reading_page_turn))
            ConnectedChoice(
                options = PageTurn.entries,
                selected = settings.pageTurn,
                label = { stringResource(it.label) },
                onSelect = { choice -> onSettings { it.copy(pageTurn = choice) } },
                check = false,
            )

            if (voices.packs.isNotEmpty()) {
                YoursLabel(stringResource(R.string.voice_title))
                val ref = remember(language, settings.voice, settings.voices) { voices.choose(language, settings) }
                val hearing = ref != null && previewer.playing == ref.toString()
                Surface(
                    onClick = { haptics.tick(); choosingVoice = true },
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ref != null) {
                            // Hear it before anything else: this is what reading aloud will sound like.
                            FilledIconButton(onClick = { haptics.tick(); previewer.toggle(ref, language) }) {
                                Icon(
                                    if (hearing) JacketIcons.Stop else JacketIcons.Play,
                                    if (hearing) stringResource(R.string.voice_stop_sample)
                                    else stringResource(R.string.voice_hear, voiceLabel(language, settings)),
                                )
                            }
                        } else {
                            Icon(JacketIcons.Volume, null, Modifier.padding(12.dp))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(voiceLabel(language, settings), style = MaterialTheme.typography.bodyLarge)
                            if (ref != null) {
                                Text(
                                    stringResource(R.string.voice_natural),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(stringResource(R.string.voice_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (choosingVoice) {
        ModalBottomSheet(onDismissRequest = { choosingVoice = false }) {
            VoicePicker(
                language = language, settings = settings, onSettings = onSettings,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun YoursLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
    )
}

/** A folded phone putting a cover on: the book lands on its screen, and the wave draws itself in. */
@Composable
private fun WearPicture(active: Boolean) {
    val landed = animateFloatAsState(if (active) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "wear")
    Box(
        Modifier
            .height(232.dp)
            .aspectRatio(0.5f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF101213))
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primaryContainer)) {
            MorphBadge(
                MaterialShapes.Cookie9Sided, MaterialTheme.colorScheme.primary,
                Modifier.align(Alignment.Center).size(64.dp).graphicsLayer {
                    val down = landed.value
                    translationY = (down - 1f) * 56.dp.toPx()
                    alpha = down.coerceIn(0f, 1f)
                    val scale = 0.6f + 0.4f * down
                    scaleX = scale
                    scaleY = scale
                },
            ) { Icon(painterResource(R.drawable.ic_brand_mark), null, Modifier.height(30.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            LinearWavyProgressIndicator(
                progress = { 0.46f * landed.value.coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 16.dp).fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
                waveSpeed = 0.dp,
            )
        }
    }
}

/** An open book whose right-hand page keeps turning over onto the left. */
@Composable
private fun TurnPicture(active: Boolean) {
    val turn = remember { Animatable(0f) }
    // A marker drawn across a line of the left-hand page, between turns: highlights.
    val marked = remember { Animatable(0f) }
    LaunchedEffect(active) {
        turn.snapTo(0f)
        marked.snapTo(0f)
        if (!active) return@LaunchedEffect
        if (motionRemoved()) {
            marked.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
            delay(500)
            marked.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            delay(700)
            turn.animateTo(1f, tween(1_000, easing = FastOutSlowInEasing))
            marked.snapTo(0f)
            delay(300)
            turn.snapTo(0f)
        }
    }
    val paper = MaterialTheme.colorScheme.surfaceContainerHigh
    val leaf = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.size(248.dp, 172.dp)) {
        val spine = size.width / 2f
        val gap = 3.dp.toPx()
        val page = Size(spine - gap, size.height)
        val corner = CornerRadius(14.dp.toPx())
        fun lines(left: Float, width: Float, alpha: Float) {
            val inset = width * 0.14f
            for (line in 0 until 7) {
                val y = size.height * (0.16f + 0.1f * line)
                val length = (width - 2 * inset) * if (line == 6) 0.55f else 1f
                drawRoundRect(ink, Offset(left + inset, y), Size(length, 5.dp.toPx()), CornerRadius(3.dp.toPx()), alpha = alpha)
            }
        }
        drawRoundRect(paper, Offset.Zero, page, corner)
        drawRoundRect(paper, Offset(spine + gap, 0f), page, corner)
        if (marked.value > 0f) {
            val inset = page.width * 0.14f
            drawRoundRect(
                marker, Offset(inset - 3.dp.toPx(), size.height * 0.36f - 4.dp.toPx()),
                Size((page.width - 2 * inset + 6.dp.toPx()) * marked.value, 13.dp.toPx()), CornerRadius(4.dp.toPx()), alpha = 0.34f,
            )
        }
        lines(0f, page.width, 1f)
        lines(spine + gap, page.width, 1f)
        // The leaf swings on the spine: seen edge-on halfway, then lying on the left page.
        val swing = cos(turn.value * PI).toFloat()
        if (turn.value > 0f && turn.value < 1f) {
            val width = page.width * abs(swing)
            val left = if (swing >= 0f) spine + gap else spine - gap - width
            drawRoundRect(Color.Black, Offset(left + 4.dp.toPx(), 4.dp.toPx()), Size(width, size.height), corner, alpha = 0.12f)
            drawRoundRect(leaf, Offset(left, 0f), Size(width, size.height), corner)
            if (width > page.width * 0.3f) lines(left, width, abs(swing))
        }
    }
}

/**
 * A page being read aloud: its words take their ink one by one as the voice reaches them, under a
 * play button that turns while it reads.
 */
@Composable
private fun ListenPicture(active: Boolean) {
    val spoken = remember { Animatable(0f) }
    LaunchedEffect(active) {
        spoken.snapTo(0f)
        if (!active) return@LaunchedEffect
        if (motionRemoved()) {
            spoken.snapTo(0.55f)
            return@LaunchedEffect
        }
        while (true) {
            delay(400)
            spoken.animateTo(1f, tween(5_200, easing = LinearEasing))
            delay(900)
            spoken.snapTo(0f)
        }
    }
    val turn = rememberTurn(active)
    val paper = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = MaterialTheme.colorScheme.primary
    Box(Modifier.size(232.dp, 196.dp)) {
        Canvas(Modifier.size(232.dp, 172.dp)) {
            drawRoundRect(paper, Offset.Zero, size, CornerRadius(18.dp.toPx()))
            // Lines of words of different lengths, like a paragraph.
            val words = WORD_LINES.sumOf { it.size }
            val reached = spoken.value * words
            var n = 0
            val left = 22.dp.toPx()
            val high = 9.dp.toPx()
            WORD_LINES.forEachIndexed { line, lengths ->
                var x = left
                val y = 26.dp.toPx() + line * 24.dp.toPx()
                for (length in lengths) {
                    val width = length.dp.toPx()
                    val said = (reached - n).coerceIn(0f, 1f)
                    // The word being spoken is marked; the ones to come wait in pale ink.
                    if (said > 0f && said < 1f) {
                        drawRoundRect(mark, Offset(x - 3.dp.toPx(), y - 4.dp.toPx()), Size(width + 6.dp.toPx(), high + 8.dp.toPx()), CornerRadius(5.dp.toPx()), alpha = 0.24f)
                    }
                    drawRoundRect(ink, Offset(x, y), Size(width, high), CornerRadius(4.dp.toPx()), alpha = 0.28f + 0.62f * said)
                    x += width + 8.dp.toPx()
                    n++
                }
            }
        }
        MorphBadge(
            MaterialShapes.Cookie9Sided, MaterialTheme.colorScheme.primary,
            Modifier.align(Alignment.BottomEnd).padding(end = 14.dp).size(60.dp),
            rotation = { turn.value },
        ) { Icon(JacketIcons.Play, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimary) }
    }
}

/** The words of [ListenPicture]'s page: each line a list of word widths (dp). */
private val WORD_LINES = listOf(
    listOf(34, 52, 22, 46),
    listOf(48, 20, 58, 30),
    listOf(26, 44, 36, 24, 30),
    listOf(56, 28, 40),
    listOf(30, 50, 22),
)

/** A badge that can't settle on a shape: the book to come. */
@Composable
private fun AddPicture(active: Boolean) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active || motionRemoved()) return@LaunchedEffect
        while (true) {
            delay(1_500)
            step++
        }
    }
    val shapes = remember {
        listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie6Sided, MaterialShapes.SoftBurst)
    }
    MorphBadge(shapes[step % shapes.size], MaterialTheme.colorScheme.primaryContainer, Modifier.size(188.dp)) {
        Icon(painterResource(R.drawable.ic_brand_mark), null, Modifier.height(72.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Where you are among the pages: the dot for this one stretches into a pill as it arrives. */
@Composable
private fun PageDots(pager: PagerState, pages: Int) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(width = (pages * 8 + (pages - 1) * 8 + 16).dp, height = 8.dp)) {
        val dot = 8.dp.toPx()
        val at = pager.currentPage + pager.currentPageOffsetFraction
        var x = 0f
        for (page in 0 until pages) {
            val here = (1f - abs(at - page)).coerceIn(0f, 1f)
            val width = dot + 16.dp.toPx() * here
            drawRoundRect(off, Offset(x, 0f), Size(width, dot), CornerRadius(dot / 2f))
            drawRoundRect(on, Offset(x, 0f), Size(width, dot), CornerRadius(dot / 2f), alpha = here)
            x += width + dot
        }
    }
}
