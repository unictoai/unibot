package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.github.GitHubConnector
import ai.unicto.unibot.connectors.telegram.TelegramConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

/**
 * GitHub connector agent tools (personal access token, gated on connection).
 * Only exposed when the user has added a PAT in Settings → Connectors.
 */
object GitHubTool {

    const val REPOS_NAME = "github_repos"
    const val READ_NAME = "github_read"
    const val ISSUES_NAME = "github_issues"
    const val CREATE_ISSUE_NAME = "github_create_issue"

    fun isConnected(context: Context): Boolean = GitHubConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = REPOS_NAME,
            description = "List the user's GitHub repositories (most recently updated first).",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read a file from a GitHub repo. Returns the decoded file text.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "repo" to AgentToolParam("string", "Repository as owner/name, e.g. unictoai/unibot."),
                "path" to AgentToolParam("string", "File path inside the repo, e.g. README.md."),
            ),
            required = listOf("tool_title", "repo", "path"),
            propertyOrdering = listOf("tool_title", "repo", "path"),
        ),
        AgentToolDefinition(
            name = ISSUES_NAME,
            description = "List open issues of a GitHub repo.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "repo" to AgentToolParam("string", "Repository as owner/name, e.g. unictoai/unibot."),
            ),
            required = listOf("tool_title", "repo"),
            propertyOrdering = listOf("tool_title", "repo"),
        ),
        AgentToolDefinition(
            name = CREATE_ISSUE_NAME,
            description = "Open a new issue on a GitHub repo. Only create when the user explicitly asked for it.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "repo" to AgentToolParam("string", "Repository as owner/name, e.g. unictoai/unibot."),
                "title" to AgentToolParam("string", "Issue title."),
                "body" to AgentToolParam("string", "Issue body (markdown)."),
            ),
            required = listOf("tool_title", "repo", "title"),
            propertyOrdering = listOf("tool_title", "repo", "title", "body"),
        ),
    )

    suspend fun executeRepos(argsJson: String, context: Context): ToolExecutionResult {
        val title = JSONObject(argsJson).optString("tool_title", REPOS_NAME)
        return when (val r = GitHubConnector.listRepos(context)) {
            is GitHubConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No repositories found.", true, toolTitle = title)
                val out = buildString {
                    appendLine("GitHub repos:")
                    r.value.forEach {
                        append("- ${it.fullName}${if (it.isPrivate) " (private)" else ""}")
                        if (it.description.isNotBlank()) append(" — ${it.description}")
                        appendLine()
                    }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is GitHubConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is GitHubConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeRead(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = args.optString("tool_title", READ_NAME)
        val repo = args.optString("repo", "").trim()
        val path = args.optString("path", "").trim()
        if (repo.isEmpty() || path.isEmpty()) {
            return ToolExecutionResult("Error: 'repo' and 'path' are required", false, toolTitle = title)
        }
        return when (val r = GitHubConnector.readFile(context, repo, path)) {
            is GitHubConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is GitHubConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is GitHubConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeIssues(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = args.optString("tool_title", ISSUES_NAME)
        val repo = args.optString("repo", "").trim()
        if (repo.isEmpty()) {
            return ToolExecutionResult("Error: 'repo' is required", false, toolTitle = title)
        }
        return when (val r = GitHubConnector.listIssues(context, repo)) {
            is GitHubConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No open issues on $repo.", true, toolTitle = title)
                val out = buildString {
                    appendLine("Open issues on $repo:")
                    r.value.forEach { appendLine("#${it.number} ${it.title} (${it.url})") }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is GitHubConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is GitHubConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeCreateIssue(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = args.optString("tool_title", CREATE_ISSUE_NAME)
        val repo = args.optString("repo", "").trim()
        val issueTitle = args.optString("title", "").trim()
        if (repo.isEmpty() || issueTitle.isEmpty()) {
            return ToolExecutionResult("Error: 'repo' and 'title' are required", false, toolTitle = title)
        }
        return when (val r = GitHubConnector.createIssue(context, repo, issueTitle, args.optString("body", ""))) {
            is GitHubConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is GitHubConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is GitHubConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}

/**
 * Telegram connector agent tools (bot token, gated on connection).
 * Only exposed when the user has added a bot token in Settings → Connectors.
 */
object TelegramTool {

    const val RESOLVE_NAME = "telegram_resolve"
    const val SEND_NAME = "telegram_send"
    const val READ_NAME = "telegram_read"

    fun isConnected(context: Context): Boolean = TelegramConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = RESOLVE_NAME,
            description = "Find the Telegram chat with the user's bot (the user must have messaged " +
                "the bot first) and remember it for sending.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = SEND_NAME,
            description = "Send a message through the user's Telegram bot. Run telegram_resolve " +
                "first if the chat is unknown. Only send when the user explicitly asked for a message to be sent.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "text" to AgentToolParam("string", "Message text."),
            ),
            required = listOf("tool_title", "text"),
            propertyOrdering = listOf("tool_title", "text"),
        ),
        AgentToolDefinition(
            name = READ_NAME,
            description = "Read recent messages sent TO the user's Telegram bot.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "limit" to AgentToolParam("integer", "How many recent messages to show (default 10)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "limit"),
        ),
    )

    suspend fun executeResolve(argsJson: String, context: Context): ToolExecutionResult {
        val title = JSONObject(argsJson).optString("tool_title", RESOLVE_NAME)
        return when (val r = TelegramConnector.resolveChat(context)) {
            is TelegramConnector.ApiResult.Ok ->
                ToolExecutionResult("Telegram chat resolved (id ${r.value}). You can now use telegram_send.", true, toolTitle = title)
            is TelegramConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is TelegramConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeSend(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = args.optString("tool_title", SEND_NAME)
        val text = args.optString("text", "").trim()
        if (text.isEmpty()) {
            return ToolExecutionResult("Error: 'text' is required", false, toolTitle = title)
        }
        return when (val r = TelegramConnector.send(context, text)) {
            is TelegramConnector.ApiResult.Ok -> ToolExecutionResult(r.value, true, toolTitle = title)
            is TelegramConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is TelegramConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeRead(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val title = args.optString("tool_title", READ_NAME)
        val limit = args.optInt("limit", 10)
        return when (val r = TelegramConnector.recentMessages(context, limit)) {
            is TelegramConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) return ToolExecutionResult("No messages to the bot yet.", true, toolTitle = title)
                val out = buildString {
                    appendLine("Recent messages to your Telegram bot:")
                    r.value.forEach { appendLine("- ${it.from}: ${it.text}") }
                }
                ToolExecutionResult(out, true, toolTitle = title)
            }
            is TelegramConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = title)
            is TelegramConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }
}
