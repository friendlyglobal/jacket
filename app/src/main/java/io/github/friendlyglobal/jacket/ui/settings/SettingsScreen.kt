package io.github.friendlyglobal.jacket.ui.settings

import android.content.Intent
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.reader.ReadingControls
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import kotlinx.coroutines.CancellationException

private const val SOURCE_URL = "https://github.com/friendlyglobal/jacket"

@Composable
fun SettingsScreen(
    state: LibraryState,
    outerAvailable: Boolean,
    onSettings: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
) {
    val settings = state.settings
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val toggle = { on: Boolean, change: (Settings) -> Settings ->
        haptics.toggle(on)
        onSettings(change)
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scroll.nestedScrollConnection)
            .graphicsLayer {
                val s = 1f - 0.08f * backProgress
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape((32 * backProgress).dp)
                clip = backProgress > 0f
            },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                subtitle = { Text("Profile, cover screen and reading") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()

            item { ProfileCard(state, column) }

            item { GroupTitle("Cover screen", column) }
            item {
                Group(column) {
                    val count = 5
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(0, count),
                        leadingContent = { Icon(JacketIcons.OuterScreen, null) },
                        supportingContent = {
                            Text(
                                if (outerAvailable) "Your phone can show the cover on its outer screen while you read inside."
                                else "Unfold a book-style foldable to use its outer screen. Flip phones use the widget below.",
                            )
                        },
                    ) { Text(if (outerAvailable) "Outer screen ready" else "Outer screen not available right now") }
                    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(1, count)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("Widget style")
                            Text(
                                "For the Galaxy Z Flip cover screen and home screens",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            ConnectedChoice(
                                options = CoverStyle.entries, selected = settings.coverStyle, label = { it.label },
                                onSelect = { style -> onSettings { it.copy(coverStyle = style) } },
                            )
                        }
                    }
                    SwitchRow(2, count, "Reading progress", "A slim progress bar and percentage on the cover", settings.showProgress) {
                        toggle(it) { s -> s.copy(showProgress = it) }
                    }
                    SwitchRow(3, count, "Ambient dim", "The outer screen dims after 30 seconds untouched", settings.ambientDim) {
                        toggle(it) { s -> s.copy(ambientDim = it) }
                    }
                    SwitchRow(4, count, "Cover while reading", "Put the cover on the outer screen whenever you unfold to read", settings.autoOuterScreen) {
                        toggle(it) { s -> s.copy(autoOuterScreen = it) }
                    }
                }
            }
            item {
                Hint(
                    "Galaxy Z Flip: add “Jacket cover” under Settings › Cover screen › Widgets. " +
                        "Motorola Razr: open Jacket from the external display.",
                    column,
                )
            }

            item { GroupTitle("Reading", column) }
            item { ReadingControls(settings, onSettings, column) }

            item { GroupTitle("Appearance", column) }
            item {
                Group(column) {
                    SwitchRow(
                        0, 1, "Colors from your book",
                        "Tint Jacket with the cover of the book you're reading. Off: use your phone's palette.",
                        settings.bookColors,
                    ) { toggle(it) { s -> s.copy(bookColors = it) } }
                }
            }

            item { GroupTitle("About", column) }
            item {
                Group(column) {
                    val version = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
                    }
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(0, 3),
                        leadingContent = { Icon(JacketIcons.Info, null) },
                        supportingContent = { Text("Version $version") },
                    ) { Text("Jacket") }
                    SegmentedListItem(
                        onClick = {
                            haptics.tick()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())) }
                        },
                        shapes = ListItemDefaults.segmentedShapes(1, 3),
                        leadingContent = { Icon(JacketIcons.Code, null) },
                        supportingContent = { Text("Open source on GitHub") },
                    ) { Text("Source code") }
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(2, 3),
                        leadingContent = { Icon(JacketIcons.Book, null) },
                        supportingContent = { Text("App: Apache 2.0. Readium toolkit: BSD 3-Clause. Literata, Atkinson Hyperlegible and OpenDyslexic: SIL Open Font License.") },
                    ) { Text("Licenses") }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(state: LibraryState, modifier: Modifier) {
    val books = state.books
    val finished = books.count { it.progress >= 0.99f }
    val reading = books.count { !it.isNew && it.progress < 0.99f }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.padding(top = 8.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(JacketIcons.Person, null, tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Your library", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    listOf(
                        if (books.size == 1) "1 book" else "${books.size} books",
                        "$reading reading",
                        "$finished finished",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.current?.let {
                    Text("On your cover: ${it.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp),
    )
}

@Composable
private fun Group(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { content() }
}

@Composable
private fun SwitchRow(index: Int, count: Int, title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    { Icon(JacketIcons.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        },
    ) { Text(title) }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
    )
}
