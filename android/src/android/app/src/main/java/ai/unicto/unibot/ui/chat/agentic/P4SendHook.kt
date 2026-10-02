package ai.unicto.unibot.ui.chat.agentic

import ai.unicto.unibot.chat.SessionAddenda
import ai.unicto.unibot.provider.LLMProvider
import ai.unicto.unibot.ui.chat.ChatViewModel

/**
 * P4 send-path hook (v0.2.0). Called from ChatViewModel:
 * - [p4PreSend] — top of the private sendMessage(): binds P4Actions and
 *   computes the routing decision. Returns a per-send token (null when the
 *   message is confidently plain chat).
 * - [p4ApplyRouting] — inside the send coroutine after ensureSession():
 *   injects the mode addendum, shows the route badge, and (only when the
 *   heuristic was uncertain AND Smart routing is on) runs the one-shot LLM
 *   classifier before the request is built.
 *
 * The token travels as a local through the send call — no shared mutable
 * state, so rapid successive sends can't clobber each other's routing.
 * Everything here degrades to plain chat on any failure — routing must
 * never break a send.
 */
data class P4RouteToken(
    val decision: P4Router.Decision,
    val text: String,
)

internal fun ChatViewModel.p4PreSend(text: String): P4RouteToken? {
    P4Actions.bind(this)
    val sid = realSessionId.ifEmpty { sessionId }
    val forced = P4ModeStore.forcedMode(context, sid)
    val voiceArmed = P4ModeStore.consumeVoiceWorkflowArmed(context)
    val decision: P4Router.Decision = when {
        forced != null ->
            P4Router.Decision(forced, true, "slash override", P4Router.Source.FORCED)
        voiceArmed ->
            P4Router.Decision(P4Mode.WORKFLOW, true, "voice toggle", P4Router.Source.VOICE)
        else -> P4Router.classify(text)
    }
    // Confident plain chat: no token, no further work.
    if (decision.mode == P4Mode.CHAT && decision.confident) return null
    return P4RouteToken(decision, text.take(2000))
}

/**
 * Applies the routing token from [p4PreSend]. Runs on the send coroutine
 * (after ensureSession, before the system prompt is built).
 */
internal suspend fun ChatViewModel.p4ApplyRouting(
    activeSessionId: String,
    provider: LLMProvider,
    route: P4RouteToken?,
) {
    if (route == null) return
    val decision = route.decision
    if (decision.mode != P4Mode.CHAT) {
        applyMode(activeSessionId, decision)
        return
    }
    // Uncertain heuristic + Smart routing → one cheap classifier call.
    if (!P4ModeStore.isSmartRouting(context)) return
    val mode = runCatching { P4Router.llmClassify(provider, route.text) }.getOrNull()
        ?: return
    if (mode == P4Mode.CHAT) {
        P4ModeStore.setLastRoute(context, activeSessionId, P4Mode.CHAT)
        return
    }
    applyMode(activeSessionId, P4Router.Decision(mode, true, "llm classifier", P4Router.Source.LLM))
}

private fun ChatViewModel.applyMode(sid: String, decision: P4Router.Decision) {
    val addendum = P4Prompts.addendumFor(decision.mode)
    if (addendum.isNotBlank()) {
        // Same tag replaces any earlier routing addendum for this session.
        SessionAddenda.add(sid, "p4-mode", addendum, turns = 8)
    }
    P4ModeStore.setLastRoute(context, sid, decision.mode)
    val via = when (decision.source) {
        P4Router.Source.FORCED -> " · manual"
        P4Router.Source.VOICE -> " · from voice"
        P4Router.Source.LLM -> " · smart"
        P4Router.Source.HEURISTIC -> ""
    }
    p4NoteRoute("Routed to ${decision.mode.label} mode$via")
}

/**
 * Slash-command override: `/agent`, `/research`, `/workflow` force a mode
 * for the session; `/chat` clears back to auto-routing.
 */
internal fun ChatViewModel.p4SetForcedMode(mode: P4Mode?) {
    val sid = realSessionId.ifEmpty { sessionId }
    P4ModeStore.setForcedMode(context, sid, mode)
    if (mode == null) {
        SessionAddenda.remove(sid, "p4-mode")
        p4NoteRoute("Auto routing on — no forced mode")
    } else {
        // Arm immediately (not just on the next send) so the mode also
        // covers a prompt that gets queued while a turn is streaming.
        P4Prompts.addendumFor(mode).takeIf { it.isNotBlank() }?.let {
            SessionAddenda.add(sid, "p4-mode", it, turns = 8)
        }
        p4NoteRoute("${mode.label} mode on — your messages run as ${mode.label.lowercase()} tasks. /chat to go back to auto.")
    }
}
