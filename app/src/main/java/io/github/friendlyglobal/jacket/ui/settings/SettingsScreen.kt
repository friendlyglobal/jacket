package io.github.friendlyglobal.jacket.ui.settings

import android.content.Intent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.AnnotationKind
import io.github.friendlyglobal.jacket.data.CoverStyle
import io.github.friendlyglobal.jacket.data.LibraryState
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.reader.HIGHLIGHTS_MIME
import io.github.friendlyglobal.jacket.reader.ReadingControls
import io.github.friendlyglobal.jacket.reader.allHighlightsMarkdown
import io.github.friendlyglobal.jacket.reader.highlightPlace
import io.github.friendlyglobal.jacket.reader.highlightsFileName
import io.github.friendlyglobal.jacket.reader.saveText
import io.github.friendlyglobal.jacket.ui.ConnectedChoice
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.hasHinge
import io.github.friendlyglobal.jacket.ui.predictiveBack
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import io.github.friendlyglobal.jacket.data.Backup
import io.github.friendlyglobal.jacket.library
import io.github.friendlyglobal.jacket.ui.voices.SayItDialog
import io.github.friendlyglobal.jacket.ui.voices.SayItDraft
import io.github.friendlyglobal.jacket.ui.voices.SayItList
import io.github.friendlyglobal.jacket.ui.voices.VoicePicker
import io.github.friendlyglobal.jacket.ui.voices.voiceLabel
import io.github.friendlyglobal.jacket.voices
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import android.text.format.DateUtils
import io.github.friendlyglobal.jacket.autoBackup
import io.github.friendlyglobal.jacket.data.AutoBackup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.ui.asHeading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

private const val SOURCE_URL = "https://github.com/friendlyglobal/jacket"
private const val PRIVACY_URL = "https://usefriendly.app/products/jacket/privacy/"

@Composable
fun SettingsScreen(
    state: LibraryState,
    outerAvailable: Boolean,
    onSettings: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onLicenses: () -> Unit = {},
    /** Opens "Your reading". */
    onReading: () -> Unit = {},
) {
    val settings = state.settings
    val library = LocalContext.current.library
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    // The voice is chosen here as it will sound in the phone's own language.
    var choosingVoice by remember { mutableStateOf(false) }
    // "Say it this way": every word the voice says the listener's way, whichever book it is for.
    var showingWords by remember { mutableStateOf(false) }
    var saying by remember { mutableStateOf<SayItDraft?>(null) }
    val phoneLanguage = LocalConfiguration.current.locales[0]?.toLanguageTag()
    // A backup that keeps itself up to date: a file chosen once, in Drive say, written after changes.
    val autoBackup = context.autoBackup
    val autoStatus by autoBackup.status.collectAsStateWithLifecycle()
    val keeper = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri != null) {
            haptics.confirm()
            autoBackup.enable(uri)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { library.exportBackup(uri) }
                .onSuccess { haptics.confirm() }
                .onFailure { haptics.reject() }
                .fold(
                    { resources.getString(R.string.settings_backup_saved) },
                    { resources.getString(R.string.settings_backup_save_failed, it.message) },
                )
        }
    }
    // Every book's highlights and notes in one Markdown file, as the reader saves one book's.
    val highlightsFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(HIGHLIGHTS_MIME)) { uri ->
        if (uri != null) scope.launch {
            val text = allHighlightsMarkdown(library.state.value) { highlightPlace(resources, it) }
            message = runCatching { saveText(context, uri, text.orEmpty()) }
                .onSuccess { haptics.confirm() }
                .onFailure { haptics.reject() }
                .fold(
                    { resources.getString(R.string.highlights_saved) },
                    { resources.getString(R.string.highlights_save_failed, it.message.orEmpty()) },
                )
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { library.importBackup(uri) }.onSuccess { haptics.confirm() }.onFailure { haptics.reject() }.fold(
                { missing ->
                    if (missing == 0) resources.getString(R.string.settings_backup_restored)
                    else resources.getQuantityString(R.plurals.settings_backup_restored_missing, missing, missing)
                },
                // Backup.read throws IllegalArgumentException for a file that isn't a backup.
                {
                    if (it is IllegalArgumentException) resources.getString(R.string.settings_backup_not_a_backup)
                    else it.message ?: resources.getString(R.string.settings_backup_unreadable)
                },
            )
        }
    }
    // A folder to watch: Jacket keeps the right to read it, and looks in it straight away.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            haptics.confirm()
            onSettings { it.copy(watchFolder = uri.toString(), watchSeen = emptySet()) }
            scope.launch { library.scanWatchedFolder()?.message(resources)?.takeIf { it.isNotEmpty() }?.let { message = it } }
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val toggle = { on: Boolean, change: (Settings) -> Settings ->
        haptics.toggle(on)
        onSettings(change)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).predictiveBack(onBack),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title), modifier = Modifier.asHeading()) },
                subtitle = { Text(stringResource(R.string.settings_subtitle)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(JacketIcons.Back, stringResource(R.string.common_back)) } },
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

            item { GroupTitle(stringResource(R.string.settings_group_cover_screen), column) }
            item {
                Group(column) {
                    // Only a phone that folds can be closed on a book.
                    val folds = remember { context.hasHinge() }
                    val count = if (folds) 9 else 7
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(0, count),
                        leadingContent = { Icon(JacketIcons.OuterScreen, null) },
                        supportingContent = {
                            Text(
                                if (outerAvailable) stringResource(R.string.settings_outer_ready_description)
                                else stringResource(R.string.settings_outer_unavailable_description),
                            )
                        },
                    ) {
                        Text(
                            if (outerAvailable) stringResource(R.string.settings_outer_ready)
                            else stringResource(R.string.settings_outer_unavailable),
                        )
                    }
                    // What it will look like, following every switch below.
                    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(1, count)) {
                        CoverScreenPreview(state.current, settings)
                    }
                    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(2, count)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.settings_widget_style))
                            Text(
                                stringResource(R.string.settings_widget_style_description),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            WidgetStylePicker(state.current, settings, haptics) { style -> onSettings { it.copy(coverStyle = style) } }
                        }
                    }
                    SwitchRow(
                        3, count, stringResource(R.string.settings_show_progress),
                        stringResource(R.string.settings_show_progress_description), settings.showProgress,
                    ) {
                        toggle(it) { s -> s.copy(showProgress = it) }
                    }
                    SwitchRow(
                        4, count, stringResource(R.string.settings_ambient_dim),
                        stringResource(R.string.settings_ambient_dim_description), settings.ambientDim,
                    ) {
                        toggle(it) { s -> s.copy(ambientDim = it) }
                    }
                    SwitchRow(5, count, stringResource(R.string.settings_ambient_quote), null, settings.ambientQuote) {
                        toggle(it) { s -> s.copy(ambientQuote = it) }
                    }
                    SwitchRow(
                        6, count, stringResource(R.string.settings_auto_outer),
                        stringResource(R.string.settings_auto_outer_description), settings.autoOuterScreen,
                    ) {
                        toggle(it) { s -> s.copy(autoOuterScreen = it) }
                    }
                    if (folds) {
                        SwitchRow(
                            7, count, stringResource(R.string.settings_cover_when_closed),
                            stringResource(R.string.settings_cover_when_closed_description), settings.coverWhenClosed,
                        ) {
                            toggle(it) { s -> s.copy(coverWhenClosed = it) }
                        }
                        SwitchRow(
                            8, count, stringResource(R.string.settings_tabletop),
                            stringResource(R.string.settings_tabletop_description), settings.tabletop,
                        ) {
                            toggle(it) { s -> s.copy(tabletop = it) }
                        }
                    }
                }
            }
            item {
                Hint(stringResource(R.string.settings_cover_hint), column)
            }

            item { GroupTitle(stringResource(R.string.settings_group_reading), column) }
            item { ReadingControls(settings, onSettings, column) }

            item { GroupTitle(stringResource(R.string.settings_group_listening), column) }
            item {
                Group(column) {
                    SegmentedListItem(
                        onClick = { haptics.tick(); choosingVoice = true },
                        shapes = ListItemDefaults.segmentedShapes(0, 5),
                        leadingContent = { Icon(JacketIcons.Voice, null) },
                        supportingContent = { Text(voiceLabel(phoneLanguage, settings)) },
                    ) { Text(stringResource(R.string.voice_title)) }
                    SegmentedListItem(
                        onClick = {
                            haptics.tick()
                            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        shapes = ListItemDefaults.segmentedShapes(1, 5),
                        leadingContent = { Icon(JacketIcons.Volume, null) },
                        supportingContent = { Text(stringResource(R.string.voice_phone_settings_description)) },
                    ) { Text(stringResource(R.string.voice_phone_settings)) }
                    SegmentedListItem(
                        onClick = { haptics.tick(); showingWords = true },
                        shapes = ListItemDefaults.segmentedShapes(2, 5),
                        leadingContent = { Icon(JacketIcons.Voice, null) },
                        supportingContent = { Text(stringResource(R.string.say_hint)) },
                    ) { Text(stringResource(R.string.say_title)) }
                    SwitchRow(
                        3, 5, stringResource(R.string.listen_skip_notes),
                        stringResource(R.string.listen_skip_notes_description), settings.skipNoteNumbers,
                    ) { toggle(it) { s -> s.copy(skipNoteNumbers = it) } }
                    SwitchRow(
                        4, 5, stringResource(R.string.listen_screen_on),
                        stringResource(R.string.listen_screen_on_description), settings.screenOnWhileListening,
                    ) { toggle(it) { s -> s.copy(screenOnWhileListening = it) } }
                }
            }
            item { Hint(stringResource(R.string.voice_settings_hint), column) }

            item { GroupTitle(stringResource(R.string.settings_group_appearance), column) }
            item {
                Group(column) {
                    SwitchRow(
                        0, 1, stringResource(R.string.settings_book_colors),
                        stringResource(R.string.settings_book_colors_description),
                        settings.bookColors,
                    ) { toggle(it) { s -> s.copy(bookColors = it) } }
                }
            }

            item { GroupTitle(stringResource(R.string.library_title), column) }
            item {
                Group(column) {
                    val folder = settings.watchFolder
                    SegmentedListItem(
                        onClick = { haptics.tick(); folderPicker.launch(null) },
                        shapes = ListItemDefaults.segmentedShapes(0, 3),
                        leadingContent = { Icon(JacketIcons.Download, null) },
                        supportingContent = {
                            // The folder's own name once one is chosen.
                            Text(
                                folder?.let { it.toUri().lastPathSegment?.substringAfterLast(':')?.ifBlank { null } }
                                    ?: stringResource(R.string.settings_watch_folder_description),
                            )
                        },
                        trailingContent = if (folder != null) {
                            {
                                IconButton(
                                    onClick = {
                                        haptics.tick()
                                        runCatching {
                                            context.contentResolver.releasePersistableUriPermission(
                                                folder.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                            )
                                        }
                                        onSettings { it.copy(watchFolder = null, watchSeen = emptySet()) }
                                    },
                                ) { Icon(JacketIcons.Close, stringResource(R.string.search_clear)) }
                            }
                        } else {
                            null
                        },
                    ) { Text(stringResource(R.string.settings_watch_folder)) }
                    SwitchRow(1, 3, stringResource(R.string.settings_reading_stats), null, settings.readingStats) {
                        toggle(it) { s -> s.copy(readingStats = it) }
                    }
                    // The page of that reading time. With it switched off the row stays, greyed out:
                    // there is nothing to open, and the switch above is what brings it back.
                    SegmentedListItem(
                        onClick = { haptics.tick(); onReading() },
                        enabled = settings.readingStats,
                        shapes = ListItemDefaults.segmentedShapes(2, 3),
                        leadingContent = { Icon(JacketIcons.Clock, null) },
                        supportingContent = { Text(stringResource(R.string.settings_reading_description)) },
                    ) { Text(stringResource(R.string.reading_title)) }
                }
            }

            item { GroupTitle(stringResource(R.string.settings_group_backup), column) }
            item {
                Group(column) {
                    SegmentedListItem(
                        onClick = {
                            haptics.tick()
                            exporter.launch("Jacket backup ${java.time.LocalDate.now()}.json")
                        },
                        shapes = ListItemDefaults.segmentedShapes(0, 3),
                        leadingContent = { Icon(JacketIcons.Download, null) },
                        supportingContent = { Text(stringResource(R.string.settings_backup_save_description)) },
                    ) { Text(stringResource(R.string.settings_backup_save)) }
                    AutoBackupRow(
                        status = autoStatus,
                        onChange = { on ->
                            haptics.toggle(on)
                            if (on) keeper.launch(AUTO_BACKUP_NAME) else autoBackup.disable()
                        },
                        // It couldn't write: the file may have gone, or the permission with it.
                        onChooseAgain = { haptics.tick(); keeper.launch(AUTO_BACKUP_NAME) },
                    )
                    SegmentedListItem(
                        onClick = { haptics.tick(); importer.launch(arrayOf(Backup.MIME, "application/octet-stream", "text/plain")) },
                        shapes = ListItemDefaults.segmentedShapes(2, 3),
                        leadingContent = { Icon(JacketIcons.Upload, null) },
                        supportingContent = { Text(stringResource(R.string.settings_backup_restore_description)) },
                    ) { Text(stringResource(R.string.settings_backup_restore)) }
                }
            }

            // Reading on two devices: each offers to go to the page the other reached.
            item { GroupTitle(stringResource(R.string.catch_up_group), column) }
            item { CatchUpSettings(haptics, column) }
            item { Hint(stringResource(R.string.catch_up_hint), column) }

            item { GroupTitle(stringResource(R.string.notes_tab_highlights), column) }
            item {
                Group(column) {
                    SegmentedListItem(
                        onClick = {
                            haptics.tick()
                            // Nothing to put in a file yet: said here, rather than after a file has been named for it.
                            if (state.annotations.none { it.kind == AnnotationKind.HIGHLIGHT && it.text.isNotBlank() }) {
                                message = resources.getString(R.string.notes_no_highlights)
                            } else {
                                val word = resources.getString(R.string.notes_tab_highlights)
                                highlightsFile.launch(highlightsFileName(resources.getString(R.string.app_name), word, java.time.LocalDate.now().toString()))
                            }
                        },
                        shapes = ListItemDefaults.segmentedShapes(0, 1),
                        leadingContent = { Icon(JacketIcons.Highlight, null) },
                        supportingContent = { Text(stringResource(R.string.settings_highlights_save_description)) },
                    ) { Text(stringResource(R.string.settings_highlights_save)) }
                }
            }

            item { GroupTitle(stringResource(R.string.settings_group_about), column) }
            item {
                Group(column) {
                    val version = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
                    }
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(0, 4),
                        leadingContent = { Icon(JacketIcons.Info, null) },
                        supportingContent = { Text(stringResource(R.string.settings_about_version, version)) },
                    ) { Text(stringResource(R.string.app_name)) }
                    // Play asks for the policy inside the app too; the browser opens it, so Jacket
                    // itself still needs no internet permission.
                    SegmentedListItem(
                        onClick = {
                            haptics.tick()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_URL.toUri())) }
                        },
                        shapes = ListItemDefaults.segmentedShapes(1, 4),
                        leadingContent = { Icon(JacketIcons.Lock, null) },
                        supportingContent = { Text(stringResource(R.string.settings_about_privacy_description)) },
                    ) { Text(stringResource(R.string.settings_about_privacy)) }
                    SegmentedListItem(
                        onClick = {
                            haptics.tick()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())) }
                        },
                        shapes = ListItemDefaults.segmentedShapes(2, 4),
                        leadingContent = { Icon(JacketIcons.Code, null) },
                        supportingContent = { Text(stringResource(R.string.settings_about_source_description)) },
                    ) { Text(stringResource(R.string.settings_about_source)) }
                    SegmentedListItem(
                        onClick = { haptics.tick(); onLicenses() },
                        shapes = ListItemDefaults.segmentedShapes(3, 4),
                        leadingContent = { Icon(JacketIcons.Gavel, null) },
                        supportingContent = { Text(stringResource(R.string.settings_about_licenses_description)) },
                    ) { Text(stringResource(R.string.settings_about_licenses)) }
                }
            }
        }
    }
    if (choosingVoice) {
        ModalBottomSheet(onDismissRequest = { choosingVoice = false }) {
            VoicePicker(
                language = phoneLanguage,
                settings = settings,
                onSettings = onSettings,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            )
        }
    }
    if (showingWords) {
        ModalBottomSheet(onDismissRequest = { showingWords = false }) {
            val everyBook = stringResource(R.string.say_scope_all)
            SayItList(
                sayings = state.sayings,
                // Each says where it applies: in every book, or in the one named.
                scope = { saying -> if (saying.bookId == null) everyBook else state.find(saying.bookId)?.title },
                haptics = haptics,
                onAdd = { saying = SayItDraft() },
                onEdit = { saying = SayItDraft(it) },
                onDelete = { library.deleteSaying(it.id) },
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            )
        }
    }
    saying?.let { draft ->
        SayItDialog(
            draft = draft,
            // From here a new word is for every book.
            bookId = null,
            // Heard in the voice that reads the listener's own language.
            voice = remember(settings.voice, settings.voices, phoneLanguage) { context.voices.choose(phoneLanguage, settings) },
            language = phoneLanguage,
            haptics = haptics,
            onSave = {
                library.saveSaying(it)
                saying = null
            },
            onDelete = {
                library.deleteSaying(it.id)
                saying = null
            },
            onDismiss = { saying = null },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun ProfileCard(state: LibraryState, modifier: Modifier) {
    val books = state.books
    val finished = books.count { it.isFinished }
    val reading = books.count { !it.isNew && !it.isFinished }
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
                Text(stringResource(R.string.settings_profile_title), style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    stringResource(
                        R.string.settings_profile_summary,
                        pluralStringResource(R.plurals.settings_profile_books, books.size, books.size),
                        pluralStringResource(R.plurals.settings_profile_reading, reading, reading),
                        pluralStringResource(R.plurals.settings_profile_finished, finished, finished),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.current?.let {
                    Text(stringResource(R.string.settings_profile_on_cover, it.title), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

/** The name of a group of settings: a heading, so a screen reader can go from group to group down a long page. */
@Composable
private fun GroupTitle(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp).asHeading(),
    )
}

@Composable
private fun Group(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { content() }
}

/** The file name a backup kept up to date starts with. */
private const val AUTO_BACKUP_NAME = "Jacket backup.json"

/**
 * "Keep a backup up to date": off, it says what it does; on, when the file was last written and
 * where it is ("Updated 5 minutes ago · Jacket backup.json in Drive"), or that it couldn't be.
 */
@Composable
private fun AutoBackupRow(status: AutoBackup.Status, onChange: (Boolean) -> Unit, onChooseAgain: () -> Unit) {
    val where = listOfNotNull(status.name, status.place).joinToString(stringResource(R.string.separator))
    val description = when {
        !status.on -> stringResource(R.string.settings_backup_auto_description)
        status.failed -> stringResource(R.string.settings_backup_auto_failed)
        status.writtenAt > 0L -> {
            val now = System.currentTimeMillis()
            val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
            // "Updated just now", else "Updated 5 minutes ago" (the time read mid-sentence, so lower case).
            val updated = if (now - status.writtenAt < DateUtils.MINUTE_IN_MILLIS) {
                stringResource(R.string.settings_backup_auto_just_now)
            } else {
                val ago = DateUtils.getRelativeTimeSpanString(status.writtenAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
                stringResource(R.string.settings_backup_auto_updated, ago.replaceFirstChar { it.lowercase(locale) })
            }
            updated + if (where.isNotEmpty()) stringResource(R.string.separator) + where else ""
        }
        else -> where.ifEmpty { stringResource(R.string.settings_backup_auto_description) }
    }
    SegmentedListItem(
        checked = status.on,
        onCheckedChange = { if (status.on && status.failed) onChooseAgain() else onChange(it) },
        modifier = Modifier.asSwitch(),
        shapes = ListItemDefaults.segmentedShapes(1, 3),
        leadingContent = { Icon(JacketIcons.Upload, null) },
        supportingContent = {
            Text(description, color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Switch(
                checked = status.on,
                onCheckedChange = null,
                thumbContent = if (status.on) {
                    { Icon(JacketIcons.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        },
    ) { Text(stringResource(R.string.settings_backup_auto)) }
}

@Composable
private fun SwitchRow(index: Int, count: Int, title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.asSwitch(),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        supportingContent = if (description != null) { { Text(description) } } else null,
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

/** A row that wears a switch is a switch to a screen reader too ("on", "off"), as the same rows in the reader's sheet are: not a tick box. */
internal fun Modifier.asSwitch(): Modifier = semantics { role = Role.Switch }

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
    )
}
