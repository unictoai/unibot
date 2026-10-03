package ai.unicto.unibot.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.backup.ScheduledBackup
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.theme.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * [v12-G] Scheduled local backup settings. Daily/weekly toggle, time picker,
 * destination folder (app-private default or a user-picked on-device folder),
 * last-run status and a manual "Back up now".
 *
 * Everything here is on-device: the export never leaves the phone (no rclone
 * remotes involved). The alarm is armed via [ScheduledBackup.schedule] when
 * the toggle flips on; the receiver needs its manifest entry (see
 * [ai.unicto.unibot.backup.ScheduledBackupReceiver]) to fire while dead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(ScheduledBackup.load(context)) }
    var running by remember { mutableStateOf(false) }

    // Defensive: if the alarm was lost (e.g. the receiver's manifest entry
    // landed after the toggle was flipped), re-arm on every visit.
    LaunchedEffect(Unit) { ScheduledBackup.rescheduleIfEnabled(context) }

    fun persist(next: ScheduledBackup.Config) {
        config = next
        ScheduledBackup.save(context, next)
    }

    val timeState = rememberTimePickerState(
        initialHour = config.hour,
        initialMinute = config.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    // Push picker edits back into the persisted config (re-arms the alarm).
    LaunchedEffect(timeState.hour, timeState.minute) {
        if (timeState.hour != config.hour || timeState.minute != config.minute) {
            persist(config.copy(hour = timeState.hour, minute = timeState.minute))
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist the grant so the headless receiver can write while the app
        // is dead. Cloud providers are rejected — backups stay on-device.
        if (uri.authority != "com.android.externalstorage.documents") return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        persist(config.copy(treeUri = uri.toString()))
    }

    SettingsScaffold(title = stringResource(R.string.v12g_sched_backup_title), onBack = onBack) {
        SettingsSection(
            modifier = Modifier.staggeredEntrance(0),
            footer = stringResource(R.string.v12g_sched_backup_enable_sub),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.v12g_sched_backup_enable),
                icon = Icons.Outlined.Schedule,
                checked = config.enabled,
                onCheckedChange = { persist(config.copy(enabled = it)) },
                showDivider = false,
            )
        }

        if (config.enabled) {
            SettingsSection(
                header = stringResource(R.string.v12g_sched_backup_frequency),
                modifier = Modifier.staggeredEntrance(1),
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val opts = listOf(
                        ScheduledBackup.Frequency.DAILY to R.string.v12g_sched_backup_daily,
                        ScheduledBackup.Frequency.WEEKLY to R.string.v12g_sched_backup_weekly,
                    )
                    opts.forEachIndexed { idx, (freq, resId) ->
                        SegmentedButton(
                            selected = config.frequency == freq,
                            onClick = { persist(config.copy(frequency = freq)) },
                            shape = SegmentedButtonDefaults.itemShape(idx, opts.size),
                        ) { Text(stringResource(resId)) }
                    }
                }
            }

            SettingsSection(
                header = stringResource(R.string.v12g_sched_backup_time),
                modifier = Modifier.staggeredEntrance(2),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TimeInput(state = timeState)
                }
            }

            SettingsSection(
                header = stringResource(R.string.v12g_sched_backup_destination),
                modifier = Modifier.staggeredEntrance(3),
            ) {
                val destSubtitle = config.treeUri?.let { prettyTreeName(it) }
                    ?: stringResource(R.string.v12g_sched_backup_dest_private)
                SettingsRow(
                    title = stringResource(R.string.v12g_sched_backup_destination),
                    subtitle = destSubtitle,
                    icon = if (config.treeUri != null) Icons.Outlined.FolderOpen else Icons.Outlined.Smartphone,
                    onClick = { folderLauncher.launch(null) },
                )
                if (config.treeUri != null) {
                    SettingsRow(
                        title = stringResource(R.string.v12g_sched_backup_use_default),
                        icon = Icons.Outlined.Smartphone,
                        onClick = { persist(config.copy(treeUri = null)) },
                        showDivider = false,
                    )
                }
            }

            SettingsSection(
                header = stringResource(R.string.v12g_sched_backup_last_run),
                modifier = Modifier.staggeredEntrance(4),
            ) {
                val lastText = if (config.lastRunMs == 0L) {
                    stringResource(R.string.v12g_sched_backup_never)
                } else {
                    val when_ = DateFormat.getMediumDateFormat(context).format(Date(config.lastRunMs)) +
                        " " + DateFormat.getTimeFormat(context).format(Date(config.lastRunMs))
                    val size = Formatter.formatFileSize(context, config.lastBytes)
                    val state = stringResource(
                        if (config.lastOk) R.string.v12g_sched_backup_ok
                        else R.string.v12g_sched_backup_failed,
                    )
                    "$when_ · $size · $state"
                }
                SettingsRow(
                    title = lastText,
                    showChevron = false,
                    onClick = null,
                    showDivider = false,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    UnibotButton(
                        onClick = {
                            if (running) return@UnibotButton
                            running = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { ScheduledBackup.runNow(context) }
                                }
                                config = ScheduledBackup.load(context)
                                running = false
                            }
                        },
                        enabled = !running,
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(stringResource(R.string.v12g_sched_backup_running))
                        } else {
                            Text(stringResource(R.string.v12g_sched_backup_run_now))
                        }
                    }
                }
            }
        }

        SettingsSection(
            footer = stringResource(R.string.v12g_sched_backup_footer),
            modifier = Modifier.staggeredEntrance(5),
        ) {
            // Footer-only section: keeps the encryption callout visually grouped.
        }
    }
}

/** "primary:Backups" → "Backups"; falls back to the raw segment. */
private fun prettyTreeName(treeUri: String): String {
    val seg = runCatching { Uri.parse(treeUri).lastPathSegment }.getOrNull() ?: return treeUri
    val decoded = runCatching { Uri.decode(seg) }.getOrDefault(seg)
    return decoded.substringAfter(":", decoded).takeIf { it.isNotBlank() } ?: treeUri
}
