package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.calendar.CalendarConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Google Calendar connector agent tools. Only exposed when the user has
 * connected Calendar in Settings → Connectors.
 */
object CalendarTool {
    const val LIST_NAME = "calendar_list"
    const val CREATE_NAME = "calendar_create"

    fun isConnected(context: Context): Boolean = CalendarConnector.isConnected(context)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = LIST_NAME,
            description = "List the user's upcoming Google Calendar events, soonest first.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "max_results" to AgentToolParam("integer", "Max events to return (default 10, max 25)."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title", "max_results"),
        ),
        AgentToolDefinition(
            name = CREATE_NAME,
            description = "Create a Google Calendar event. Only create events the user explicitly asked for, and confirm the details with them first if there is any doubt.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "summary" to AgentToolParam("string", "Event title."),
                "start" to AgentToolParam("string", "\"yyyy-MM-dd HH:mm\" in the device timezone, e.g. \"2026-10-03 15:00\"."),
                "end" to AgentToolParam("string", "\"yyyy-MM-dd HH:mm\" in the device timezone."),
                "description" to AgentToolParam("string", "Optional event description."),
                "location" to AgentToolParam("string", "Optional event location."),
            ),
            required = listOf("tool_title", "summary", "start", "end"),
            propertyOrdering = listOf("tool_title", "summary", "start", "end", "description", "location"),
        ),
    )

    suspend fun executeList(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", LIST_NAME)
        return when (val r = CalendarConnector.listUpcoming(context, args.optInt("max_results", 10))) {
            is CalendarConnector.ApiResult.Ok -> {
                if (r.value.isEmpty()) {
                    ToolExecutionResult("No upcoming events.", true, toolTitle = toolTitle)
                } else {
                    val out = buildString {
                        appendLine("Upcoming events:")
                        r.value.forEach {
                            appendLine("- ${it.summary}")
                            appendLine("  when: ${it.start} → ${it.end}")
                            if (it.location.isNotBlank()) appendLine("  where: ${it.location}")
                            if (it.description.isNotBlank()) appendLine("  notes: ${it.description}")
                        }
                    }
                    ToolExecutionResult(out, true, toolTitle = toolTitle)
                }
            }
            is CalendarConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is CalendarConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeCreate(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", CREATE_NAME)
        val summary = args.optString("summary", "").trim()
        val start = args.optString("start", "").trim()
        val end = args.optString("end", "").trim()
        if (summary.isEmpty() || start.isEmpty() || end.isEmpty()) {
            return ToolExecutionResult(
                "Error: 'summary', 'start' and 'end' are all required",
                false,
                toolTitle = toolTitle,
            )
        }
        return when (
            val r = CalendarConnector.create(
                context, summary, start, end,
                args.optString("description", ""),
                args.optString("location", ""),
            )
        ) {
            is CalendarConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = toolTitle)
            is CalendarConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is CalendarConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }
}
