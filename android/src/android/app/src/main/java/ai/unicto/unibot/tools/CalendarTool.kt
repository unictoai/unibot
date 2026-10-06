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
    const val EDIT_NAME = "calendar_edit"
    const val DELETE_NAME = "calendar_delete"
    const val QUICK_ADD_NAME = "calendar_quick_add"

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
        AgentToolDefinition(
            name = EDIT_NAME,
            description = "Edit a Google Calendar event (title, time, description, location). Only change what the user asked to change. Confirm the new details with the user first if there is any doubt — silently rescheduling someone's meeting is worse than asking.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "id" to AgentToolParam("string", "The event id from calendar_list."),
                "summary" to AgentToolParam("string", "New event title (omit to keep)."),
                "start" to AgentToolParam("string", "\"yyyy-MM-dd HH:mm\" in the device timezone (omit to keep)."),
                "end" to AgentToolParam("string", "\"yyyy-MM-dd HH:mm\" in the device timezone (omit to keep)."),
                "description" to AgentToolParam("string", "New description (omit to keep)."),
                "location" to AgentToolParam("string", "New location (omit to keep)."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id", "summary", "start", "end", "description", "location"),
        ),
        AgentToolDefinition(
            name = DELETE_NAME,
            description = "Delete a Google Calendar event. This is destructive — only delete when the user explicitly asked, and name the event back to them in your reply so they can spot a mistake.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "id" to AgentToolParam("string", "The event id from calendar_list."),
            ),
            required = listOf("tool_title", "id"),
            propertyOrdering = listOf("tool_title", "id"),
        ),
        AgentToolDefinition(
            name = QUICK_ADD_NAME,
            description = "Create a Google Calendar event from ONE line of natural language — the one-tap \"add to calendar\" for any chat message. " +
                "Examples: \"Dentist tomorrow 3pm\", \"Team sync Friday 10-11am\", \"Lunch with Sara on Oct 8 at 1pm\". " +
                "The title and time are parsed from the text; events default to 1 hour. " +
                "Only create events the user asked for — when the text has no date/time, this fails and you should ask instead of guessing.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "text" to AgentToolParam("string", "The message text containing the event title and when it happens."),
                "location" to AgentToolParam("string", "Optional event location."),
            ),
            required = listOf("tool_title", "text"),
            propertyOrdering = listOf("tool_title", "text", "location"),
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

    suspend fun executeEdit(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", EDIT_NAME)
        val id = args.optString("id", "").trim()
        if (id.isEmpty()) {
            return ToolExecutionResult("Error: 'id' is required", false, toolTitle = toolTitle)
        }
        fun opt(key: String): String? =
            args.optString(key, "").trim().takeIf { it.isNotEmpty() }
        if (opt("summary") == null && opt("start") == null && opt("end") == null &&
            opt("description") == null && opt("location") == null
        ) {
            return ToolExecutionResult(
                "Error: nothing to change — pass at least one of summary, start, end, description, location",
                false,
                toolTitle = toolTitle,
            )
        }
        return when (
            val r = CalendarConnector.update(
                context, id,
                summary = opt("summary"),
                start = opt("start"),
                end = opt("end"),
                description = opt("description"),
                location = opt("location"),
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

    suspend fun executeDelete(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", DELETE_NAME)
        val id = args.optString("id", "").trim()
        if (id.isEmpty()) {
            return ToolExecutionResult("Error: 'id' is required", false, toolTitle = toolTitle)
        }
        return when (val r = CalendarConnector.delete(context, id)) {
            is CalendarConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = toolTitle)
            is CalendarConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is CalendarConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }

    suspend fun executeQuickAdd(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val toolTitle = args.optString("tool_title", QUICK_ADD_NAME)
        val text = args.optString("text", "").trim()
        if (text.isEmpty()) {
            return ToolExecutionResult("Error: 'text' is required", false, toolTitle = toolTitle)
        }
        val parsed = CalendarQuickAddParser.parse(text)
            ?: return ToolExecutionResult(
                "Error: could not find a date/time in \"$text\". " +
                    "Ask the user when the event is, or use calendar_create with explicit fields.",
                false,
                toolTitle = toolTitle,
            )
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        val start = fmt.format(java.util.Date(parsed.startMs))
        val end = fmt.format(java.util.Date(parsed.endMs))
        return when (
            val r = CalendarConnector.create(
                context, parsed.title, start, end,
                location = args.optString("location", ""),
            )
        ) {
            is CalendarConnector.ApiResult.Ok ->
                ToolExecutionResult(
                    "${r.value}\n(Parsed from: \"$text\" → \"${parsed.title}\".)",
                    true,
                    toolTitle = toolTitle,
                )
            is CalendarConnector.ApiResult.NotConnected ->
                ToolExecutionResult("Error: ${r.hint}", false, toolTitle = toolTitle)
            is CalendarConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = toolTitle)
        }
    }
}
