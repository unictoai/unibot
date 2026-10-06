package ai.unicto.unibot.ui.scheduled

import ai.unicto.unibot.scheduled.QuietHours
import ai.unicto.unibot.scheduled.SchedulePolicyStore
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Item 92 — quiet hours + battery controls, pinned at the top of the
 * scheduled-tasks list. Tasks with "Respect quiet hours" on are postponed to
 * the end of this window instead of firing at night; the battery-saver pause
 * defers everything while the OS power saver is active.
 */
@Composable
fun QuietHoursCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SchedulePolicyStore(context) }
    val haptics = rememberHaptic()

    var quietHours by remember { mutableStateOf(store.quietHours()) }
    var batteryPause by remember { mutableStateOf(store.batterySaverPause()) }
    var editing by remember { mutableStateOf<EditingEnd?>(null) }

    fun persist(qh: QuietHours) {
        quietHours = qh
        store.setQuietHours(qh)
    }

    MuseCard(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Quiet hours", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (quietHours.enabled) {
                        "Tasks wait until ${fmt(quietHours.endHour, quietHours.endMinute)}."
                    } else {
                        "Tasks run at their scheduled time, day or night."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = quietHours.enabled,
                onCheckedChange = {
                    haptics.toggle()
                    persist(quietHours.copy(enabled = it))
                },
            )
        }

        if (quietHours.enabled) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton(
                    label = "From",
                    time = fmt(quietHours.startHour, quietHours.startMinute),
                    onClick = { editing = EditingEnd.START },
                    modifier = Modifier.weight(1f),
                )
                TimeButton(
                    label = "To",
                    time = fmt(quietHours.endHour, quietHours.endMinute),
                    onClick = { editing = EditingEnd.END },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pause during battery saver", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Defer tasks while the system battery saver is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = batteryPause,
                onCheckedChange = {
                    haptics.toggle()
                    batteryPause = it
                    store.setBatterySaverPause(it)
                },
            )
        }
    }

    editing?.let { which ->
        QuietHoursTimeDialog(
            title = if (which == EditingEnd.START) "Quiet hours start" else "Quiet hours end",
            initialHour = if (which == EditingEnd.START) quietHours.startHour else quietHours.endHour,
            initialMinute = if (which == EditingEnd.START) quietHours.startMinute else quietHours.endMinute,
            onDismiss = { editing = null },
            onConfirm = { h, m ->
                persist(
                    if (which == EditingEnd.START) quietHours.copy(startHour = h, startMinute = m)
                    else quietHours.copy(endHour = h, endMinute = m),
                )
                editing = null
            },
        )
    }
}

private enum class EditingEnd { START, END }

private fun fmt(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour, minute)

@Composable
private fun TimeButton(
    label: String,
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(time, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursTimeDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            UnibotTextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("Set")
            }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
