package io.github.friendlyglobal.jacket.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.predictiveBack
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One open-source component Jacket ships, from assets/licenses/libraries.json. */
private class Component(val name: String, val license: String, val url: String, val copyright: String)

private fun loadComponents(context: Context): List<Component> = runCatching {
    val array = JSONArray(context.assets.open("licenses/libraries.json").bufferedReader().use { it.readText() })
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Component(o.getString("name"), o.getString("license"), o.optString("url"), o.optString("copyright"))
    }
}.getOrDefault(emptyList())

/** Jacket's own license and every open-source component inside it, with full license texts. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val components by produceState(emptyList<Component>()) { value = withContext(Dispatchers.IO) { loadComponents(context) } }
    var open by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).predictiveBack(onBack),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                subtitle = { Text(stringResource(R.string.licenses_subtitle)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, stringResource(R.string.common_back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            item {
                Entry(
                    stringResource(R.string.app_name), "Apache-2.0", "Copyright 2026 the Jacket authors", "jacket-LICENSE.txt",
                    expanded = open == "Jacket", modifier = column,
                ) { haptics.tick(); open = if (open == "Jacket") null else "Jacket" }
            }
            items(components, key = { it.name }) { c ->
                Entry(
                    c.name, c.license, c.copyright, licenseFile(c),
                    expanded = open == c.name, modifier = column,
                ) { haptics.tick(); open = if (open == c.name) null else c.name }
            }
        }
    }
}

private fun licenseFile(c: Component) =
    if (c.name.startsWith("Readium Kotlin")) "readium-LICENSE.txt" else "${c.license}.txt"

@Composable
private fun Entry(
    name: String,
    license: String,
    copyright: String,
    file: String,
    expanded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier.padding(vertical = 2.dp).clip(MaterialTheme.shapes.large)) {
        ListItem(
            onClick = onClick,
            colors = ListItemDefaults.colors(
                containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
            supportingContent = { Text(listOf(license, copyright.lines().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(stringResource(R.string.separator))) },
        ) { Text(name) }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val text by produceState("", file) {
                value = withContext(Dispatchers.IO) {
                    runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("")
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Text(
                    // License files that already carry their copyright lines don't repeat them.
                    (if (copyright.isNotBlank() && !text.contains(copyright.lines().first())) copyright + "\n\n" else "") + text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
