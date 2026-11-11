package io.github.friendlyglobal.jacket.ui.voices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.friendlyglobal.jacket.R
import io.github.friendlyglobal.jacket.data.Settings
import io.github.friendlyglobal.jacket.ui.JacketIcons
import io.github.friendlyglobal.jacket.ui.rememberHaptics
import io.github.friendlyglobal.jacket.voice.PackVoice
import io.github.friendlyglobal.jacket.voice.VoiceGender
import io.github.friendlyglobal.jacket.voice.VoiceRef
import io.github.friendlyglobal.jacket.voice.accentFor
import io.github.friendlyglobal.jacket.voice.languageKey
import io.github.friendlyglobal.jacket.voice.voicesFor
import io.github.friendlyglobal.jacket.voices
import java.util.Locale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.text.style.TextOverflow

/** What a natural voice is called: "Female voice 2", or its maker's name for it where that says more. */
@Composable
fun voiceName(voice: PackVoice): String = when {
    voice.name != voice.id -> voice.name
    voice.number > 0 && voice.gender == VoiceGender.FEMALE -> stringResource(R.string.voice_female, voice.number)
    voice.number > 0 && voice.gender == VoiceGender.MALE -> stringResource(R.string.voice_male, voice.number)
    else -> voice.name
}

/** What the voice reading [language] is called, for the player and for Settings. */
@Composable
fun voiceLabel(language: String?, settings: Settings): String {
    val voices = LocalContext.current.voices
    val ref = remember(language, settings.voice, settings.voices) { voices.choose(language, settings) }
    val voice = ref?.let { voices.pack(it.pack)?.voice(it.voice) }
    return if (voice != null) voiceName(voice) else stringResource(R.string.voice_phone)
}

/** A language's name in the reader's own language, as a title. */
@Composable
fun languageName(tag: String?): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    return remember(tag, locale) {
        val name = tag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale) }.orEmpty()
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

/**
 * Choosing the voice that reads aloud: the phone's own, or one of the natural voices, each with a
 * button to hear it first. The choice is for every book, unless "Only for books in ..." is on.
 * In the reader ([onLanguage] given) the book's language can be put right too.
 */
@Composable
fun VoicePicker(
    /** The language the choice is being made in: the book's, or the phone's in Settings. */
    language: String?,
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
    /** In the reader: what the book itself gives as its language, and how to read it in another. */
    bookLanguage: String? = null,
    onLanguage: ((String?) -> Unit)? = null,
) {
    val voices = LocalContext.current.voices
    val haptics = rememberHaptics()
    val previewer = rememberVoicePreviewer()
    val key = languageKey(language)
    val current = remember(language, settings.voice, settings.voices) { voices.choose(language, settings) }
    val onlyHere = key in settings.voices
    val named = languageName(language)
    var about by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }

    // Where a language has a voice with the reader's own accent (Brazil's Portuguese), a voice
    // picked here is picked for this language: the reader's own for it alone, any other for every
    // book and, whatever its accent, for this language too.
    val own = remember(language) { voices.packs.filter { it.speaks(key) && it.hasAccentOf(accentFor(language)) }.map { it.id } }
    val choose = { value: String ->
        haptics.tick()
        val pack = VoiceRef.parse(value)?.pack
        onSettings { s ->
            when {
                key in s.voices -> s.copy(voices = s.voices + (key to value))
                pack != null && pack in own -> s.copy(voices = s.voices + (key to value))
                pack != null && own.isNotEmpty() -> s.copy(voice = value, voices = s.voices + (key to value))
                else -> s.copy(voice = value)
            }
        }
    }

    Column(modifier) {
        Text(
            stringResource(R.string.voice_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp).semantics { heading() },
        )

        if (onLanguage != null && named.isNotEmpty()) {
            Surface(
                onClick = { haptics.tick(); choosingLanguage = true },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(JacketIcons.Language, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.voice_book_language, named), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.voice_book_language_hint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(stringResource(R.string.voice_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        val natural = remember(language) { voicesFor(language, voices.packs) }
        val count = natural.size + 1
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            SegmentedListItem(
                selected = current == null,
                onClick = { if (current != null) choose(VoiceRef.SYSTEM) },
                shapes = ListItemDefaults.segmentedShapes(0, count),
                leadingContent = { Icon(JacketIcons.Volume, null, Modifier.padding(horizontal = 8.dp)) },
                supportingContent = { Text(stringResource(R.string.voice_phone_description)) },
                trailingContent = if (current == null) {
                    { Icon(JacketIcons.Check, stringResource(R.string.reading_selected)) }
                } else null,
            ) { Text(stringResource(R.string.voice_phone)) }

            natural.forEachIndexed { index, (pack, voice) ->
                val ref = VoiceRef(pack.id, voice.id)
                val selected = current == ref
                val name = voiceName(voice)
                val hearing = previewer.playing == ref.toString()
                SegmentedListItem(
                    selected = selected,
                    onClick = { if (!selected) choose(ref.toString()) },
                    shapes = ListItemDefaults.segmentedShapes(index + 1, count),
                    leadingContent = {
                        FilledTonalIconButton(onClick = { haptics.tick(); previewer.toggle(ref, language) }) {
                            Icon(
                                if (hearing) JacketIcons.Stop else JacketIcons.Play,
                                if (hearing) stringResource(R.string.voice_stop_sample) else stringResource(R.string.voice_hear, name),
                            )
                        }
                    },
                    supportingContent = { NaturalTag(pack.accents[key]) },
                    trailingContent = if (selected) {
                        { Icon(JacketIcons.Check, stringResource(R.string.reading_selected)) }
                    } else null,
                ) { Text(name) }
            }
        }

        if (natural.isEmpty() && voices.packs.isNotEmpty() && named.isNotEmpty()) {
            // A language the natural voices haven't learnt: the phone's voice has it.
            Text(
                stringResource(R.string.voice_not_in_language, named),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
        }

        if (natural.isNotEmpty() && named.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(MaterialTheme.shapes.large)
                    .toggleable(onlyHere, role = Role.Switch) { on ->
                        haptics.toggle(on)
                        val now = current?.toString() ?: VoiceRef.SYSTEM
                        onSettings { s -> if (on) s.copy(voices = s.voices + (key to now)) else s.copy(voices = s.voices - key) }
                    }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.voice_only_language, named), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.voice_only_language_description),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = onlyHere, onCheckedChange = null)
            }
        }

        if (voices.packs.isNotEmpty()) {
            Text(
                stringResource(R.string.voice_private),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
            TextButton(onClick = { haptics.tick(); about = true }, modifier = Modifier.padding(top = 2.dp)) {
                Text(stringResource(R.string.voice_about))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.voice_about)) },
            text = {
                Column {
                    Text(stringResource(R.string.voice_about_text))
                    Spacer(Modifier.height(12.dp))
                    // The makers' own credit lines, as their licences ask; they aren't translated.
                    voices.packs.forEach { pack ->
                        Text(
                            pack.license.attribution, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    if (choosingLanguage && onLanguage != null) {
        LanguageDialog(
            current = key,
            bookLanguage = bookLanguage,
            offered = remember { (voices.packs.flatMap { it.languages } + EXTRA_LANGUAGES).distinct() },
            onPick = { picked ->
                haptics.tick()
                choosingLanguage = false
                onLanguage(picked)
            },
            onDismiss = { choosingLanguage = false },
        )
    }
}

/** Languages the phone's voice may read though no natural voice does. */
private val EXTRA_LANGUAGES = listOf("zh", "he", "th", "nb", "ca", "fa", "bn", "ta", "ms")

/**
 * The small "Natural" mark on a voice that isn't the phone's: a plain word, like any other detail;
 * with its accent where the language has several ("Natural · Portugal accent").
 */
@Composable
private fun NaturalTag(accent: String?) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val region = remember(accent, locale) { accent?.let { Locale("", it).getDisplayCountry(locale) }?.takeIf { it.isNotBlank() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.voice_natural), color = MaterialTheme.colorScheme.primary)
        if (region != null) {
            Text(
                stringResource(R.string.separator) + stringResource(R.string.voice_accent, region),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "What language is this book in?", for a book that says the wrong one or none. */
@Composable
private fun LanguageDialog(current: String, bookLanguage: String?, offered: List<String>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val names = offered.associateWith { languageName(it) }
    val sorted = remember(names) { offered.filter { names[it].orEmpty().isNotEmpty() }.sortedBy { names[it] } }
    val fromBook = languageName(bookLanguage)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_language_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (fromBook.isNotEmpty()) {
                    item {
                        ListItem(
                            onClick = { onPick(null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            supportingContent = { Text(stringResource(R.string.voice_language_from_book)) },
                            trailingContent = if (languageKey(bookLanguage) == current) {
                                { Icon(JacketIcons.Check, stringResource(R.string.reading_selected)) }
                            } else null,
                        ) { Text(fromBook) }
                    }
                }
                items(sorted.filter { it != languageKey(bookLanguage) }) { code ->
                    ListItem(
                        onClick = { onPick(code) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        trailingContent = if (code == current) {
                            { Icon(JacketIcons.Check, stringResource(R.string.reading_selected)) }
                        } else null,
                    ) { Text(names[code].orEmpty()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The voice's name on the player, to tap for the picker: a tonal Material 3 Expressive button,
 * round and squeezing towards a square under the thumb like the player's other buttons. When the
 * phone can't keep up with a natural voice it says so instead, in the colours of something that
 * needs attention; the name rolls over to the new one when the voice changes, and the button
 * springs to its new width.
 */
@Composable
fun VoiceChip(label: String, natural: Boolean, struggling: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val containerColor by animateColorAsState(if (struggling) scheme.errorContainer else scheme.secondaryContainer, motion.defaultEffectsSpec(), label = "voice-container")
    val contentColor by animateColorAsState(if (struggling) scheme.onErrorContainer else scheme.onSecondaryContainer, motion.defaultEffectsSpec(), label = "voice-content")
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, true, true),
        modifier = modifier.animateContentSize(motion.defaultSpatialSpec()),
    ) {
        // The same voice icon for every voice: a natural one is simply a better voice, not a feature of its own.
        Icon(JacketIcons.Voice, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        AnimatedContent(
            targetState = if (struggling) stringResource(R.string.voice_slow) else label,
            transitionSpec = {
                (slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()))
                    .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false))
            },
            label = "voice-name",
            modifier = Modifier.weight(1f, fill = false),
        ) { text ->
            // The slow-phone warning is a sentence: it gets a second line rather than an ellipsis.
            Text(text, maxLines = if (struggling) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Icon(JacketIcons.ExpandMore, stringResource(R.string.voice_change), Modifier.size(ButtonDefaults.IconSize))
    }
}
