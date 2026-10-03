package ai.unicto.unibot.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ai.unicto.unibot.R

/**
 * [v12-G] Batch G settings rows. The coordinator wires these into
 * SettingsScreen (inside the System section) — this file owns the rows so
 * Batch G never has to edit SettingsScreen.kt itself.
 *
 * - [StorageBreakdownRow] → [Routes.STORAGE_BREAKDOWN]
 * - [BatterySaverRow] — self-contained toggle (posts/removes the indicator
 *   notification via [BatterySaverStore]).
 * - [ScheduledBackupRow] → [Routes.SCHEDULED_BACKUP]
 */
@Composable
fun StorageBreakdownRow(onClick: () -> Unit) {
    SettingsRow(
        title = stringResource(R.string.v12g_row_storage_breakdown),
        subtitle = stringResource(R.string.v12g_row_storage_breakdown_sub),
        icon = Icons.Outlined.Storage,
        onClick = onClick,
    )
}

@Composable
fun BatterySaverRow() {
    val context = LocalContext.current
    remember(context) { BatterySaverStore.ensureLoaded(context) }
    val enabled by BatterySaverStore.enabled.collectAsState()
    SettingsSwitchRow(
        title = stringResource(R.string.v12g_battery_saver_title),
        subtitle = stringResource(
            if (enabled) R.string.v12g_battery_saver_on_sub
            else R.string.v12g_battery_saver_off_sub,
        ),
        icon = Icons.Outlined.Eco,
        checked = enabled,
        onCheckedChange = { BatterySaverStore.setEnabled(context, it) },
    )
}

@Composable
fun ScheduledBackupRow(onClick: () -> Unit) {
    SettingsRow(
        title = stringResource(R.string.v12g_row_sched_backup),
        subtitle = stringResource(R.string.v12g_sched_backup_subtitle),
        icon = Icons.Outlined.Schedule,
        onClick = onClick,
    )
}

/** Convenience: the whole section, if the coordinator wants it as one block. */
@Composable
fun SystemV12Section(
    onStorageBreakdownClick: () -> Unit,
    onScheduledBackupClick: () -> Unit,
) {
    SettingsSection(header = stringResource(R.string.v12g_system_section)) {
        StorageBreakdownRow(onClick = onStorageBreakdownClick)
        BatterySaverRow()
        ScheduledBackupRow(onClick = onScheduledBackupClick)
    }
}
