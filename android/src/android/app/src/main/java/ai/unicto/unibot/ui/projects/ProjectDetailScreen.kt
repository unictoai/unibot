package ai.unicto.unibot.ui.projects

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import ai.unicto.unibot.UnibotApp
import ai.unicto.unibot.projects.Project
import ai.unicto.unibot.projects.ProjectSessions
import ai.unicto.unibot.projects.ProjectStore
import ai.unicto.unibot.ui.home.HomeShell
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseSectionLabel
import ai.unicto.unibot.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch

/**
 * A project's detail: its chats, its files, its custom instructions.
 * Chats open inside the home shell; new chats are filed into the project.
 * Files are copied into the project's own on-device dir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    navController: NavController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? UnibotApp
    val store = remember { ProjectStore.get(context) }
    val projects by store.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var newChatBusy by remember { mutableStateOf(false) }

    val sessions by remember(app) {
        app?.chatRepositoryOrNull?.observeSessions()
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val projectSessions = remember(project, sessions) {
        val ids = project?.sessionIds ?: emptyList()
        sessions.filter { it.id in ids }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null || project == null || app == null) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val name = queryDisplayName(context, uri) ?: "file_${System.currentTimeMillis()}"
            store.copyIntoProject(project.id, uri, name)
        }
    }

    if (project == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(project.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete project")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // ── Chats ──
            item {
                MuseSectionLabel("Chats")
                MuseCard {
                    if (projectSessions.isEmpty()) {
                        Text(
                            "No chats yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        projectSessions.forEachIndexed { i, s ->
                            if (i > 0) MuseRowDivider()
                            MuseRow(
                                title = s.title?.takeIf { it.isNotBlank() } ?: "New chat",
                                onClick = { HomeShell.openSession(navController, s.id) },
                                icon = Icons.Outlined.ChatBubbleOutline,
                            )
                        }
                    }
                    MuseRowDivider()
                    MuseRow(
                        title = "New chat in project",
                        onClick = {
                            if (newChatBusy || app == null) return@MuseRow
                            newChatBusy = true
                            scope.launch {
                                val id = ProjectSessions.create(app, project.id)
                                newChatBusy = false
                                if (id != null) HomeShell.openSession(navController, id)
                            }
                        },
                        icon = Icons.Outlined.Add,
                        chevron = false,
                    )
                }
            }

            // ── Files ──
            item {
                Spacer(Modifier.height(16.dp))
                MuseSectionLabel("Files")
                MuseCard {
                    if (project.files.isEmpty()) {
                        Text(
                            "Attach reference files to this project — they stay on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        project.files.forEachIndexed { i, f ->
                            if (i > 0) MuseRowDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(26.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.name, fontSize = 16.sp, lineHeight = 21.sp)
                                    if (f.sizeBytes > 0) {
                                        Text(
                                            formatBytes(f.sizeBytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        store.fileFor(project.id, f).takeIf { it.exists() }?.delete()
                                        store.upsert(project.copy(files = project.files - f))
                                    }
                                }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Remove file",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    MuseRowDivider()
                    MuseRow(
                        title = "Add file",
                        onClick = { pickFile.launch("*/*") },
                        icon = Icons.Outlined.Add,
                        chevron = false,
                    )
                }
            }

            // ── Instructions ──
            item {
                Spacer(Modifier.height(16.dp))
                MuseSectionLabel("Custom instructions")
                MuseCard {
                    ProjectInstructionsEditor(project = project, onSave = { store.upsert(it) })
                }
                MuseCaption(
                    "These instructions are added to the system prompt of every chat in this project.",
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }

            // ── Rename ──
            item {
                MuseCard {
                    MuseRow(
                        title = "Rename project",
                        onClick = { showRename = true },
                        icon = Icons.Outlined.Edit,
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) store.upsert(project.copy(name = name.trim()))
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "\"${project.name}\" and its files will be deleted. Its chats stay in the archive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(project.id)
                    showDelete = false
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ProjectInstructionsEditor(project: Project, onSave: (Project) -> Unit) {
    var draft by remember(project.id) { mutableStateOf(project.instructions) }
    val dirty = draft != project.instructions
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .verticalScroll(rememberScrollState()),
            placeholder = {
                Text(
                    "e.g. Always answer in short bullet points. When I paste code, review it line by line.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSave(project.copy(instructions = draft)) },
            enabled = dirty,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save")
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024f / 1024f)
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
