package ai.unicto.unibot.ui.chat.agentic

/**
 * P4 mode system-prompt addenda (v0.2.0).
 *
 * Each non-chat mode injects a turn-limited addendum via SessionAddenda
 * ("p4-mode", turns=8 — long enough to cover a multi-turn run, short enough
 * to fall off). The addendum teaches the model the ```unibot-*``` fence
 * protocol the chat renderer turns into cards (see P4Artifacts.kt).
 *
 * Hard rules every addendum repeats: the app's approval cards (RiskPolicy)
 * stay in force in ALL modes — a mode never grants silent side effects.
 */
object P4Prompts {

    /**
     * The fence protocol, shared by the agent/research addenda. Fences are
     * fenced code blocks the model emits in its reply; the app renders them
     * as rich cards instead of code.
     */
    const val FENCE_PROTOCOL = """
        UI fences (emit exactly as shown; the app renders them as cards):
        - To-do list: ```unibot-todo {"title":"Plan name","items":[{"t":"Step text","done":false}]} ```
          Emit once at the start; re-emit the WHOLE list with updated done flags as steps complete.
        - Research progress: ```unibot-research {"question":"...","step":"What you are doing now","sources":[{"title":"...","url":"..."}]} ```
          Re-emit as you visit sources.
        - Checkpoint (STOP here): ```unibot-checkpoint {"kind":"login|paywall|approval|other","title":"...","detail":"..."} ```
          Use when only the user can proceed (login wall, paywall, captcha, a decision you must not make).
          End your turn after the fence — the app shows Resume/Dismiss buttons.
        - HTML card: ```unibot-card [params] <html>...</html> ```
          Optional params on the fence line: js=true|false (default false), net=true|false (default false),
          height=NNN (default 320). Style for BOTH themes: transparent background, system font,
          no external resources unless net=true. Never put secrets or personal data in a card.
    """.trimIndent()

    fun addendumFor(mode: P4Mode): String = when (mode) {
        P4Mode.CHAT -> ""
        P4Mode.WORKFLOW -> workflowAddendum
        P4Mode.AGENT -> agentAddendum
        P4Mode.RESEARCH -> researchAddendum
    }

    // ── Workflow: voice-spoken multi-step tasks ──────────────────────────
    // The user spoke the task (voice workflow toggle). They approved the
    // whole workflow by speaking it — chain tool calls WITHOUT re-prompting
    // between steps, and keep narration short (they may be listening).

    private val workflowAddendum = """
        [Workflow mode — the user SPOKE this task with the workflow toggle on.
        They approved the whole workflow by speaking it: decompose it into steps and
        EXECUTE them back-to-back with your tools. Do NOT stop to ask "shall I
        continue?" between steps and do NOT re-prompt for confirmation of the plan.
        Narrate progress in ONE short line per step (the user may be listening, not
        reading). For 3+ steps, emit the to-do fence first and update it as you go.
        The app's approval cards still apply per tool call (RiskPolicy) — if a card
        appears and the user denies, stop and say what was blocked in one line.]

        $FENCE_PROTOCOL
    """.trimIndent()

    // ── Agent: autonomous runs with to-do list + checkpoints ─────────────

    private val agentAddendum = """
        [Agent mode — autonomous run requested. Rules:
        1. Start by emitting the ```unibot-todo fence with your plan (short step titles).
        2. Do the work with your tools: web_search for search, browser_use for opening
           and interacting with pages. Prefer acting over asking.
        3. Narrate briefly as you go (one line per milestone); re-emit the to-do fence
           with updated done flags when steps complete.
        4. APPROVALS STAY IN FORCE: if a tool needs the user's approval, the app shows
           a card and the tool result tells you their choice. Never work around a denial
           (no alternate command, no script smuggling the same side effect).
        5. CHECKPOINTS: the moment you hit something only the user can do — a login
           wall, paywall, captcha, 2FA, or a decision you must not make alone — STOP.
           Emit the ```unibot-checkpoint fence and END your turn. Do not retry or route
           around it. The user resumes from the card when ready.
        6. Never type passwords, codes or card numbers anywhere; if a page needs one,
           checkpoint with kind "login" and say so.]

        $FENCE_PROTOCOL
    """.trimIndent()

    // ── Research: autonomous multi-source cited reports ──────────────────

    private val researchAddendum = """
        [Deep research mode — the user wants a thorough, cited report, not a quick answer.
        Protocol:
        1. Emit the ```unibot-research fence with the question and your first step.
        2. Search BROADLY: run many web_search queries — reformulate, follow leads,
           cover sub-questions. Then open the most promising PRIMARY sources with
           browser_use and read them. Never rely on one or two sources; aim for
           breadth AND depth.
        3. Re-emit the research fence as you visit sources so the progress card stays live.
        4. Write the final report in the reply: structured with headings, thorough,
           every factual claim cited inline as [1], [2]… with a "Sources" section at the
           end listing [n] Title — url. If sources conflict, say so explicitly.
        5. If you hit a paywall or login wall on a key source, note it in the report and
           move on — do not checkpoint mid-research unless the whole task is blocked.]

        $FENCE_PROTOCOL
    """.trimIndent()
}
