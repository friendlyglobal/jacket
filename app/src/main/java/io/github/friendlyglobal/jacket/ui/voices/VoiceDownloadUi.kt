package io.github.friendlyglobal.jacket.ui.voices

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.ui.Haptics
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.voice.message
import io.github.friendlyglobal.jacket.voice.PackState
import io.github.friendlyglobal.jacket.voice.VoiceDownloads
import io.github.friendlyglobal.jacket.voice.VoicePack
import io.github.friendlyglobal.jacket.voices
import java.text.NumberFormat
import java.util.Locale

/*
 * Getting a natural voice onto the phone, in plain words: what it is, how big, and that it comes
 * from GitHub, once. The download itself is VoiceDownloads'.
 */

/** A size as the phone writes sizes: "145 MB". */
@Composable
fun sizeText(bytes: Long): String {
    val context = LocalContext.current
    return remember(bytes) { Formatter.formatShortFileSize(context, bytes) }
}

/** A share as a percentage in the reader's language: "42 %", "42%", "%42". */
@Composable
fun percentText(share: Float): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val percent = (share * 100).toInt().coerceIn(0, 100)
    return remember(percent, locale) { NumberFormat.getPercentInstance(locale).format(percent / 100.0) }
}

/** What a pack is, in plain words: "Natural voices in 31 languages", "Natural voice in Portuguese (Brazil)". */
@Composable
fun packTitle(pack: VoicePack): String {
    if (pack.languages.size > 1) return pluralStringResource(R.plurals.voice_pack_many, pack.languages.size, pack.languages.size)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val language = pack.languages.single()
    // (As the language is named mid-sentence: "Natural voice in Portuguese (Brazil)", "Voix naturelle en portugais (Brésil)".)
    val name = remember(pack.id, locale) { Locale(language, pack.accents[language].orEmpty()).getDisplayName(locale) }
    return stringResource(R.string.voice_pack_one, name)
}

/** Why a download stopped, said plainly. */
@Composable
fun problemText(state: PackState): String {
    val context = LocalContext.current
    // (Read again when the language changes.)
    val configuration = LocalConfiguration.current
    return remember(state.problem, state.needs, configuration) { state.problem?.message(context, state.needs).orEmpty() }
}

/** What is said of a download before it starts: the size, where it comes from, what GitHub sees. */
@Composable
private fun AboutTheDownload(size: String, mobile: Boolean) {
    Text(stringResource(R.string.voice_offer_body, size), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.voice_offer_privacy),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (mobile) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.voice_offer_mobile, size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * The sheet shown the first time Listen is pressed for a book whose natural voice isn't on the
 * phone. The phone's own voice is reading already, underneath it: nothing waits for an answer.
 * Download fetches the voice, which takes over once it is here; "Use the phone's voice" means
 * the question isn't asked again (Settings › Voice still offers the download).
 */
@Composable
fun VoiceOfferSheet(offer: VoiceDownloads.Offer, onDismiss: () -> Unit) {
    val downloads = LocalContext.current.voices.downloads
    val haptics = rememberHaptics()
    val mobile = remember { downloads.onMobileData() }
    val size = sizeText(downloads.state(offer.pack).left.takeIf { it > 0 } ?: offer.pack.bytes)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        ) {
            Box(
                Modifier.size(48.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(JacketIcons.Voice, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(if (offer.updated) R.string.voice_offer_title_updated else R.string.voice_offer_title),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            if (offer.updated) {
                Text(stringResource(R.string.voice_offer_updated), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
            }
            AboutTheDownload(size, mobile)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (offer.reading) R.string.voice_offer_meanwhile_natural else R.string.voice_offer_meanwhile),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    haptics.confirm()
                    downloads.download(offer.pack, mobileData = mobile)
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(JacketIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(if (mobile) R.string.voice_download_mobile else R.string.voice_download, size))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    haptics.tick()
                    downloads.usePhoneVoice()
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(if (offer.reading) R.string.voice_keep_current else R.string.voice_use_phone)) }
        }
    }
}

/** "Download this voice?", from the voices themselves: a voice tapped, or its sample, before it is on the phone. */
@Composable
fun DownloadVoiceDialog(pack: VoicePack, onDownload: (mobileData: Boolean) -> Unit, onDismiss: () -> Unit) {
    val downloads = LocalContext.current.voices.downloads
    val mobile = remember { downloads.onMobileData() }
    val size = sizeText(downloads.state(pack).left.takeIf { it > 0 } ?: pack.bytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(JacketIcons.Download, null) },
        title = { Text(stringResource(R.string.voice_offer_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { AboutTheDownload(size, mobile) } },
        confirmButton = {
            TextButton(onClick = { onDownload(mobile) }) {
                Text(stringResource(if (mobile) R.string.voice_download_mobile else R.string.voice_download, size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The natural voices Jacket has, each with what can be done with it: download it (with its size),
 * see it arrive and cancel it, or remove it to free the space. In Settings › Voice and in the
 * reader's voices alike.
 */
@Composable
fun VoicePacks(haptics: Haptics, modifier: Modifier = Modifier) {
    val voices = LocalContext.current.voices
    val downloads = voices.downloads
    val states by downloads.states.collectAsStateWithLifecycle()
    val packs = voices.packs
    var asking by remember { mutableStateOf<VoicePack?>(null) }
    var removing by remember { mutableStateOf<VoicePack?>(null) }
    if (packs.isEmpty()) return
    Column(modifier) {
        Text(
            stringResource(R.string.voice_packs_title), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            packs.forEachIndexed { index, pack ->
                val state = states[pack.id] ?: downloads.state(pack)
                PackRow(
                    pack, state, index, packs.size, haptics,
                    onDownload = {
                        // On mobile data, the size is said and the listener agrees first.
                        if (downloads.onMobileData()) asking = pack else downloads.download(pack)
                    },
                    onCancel = { downloads.cancel(pack) },
                    onRemove = { removing = pack },
                )
            }
        }
    }
    asking?.let { pack ->
        DownloadVoiceDialog(
            pack,
            onDownload = { mobile ->
                haptics.confirm()
                downloads.download(pack, mobileData = mobile)
                asking = null
            },
            onDismiss = { asking = null },
        )
    }
    removing?.let { pack ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.voice_remove_title)) },
            text = { Text(stringResource(R.string.voice_remove_body, sizeText(downloads.state(pack).done))) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    downloads.remove(pack)
                    removing = null
                }) { Text(stringResource(R.string.voice_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun PackRow(
    pack: VoicePack,
    state: PackState,
    index: Int,
    count: Int,
    haptics: Haptics,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val size = sizeText(pack.bytes)
    val status = when {
        state.inApp -> stringResource(R.string.voice_pack_in_app)
        state.ready -> stringResource(R.string.voice_pack_ready, size)
        state.downloading -> stringResource(R.string.voice_pack_downloading, percentText(state.progress), size)
        state.problem != null -> problemText(state)
        state.partly -> stringResource(R.string.voice_pack_paused, sizeText(state.done), size)
        else -> stringResource(R.string.voice_pack_size, size)
    }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        supportingContent = {
            Column {
                Text(
                    status,
                    color = if (state.problem != null && !state.downloading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(
                    visible = state.downloading,
                    enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
                ) {
                    LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.inApp -> {}
                        state.downloading -> TextButton(onClick = { haptics.tick(); onCancel() }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        state.ready -> TextButton(onClick = { haptics.tick(); onRemove() }, shapes = ButtonDefaults.shapes()) {
                            Icon(JacketIcons.Delete, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.voice_remove))
                        }
                        else -> {
                            FilledTonalButton(onClick = { haptics.tick(); onDownload() }, shapes = ButtonDefaults.shapes()) {
                                Icon(JacketIcons.Download, null, Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(
                                    if (state.problem != null) stringResource(R.string.voice_download_again)
                                    else stringResource(R.string.voice_download, sizeText(state.left.takeIf { it > 0 } ?: pack.bytes)),
                                )
                            }
                            if (state.partly) {
                                TextButton(onClick = { haptics.tick(); onRemove() }, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.voice_remove))
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { Text(packTitle(pack)) }
}
