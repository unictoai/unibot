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
    const val LIST_NAME = "drive_list"
    const val ATTACH_NAME = "drive_attach"

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
        AgentToolDefinition(
            name = LIST_NAME,
            description = "Browse a Google Drive folder's contents (files and subfolders, folders first). Use folder_id \"root\" for My Drive top level, or a folder id from drive_search/drive_list to descend.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "folder_id" to AgentToolParam("string", "Drive folder id, or \"root\" for My Drive (default \"root\")."),
                "max_results" to AgentToolParam("integer", "Max entries to return (default 25, max 50)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "folder_id", "max_results"),
        ),
        AgentToolDefinition(
            name = ATTACH_NAME,
            description = "Download a Google Drive file and attach it to the current chat so the user can see/open it. Google Docs/Sheets/Slides are exported as PDF/XLSX/PPTX. Returns a unibot://attachments/ link — put that link in your reply so it renders inline for the user. Only attach files the user asked about or that your answer needs.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "id" to AgentToolParam("string", "The Drive file id from drive_search or drive_list."),
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

    suspend fun executeList(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", LIST_NAME)
        val folderId = args.optString("folder_id", "root").trim().ifEmpty { "root" }
        return when (
            val r = DriveConnector.list(context, folderId, args.optInt("max_results", 25))
        ) {
            is DriveConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) {
                    ToolExecutionResult("Folder is empty.", true, toolTitle = toolTitle)
                } else {
                    val out = buildString {
                        appendLine("Contents of folder '$folderId' (${r.value.size}):")
                        r.value.forEach {
                            val kind = if (it.mimeType == "application/vnd.google-apps.folder") "[folder]" else "[file]"
                            appendLine("- $kind id: ${it.id}")
                            appendLine("  name: ${it.name}")
                            appendLine("  type: ${it.mimeType}")
                            appendLine("  modified: ${it.modifiedTime}")
                        }
                        append("Use drive_read or drive_attach with an id; use drive_list with a folder id to descend.")
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

    /**
     * Download a Drive file into the session's attachments dir so it
     * renders inline in chat via a unibot://attachments/ link.
     * [sessionId] may be null (headless/background) — then the file lands
     * in a shared attachments dir instead.
     */
    suspend fun executeAttach(
        argsJson: String,
        context: Context,
        sessionId: String?,
    ): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", ATTACH_NAME)
        val id = args.optString("id", "").trim()
        if (id.isEmpty()) {
            return ToolExecutionResult("Error: 'id' is required", false, toolTitle = toolTitle)
        }
        val downloaded = when (val r = DriveConnector.download(context, id)) {
            is DriveConnector.ApiResult.Ok -> r.value
            is DriveConnector.ApiResult.NotConnected ->
                return ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is DriveConnector.ApiResult.Error ->
                return ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
        return runCatching {
            val dir = if (sessionId != null) {
                java.io.File(context.filesDir, "minis-sessions/$sessionId/attachments")
            } else {
                java.io.File(context.filesDir, "drive-attachments")
            }.also { it.mkdirs() }
            val safeName = downloaded.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeIf { it.isNotBlank() } ?: "drive-file"
            val file = java.io.File(dir, safeName)
            file.writeBytes(downloaded.bytes)
            val link = "unibot://attachments/$safeName"
            val kb = downloaded.bytes.size / 1024
            ToolExecutionResult(
                "Attached '${downloaded.name}' (${kb} KB) to this chat.\n" +
                    "Link for your reply: $link\n" +
                    "Put that link in your reply so the user can open it inline.",
                true,
                toolTitle = toolTitle,
            )
        }.getOrElse { e ->
            ToolExecutionResult(
                "Error: could not save attachment: ${e.message}", false, toolTitle = toolTitle,
            )
        }
    }
}
