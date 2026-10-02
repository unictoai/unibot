package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.drive.DriveConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Google Drive connector agent tools. Only exposed when the user has
 * connected Drive in Settings → Connectors.
 */
object DriveTool {
    const val SEARCH_NAME = "drive_search"
    const val READ_NAME = "drive_read"

    fun isConnected(context: Context): Boolean = DriveConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search the user's Google Drive by file name/content. Returns matching files (id, name, type, modified time).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Free-text query matched against file names and content."),
                "max_results" to AgentToolParam("integer", "Max files to return (default 10, max 25)."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "max_results"),
        ),
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read a Google Drive file by id (from drive_search). Google Docs/Sheets/Slides are returned as plain text. Binary files return an error.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "id" to AgentToolParam("string", "The Drive file id from drive_search."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
    )

    suspend fun executeSearch(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", SEARCH_NAME)
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' is required", false, toolTitle = toolTitle)
        }
        return when (val r = DriveConnector.search(context, query, args.optInt("max_results", 10))) {
            is DriveConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) {
                    ToolExecutionResult("No files matched.", true, toolTitle = toolTitle)
                } else {
                    val out = buildString {
                        appendLine("Matched ${r.value.size} file(s):")
                        r.value.forEach {
                            appendLine("- id: ${it.id}")
                            appendLine("  name: ${it.name}")
                            appendLine("  type: ${it.mimeType}")
                            appendLine("  modified: ${it.modifiedTime}")
                        }
                        append("Use drive_read with an id to read a file.")
                    }
                    ToolExecutionResult(out, true, toolTitle = toolTitle)
                }
            }
            is DriveConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is DriveConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeRead(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", READ_NAME)
        val id = args.optString("id", "").trim()
        if (id.isEmpty()) {
            return ToolExecutionResult("Error: 'id' is required", false, toolTitle = toolTitle)
        }
        return when (val r = DriveConnector.read(context, id)) {
            is DriveConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = toolTitle)
            is DriveConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is DriveConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }
}
