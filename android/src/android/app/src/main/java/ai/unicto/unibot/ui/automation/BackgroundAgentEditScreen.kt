package ai.unicto.unibot.ui.automation

import ai.unicto.unibot.automation.BackgroundAgent
import ai.unicto.unibot.automation.BackgroundAgentSchedule
import ai.unicto.unibot.automation.BackgroundAgentScheduler
import ai.unicto.unibot.automation.BackgroundAgentStore
import ai.unicto.unibot.ui.components.DialogTextField
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.scheduled.ScheduledTasksViewModel
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Item 91 — create / edit a background agent. The explicit consent checkbox
 * is the gate: the agent cannot be enabled (here or anywhere) without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundAgentEditScreen(
    agentId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { BackgroundAgentStore(context) }
    val scheduler = remember { BackgroundAgentScheduler(context) }
    val haptics = rememberHaptic()
    val existing = remember(agentId) { agentId?.let { store.get(it) } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    var schedule by remember { mutableStateOf(existing?.schedule ?: BackgroundAgentSchedule.MANUAL) }
    var intervalMinutes by remember { mutableStateOf((existing?.intervalMinutes ?: 60).toString()) }
    var dailyHour by remember { mutableStateOf(existing?.dailyHour ?: 8) }
    var dailyMinute by remember { mutableStateOf(existing?.dailyMinute ?: 0) }
    var modelBinding by remember { mutableStateOf(existing?.modelBinding) }
    var modelDisplay by remember { mutableStateOf<String?>(null) }
    var requireCharging by remember { mutableStateOf(existing?.requireCharging ?: false) }
    var requireUnmetered by remember { mutableStateOf(existing?.requireUnmetered ?: false) }
    var batteryNotLow by remember { mutableStateOf(existing?.batteryNotLow ?: true) }
    var consentGranted by remember { mutableStateOf(existing?.consentGranted ?: false) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showTimePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    // Resolve the pinned model's display name for an existing agent.
    val tasksVm: ScheduledTasksViewModel = viewModel(factory = ScheduledTasksViewModel.factory(context))
    remember(existing?.modelBinding) {
        modelDisplay = tasksVm.describeBinding(existing?.modelBinding)
    }

    val canSave = name.isNotBlank() && prompt.isNotBlank()

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = {
                    Text(
                        if (existing == null) "New background agent" else "Edit agent",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    UnibotTextButton(onClick = {
                        if (!canSave) {
                            error = "Give the agent a name and a prompt."
                            return@UnibotTextButton
                        }
                        val agent = (existing ?: BackgroundAgent(name = "", prompt = "")).copy(
                            name = name.trim(),
                            prompt = prompt.trim(),
                            modelBinding = modelBinding,
                            schedule = schedule,
                            intervalMinutes = intervalMinutes.toIntOrNull()?.coerceAtLeast(15) ?: 60,
                            dailyHour = dailyHour,
                            dailyMinute = dailyMinute,
                            requireCharging = requireCharging,
                            requireUnmetered = requireUnmetered,
                            batteryNotLow = batteryNotLow,
                            // Consent can only be granted here, explicitly.
                            // Enabling without it is refused by the store.
                            consentGranted = consentGranted,
                            enabled = enabled && consentGranted,
                        )
                        store.upsert(agent)
                        if (agent.enabled) scheduler.schedule(agent) else scheduler.kill(agent.id)
                        haptics.success()
                        onBack()
                    }, enabled = canSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name") },
                placeholder = { Text("e.g. Morning brief") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it; error = null },
                label = { Text("Prompt") },
                placeholder = { Text("What should the agent do each run?") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Schedule ──
            Column {
                SectionLabel("Schedule")
                Spacer(Modifier.height(8.dp))
                val kinds = listOf(
                    BackgroundAgentSchedule.MANUAL to "Manual",
                    BackgroundAgentSchedule.INTERVAL to "Interval",
                    BackgroundAgentSchedule.DAILY to "Daily",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { idx, (kind, label) ->
                        SegmentedButton(
                            selected = schedule == kind,
                            onClick = { haptics.tap(); schedule = kind },
                            shape = SegmentedButtonDefaults.itemShape(idx, kinds.size),
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (schedule) {
                    BackgroundAgentSchedule.INTERVAL -> {
                        DialogTextField(
                            value = intervalMinutes,
                            onValueChange = { intervalMinutes = it.filter { c -> c.isDigit() }.take(4) },
                            placeholder = "Minutes between runs (min 15)",
                            singleLine = true,
                        )
                    }
                    BackgroundAgentSchedule.DAILY -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Runs daily at", style = MaterialTheme.typography.bodyMedium)
                            UnibotTextButton(onClick = { showTimePicker = true }) {
                                Text("%02d:%02d".format(dailyHour, dailyMinute))
                            }
                        }
                    }
                    BackgroundAgentSchedule.MANUAL -> {
                        Text(
                            "Runs only when you tap the play button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Model ──
            Column {
                SectionLabel("Model")
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showModelPicker = true }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        modelDisplay ?: "App default",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (modelBinding != null) {
                        UnibotTextButton(onClick = {
                            modelBinding = null
                            modelDisplay = null
                        }) { Text("Clear") }
                    }
                }
                Text(
                    "The model is pinned per agent — the agent always runs on this model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Battery discipline ──
            Column {
                SectionLabel("Battery")
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = "Only when battery is OK",
                    subtitle = "Wait if the battery is low.",
                    checked = batteryNotLow,
                    onCheckedChange = { batteryNotLow = it },
                )
                SwitchRow(
                    title = "Only when charging",
                    subtitle = "Wait until the phone is plugged in.",
                    checked = requireCharging,
                    onCheckedChange = { requireCharging = it },
                )
                SwitchRow(
                    title = "Only on unmetered Wi-Fi",
                    subtitle = "Wait for Wi-Fi instead of mobile data.",
                    checked = requireUnmetered,
                    onCheckedChange = { requireUnmetered = it },
                )
            }

            // ── Consent + enabled ──
            Column {
                SectionLabel("Permission")
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { haptics.tap(); consentGranted = !consentGranted }
                        .padding(vertical = 6.dp),
                ) {
                    Checkbox(
                        checked = consentGranted,
                        onCheckedChange = { haptics.tap(); consentGranted = it },
                    )
                    Text(
                        "I allow this agent to run in the background and use my API key on its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                SwitchRow(
                    title = "Enabled",
                    subtitle = if (consentGranted) "The agent will run on its schedule."
                    else "Grant permission above first.",
                    checked = enabled && consentGranted,
                    enabled = consentGranted,
                    onCheckedChange = { enabled = it },
                )
            }

            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = dailyHour, initialMinute = dailyMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Daily run time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                UnibotButton(onClick = {
                    dailyHour = state.hour
                    dailyMinute = state.minute
                    showTimePicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                UnibotTextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }

    if (showModelPicker) {
        ModelOptionDialog(
            vm = tasksVm,
            onDismiss = { showModelPicker = false },
            onPick = { binding, display ->
                modelBinding = binding
                modelDisplay = display
                showModelPicker = false
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ModelOptionDialog(
    vm: ScheduledTasksViewModel,
    onDismiss: () -> Unit,
    onPick: (binding: String?, display: String?) -> Unit,
) {
    val options = remember { vm.listModels() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin a model") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(null, null) }
                        .padding(vertical = 8.dp),
                ) {
                    Text("App default", style = MaterialTheme.typography.bodyMedium)
                }
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(
                                    """{"type":"entry","entryId":"${option.entryId}"}""",
                                    option.displayName,
                                )
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Column {
                            Text(option.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                option.providerLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (options.isEmpty()) {
                    Text(
                        "No models configured yet — add a provider first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
