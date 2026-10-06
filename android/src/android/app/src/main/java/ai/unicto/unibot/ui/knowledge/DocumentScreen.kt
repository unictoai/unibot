package ai.unicto.unibot.ui.knowledge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ai.unicto.unibot.R
import ai.unicto.unibot.knowledge.KnowledgeDoc
import ai.unicto.unibot.knowledge.KnowledgeKind
import ai.unicto.unibot.knowledge.KnowledgeRepository
import ai.unicto.unibot.knowledge.KnowledgeStore
import ai.unicto.unibot.knowledge.PdfPage
import ai.unicto.unibot.knowledge.PdfTextExtractor
import ai.unicto.unibot.ui.chat.MarkdownDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Focused document reader (item 49): Markdown/code rendering with
 * ask-about-this-doc.
 *
 * The ask box stages the question as a chat prefill scoped to THIS document
 * ([onAskInChat]): the next send from the new chat searches only this doc,
 * so answers cite its pages. Zero chat-core changes beyond the existing
 * send hook.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    docId: String,
    onBack: () -> Unit,
    onAskInChat: (draftSessionId: String, docId: String, question: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) {
        KnowledgeRepository(KnowledgeStore(File(context.filesDir, "knowledge")))
    }

    var doc by remember(docId) { mutableStateOf<KnowledgeDoc?>(null) }
    var pages by remember(docId) { mutableStateOf<List<PdfPage>?>(null) }
    var body by remember(docId) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }

    LaunchedEffect(docId) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val found = repository.listDocs().firstOrNull { it.id == docId }
                    ?: throw IllegalStateException("Document not found")
                val bytes = repository.contentFile(docId)
                    .takeIf { it.exists() }
                    ?.readBytes()
                found to bytes
            }
        }.onSuccess { (found, bytes) ->
            doc = found
            if (found.kind == KnowledgeKind.PDF && bytes != null) {
                pages = withContext(Dispatchers.Default) { PdfTextExtractor.extract(bytes) }
                if (pages.isNullOrEmpty()) {
                    error = context.getString(R.string.ub_doc_pdf_no_text)
                }
            } else {
                val text = bytes?.toString(Charsets.UTF_8)?.trim().orEmpty()
                body = when (found.kind) {
                    KnowledgeKind.CODE -> "```\n$text\n```"
                    else -> text
                }
                if (body.isNullOrEmpty()) {
                    error = context.getString(R.string.ub_doc_empty)
                }
            }
            loading = false
        }.onFailure {
            error = it.message ?: context.getString(R.string.ub_doc_load_failed)
            loading = false
        }
    }

    fun submitQuestion() {
        val q = question.trim()
        if (q.isEmpty()) return
        question = ""
        onAskInChat("__new__${UUID.randomUUID()}", docId, q)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        doc?.title ?: stringResource(R.string.ub_doc_title),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.ub_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // Ask-about-this-doc: prefilled into a new chat, scoped to doc.
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.ub_doc_ask_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitQuestion() }),
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { submitQuestion() },
                        enabled = question.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.ub_doc_ask_send),
                            tint = if (question.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                error != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                pages != null -> PdfPagesView(pages = pages!!)
                body != null -> MarkdownDocument(
                    content = body!!,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                )
            }
        }
    }
}

/**
 * PDF rendering as extracted page text with page headers — an honest view of
 * exactly what the knowledge index (and therefore Q&A citations) sees.
 */
@Composable
private fun PdfPagesView(pages: List<PdfPage>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        items(pages, key = { it.number }) { page ->
            Text(
                text = stringResource(R.string.ub_doc_page_header, page.number),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = page.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
        }
    }
}
