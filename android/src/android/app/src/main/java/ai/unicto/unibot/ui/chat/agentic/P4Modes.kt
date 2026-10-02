package ai.unicto.unibot.ui.chat.agentic

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * P4 agentic modes (v0.2.0).
 *
 * CHAT is the default: ordinary conversation. The other three are agentic
 * overlays applied per message by [P4Router] (see P4Router.kt) or forced by
 * the user via the `/agent`, `/research`, `/workflow`, `/chat` slash
 * commands. A forced mode sticks to the session until cleared; the router
 * otherwise decides per message and shows a subtle "routed to …" divider.
 *
 * Modes are prompt + plumbing: the mode's addendum (P4Prompts) teaches the
 * model the fence protocol (P4Artifacts), and the renderer turns those
 * fences into cards (P4Cards / ArtifactCard). Approval gates are untouched —
 * RiskPolicy's prompt paragraph still governs every tool call in every mode.
 */
enum class P4Mode(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    CHAT(
        label = "Chat",
        description = "Ordinary conversation.",
        icon = Icons.Outlined.ChatBubbleOutline,
    ),
    WORKFLOW(
        label = "Workflow",
        description = "Multi-step tasks: decompose into steps, act, narrate.",
        icon = Icons.Outlined.AccountTree,
    ),
    AGENT(
        label = "Agent",
        description = "Autonomous runs: to-do list, web tasks, checkpoints.",
        icon = Icons.Outlined.SmartToy,
    ),
    RESEARCH(
        label = "Research",
        description = "Deep research across many sources, cited report.",
        icon = Icons.Outlined.TravelExplore,
    ),
}

/**
 * P4 persisted state. One SharedPreferences file for everything agentic so
 * the feature stays greppable. Nothing here leaves the device.
 */
object P4ModeStore {
    private const val PREFS = "p4_agentic_modes"
    private const val KEY_VOICE_WORKFLOW = "voice_workflow_enabled"
    private const val KEY_VOICE_WORKFLOW_ARMED = "voice_workflow_armed"
    private const val KEY_SMART_ROUTING = "smart_routing_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Forced mode (slash-command override, per session) ────────────────

    /** Forced mode for [sessionId], or null when auto-routing is in effect. */    fun forcedMode(context: Context, sessionId: String): P4Mode? {
        if (sessionId.isBlank()) return null
        val name = prefs(context).getString("forced_$sessionId", null) ?: return null
        return runCatching { P4Mode.valueOf(name) }.getOrNull()
    }

    fun setForcedMode(context: Context, sessionId: String, mode: P4Mode?) {
        if (sessionId.isBlank()) return
        prefs(context).edit().apply {
            if (mode == null) remove("forced_$sessionId") else putString("forced_$sessionId", mode.name)
        }.apply()
    }

    // ── Last route (per session, for debugging / future UI) ───────────────

    fun setLastRoute(context: Context, sessionId: String, mode: P4Mode) {
        if (sessionId.isBlank()) return
        prefs(context).edit().putString("lastroute_$sessionId", mode.name).apply()
    }

    fun lastRoute(context: Context, sessionId: String): P4Mode? {
        if (sessionId.isBlank()) return null
        val name = prefs(context).getString("lastroute_$sessionId", null) ?: return null
        return runCatching { P4Mode.valueOf(name) }.getOrNull()
    }

    // ── Voice workflow toggle ────────────────────────────────────────────
    // The toggle lives in the voice panel. When ON, the next final
    // transcript arms the flag; the send hook consumes it and routes that
    // one send to WORKFLOW mode. Arming (not the toggle itself) is consumed
    // so a stale toggle can't misroute a later typed message.

    fun isVoiceWorkflow(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_WORKFLOW, false)

    fun setVoiceWorkflow(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE_WORKFLOW, on).apply()
    }

    fun armVoiceWorkflow(context: Context) {
        prefs(context).edit().putBoolean(KEY_VOICE_WORKFLOW_ARMED, true).apply()
    }

    /** Returns true once if a voice transcript armed workflow routing. */
    fun consumeVoiceWorkflowArmed(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_VOICE_WORKFLOW_ARMED, false)) return false
        p.edit().putBoolean(KEY_VOICE_WORKFLOW_ARMED, false).apply()
        return true
    }

    // ── Smart routing (LLM classifier fallback) ──────────────────────────
    // Off by default: the heuristic router is free, instant and private.
    // When ON, messages the heuristic can't place confidently get one cheap
    // one-shot classifier call before the turn starts (P4Router.llmClassify).

    fun isSmartRouting(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_ROUTING, false)

    fun setSmartRouting(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_ROUTING, on).apply()
    }
}
