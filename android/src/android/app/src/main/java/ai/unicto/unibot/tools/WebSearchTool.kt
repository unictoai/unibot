package ai.unicto.unibot.tools

import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import ai.unicto.unibot.local.LocalCapabilities
import ai.unicto.unibot.local.LocalWebSearch
import org.json.JSONObject

/**
 * [v0.5.0-agentic-core] Real `web_search` tool for the cloud agent loop.
 *
 * Before v0.5.0 the model could *name* `web_search` (prompts referenced it)
 * but [ai.unicto.unibot.ui.chat.ChatViewModel.executeTool] had no branch for
 * it, so every call died as "Unknown tool". This wires it to the same
 * keyless DuckDuckGo client the on-device path uses
 * ([LocalWebSearch]) — free, no API key, nothing to configure.
 *
 * The result is formatted with [LocalCapabilities.buildSearchBlock] so the
 * model gets a consistent shape on both paths, and the chat UI parses the
 * same format to render the Sources card
 * ([ai.unicto.unibot.ui.chat.WebSearchSourcesCard]).
 *
 * Privacy: the user's query text is sent to DuckDuckGo; that is the only
 * disclosure. The Settings toggle ([LocalCapabilities.isWebSearchEnabled])
 * disables the tool entirely — [AgentTools.makeAgentTools] drops the
 * definition when off, so the model can't even attempt the call.
 */
object WebSearchTool {
    const val NAME = "web_search"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the live web for current information. USE THIS whenever the user's question needs fresh or external facts: recent events, news, scores, prices, release dates, weather, or anything that may have changed since your training data. Pass a concise search query (not the full user message). Results come back as a numbered list with titles, snippets and source URLs — answer using ONLY those results and never claim you lack real-time data when results are present.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this search is for, shown to the user (e.g. 'Search latest iPhone price'). Use the same language as the user."),
            "query" to AgentToolParam("string", "Concise web search query, e.g. 'Pakistan vs India cricket score today' or 'iPhone 17 price in Pakistan'."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query"),
    )

    /**
     * Runs the search. Suspends on [LocalWebSearch] (Dispatchers.IO
     * internally). Returns [LocalCapabilities.buildSearchBlock] output on
     * success, or a plain error string on failure — never throws.
     */
    suspend fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val toolTitle = args.optString("tool_title", NAME).ifBlank { NAME }
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' is required", false, toolTitle = toolTitle)
        }
        val results = runCatching {
            LocalWebSearch.search(query, maxResults = 6)
        }.getOrElse { emptyList() }
        if (results.isEmpty()) {
            return ToolExecutionResult(
                "Web search returned no results for \"$query\" (network issue or no matches). " +
                    "Say so briefly and answer from your own knowledge if you can.",
                false,
                toolTitle = toolTitle,
            )
        }
        return ToolExecutionResult(
            output = LocalCapabilities.buildSearchBlock(results),
            success = true,
            toolTitle = toolTitle,
        )
    }
}
