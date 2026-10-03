package ai.unicto.unibot.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.muse.MuseRow

/**
 * [v12-G] Batch G settings rows, coordinator-wired into SettingsScreen.
 * Converted to MuseRow for visual consistency with the settings page.
 *
 * - [StorageBreakdownRow] → [Routes.STORAGE_BREAKDOWN]
 * - [BatterySaverRow] — self-contained toggle (posts/removes the indicator
 *   notification via [BatterySaverStore]).
 * - [ScheduledBackupRow] → [Routes.SCHEDULED_BACKUP]
 */
@Composable
fun StorageBreakdownRow(onClick: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.v12g_row_storage_breakdown),
        value = stringResource(R.string.v12g_row_storage_breakdown_sub),
        icon = Icons.Outlined.Storage,
        onClick = onClick,
    )
}

@Composable
fun BatterySaverRow() {
    val context = LocalContext.current
    remember(context) { BatterySaverStore.ensureLoaded(context) }
    val enabled by BatterySaverStore.enabled.collectAsState()
    MuseRow(
        title = stringResource(R.string.v12g_battery_saver_title),
        value = stringResource(
            if (enabled) R.string.v12g_battery_saver_on_sub
            else R.string.v12g_battery_saver_off_sub,
        ),
        icon = Icons.Outlined.Eco,
        chevron = false,
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = { BatterySaverStore.setEnabled(context, it) },
            )
        },
        onClick = { BatterySaverStore.setEnabled(context, !enabled) },
    )
}

@Composable
fun ScheduledBackupRow(onClick: () -> Unit) {
    MuseRow(
        title = stringResource(R.string.v12g_row_sched_backup),
        value = stringResource(R.string.v12g_sched_backup_subtitle),
        icon = Icons.Outlined.Schedule,
        onClick = onClick,
    )
}
