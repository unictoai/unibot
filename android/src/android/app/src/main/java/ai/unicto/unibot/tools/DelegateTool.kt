package ai.unicto.unibot.tools

import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam

/**
 * The `delegate` tool: multi-agent coordination.
 *
 * Lets the main agent spawn a specialist worker sub-agent for a self-contained
 * piece of work. The worker runs its own agentic loop (same model/provider as
 * the current session) with a restricted tool set — everything except
 * `delegate` itself (no nested delegation) and `memory_write` (the worker must
 * not pollute the user's long-term memory; findings come back in its summary).
 *
 * Safety properties (enforced by the executor in ChatViewModel):
 * - The worker's tool calls go through the exact same [executeTool] path as
 *   the main agent, so approval cards, the permission gates and the "never
 *   type secrets" rule all still apply. A worker cannot bypass anything the
 *   main agent cannot.
 * - The worker cannot ask the user questions — its definition tells it to make
 *   reasonable assumptions and report them in the summary.
 * - Bounded: max_turns caps the loop (default 8, hard max 15); each tool
 *   result is truncated before being fed back, protecting the main context.
 */
object DelegateTool {
    const val NAME = "delegate"
    const val DEFAULT_MAX_TURNS = 8
    const val HARD_MAX_TURNS = 15

    fun definition(includeDelegate: Boolean = true): AgentToolDefinition? {
        if (!includeDelegate) return null
        return AgentToolDefinition(
            name = NAME,
            description = "Spawn a specialist worker sub-agent for a self-contained task and get back its summary. " +
                "Use this to parallelize independent work (e.g. 'research X while I do Y') or to offload a " +
                "well-specified subtask. The worker runs the same model with file, shell and browser tools, but " +
                "it CANNOT ask the user anything and CANNOT delegate further — give it everything it needs " +
                "up front: goal, constraints, and what the summary must contain. Its tool calls are visible in " +
                "the chat and go through the same permission approvals as yours.",
            parameters = mapOf(
                "tool_title" to AgentToolParam(
                    "string",
                    "A concise 5-10 word summary of what the worker should do, shown to the user (e.g. 'Research competitor pricing'). Use the same language as the user.",
                ),
                "task" to AgentToolParam(
                    "string",
                    "The complete task for the worker: goal, constraints, and exactly what the returned summary must contain. Be specific — the worker cannot ask follow-up questions.",
                ),
                "context" to AgentToolParam(
                    "string",
                    "Background the worker needs: relevant facts, file paths, decisions already made. Optional but strongly recommended.",
                ),
                "max_turns" to AgentToolParam(
                    "integer",
                    "Maximum agentic turns for the worker (default $DEFAULT_MAX_TURNS, max $HARD_MAX_TURNS). One turn = one model call plus its tool calls.",
                ),
            ),
            required = listOf("tool_title", "task"),
            propertyOrdering = listOf("tool_title", "task", "context", "max_turns"),
        )
    }

    /** System prompt for the worker sub-agent. */
    fun workerSystemPrompt(taskTitle: String): String = """
        You are a specialist worker sub-agent inside the unibot Android app. A coordinator agent delegated a task to you.

        Rules:
        - You CANNOT ask the user any questions. Make reasonable assumptions, note them, and continue.
        - You CANNOT delegate to other workers. Do the work yourself with your tools.
        - You do NOT have memory_write: do not try to persist anything long-term. Put every finding in your final summary.
        - Sending data outside the phone, deleting files, or paying always requires the user's approval via a card — the same as for the coordinator. Do not attempt to bypass approvals.
        - Work efficiently: prefer reading before writing, batch independent tool calls, keep tool output focused.
        - When the task is done (or you cannot proceed), reply with a concise summary under: ${taskTitle.ifBlank { "Result" }}
        - The summary must contain: what you did, the key findings/results (with file paths, URLs, or numbers as applicable), assumptions you made, and anything left undone with why.
        - Never surrender after one blocked route: try at least 3 materially different approaches before reporting failure. Your summary must list what you tried (one line each) and the single easiest next step for the user.
    """.trimIndent()
}
