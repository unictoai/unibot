package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.connectors.whatsapp.WhatsAppConnector
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import org.json.JSONObject

private fun w3Title(argsJson: String, fallback: String): String =
    JSONObject(argsJson).optString("tool_title", fallback)

private fun w3Param(argsJson: String, key: String): String =
    JSONObject(argsJson).optString(key, "").trim()

/**
 * WhatsApp connector agent tools (HONEST scope — no official personal
 * WhatsApp API exists).
 *
 * - `whatsapp_share`: gated on WhatsApp being installed. Opens WhatsApp's
 *   share sheet with the text pre-filled; the user confirms and taps send
 *   INSIDE WhatsApp. NEVER silent.
 * - `whatsapp_recent`: gated on the opt-in notification reader
 *   (Settings → Connectors → WhatsApp). Returns the on-device, in-memory
 *   notification buffer — nothing ever leaves the phone.
 */
object WhatsAppTool {

    const val SHARE_NAME = "whatsapp_share"
    const val RECENT_NAME = "whatsapp_recent"

    /** Share is available when the WhatsApp app is installed. */
    fun isShareAvailable(context: Context): Boolean =
        WhatsAppConnector.isWhatsAppInstalled(context)

    /** Reader is available when the user opted in AND granted access. */
    fun isListenerEnabled(context: Context): Boolean =
        WhatsAppConnector.isListenerEnabled(context)

    /** `whatsapp_share` definition (gated on WhatsApp being installed). */
    fun shareDefinitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = SHARE_NAME,
            description = "Open WhatsApp with a message pre-filled in the share sheet. " +
                "The user reviews and taps send INSIDE WhatsApp — this tool NEVER sends silently. " +
                "Only offer this when the user explicitly asked to share something on WhatsApp.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
                "text" to AgentToolParam("string", "The message text to pre-fill in WhatsApp."),
            ),
            required = listOf("tool_title", "text"),
            propertyOrdering = listOf("tool_title", "text"),
        ),
    )

    /** `whatsapp_recent` definition (gated on the opt-in reader). */
    fun recentDefinitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = RECENT_NAME,
            description = "Recent WhatsApp message notifications received on this phone " +
                "(opt-in reader, Settings → Connectors → WhatsApp). " +
                "On-device in-memory buffer only — the newest ~30 notifications, lost if the app restarts.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
    )

    suspend fun executeShare(argsJson: String, context: Context): ToolExecutionResult {
        val title = w3Title(argsJson, SHARE_NAME)
        val text = w3Param(argsJson, "text")
        if (text.isEmpty()) return ToolExecutionResult("Error: 'text' is required", false, toolTitle = title)
        return when (val r = WhatsAppConnector.share(context, text)) {
            is WhatsAppConnector.ApiResult.Ok ->
                ToolExecutionResult(r.value, true, toolTitle = title)
            is WhatsAppConnector.ApiResult.Error ->
                ToolExecutionResult("Error: ${r.message}", false, toolTitle = title)
        }
    }

    suspend fun executeRecent(argsJson: String, context: Context): ToolExecutionResult {
        val title = w3Title(argsJson, RECENT_NAME)
        if (!WhatsAppConnector.isListenerEnabled(context)) {
            return ToolExecutionResult(
                "Error: the WhatsApp notification reader is not enabled. " +
                    "The user can turn it on in Settings → Connectors → WhatsApp.",
                false, toolTitle = title,
            )
        }
        val entries = WhatsAppConnector.recentMessages()
        if (entries.isEmpty()) {
            return ToolExecutionResult(
                "No WhatsApp notifications buffered yet. New WhatsApp messages will appear here once they arrive.",
                true, toolTitle = title,
            )
        }
        val out = buildString {
            appendLine("Recent WhatsApp notifications (on this phone only):")
            entries.forEach { appendLine("- ${it.sender}: ${it.text}") }
        }
        return ToolExecutionResult(out, true, toolTitle = title)
    }
}
