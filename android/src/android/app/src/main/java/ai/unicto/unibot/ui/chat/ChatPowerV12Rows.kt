package ai.unicto.unibot.ui.chat

// [v12-B] Settings entry rows for the Batch B (Chat power) screens. The
// coordinator wires these into SettingsScreen — this file only defines the
// rows; it navigates nothing itself.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.muse.MuseRow

/**
 * Settings row → chat templates (prompt starters).
 * Coordinator wires [onClick] to `navController.safeNavigate(Routes.CHAT_TEMPLATES)`.
 */
@Composable
fun ChatTemplatesRow(onClick: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.v12_chat_templates_title),
        value = stringResource(R.string.v12_chat_templates_subtitle),
        onClick = onClick,
        icon = Icons.Outlined.Description,
    )
}

/**
 * Settings row → scheduled messages.
 * Coordinator wires [onClick] to `navController.safeNavigate(Routes.SCHEDULED_MESSAGES)`.
 */
@Composable
fun ScheduledMessagesRow(onClick: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.v12_scheduled_messages_title),
        value = stringResource(R.string.v12_scheduled_messages_subtitle),
        onClick = onClick,
        icon = Icons.Outlined.Schedule,
    )
}
