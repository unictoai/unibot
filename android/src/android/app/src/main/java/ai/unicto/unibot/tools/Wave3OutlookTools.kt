package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.outlook.OutlookConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Outlook connector agent tools (Microsoft Graph, gated on connection).
 * Mirrors [GmailTool]: search + read + send.
 *
 * - outlook_search: find messages (Graph $search syntax)
 * - outlook_read: read one message's headers + body
 * - outlook_send: send a plain-text email
 *
 * Sending is a consequential action: it runs through the normal tool
 * approval path like every other tool call.
 */
object OutlookTool {

    const val SEARCH_NAME = "outlook_search"
    const val READ_NAME = "outlook_read"
    const val SEND_NAME = "outlook_send"

    fun isConnected(context: Context): Boolean = OutlookConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search the user's Outlook mail. Returns matching messages (id, from, subject, received time, preview). " +
                "The query uses Microsoft Graph search syntax, e.g. 'from:boss@company.com', 'subject:invoice'.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Outlook search query (Graph search syntax)."),
                "max_results" to AgentToolParam("integer", "Max messages to return (default 10, max 25)."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "max_results"),
        ),
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read one Outlook message by id (from outlook_search). Returns from/to/subject/date and the plain-text body (truncated).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "id" to AgentToolParam("string", "The Outlook message id from outlook_search."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
        AgentToolDefinition(
            name = SEND_NAME,
            description = "Send a plain-text email from the user's Outlook account. " +
                "Only send when the user explicitly asked for an email to be sent, and confirm the recipient and content with them first if there is any doubt.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "to" to AgentToolParam("string", "Recipient email address."),
                "subject" to AgentToolParam("string", "Email subject."),
                "body" to AgentToolParam("string", "Plain-text email body."),
            ),
            required = listOf("tool_title", "to", "subject", "body"),
            propertyOrdering = listOf("tool_title", "to", "subject", "body"),
        ),
    )

    suspend fun executeSearch(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", SEARCH_NAME)
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' is required", false, toolTitle = toolTitle)
        }
        val max = args.optInt("max_results", 10)
        return when (val r = OutlookConnector.searchMail(context, query, max)) {
            is OutlookConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) {
                    ToolExecutionResult("No messages matched.", true, toolTitle = toolTitle)
                } else {
                    val out = buildString {
                        appendLine("Matched ${r.value.size} message(s):")
                        r.value.forEach {
                            appendLine("- id: ${it.id}")
                            appendLine("  from: ${it.from}")
                            appendLine("  subject: ${it.subject}")
                            appendLine("  date: ${it.receivedAt}")
                            if (it.snippet.isNotBlank()) appendLine("  snippet: ${it.snippet.take(200)}")
                        }
                        append("Use outlook_read with an id to read a full message.")
                    }
                    ToolExecutionResult(out, true, toolTitle = toolTitle)
                }
            }
            is OutlookConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is OutlookConnector.ApiResult.Error ->
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
        return when (val r = OutlookConnector.readMail(context, id)) {
            is OutlookConnector.ApiResult.Ok -> {
                val m = r.value
                ToolExecutionResult(
                    "From: ${m.from}\nTo: ${m.to}\nSubject: ${m.subject}\nDate: ${m.receivedAt}\n\n${m.body}",
                    true,
                    toolTitle = toolTitle,
                )
            }
            is OutlookConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is OutlookConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeSend(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", SEND_NAME)
        val to = args.optString("to", "").trim()
        val subject = args.optString("subject", "").trim()
        val body = args.optString("body", "")
        if (to.isEmpty() || subject.isEmpty() || body.isEmpty()) {
            return ToolExecutionResult(
                "Error: 'to', 'subject' and 'body' are all required",
                false,
                toolTitle = toolTitle,
            )
        }
        return when (val r = OutlookConnector.sendMail(context, to, subject, body)) {
            is OutlookConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = toolTitle)
            is OutlookConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is OutlookConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }
}
