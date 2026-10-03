package ai.unicto.unibot.ui.voice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.muse.MuseRow

/**
 * [unibot-voice-history] v1.2 Batch E settings rows owned by the voice
 * feature. The settings coordinator wires these into SettingsScreen —
 * this file only *exposes* them so the voice batch stays decoupled from
 * the settings tree.
 */

/**
 * Settings row opening the voice conversation history
 * ([VoiceHistoryScreen]). Wired by the coordinator (not by this batch).
 */
@Composable
fun VoiceHistoryRow(onOpenVoiceHistory: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.ub_voice_history_title),
        value = stringResource(R.string.ub_voice_history_row_desc),
        onClick = onOpenVoiceHistory,
        icon = Icons.Outlined.History,
    )
}
