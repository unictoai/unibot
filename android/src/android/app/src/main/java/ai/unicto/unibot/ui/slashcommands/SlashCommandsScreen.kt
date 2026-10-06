package ai.unicto.unibot.ui.slashcommands

import ai.unicto.unibot.slashcommands.CustomSlashCommand
import ai.unicto.unibot.slashcommands.SlashCommandExpander
import ai.unicto.unibot.slashcommands.SlashCommandStore
import ai.unicto.unibot.ui.components.DialogTextField
import ai.unicto.unibot.ui.components.UnibotButton
import ai.unicto.unibot.ui.components.UnibotTextButton
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import ai.unicto.unibot.ui.util.rememberHaptic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Item 94 — manage custom slash commands: list, add/edit (trigger,
 * description, template with a live preview), enable/disable, delete.
 *
 * Chat integration (owned by the chat theme worker): these appear in the
 * chat `/` popup and expand on send — see [SlashCommandStore] docs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlashCommandsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SlashCommandStore(context) }
    val commands by store.commands.collectAsState()
    val haptics = rememberHaptic()

    var editing by remember { mutableStateOf<CustomSlashCommand?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CustomSlashCommand?>(null) }

    Scaffold(
        topBar = {
            MuseTopAppBar(
                title = { Text("Slash commands", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = { haptics.tap(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                haptics.tap()
                editing = null
                showEditor = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add command")
            }
        },
    ) { padding ->
        if (commands.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No custom commands yet",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Define shortcuts like /brief that expand into full prompts. Use {args} for what you type after the trigger, {date} and {time} for the current date/time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(commands, key = { it.id }) { command ->
                MuseCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.tap()
                            editing = command
                            showEditor = true
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "/${command.trigger}",
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (command.description.isNotBlank()) {
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                command.template.take(120),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                            )
                        }
                        IconButton(onClick = {
                            haptics.error()
                            pendingDelete = command
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = command.enabled,
                            onCheckedChange = { haptics.toggle(); store.setEnabled(command.id, it) },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        SlashCommandEditorDialog(
            initial = editing,
            existingTriggers = commands.map { it.trigger }.toSet(),
            onDismiss = { showEditor = false },
            onSave = { trigger, description, template ->
                val base = editing ?: CustomSlashCommand(trigger = trigger, description = description, template = template)
                val saved = store.upsert(base.copy(trigger = trigger, description = description, template = template))
                if (saved != null) showEditor = false
                saved != null
            },
        )
    }

    pendingDelete?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete /${command.trigger}?") },
            text = { Text("This shortcut will stop working in chat.") },
            confirmButton = {
                UnibotTextButton(onClick = {
                    haptics.error()
                    store.delete(command.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                UnibotTextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SlashCommandEditorDialog(
    initial: CustomSlashCommand?,
    existingTriggers: Set<String>,
    onDismiss: () -> Unit,
    /** Returns false when the save was rejected (caller keeps the dialog open). */
    onSave: (trigger: String, description: String, template: String) -> Boolean,
) {
    var trigger by remember { mutableStateOf(initial?.trigger ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var template by remember { mutableStateOf(initial?.template ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val sanitized = CustomSlashCommand.sanitizeTrigger(trigger)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New slash command" else "Edit /${initial.trigger}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogTextField(
                    value = trigger,
                    onValueChange = { trigger = it; error = null },
                    placeholder = "Trigger, e.g. brief",
                    singleLine = true,
                    isError = error != null,
                )
                if (sanitized.isNotEmpty() && sanitized != trigger.trim().lowercase()) {
                    Text(
                        "Will be saved as /$sanitized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DialogTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Description (shown in the / menu)",
                    singleLine = true,
                )
                DialogTextField(
                    value = template,
                    onValueChange = { template = it; error = null },
                    placeholder = "Prompt template — use {args}, {date}, {time}",
                    singleLine = false,
                    modifier = Modifier.height(140.dp),
                    isError = error != null,
                )
                if (template.isNotBlank()) {
                    Text("Preview", style = MaterialTheme.typography.labelLarge)
                    Text(
                        SlashCommandExpander.renderTemplate(template, "example args"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error != null) {
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            UnibotButton(onClick = {
                if (sanitized.isBlank()) {
                    error = "Enter a trigger word."
                    return@UnibotButton
                }
                if (sanitized in existingTriggers && sanitized != initial?.trigger) {
                    error = "/$sanitized already exists."
                    return@UnibotButton
                }
                if (template.isBlank()) {
                    error = "Enter a prompt template."
                    return@UnibotButton
                }
                onSave(sanitized, description.trim(), template)
            }) { Text("Save") }
        },
        dismissButton = {
            UnibotTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
