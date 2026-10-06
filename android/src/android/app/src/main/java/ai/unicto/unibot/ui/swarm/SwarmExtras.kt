package ai.unicto.unibot.ui.swarm

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.swarm.SWARM_TEMPLATES
import ai.unicto.unibot.swarm.SwarmAttachment
import ai.unicto.unibot.swarm.SwarmCrewPreset
import ai.unicto.unibot.swarm.SwarmMissionRecord
import ai.unicto.unibot.swarm.SwarmPrefs
import ai.unicto.unibot.swarm.SwarmRoles
import ai.unicto.unibot.swarm.SwarmTemplate

// ─── Section header ─────────────────────────────────────────────────────────

@Composable
internal fun SwarmSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

// ─── Template gallery (item 9) ──────────────────────────────────────────────

@Composable
internal fun SwarmTemplateGallery(
    onUseTemplate: (SwarmTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwarmSectionHeader(title = "Start from a template")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(SWARM_TEMPLATES, key = { it.id }) { template ->
                TemplateCard(
                    template = template,
                    onUse = { onUseTemplate(template) },
                )
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: SwarmTemplate,
    onUse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onUse,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.size(width = 200.dp, height = 118.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = template.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Use template",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ─── Crew section with custom crew builder (item 7) ─────────────────────────

@Composable
internal fun SwarmCrewSection(
    builtins: List<SwarmCrewPreset>,
    customCrews: List<SwarmCrewPreset>,
    selectedId: String,
    onSelect: (SwarmCrewPreset) -> Unit,
    onSaveCustom: (name: String, roles: List<String>) -> Unit,
    onDeleteCustom: (SwarmCrewPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var builderOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwarmSectionHeader(
                title = "Crew",
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { builderOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("New crew")
            }
        }
        (builtins + customCrews).forEach { preset ->
            CrewPresetRow(
                preset = preset,
                selected = preset.id == selectedId,
                isCustom = customCrews.any { it.id == preset.id },
                onSelect = { onSelect(preset) },
                onDelete = { onDeleteCustom(preset) },
            )
        }
    }
    if (builderOpen) {
        CustomCrewBuilderDialog(
            onDismiss = { builderOpen = false },
            onSave = { name, roles ->
                onSaveCustom(name, roles)
                builderOpen = false
            },
        )
    }
}

@Composable
private fun CrewPresetRow(
    preset: SwarmCrewPreset,
    selected: Boolean,
    isCustom: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.large,
        border = border,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isCustom) {
                        Text(
                            text = "Custom",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "${preset.roles.size} agents · " +
                        preset.roles.joinToString(", ") { roleDisplayName(it) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCustom) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete custom crew",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomCrewBuilderDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, roles: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    val checked = remember { mutableStateListOf<String>() }
    val canSave = name.isNotBlank() && checked.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("New custom crew") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Crew name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Roles",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwarmRoles.all.forEach { role ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = checked.contains(role.id),
                            onCheckedChange = { on ->
                                if (on) checked.add(role.id) else checked.remove(role.id)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = role.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        SwarmRoles.all.map { it.id }.filter { checked.contains(it) },
                    )
                },
                enabled = canSave,
            ) {
                Text("Save crew")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ─── Run settings (items 2, 3, 11) ──────────────────────────────────────────

@Composable
internal fun SwarmRunSettingsSection(
    prefs: SwarmPrefs,
    onPrefsChange: (SwarmPrefs) -> Unit,
    notificationsGranted: Boolean,
    onRequestNotificationsPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SwarmSectionHeader(title = "Run settings")
        // Item 3 — worker-parallelism ceiling, 1..8.
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Max parallel workers",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = prefs.maxWorkers.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = prefs.maxWorkers.toFloat(),
                onValueChange = { onPrefsChange(prefs.withMaxWorkers(it.toInt())) },
                valueRange = 1f..8f,
                steps = 6,
            )
            Text(
                text = "Caps how many agents may work at once — phone RAM and " +
                    "quota stay in budget. Workers run one at a time in this " +
                    "version; the cap is each mission's parallelism budget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Item 2 — plan-approval gate default.
        SettingsSwitchRow(
            title = "Review plan before agents run",
            description = "The swarm shows its decomposed plan first — approve or edit it before workers start.",
            checked = prefs.requirePlanApproval,
            onCheckedChange = { onPrefsChange(prefs.copy(requirePlanApproval = it)) },
        )
        // Item 11 — completion notifications.
        SettingsSwitchRow(
            title = "Notify when a mission finishes",
            description = if (notificationsGranted) {
                "Posts a quiet notification when a long mission completes while the app is in the background."
            } else {
                "Posts a quiet notification on completion. Needs notification permission — you'll be asked when you enable it."
            },
            checked = prefs.completionNotificationsEnabled,
            onCheckedChange = { on ->
                if (on && !notificationsGranted) onRequestNotificationsPermission()
                onPrefsChange(prefs.copy(completionNotificationsEnabled = on))
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

// ─── Mission history (item 4) ───────────────────────────────────────────────

@Composable
internal fun SwarmHistorySection(
    records: List<SwarmMissionRecord>,
    onRerun: (SwarmMissionRecord) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwarmSectionHeader(
                title = "History",
                modifier = Modifier.weight(1f),
            )
            if (records.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
            }
        }
        if (records.isEmpty()) {
            Text(
                text = "No missions yet. Finished missions land here — rerun any of them with one tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            records.forEach { record ->
                HistoryRow(
                    record = record,
                    onRerun = { onRerun(record) },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: SwarmMissionRecord,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = record.mission.ifBlank { "(no mission text)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${record.crewName} · ${historyOutcomeLabel(record.outcome)} · " +
                        "${formatTokens(record.totalTokens)} tokens · " +
                        HISTORY_DATE_FORMAT.format(java.util.Date(record.finishedAtMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRerun) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Rerun mission",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private val HISTORY_DATE_FORMAT = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)

private fun historyOutcomeLabel(outcome: String): String = when (outcome) {
    SwarmMissionRecord.OUTCOME_DONE -> "Done"
    SwarmMissionRecord.OUTCOME_CANCELLED -> "Cancelled"
    SwarmMissionRecord.OUTCOME_FAILED -> "Failed"
    else -> outcome
}

// ─── Plan-approval gate card (item 2) ───────────────────────────────────────

@Composable
internal fun PlanApprovalCard(
    plan: List<String>,
    onApprove: (List<String>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var edits by remember(plan) { mutableStateOf(plan) }
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Review the plan",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "The manager decomposed your mission into these steps. " +
                        "Edit any step, then approve — or reject to cancel the mission.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            edits.forEachIndexed { index, step ->
                OutlinedTextField(
                    value = step,
                    onValueChange = { value ->
                        edits = edits.toMutableList().also { it[index] = value }
                    },
                    label = { Text("Step ${index + 1}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onReject) {
                    Text("Reject")
                }
                OutlinedButton(
                    onClick = { onApprove(edits) },
                ) {
                    Text("Approve & run")
                }
            }
        }
    }
}

// ─── Mid-run steering (item 6) ──────────────────────────────────────────────

@Composable
internal fun SteeringInputRow(
    onSteer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var instruction by remember { mutableStateOf("") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text("Steer the swarm…") },
            placeholder = { Text("e.g. Focus on pricing, skip the history section") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onSteer(instruction.trim())
                instruction = ""
            },
            enabled = instruction.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send steering instruction",
                tint = if (instruction.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ─── Attach files to a mission (item 10) ────────────────────────────────────

@Composable
internal fun AttachmentPickerRow(
    attachments: List<SwarmAttachment>,
    onAttachmentsChange: (List<SwarmAttachment>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        readAttachment(context, uri)?.let { attachment ->
            if (attachments.size < SwarmAttachment.MAX_ATTACHMENTS &&
                attachments.none { it.id == attachment.id }
            ) {
                onAttachmentsChange(attachments + attachment)
            }
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            attachments.forEach { attachment ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${attachment.text.length} chars — workers will read this",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                onAttachmentsChange(attachments.filterNot { it.id == attachment.id })
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove attachment",
                            )
                        }
                    }
                }
            }
        }
        if (attachments.size < SwarmAttachment.MAX_ATTACHMENTS) {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("text/plain", "text/markdown", "text/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Attach a document (${attachments.size}/${SwarmAttachment.MAX_ATTACHMENTS})")
            }
            Text(
                text = "Text files the workers read as mission context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Reads a document URI into a [SwarmAttachment], capped at
 * [SwarmAttachment.MAX_CHARS_PER_ATTACHMENT] chars. Returns null when the
 * content cannot be read as text.
 */
private fun readAttachment(
    context: android.content.Context,
    uri: Uri,
): SwarmAttachment? = runCatching {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
    } ?: "document.txt"
    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).readText()
    } ?: return null
    val capped = text.take(SwarmAttachment.MAX_CHARS_PER_ATTACHMENT)
    if (capped.isBlank()) return null
    SwarmAttachment(id = uri.toString(), name = name, text = capped)
}.getOrNull()

// ─── Result export row (item 5) ─────────────────────────────────────────────

@Composable
internal fun ReportExportRow(
    onShareMarkdown: () -> Unit,
    onSharePdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onShareMarkdown,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Markdown")
        }
        OutlinedButton(
            onClick = onSharePdf,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Filled.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("PDF")
        }
    }
}
