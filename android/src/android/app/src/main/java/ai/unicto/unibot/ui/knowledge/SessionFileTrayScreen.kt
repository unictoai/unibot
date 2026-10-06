package ai.unicto.unibot.ui.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.data.storage.MediaStore
import ai.unicto.unibot.knowledge.SmartNamer
import ai.unicto.unibot.ui.sandbox.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Every file attached in one chat, listed in one place (item 52).
 *
 * Reads the session's persisted messages, collects every stored attachment
 * reference, and resolves them against [MediaStore] — so files stay listed
 * (and openable) across restarts (item 51). Files whose bytes are gone are
 * shown as unavailable rather than silently dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionFileTrayScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaStore = remember(context) { MediaStore(context) }

    var entries by remember(sessionId) { mutableStateOf<List<TrayEntry>?>(null) }

    LaunchedEffect(sessionId) {
        entries = withContext(Dispatchers.IO) {
            runCatching { collectTrayEntries(chatRepository, mediaStore, sessionId) }
                .getOrDefault(emptyList())
        }
    }    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ub_file_tray_title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.ub_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val list = entries) {
                null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> if (list.isEmpty()) {
                    TrayEmptyState()
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.key }) { entry ->
                            TrayRow(
                                entry = entry,
                                onOpen = {
                                    entry.file?.let { f ->
                                        onOpenFile(
                                            FileItem.from(f)
                                                ?: FileItem(f, f.name, false, false, f.length(), f.lastModified()),
                                        )
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private data class TrayEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val file: File?,
    val mimeType: String,
    val sentAtMs: Long,
)

/**
 * Walks every persisted message of the session and collects attachment
 * references. Suspends on the DAO read; the parse itself is pure.
 */
private suspend fun collectTrayEntries(
    chatRepository: ChatRepository,
    mediaStore: MediaStore,
    sessionId: String,
): List<TrayEntry> {
    // ChatRepository.dao is the same accessor LibraryIndex uses.
    val messages = withContext(Dispatchers.IO) { chatRepository.dao.loadMessages(sessionId) }
    val out = ArrayList<TrayEntry>()
    for (entity in messages) {
        val arr = runCatching { JSONArray(entity.partsJson) }.getOrNull() ?: continue
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type") != "mediaRef") continue
            val value = obj.optJSONObject("value") ?: continue
            val rel = value.optString("relativePath", "")
            if (rel.isEmpty()) continue
            val mime = value.optString("mimeType", "")
            val original = value.optString("originalFileName", "").ifEmpty { null }
            val file = File(mediaStore.mediaBaseDir, rel)
            val exists = file.exists() && file.isFile
            val title = original
                ?: SmartNamer.suggestTitle(file.name, mime.ifEmpty { null }, null)
            val subtitle = buildString {
                append(if (mime.isNotEmpty()) mime else "file")
                if (exists) {
                    append(" · ")
                    append(formatSize(file.length()))
                } else {
                    append(" · unavailable")
                }
                append(" · ")
                append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entity.createdAt)))
            }
            out += TrayEntry(
                key = "${entity.id}:$rel",
                title = title,
                subtitle = subtitle,
                file = if (exists) file else null,
                mimeType = mime,
                sentAtMs = entity.createdAt,
            )
        }
    }
    return out.sortedByDescending { it.sentAtMs }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024f * 1024f))
}

@Composable
private fun TrayRow(entry: TrayEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.file != null, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                entry.mimeType.startsWith("image/") -> Icons.Outlined.Image
                entry.mimeType.startsWith("video/") -> Icons.Outlined.VideoFile
                entry.mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
                entry.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
                else -> Icons.Outlined.Description
            },
            contentDescription = null,
            tint = if (entry.file != null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (entry.file != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrayEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.FolderOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ub_file_tray_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ub_file_tray_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
