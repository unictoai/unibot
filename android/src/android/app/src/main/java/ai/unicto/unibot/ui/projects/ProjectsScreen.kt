package ai.unicto.unibot.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.unicto.unibot.projects.Project
import ai.unicto.unibot.projects.ProjectStore
import ai.unicto.unibot.ui.home.MuseTones
import ai.unicto.unibot.ui.muse.MuseCard
import ai.unicto.unibot.ui.muse.MuseCaption
import ai.unicto.unibot.ui.muse.MuseRow
import ai.unicto.unibot.ui.muse.MuseRowDivider
import ai.unicto.unibot.ui.muse.MuseTopAppBar

/**
 * Projects list — workspace bundles of chats + files + custom instructions.
 * Muse settings design language: disc back button, centered title, card rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenProject: (projectId: String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ProjectStore.get(context) }
    val projects by store.projects.collectAsState()
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showNew = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "New project")
                    }
                },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(72.dp))
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MuseTones.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "Bundle chats, files and instructions",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A project keeps everything for one topic in one place. Its custom instructions apply to every chat inside it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    MuseCard {
                        projects.forEachIndexed { i, project ->
                            if (i > 0) MuseRowDivider()
                            ProjectListRow(project = project, onClick = { onOpenProject(project.id) })
                        }
                    }
                }
                item {
                    MuseCaption(
                        "Instructions prepend to the system prompt of each chat in the project. Everything stays on this phone.",
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        var colorIndex by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New project") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Color", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(12.dp))
                        Project.COLORS.forEachIndexed { i, c ->
                            val color = androidx.compose.ui.graphics.Color(c)
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(if (i == colorIndex) 32.dp else 26.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable { colorIndex = i },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            store.upsert(Project(name = name.trim(), colorIndex = colorIndex))
                            showNew = false
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectListRow(project: Project, onClick: () -> Unit) {
    MuseRow(
        title = project.name,
        value = listOfNotNull(
            "${project.sessionIds.size} chat${if (project.sessionIds.size == 1) "" else "s"}",
            "${project.files.size} file${if (project.files.size == 1) "" else "s"}",
        ).joinToString(" · "),
        onClick = onClick,
        icon = Icons.Outlined.Folder,
    )
}
