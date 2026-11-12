package io.github.friendlyglobal.jacket.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.catchUp
import io.github.friendlyglobal.jacket.data.Backup
import io.github.friendlyglobal.jacket.data.CatchUp
import io.github.friendlyglobal.jacket.data.DeviceFiles
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.asHeading

/**
 * "Catch up between devices": the switch, and with it on, the reader's other devices as they are
 * found. Turning it on first says in plain words what it does and asks what this device is to be
 * called on the others; then the system's picker is where the folder is chosen (or, where a
 * folder can't be, the file).
 */
@Composable
internal fun CatchUpSettings(haptics: Haptics, modifier: Modifier = Modifier) {
    val catchUp = LocalContext.current.catchUp
    val status by catchUp.status.collectAsStateWithLifecycle()
    var settingUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            haptics.confirm()
            catchUp.enableFolder(uri, name)
        }
    }
    val ownFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri != null) {
            haptics.confirm()
            catchUp.enableFile(uri, name)
        }
    }
    val otherFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            haptics.confirm()
            catchUp.addFiles(uris)
        }
    }
    val setUp = {
        name = status.device.ifEmpty { catchUp.suggestedName() }
        settingUp = true
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        val rows = if (status.on) 2 else 1
        SegmentedListItem(
            checked = status.on,
            onCheckedChange = { on ->
                if (on) {
                    haptics.tick()
                    setUp()
                } else {
                    haptics.toggle(false)
                    catchUp.disable()
                }
            },
            modifier = Modifier.asSwitch(),
            shapes = ListItemDefaults.segmentedShapes(0, rows),
            leadingContent = { Icon(JacketIcons.Devices, null) },
            supportingContent = {
                Text(catchUpSummary(status), color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
        ) { Text(stringResource(R.string.catch_up_title)) }
        if (status.on) {
            val names = status.others.joinToString { it.name }
            SegmentedListItem(
                // Where the files were chosen one by one, this is where the next is added;
                // with a folder, it looks in the folder again.
                onClick = {
                    haptics.tick()
                    if (status.folder) catchUp.look(force = true) else otherFiles.launch(arrayOf(Backup.MIME, "application/octet-stream", "text/plain"))
                },
                shapes = ListItemDefaults.segmentedShapes(1, rows),
                leadingContent = { Icon(JacketIcons.Device, null) },
                supportingContent = {
                    Text(
                        when {
                            names.isEmpty() && status.folder -> stringResource(R.string.catch_up_others_none_folder)
                            names.isEmpty() -> stringResource(R.string.catch_up_others_none_files)
                            status.folder -> names
                            else -> stringResource(R.string.catch_up_others_add, names)
                        },
                    )
                },
            ) { Text(stringResource(R.string.catch_up_others)) }
        }
    }

    if (settingUp) {
        AlertDialog(
            onDismissRequest = { settingUp = false },
            title = { Text(stringResource(R.string.catch_up_title), modifier = Modifier.asHeading()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.catch_up_setup_body))
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text(stringResource(R.string.catch_up_device_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.catch_up_setup_files_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            haptics.tick()
                            settingUp = false
                            ownFile.launch(DeviceFiles.nameFor(name.ifBlank { catchUp.suggestedName() }))
                        },
                    ) { Text(stringResource(R.string.catch_up_setup_files)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.tick()
                        settingUp = false
                        folder.launch(null)
                    },
                ) { Text(stringResource(R.string.catch_up_choose_folder)) }
            },
            dismissButton = { TextButton(onClick = { settingUp = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/**
 * Under the switch. Off, what it does; on, where this device's file is and when it was last
 * written ("Updated 5 minutes ago · Jacket · Drive"), or that it couldn't be.
 */
@Composable
private fun catchUpSummary(status: CatchUp.Status): String {
    val where = listOfNotNull(status.where, status.place).joinToString(stringResource(R.string.separator))
    return when {
        !status.on -> stringResource(R.string.catch_up_description)
        status.failed -> stringResource(R.string.catch_up_failed)
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
        else -> where.ifEmpty { stringResource(R.string.catch_up_description) }
    }
}
