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
import io.github.friendlyglobal.jacket.importer.BookImporter
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.MorphBadge
import io.github.friendlyglobal.jacket.ui.motionRemoved
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

private const val PAGES = 3

/**
 * The welcome, shown once before the first book: what Jacket is for, how pages turn, and the way
 * in. Three pages to swipe through, each with a small moving picture; the last opens the file
 * picker. Its words are the library's and the settings' own, so it says nothing they don't.
 */
@Composable
fun Onboarding(onImport: (List<Uri>) -> Unit, onDone: () -> Unit) {
    val haptics = rememberHaptics()
    val pager = rememberPagerState { PAGES }
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
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            WelcomePage(page, pager)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageDots(pager)
            Spacer(Modifier.weight(1f))
            // The button changes its mind on the last page: from moving on to letting you in.
            val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = pager.currentPage == PAGES - 1,
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                contentAlignment = Alignment.CenterEnd,
                label = "welcome-action",
            ) { last ->
                if (last) {
                    Button(onClick = { haptics.tick(); picker.launch(BookImporter.MIME_TYPES) }, shapes = ButtonDefaults.shapes()) {
                        Icon(JacketIcons.Add, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.library_add_books))
                    }
                } else {
                    Button(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(page: Int, pager: PagerState) {
    // The picture plays while its page is the one in front.
    val active = pager.currentPage == page
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            // The picture travels a little further than its words as pages change places.
            Modifier.size(260.dp, 240.dp).graphicsLayer {
                val away = (pager.currentPage - page) + pager.currentPageOffsetFraction
                translationX = -away * 96.dp.toPx()
                val near = 1f - abs(away).coerceAtMost(1f)
                alpha = near
                scaleX = 0.85f + 0.15f * near
                scaleY = 0.85f + 0.15f * near
            },
            contentAlignment = Alignment.Center,
        ) {
            when (page) {
                0 -> WearPicture(active)
                1 -> TurnPicture(active)
                else -> AddPicture(active)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(
                when (page) {
                    0 -> R.string.library_empty_title
                    1 -> R.string.settings_group_reading
                    else -> R.string.library_add_books
                },
            ),
            style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                when (page) {
                    0 -> R.string.library_empty_message
                    1 -> R.string.reading_layout_hint
                    else -> R.string.onboarding_private
                },
            ),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
    }
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
    LaunchedEffect(active) {
        turn.snapTo(0f)
        if (!active || motionRemoved()) return@LaunchedEffect
        while (true) {
            delay(900)
            turn.animateTo(1f, tween(1_000, easing = FastOutSlowInEasing))
            delay(300)
            turn.snapTo(0f)
        }
    }
    val paper = MaterialTheme.colorScheme.surfaceContainerHigh
    val leaf = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.outlineVariant
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
private fun PageDots(pager: PagerState) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(width = (PAGES * 8 + (PAGES - 1) * 8 + 16).dp, height = 8.dp)) {
        val dot = 8.dp.toPx()
        val at = pager.currentPage + pager.currentPageOffsetFraction
        var x = 0f
        for (page in 0 until PAGES) {
            val here = (1f - abs(at - page)).coerceIn(0f, 1f)
            val width = dot + 16.dp.toPx() * here
            drawRoundRect(off, Offset(x, 0f), Size(width, dot), CornerRadius(dot / 2f))
            drawRoundRect(on, Offset(x, 0f), Size(width, dot), CornerRadius(dot / 2f), alpha = here)
            x += width + dot
        }
    }
}
