package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.photos.PhotosConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

private fun p3Title(argsJson: String, fallback: String): String =
    JSONObject(argsJson).optString("tool_title", fallback)

private fun p3Param(argsJson: String, key: String): String =
    JSONObject(argsJson).optString(key, "").trim()

/**
 * Google Photos connector agent tools (Google OAuth, gated on connection).
 *
 * Thumbnails are downloaded with the bearer token into the app cache dir
 * (the Photos baseUrls need auth, so chat can't load them directly) and
 * embedded as `![filename](file://…)` markdown — chat's StreamingMarkdownText
 * renders those as tap-to-view images natively, no shared-file changes needed.
 */
object PhotosTool {

    const val SEARCH_NAME = "photos_search"
    const val LIST_RECENT_NAME = "photos_list_recent"

    fun isConnected(context: Context): Boolean = PhotosConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search the user's Google Photos by filename or description. The Photos API has no free-text search, so this matches against recent items and shows the most recent photos as a fallback.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Search text, e.g. 'beach', 'IMG_2024', 'birthday'."),
                "max_results" to AgentToolParam("integer", "Max photos to return (default 6, max 12)."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "max_results"),
        ),
        AgentToolDefinition(
            name = LIST_RECENT_NAME,
            description = "Show the user's most recent Google Photos (filename, date, thumbnail).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "max_results" to AgentToolParam("integer", "Max photos to return (default 6, max 12)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "max_results"),
        ),
    )

    private fun maxResults(argsJson: String): Int =
        JSONObject(argsJson).optInt("max_results", 6).coerceIn(1, 12)

    /**
     * Render one photo as a markdown image plus a caption line.
     * The thumbnail download may fail (expired baseUrl, network) — then we
     * still list the photo textually.
     */
    private suspend fun photoLine(context: Context, photo: PhotosConnector.Photo): String {
        val thumb = PhotosConnector.downloadThumbnail(context, photo)
        val caption = photo.filename.ifBlank { photo.id }
        val date = photo.creationTime.take(10).ifBlank { "date unknown" }
        return if (thumb != null) {
            "![$caption](${PhotosConnector.fileUri(thumb)})\n$caption ($date, id: ${photo.id})"
        } else {
            "$caption ($date, id: ${photo.id}) — thumbnail unavailable"
        }
    }

    private suspend fun render(
        context: Context,
        photos: List<PhotosConnector.Photo>,
        header: String,
        title: String,
    ): ToolExecutionResult {
        if (photos.isEmpty()) {
            return ToolExecutionResult("No photos found.", true, toolTitle = title)
        }
        val out = buildString {
            appendLine(header)
            appendLine()
            photos.forEach { appendLine(photoLine(context, it)); appendLine() }
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = title)
    }

    suspend fun executeSearch(argsJson: String, context: Context): ToolExecutionResult {
        val title = p3Title(argsJson, SEARCH_NAME)
        val q = p3Param(argsJson, "query")
        if (q.isEmpty()) return ToolExecutionResult("Error: 'query' is required", false, toolTitle = title)
        return when (val r = PhotosConnector.search(context, q, maxResults(argsJson))) {
            is PhotosConnector.ApiResult.Ok -> {
                val header = if (r.value.isEmpty()) "No photos matched \"$q\"." else "Google Photos results for \"$q\":"
                render(context, r.value, header, title)
            }
            is PhotosConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is PhotosConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeListRecent(argsJson: String, context: Context): ToolExecutionResult {
        val title = p3Title(argsJson, LIST_RECENT_NAME)
        return when (val r = PhotosConnector.listRecent(context, maxResults(argsJson))) {
            is PhotosConnector.ApiResult.Ok ->
                render(context, r.value, "Your most recent Google Photos:", title)
            is PhotosConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is PhotosConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}
