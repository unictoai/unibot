package ai.unicto.unibot.share

import android.content.Context
import android.net.Uri
import ai.unicto.unibot.data.repository.ChatRepository
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * [P2-import] Import a chat exported by [ChatExporter] (zip with
 * `messages.json` + `session.json`) back into a new session.
 *
 * Accepted inputs:
 * - The export zip (preferred): messages + title + model restored.
 * - A bare `messages.json` file with the same array shape.
 *
 * Message JSON shape (matches ChatExporter's writer):
 *   `[{"id":…, "role":"user"|"assistant"|…, "content":"<parts_json>", "created_at":…}]`
 * Ids are NOT preserved (fresh UUIDs); order and roles are.
 */
object ChatImporter {

    private const val TAG = "ChatImporter"

    data class ImportResult(
        val sessionId: String,
        val title: String,
        val messageCount: Int,
    )

    /**
     * Read [uri] (content:// from the system file picker) and materialize a
     * new session. Throws on unreadable / malformed input — the caller
     * surfaces a toast.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        repository: ChatRepository,
        defaultModelId: String,
    ): ImportResult = withContext(Dispatchers.IO) {
        // Stage to a temp file so ZipFile gets a seekable handle.
        val staging = File(context.cacheDir, "import-staging").apply { mkdirs() }
        val tmp = File(staging, "import-${UUID.randomUUID()}.bin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Cannot open file")

            var messagesJson: String? = null
            var sessionJson: JSONObject? = null
            var looksLikeZip = false
            try {
                ZipFile(tmp).use { zip ->
                    looksLikeZip = true
                    val messagesEntry = zip.getEntry("messages.json")
                        ?: zip.entries().asSequence().firstOrNull {
                            it.name.endsWith("messages.json", ignoreCase = true)
                        }
                    messagesJson = messagesEntry?.let {
                        zip.getInputStream(it).bufferedReader().readText()
                    }
                    val sessionEntry = zip.getEntry("session.json")
                    sessionJson = sessionEntry?.let {
                        JSONObject(zip.getInputStream(it).bufferedReader().readText())
                    }
                }
            } catch (_: Exception) {
                // Not a zip — fall through to bare-JSON handling.
            }
            if (!looksLikeZip) {
                val text = tmp.readText()
                val trimmed = text.trimStart()
                if (trimmed.startsWith("[")) {
                    messagesJson = text
                } else {
                    // Maybe a bare session.json? No messages → nothing to do.
                    throw IllegalArgumentException("Not a unibot chat export")
                }
            }
            val arrayText = messagesJson
                ?: throw IllegalArgumentException("Archive has no messages.json")
            val array = JSONArray(arrayText)
            if (array.length() == 0) throw IllegalArgumentException("Export has no messages")

            val title = (sessionJson?.optString("title")?.takeIf { it.isNotBlank() }
                ?: "Imported chat")
            val modelId = sessionJson?.optString("model_id")?.takeIf { it.isNotBlank() }
                ?: defaultModelId

            val session = repository.createSession(modelId = modelId, title = title)
            var count = 0
            var lastParts: String? = null
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val role = obj.optString("role", "").lowercase()
                if (role != "user" && role != "assistant" && role != "system") continue
                val parts = obj.optString("content", "")
                if (parts.isBlank()) continue
                val createdAt = obj.optLong("created_at", System.currentTimeMillis())
                repository.importMessage(session.id, role, parts, createdAt)
                lastParts = parts
                count++
            }
            if (count == 0) {
                repository.deleteSession(session.id)
                throw IllegalArgumentException("Export has no importable messages")
            }
            lastParts?.let { repository.updateSessionPreview(session.id, it) }
            AppLogger.info(TAG, "imported $count message(s) into ${session.id}")
            ImportResult(session.id, title, count)
        } finally {
            runCatching { tmp.delete() }
        }
    }
}
