package ai.unicto.unibot.ui.chat

// [P2-prompt-library] The library screen: save/load prompt presets and
// composer modes. Entry points: Settings → Prompt Library (see
// SettingsScreen), `/` menu mode rows, @-mention text snippets.

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.R
import ai.unicto.unibot.ui.home.HomeBus
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    PromptLibraryStore.ensureInit(context)
    val presets by PromptLibraryStore.presets.collectAsState()
    val activeModeId by PromptLibraryStore.activeModeId.collectAsState()

    var editorPreset by remember { mutableStateOf<PromptPreset?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PromptPreset?>(null) }
    // v1.4.0 item 95 (power-user handoff) — the "searchable" part: filter both
    // sections by name, description, or content. One-tap insert stays on the
    // row tap (prefillComposer) — search only narrows the list.
    var query by remember { mutableStateOf("") }

    val modes = presets.filter { it.kind == PresetKind.MODE }
        .sortedWith(compareBy({ !it.builtIn }, { it.createdAt }))
    val texts = presets.filter { it.kind == PresetKind.TEXT }
        .sortedWith(compareBy({ !it.builtIn }, { it.createdAt }))
    val q = query.trim().lowercase()
    fun matches(p: PromptPreset): Boolean =
        q.isEmpty() || p.name.lowercase().contains(q) ||
            p.description.lowercase().contains(q) || p.content.lowercase().contains(q)
    val shownModes = modes.filter(::matches)
    val shownTexts = texts.filter(::matches)

    Column(modifier = Modifier.fillMaxSize()) {
        MuseTopAppBar(
            title = { Text(stringResource(R.string.ub_prompt_library_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = {
                    editorPreset = PromptPreset(name = "", kind = PresetKind.TEXT, content = "")
                    editorIsNew = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ub_prompt_library_new))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            // v1.4.0 item 95 — search across the whole library.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.ub_prompt_library_search)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                singleLine = true,
            )
            if (q.isNotEmpty() && shownModes.isEmpty() && shownTexts.isEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                EmptyHint(stringResource(R.string.ub_prompt_library_no_results))
            }
            Spacer(modifier = Modifier.height(8.dp))
            MuseSectionLabel(stringResource(R.string.ub_prompt_library_modes))
            Spacer(modifier = Modifier.height(8.dp))
            MuseCard {
                if (shownModes.isEmpty()) {
                    EmptyHint(
                        if (q.isNotEmpty()) stringResource(R.string.ub_prompt_library_no_results)
                        else stringResource(R.string.ub_prompt_library_empty),
                    )
                } else {
                    shownModes.forEachIndexed { i, preset ->
                        if (i > 0) MuseRowDivider()
                        val isActive = preset.id == activeModeId
                        MuseRow(
                            title = preset.name,
                            icon = Icons.Outlined.School,
                            chevron = false,
                            value = if (isActive) stringResource(R.string.ub_prompt_library_active) else null,
                            titleColor = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                PromptLibraryStore.setActiveModeId(
                                    context,
                                    if (isActive) null else preset.id,
                                )
                            },
                            trailing = {
                                if (!preset.builtIn) {
                                    PresetRowActions(
                                        onEdit = {
                                            editorPreset = preset
                                            editorIsNew = false
                                        },
                                        onDelete = { deleteTarget = preset },
                                    )
                                }
                            },
                        )
                        if (preset.description.isNotBlank()) {
                            MuseCaption(
                                preset.description,
                                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            MuseCaption(stringResource(R.string.ub_prompt_library_hint_mode))

            Spacer(modifier = Modifier.height(16.dp))
            MuseSectionLabel(stringResource(R.string.ub_prompt_library_texts))
            Spacer(modifier = Modifier.height(8.dp))
            MuseCard {
                if (shownTexts.isEmpty()) {
                    EmptyHint(
                        if (q.isNotEmpty()) stringResource(R.string.ub_prompt_library_no_results)
                        else stringResource(R.string.ub_prompt_library_empty),
                    )
                } else {
                    shownTexts.forEachIndexed { i, preset ->
                        if (i > 0) MuseRowDivider()
                        MuseRow(
                            title = preset.name,
                            icon = Icons.Outlined.TextFields,
                            onClick = {
                                // Send the snippet to the chat composer.
                                HomeBus.prefillComposer(preset.content)
                                onBack()
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!preset.builtIn) {
                                        PresetRowActions(
                                            onEdit = {
                                                editorPreset = preset
                                                editorIsNew = false
                                            },
                                            onDelete = { deleteTarget = preset },
                                        )
                                    }
                                }
                            },
                        )
                        if (preset.description.isNotBlank()) {
                            MuseCaption(
                                preset.description,
                                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            MuseCaption(stringResource(R.string.ub_prompt_library_hint_text))
        }
    }

    editorPreset?.let { preset ->
        PresetEditorDialog(
            preset = preset,
            isNew = editorIsNew,
            onDismiss = { editorPreset = null },
            onSave = { updated ->
                PromptLibraryStore.upsert(context, updated)
                editorPreset = null
            },
        )
    }
    deleteTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(preset.name) },
            text = { Text(stringResource(R.string.ub_prompt_library_delete) + "?") },
            confirmButton = {
                TextButton(onClick = {
                    PromptLibraryStore.delete(context, preset.id)
                    deleteTarget = null
                }) {
                    Text(
                        stringResource(R.string.ub_prompt_library_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun PresetRowActions(onEdit: () -> Unit, onDelete: () -> Unit) {
    Row {
        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PresetEditorDialog(
    preset: PromptPreset,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (PromptPreset) -> Unit,
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var description by remember(preset.id) { mutableStateOf(preset.description) }
    var content by remember(preset.id) { mutableStateOf(preset.content) }
    var kind by remember(preset.id) { mutableStateOf(preset.kind) }
    val canSave = name.isNotBlank() && content.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) stringResource(R.string.ub_prompt_library_new)
                else preset.name,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Kind selector (new presets only — kind is fixed afterwards).
                if (isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KindChip(
                            selected = kind == PresetKind.TEXT,
                            label = stringResource(R.string.ub_prompt_library_kind_text),
                            onClick = { kind = PresetKind.TEXT },
                        )
                        KindChip(
                            selected = kind == PresetKind.MODE,
                            label = stringResource(R.string.ub_prompt_library_kind_mode),
                            onClick = { kind = PresetKind.MODE },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ub_prompt_library_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.ub_prompt_library_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.ub_prompt_library_content)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == PresetKind.MODE) {
                    MuseCaption(stringResource(R.string.ub_prompt_library_hint_mode))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(preset.copy(name = name, description = description, content = content, kind = kind))
            }, enabled = canSave) {
                Text(stringResource(R.string.ub_prompt_library_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun KindChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}
