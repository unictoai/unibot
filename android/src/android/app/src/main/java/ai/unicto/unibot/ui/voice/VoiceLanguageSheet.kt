package ai.unicto.unibot.ui.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ai.unicto.unibot.R
import ai.unicto.unibot.speech.SpeechRecognitionManager
import ai.unicto.unibot.speech.TextToSpeechManager
import ai.unicto.unibot.ui.muse.MuseCard
import java.util.Locale

/**
 * Voice theme item 34: quick voice-language switch from the voice bar.
 *
 * Two independent switches:
 *  - "Listen in": the STT locale ([SpeechRecognitionManager.selectLocale] —
 *    persisted by the manager, takes effect immediately, even mid-recording).
 *  - "Speak in": the read-aloud TTS language override ([TextToSpeechManager]
 *    — "Auto" keeps the per-utterance auto-detect).
 *
 * The conversation's on-device (sherpa) voice follows the downloaded voice
 * choice in voice settings, not a language tag — the sheet labels the TTS
 * section accordingly so it never promises what it can't do.
 */
@Composable
fun VoiceLanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    VoiceConversationPrefs.init(context)

    val sttLocale by SpeechRecognitionManager.locale.collectAsState()
    val supportedStt by SpeechRecognitionManager.supportedLocales.collectAsState()
    val ttsLanguage by VoiceConversationPrefs.ttsLanguage.collectAsState()

    // The TTS override is process-wide (the read-aloud players each own a
    // manager); make sure the persisted choice is applied when the sheet
    // opens, so a fresh process honors it without visiting settings.
    LaunchedEffect(Unit) {
        TextToSpeechManager.preferredLanguageOverride = ttsLanguage
    }

    Dialog(onDismissRequest = onDismiss) {
        MuseCard(inset = 0.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.ub_voice_language_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))

                SectionLabel(stringResource(R.string.ub_voice_language_listen))
                val sttOptions = remember(supportedStt, sttLocale) {
                    (listOf(sttLocale) + supportedStt)
                        .distinctBy { it.toLanguageTag() }
                        .sortedWith(
                            compareBy({ it.toLanguageTag() != sttLocale.toLanguageTag() },
                                { displayName(it) }),
                        )
                }
                sttOptions.forEach { locale ->
                    LanguageRow(
                        label = displayName(locale),
                        selected = locale.toLanguageTag() == sttLocale.toLanguageTag(),
                        onClick = {
                            SpeechRecognitionManager.selectLocale(locale)
                            onDismiss()
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.ub_voice_language_speak))
                LanguageRow(
                    label = stringResource(R.string.ub_voice_choice_auto),
                    selected = ttsLanguage == null,
                    onClick = {
                        VoiceConversationPrefs.setTtsLanguage(null)
                        TextToSpeechManager.preferredLanguageOverride = null
                        onDismiss()
                    },
                )
                // TTS language options mirror the STT list: those are the
                // locales this phone actually handles for speech.
                sttOptions.forEach { locale ->
                    LanguageRow(
                        label = displayName(locale),
                        selected = locale.toLanguageTag() == ttsLanguage?.toLanguageTag(),
                        onClick = {
                            VoiceConversationPrefs.setTtsLanguage(locale)
                            TextToSpeechManager.preferredLanguageOverride = locale
                            onDismiss()
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun displayName(locale: Locale): String {
    val name = locale.getDisplayName(locale)
    return name.replaceFirstChar { it.uppercaseChar() }
}
