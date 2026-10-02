package ai.unicto.unibot.ui.chat.agentic

import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.ThinkingLevel
import ai.unicto.unibot.provider.LLMProvider

/**
 * P4 unified auto-router (v0.2.0).
 *
 * Decides per message whether it is plain chat, a multi-step workflow, an
 * autonomous agent run, or deep research — so the user never has to pick a
 * mode. Two stages:
 *
 * 1. [classify] — lightweight LOCAL heuristic. Synchronous, free, private
 *    (no network). This is what runs on every send.
 * 2. [llmClassify] — one-shot LLM classifier call, used ONLY when the
 *    heuristic is uncertain AND the user enabled "Smart routing" in
 *    Settings (default off — privacy-first: no hidden extra call).
 *
 * A forced mode (slash commands `/agent` `/research` `/workflow` `/chat`)
 * always wins over both stages. The chosen route is shown as a subtle
 * "routed to …" divider row in the chat (see P4SendHook).
 */
object P4Router {

    /** Where a routing decision came from. */
    enum class Source { FORCED, VOICE, HEURISTIC, LLM }

    data class Decision(
        val mode: P4Mode,
        /** False when the heuristic couldn't place the message. */
        val confident: Boolean,
        val reason: String,
        val source: Source,
    )

    // ── Stage 1: local heuristic ─────────────────────────────────────────

    private val researchSignals = listOf(
        "research", "deep dive", "deep-dive", "deepdrive", "investigate",
        "literature", "survey", "state of the art", "state-of-the-art",
        "compare", "comparison", "pros and cons", "comprehensive",
        "thoroughly", "in detail", "in-depth", "citations", "cite your",
        "sources", "whitepaper", "white paper", "meta-analysis",
        "what's the latest", "what is the latest", "history of",
    )
    private val agentSignals = listOf(
        "book ", "find me", "search for", "look up", "look for", "buy ",
        "order ", "track ", "monitor ", "scrape", "browse", "sign up",
        "apply for", "fill out", "fill in", "download ", "check the price",
        "price of", "cheapest", "near me", "opening hours", "schedule ",
        "plan a trip", "plan my", "itinerary", "do it for me",
    )
    private val workflowSignals = listOf(
        "step by step", "step-by-step", "do the following", "steps:",
        "first,", "then,", "and then", "after that", "finally,",
        "1.", "1)", "todo", "to-do", "checklist",
    )
    // Plain-chat markers that veto agentic routing even when a signal word
    // appears ("what does 'research' mean?" must not open research mode).
    private val chatVetoes = listOf(
        "what does", "what do you mean", "define ", "meaning of",
        "translate", "summarize this", "tldr",
    )

    /**
     * Synchronous local classification. Returns CHAT with confident=false
     * when nothing matches — the caller then either stays in chat or, with
     * Smart routing on, escalates to [llmClassify].
     */
    fun classify(text: String): Decision {
        val t = " ${text.lowercase().replace('\n', ' ')} "
        if (t.length < 6) return Decision(P4Mode.CHAT, true, "too short", Source.HEURISTIC)
        if (chatVetoes.any { t.contains(it) }) {
            return Decision(P4Mode.CHAT, true, "chat veto", Source.HEURISTIC)
        }
        val researchHits = researchSignals.count { t.contains(it) }
        val agentHits = agentSignals.count { t.contains(it) }
        val workflowHits = workflowSignals.count { t.contains(it) }
        // Multi-clause / list-shaped messages are inherently multi-step.
        val multiStepShape = t.contains(" and then ") || t.contains(" after that ") ||
            Regex("\\b[123]\\s*[.)]\\s").containsMatchIn(t) ||
            t.count { it == ',' } >= 3 && t.length > 120
        return when {
            researchHits >= 2 || (researchHits == 1 && t.length > 60) ->
                Decision(P4Mode.RESEARCH, true, "research signals", Source.HEURISTIC)
            agentHits >= 1 && (workflowHits >= 1 || multiStepShape || agentHits >= 2) ->
                Decision(P4Mode.AGENT, true, "web task + steps", Source.HEURISTIC)
            agentHits >= 1 ->
                // A lone web-ish verb ("find me a good sushi place") is
                // ambiguous: could be a quick answer or an agent run.
                Decision(P4Mode.CHAT, false, "single web verb", Source.HEURISTIC)
            workflowHits >= 2 || (workflowHits >= 1 && multiStepShape) ->
                Decision(P4Mode.WORKFLOW, true, "multi-step shape", Source.HEURISTIC)
            workflowHits == 1 ->
                Decision(P4Mode.CHAT, false, "single step marker", Source.HEURISTIC)
            else -> Decision(P4Mode.CHAT, true, "no signals", Source.HEURISTIC)
        }
    }

    // ── Stage 2: LLM classifier ──────────────────────────────────────────
    // A tiny one-shot call on the CURRENT provider (BYOK: the user's own
    // key pays for it, ~30 tokens). Only invoked for uncertain messages.

    /** The exact prompt sent to the classifier. Kept tiny on purpose. */
    fun llmClassifyPrompt(text: String): String = """
        Classify this chat message into exactly one category. Reply with ONLY the word.

        CHAT — ordinary conversation, a question, small talk, or a simple task.
        WORKFLOW — a multi-step task the assistant should decompose and execute step by step (e.g. "do the following: 1... 2... 3...").
        AGENT — an autonomous web task: searching, browsing sites, comparing options, booking, tracking (e.g. "find the cheapest flight and...").
        RESEARCH — deep research: investigate a topic across many sources and write a long cited report (e.g. "research the history of...").

        Message: "${text.take(500).replace('"', '\'')}"

        Category:
    """.trimIndent()

    /**
     * Runs the classifier against [provider]. Returns null on any failure —
     * the caller must fall back to CHAT (never break the send on this).
     */
    suspend fun llmClassify(provider: LLMProvider, text: String): P4Mode? {
        return runCatching {
            val response = provider.sendMessage(
                messages = listOf(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = llmClassifyPrompt(text),
                    ),
                ),
                systemPrompt = "You are a message router. Reply with exactly one word: CHAT, WORKFLOW, AGENT, or RESEARCH.",
                maxTokens = 16,
                temperature = 0.0,
                thinkingLevel = ThinkingLevel.OFF,
            )
            parseLlmVerdict(response.text)
        }.getOrNull()
    }

    /** Lenient parse: first known token wins, anything else → null. */
    fun parseLlmVerdict(raw: String?): P4Mode? {
        if (raw.isNullOrBlank()) return null
        val token = raw.trim().uppercase().split(Regex("[^A-Z]+")).firstOrNull { it.isNotEmpty() }
        return when (token) {
            "CHAT" -> P4Mode.CHAT
            "WORKFLOW" -> P4Mode.WORKFLOW
            "AGENT" -> P4Mode.AGENT
            "RESEARCH" -> P4Mode.RESEARCH
            else -> null
        }
    }
}
