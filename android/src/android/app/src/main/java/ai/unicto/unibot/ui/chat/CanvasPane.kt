package ai.unicto.unibot.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.ui.home.MuseTones
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * P5 content creation — Canvas: a side-by-side editor for long-form content.
 *
 * Flow:
 * 1. The assistant emits ```unibot-canvas {"title": "...", "text": "...",
 *    "suggestions": [{"title": "...", "find": "...", "replace": "..."}]}
 *    → [CanvasCard] renders an inline preview with "Open in Canvas".
 *    Long code blocks (>= [CANVAS_MIN_CHARS]) also get an "Open in Canvas"
 *    affordance in their header, wired in StreamingMarkdownText.
 * 2. [CanvasStore.open] puts the document in the process-wide store.
 * 3. [CanvasHost] — placed ONCE near the chat scaffold (e.g. inside
 *    UnibotHome, above the tab content) — renders the slide-over editor
 *    panel whenever a document is open: full-width sheet on phones,
 *    right-anchored 560dp panel on wide screens.
 * 4. Targeted edits arrive as ```unibot-canvas-edit {"title": "...",
 *    "find": "...", "replace": "..."} → [CanvasEditCard] with an
 *    "Apply suggestion" button and a +/- diff preview; applying patches the
 *    open document in place (or opens it first).
 *
 * The document lives in memory for the session; Copy / Share (writes a
 * `.md` into cacheDir/shared and fires the system share sheet via the
 * existing FileProvider) export it. Phone-friendly: the editor is a plain
 * scrolling text field, no desktop-only chrome.
 */

/** Code blocks at least this long get the "Open in Canvas" header affordance. */
internal const val CANVAS_MIN_CHARS = 400

data class CanvasDoc(val id: String, val title: String, val text: String)

data class CanvasSuggestion(val title: String, val find: String, val replace: String)

object CanvasStore {
    private val _doc = MutableStateFlow<CanvasDoc?>(null)
    val doc: StateFlow<CanvasDoc?> = _doc

    fun open(title: String, text: String) {
        _doc.value = CanvasDoc(UUID.randomUUID().toString(), title.ifBlank { "Document" }, text)
        AppLogger.info("Canvas", "opened '${title.take(40)}' (${text.length} chars)")
    }

    fun openSuggestionTarget(title: String, text: String): CanvasDoc {
        val existing = _doc.value
        return if (existing != null) existing
        else CanvasDoc(UUID.randomUUID().toString(), title.ifBlank { "Document" }, text).also { _doc.value = it }
    }

    fun close() { _doc.value = null }

    fun setText(text: String) {
        _doc.value = _doc.value?.copy(text = text)
    }

    /**
     * Applies a targeted edit: replaces the FIRST occurrence of [suggestion.find]
     * with [suggestion.replace]. Returns false when the anchor text isn't found
     * (stale suggestion) — the UI then says so instead of silently doing nothing.
     */
    fun applySuggestion(suggestion: CanvasSuggestion): Boolean {
        val doc = _doc.value ?: return false
        val idx = doc.text.indexOf(suggestion.find)
        if (idx < 0) return false
        val patched = doc.text.substring(0, idx) + suggestion.replace +
            doc.text.substring(idx + suggestion.find.length)
        _doc.value = doc.copy(text = patched)
        AppLogger.info("Canvas", "applied suggestion '${suggestion.title.take(40)}'")
        return true
    }
}

/**
 * Slide-over editor panel. Place once near the chat scaffold; it renders
 * nothing until [CanvasStore.open] is called.
 */
@Composable
fun CanvasHost() {
    val doc by CanvasStore.doc.collectAsState()
    val current = doc ?: return
    Dialog(
        onDismissRequest = { CanvasStore.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = if (maxWidth > 700.dp) 560.dp else maxWidth * 0.94f
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MuseTones.hairline),
                ) {
                    CanvasTopBar(doc = current, onClose = { CanvasStore.close() })
                    CanvasEditor(
                        text = current.text,
                        onTextChange = { CanvasStore.setText(it) },
                        modifier = Modifier.weight(1f),
                    )
                    CanvasBottomBar(doc = current)
                }
            }
        }
    }
}

@Composable
private fun CanvasTopBar(doc: CanvasDoc, onClose: () -> Unit) {
    val words = remember(doc.text) {
        doc.text.split(Regex("\\s+")).count { it.isNotBlank() }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                "$words words · ${doc.text.length} chars",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close canvas")
        }
    }
}

@Composable
private fun CanvasEditor(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MuseTones.fill)
            .padding(4.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(12.dp),
        )
    }
}

@Composable
private fun CanvasBottomBar(doc: CanvasDoc) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(doc.text))
            AppLogger.info("Canvas", "copied ${doc.text.length} chars")
        }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text("Copy")
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { shareCanvasDoc(context, doc) }) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text("Share")
        }
    }
}

private fun shareCanvasDoc(context: android.content.Context, doc: CanvasDoc) {
    runCatching {
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safe = doc.title.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(48).ifEmpty { "canvas" }
        val file = File(sharedDir, "$safe.md").apply { writeText(doc.text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, doc.title))
        AppLogger.info("Canvas", "shared ${file.name}")
    }.onFailure { AppLogger.warning("Canvas", "share failed: ${it.message}") }
}

/** Inline card for a ```unibot-canvas fence: preview + open + suggestion chips. */
@Composable
fun CanvasCard(json: String, modifier: Modifier = Modifier) {
    val parsed = remember(json) { parseCanvasJson(json) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MuseTones.surface)
            .border(1.dp, MuseTones.hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (parsed == null) {
            Text("Couldn't read this canvas document — showing raw:", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(json, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CANVAS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(parsed.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        val preview = remember(parsed.text) { parsed.text.lines().take(8).joinToString("\n") }
        Text(
            preview, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8,
        )
        if (parsed.text.lines().size > 8) {
            Text("… ${parsed.text.lines().size - 8} more lines",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { CanvasStore.open(parsed.title, parsed.text) }) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Open in Canvas")
            }
        }
        parsed.suggestions.forEach { s ->
            Spacer(Modifier.height(6.dp))
            CanvasSuggestionRow(suggestion = s, docTitle = parsed.title, docText = parsed.text)
        }
    }
}

/** Inline card for a ```unibot-canvas-edit fence: targeted in-place edit with diff + apply. */
@Composable
fun CanvasEditCard(json: String, modifier: Modifier = Modifier) {
    val suggestion = remember(json) { parseSuggestionJson(json) }
    val doc by CanvasStore.doc.collectAsState()
    var applied by remember { mutableStateOf<Boolean?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    // Reset the applied flag whenever a different document is opened.
    LaunchedEffect(doc?.id) { applied = null }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MuseTones.surface)
            .border(1.dp, MuseTones.hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (suggestion == null) {
            Text("Couldn't read this edit suggestion.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Text("SUGGESTED EDIT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(suggestion.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    if (doc != null) applied = CanvasStore.applySuggestion(suggestion)
                },
                enabled = doc != null,
            ) {
                Text(if (applied == true) "Applied ✓" else "Apply suggestion")
            }
            TextButton(onClick = { showDiff = !showDiff }) {
                Text(if (showDiff) "Hide diff" else "Show diff")
            }
        }
        if (doc == null) {
            Spacer(Modifier.height(4.dp))
            Text("Open a canvas document first (the card above, or any long reply's edit icon), then apply.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (applied == false) {
            Spacer(Modifier.height(4.dp))
            Text("Couldn't apply — the text it edits isn't in the open document (it may be stale).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (showDiff) {
            Spacer(Modifier.height(6.dp))
            SuggestionDiff(suggestion)
        }
    }
}

@Composable
private fun CanvasSuggestionRow(suggestion: CanvasSuggestion, docTitle: String, docText: String) {
    var applied by remember { mutableStateOf<Boolean?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MuseTones.fill)
            .padding(10.dp),
    ) {
        Text(suggestion.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                CanvasStore.openSuggestionTarget(docTitle, docText)
                applied = CanvasStore.applySuggestion(suggestion)
            }) {
                Text(if (applied == true) "Applied ✓" else "Apply")
            }
            TextButton(onClick = { showDiff = !showDiff }) {
                Text(if (showDiff) "Hide diff" else "Diff")
            }
        }
        if (applied == false) {
            Text("Anchor text not found — open the document first, or the suggestion is stale.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (showDiff) SuggestionDiff(suggestion)
    }
}

/** Minimal +/- diff of one targeted edit: removed lines red, added lines green. */
@Composable
private fun SuggestionDiff(suggestion: CanvasSuggestion) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        suggestion.find.lines().forEach { line ->
            Text("- $line", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = Color(0xFFFF453A))
        }
        suggestion.replace.lines().forEach { line ->
            Text("+ $line", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = Color(0xFF30D158))
        }
    }
}

private data class ParsedCanvas(val title: String, val text: String, val suggestions: List<CanvasSuggestion>)

private fun parseCanvasJson(json: String): ParsedCanvas? = runCatching {
    val o = JSONObject(json.trim())
    ParsedCanvas(
        title = o.optString("title", "Document"),
        text = o.optString("text", ""),
        suggestions = o.optJSONArray("suggestions")?.let { arr ->
            List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                CanvasSuggestion(s.optString("title", "Suggestion"), s.optString("find"), s.optString("replace"))
            }
        }.orEmpty(),
    )
}.getOrNull()

private fun parseSuggestionJson(json: String): CanvasSuggestion? = runCatching {
    val o = JSONObject(json.trim())
    CanvasSuggestion(
        title = o.optString("title", "Suggested edit"),
        find = o.optString("find"),
        replace = o.optString("replace"),
    ).takeIf { it.find.isNotEmpty() }
}.getOrNull()
