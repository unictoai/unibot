package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.gmail.GmailApi
import ai.unicto.unibot.connectors.gmail.GmailLabelApplier
import ai.unicto.unibot.connectors.gmail.GmailStore
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Gmail connector agent tools. Only exposed when the user has connected
 * their Google account (see [AgentTools.makeAgentTools] `gmailConnected`).
 *
 * - gmail_search: find messages (Gmail search syntax)
 * - gmail_read: read one message's headers + body
 * - gmail_send: send a plain-text email
 *
 * Sending is a consequential action: it runs through the normal tool
 * approval path like every other tool call.
 */
object GmailTool {
    const val SEARCH_NAME = "gmail_search"
    const val READ_NAME = "gmail_read"
    const val SEND_NAME = "gmail_send"
    const val APPLY_LABEL_RULES_NAME = "gmail_apply_label_rules"

    fun isConnected(context: Context): Boolean = GmailStore.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SEARCH_NAME,
            description = "Search the user's Gmail. Returns matching messages (id, from, subject, date, snippet). " +
                "The query uses Gmail search syntax, e.g. 'from:boss@company.com', 'subject:invoice newer_than:30d', 'is:unread'.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "query" to AgentToolParam("string", "Gmail search query (Gmail search syntax)."),
                "max_results" to AgentToolParam("integer", "Max messages to return (default 10, max 25)."),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "max_results"),
        ),
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read one Gmail message by id (from gmail_search). Returns from/to/subject/date and the plain-text body (truncated).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "id" to AgentToolParam("string", "The Gmail message id from gmail_search."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
        AgentToolDefinition(
            name = SEND_NAME,
            description = "Send a plain-text email from the user's Gmail account. " +
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
        AgentToolDefinition(
            name = APPLY_LABEL_RULES_NAME,
            description = "Apply the user's Gmail auto-label rules right now (the same rules that run every 2 hours in the background). " +
                "Use when the user says \"label my mail\" or asks what the rules would do. Reports per-rule counts.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
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
        return when (val r = GmailApi.search(context, query, max)) {
            is GmailApi.ApiResult.Ok -> {
                if (r.value.isEmpty()) {
                    ToolExecutionResult("No messages matched.", true, toolTitle = toolTitle)
                } else {
                    val out = buildString {
                        appendLine("Matched ${r.value.size} message(s):")
                        r.value.forEach {
                            appendLine("- id: ${it.id}")
                            appendLine("  from: ${it.from}")
                            appendLine("  subject: ${it.subject}")
                            appendLine("  date: ${it.date}")
                            if (it.snippet.isNotBlank()) appendLine("  snippet: ${it.snippet}")
                        }
                        append("Use gmail_read with an id to read a full message.")
                    }
                    ToolExecutionResult(out, true, toolTitle = toolTitle)
                }
            }
            is GmailApi.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is GmailApi.ApiResult.Error ->
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
        return when (val r = GmailApi.read(context, id)) {
            is GmailApi.ApiResult.Ok -> {
                val m = r.value
                ToolExecutionResult(
                    "From: ${m.from}\nTo: ${m.to}\nSubject: ${m.subject}\nDate: ${m.date}\n\n${m.body}",
                    true,
                    toolTitle = toolTitle,
                )
            }
            is GmailApi.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is GmailApi.ApiResult.Error ->
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
        return when (val r = GmailApi.send(context, to, subject, body)) {
            is GmailApi.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = toolTitle)
            is GmailApi.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is GmailApi.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeApplyLabelRules(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", APPLY_LABEL_RULES_NAME)
        if (!GmailStore.isConnected(context)) {
            return ToolExecutionResult(
                "Error: Gmail is not connected. Ask the user to connect it in Settings → Connectors.",
                false, toolTitle = toolTitle,
            )
        }
        val report = GmailLabelApplier.applyAll(context)
        if (report.rulesRun == 0) {
            return ToolExecutionResult(
                "No enabled label rules. The user can add some in Settings → Connectors → Gmail → Label rules.",
                true, toolTitle = toolTitle,
            )
        }
        val out = buildString {
            appendLine("Applied ${report.rulesRun} rule(s): ${report.labeledTotal} message(s) labeled.")
            report.perRule.forEach { (name, n) -> appendLine("- $name: $n labeled") }
        }
        return ToolExecutionResult(out.trimEnd(), true, toolTitle = toolTitle)
    }
}
