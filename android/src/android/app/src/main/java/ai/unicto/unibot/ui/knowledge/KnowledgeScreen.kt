package ai.unicto.unibot.ui.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.knowledge.KnowledgeDoc
import ai.unicto.unibot.knowledge.KnowledgeHit
import ai.unicto.unibot.knowledge.KnowledgeKind
import ai.unicto.unibot.knowledge.KnowledgePrefs
import ai.unicto.unibot.knowledge.KnowledgeRepository
import ai.unicto.unibot.knowledge.KnowledgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The personal knowledge base screen (item 47).
 *
 * Lists indexed documents, adds files via the system picker, searches doc
 * titles and content, toggles chat-time retrieval, and deletes documents.
 * Professional-UI rules: semantic color/type roles only, loading / empty /
 * error states, no decoration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onBack: () -> Unit,
    onOpenDocument: (docId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        KnowledgeRepository(KnowledgeStore(File(context.filesDir, "knowledge")))
    }
    val prefs = remember(context) { KnowledgePrefs(context) }

    var docs by remember { mutableStateOf<List<KnowledgeDoc>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<KnowledgeHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchEnabled by remember { mutableStateOf(prefs.searchEnabled) }
    var pendingDelete by remember { mutableStateOf<KnowledgeDoc?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { withContext(Dispatchers.IO) { repository.listDocs() } }
                .onSuccess { docs = it; loading = false }
                .onFailure { error = it.message ?: "Couldn't load"; loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // Debounced content search as the query changes.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(300)
        if (query.trim() != q) return@LaunchedEffect
        runCatching { withContext(Dispatchers.Default) { repository.search(q, topK = 12) } }
            .onSuccess { hits = it }
            .onFailure { hits = emptyList() }
        searching = false
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val name = queryDisplayName(context, uri) ?: "document"
                    val mime = context.contentResolver.getType(uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't read the file")
                    repository.addFile(name, mime, bytes)
                }
            }
            importing = false
            result
                .onSuccess { refresh() }
                .onFailure { error = it.message ?: "Couldn't add the file" }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ub_knowledge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.ub_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.ub_knowledge_add),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (loading || importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Retrieval toggle — chat searches the base before each send.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ub_knowledge_search_toggle),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.ub_knowledge_search_toggle_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = searchEnabled,
                    onCheckedChange = {
                        searchEnabled = it
                        prefs.searchEnabled = it
                    },
                )
            }
            HorizontalDivider()

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.ub_knowledge_search_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
            )

            when {
                error != null -> KnowledgeErrorState(
                    message = error!!,
                    onRetry = { refresh() },
                )
                loading && docs.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                query.isNotBlank() -> KnowledgeSearchResults(
                    hits = hits,
                    searching = searching,
                    onOpenDocument = onOpenDocument,
                )
                docs.isEmpty() -> KnowledgeEmptyState(
                    onAdd = { picker.launch(arrayOf("*/*")) },
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(docs, key = { it.id }) { doc ->
                        KnowledgeDocRow(
                            doc = doc,
                            onOpen = { onOpenDocument(doc.id) },
                            onDelete = { pendingDelete = doc },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ub_knowledge_delete_title)) },
            text = { Text(stringResource(R.string.ub_knowledge_delete_body, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.deleteDoc(target.id) }
                        refresh()
                    }
                }) { Text(stringResource(R.string.ub_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.ub_cancel))
                }
            },
        )
    }
}

@Composable
private fun KnowledgeDocRow(
    doc: KnowledgeDoc,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = remember(doc.addedAtMs) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.addedAtMs))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (doc.kind) {
                KnowledgeKind.PDF -> Icons.Outlined.PictureAsPdf
                KnowledgeKind.NOTE -> Icons.AutoMirrored.Outlined.NoteAdd
                KnowledgeKind.IMAGE_TEXT -> Icons.Outlined.Image
                KnowledgeKind.CODE -> Icons.Outlined.Code
                KnowledgeKind.DOCUMENT -> Icons.Outlined.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = doc.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${kindLabel(doc.kind)} · ${doc.chunkCount} excerpts · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.ub_knowledge_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun kindLabel(kind: KnowledgeKind): String = stringResource(
    when (kind) {
        KnowledgeKind.PDF -> R.string.ub_knowledge_kind_pdf
        KnowledgeKind.NOTE -> R.string.ub_knowledge_kind_note
        KnowledgeKind.IMAGE_TEXT -> R.string.ub_knowledge_kind_image
        KnowledgeKind.CODE -> R.string.ub_knowledge_kind_code
        KnowledgeKind.DOCUMENT -> R.string.ub_knowledge_kind_doc
    },
)

@Composable
private fun KnowledgeSearchResults(
    hits: List<KnowledgeHit>?,
    searching: Boolean,
    onOpenDocument: (docId: String) -> Unit,
) {
    when {
        searching && hits == null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        hits.isNullOrEmpty() -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.ub_knowledge_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(hits!!, key = { "${it.doc.id}:${it.chunkIndex}" }) { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDocument(hit.doc.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = hit.citation(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = hit.text.take(280),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun KnowledgeEmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ub_knowledge_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ub_knowledge_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(onClick = onAdd) {
            Text(stringResource(R.string.ub_knowledge_add))
        }
    }
}

@Composable
private fun KnowledgeErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.ub_retry))
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
