package ai.unicto.unibot.swarm

/**
 * Launch-time inputs for a swarm run (v1.4.0).
 *
 * Bundles everything `launch()` needs beyond the mission text and crew so
 * the engine signature stays stable as new launch options arrive. All fields
 * have defaults that reproduce the v1.3.0 behaviour exactly:
 * no approval gate, one worker at a time, no attachments.
 */
data class SwarmLaunchOptions(
    /**
     * When true the run pauses after the manager decomposes the mission and
     * waits for [SwarmEngine.approvePlan]: the user sees the plan first and
     * can edit it before any worker runs. The engine keeps the deterministic
     * lifecycle — the gate is expressed as PLANNING -> PAUSED with
     * [SwarmUiState.awaitingApproval] set, then PAUSED -> RUNNING on
     * approval. No new lifecycle states.
     */
    val requirePlanApproval: Boolean = false,
    /**
     * Ceiling on parallel workers for this mission, 1..8. The v1.4.0
     * executor still runs workers sequentially (phone-safe: battery, quota,
     * memory) — the cap is recorded on the run and enforced by
     * [batchWorkerIndices], the scheduler the future parallel executor will
     * consume, so a mission's parallelism budget is explicit from day one.
     */
    val maxWorkers: Int = 1,
    /** Documents the workers should read as mission context (v1.4.0 item 10). */
    val attachments: List<SwarmAttachment> = emptyList(),
)

/**
 * A document attached to a mission (v1.4.0 item 10).
 *
 * The text is read at attach time (via Storage Access Framework — no
 * permission needed) and size-capped so the checkpoint blob stays small.
 * Workers receive the content as a prompt section; they never see the file
 * itself.
 */
data class SwarmAttachment(
    val id: String,
    val name: String,
    val text: String,
) {
    companion object {
        const val MAX_ATTACHMENTS = 4
        const val MAX_CHARS_PER_ATTACHMENT = 8_000
    }
}

/**
 * Renders attached documents as a worker-prompt section. Empty string when
 * there are no attachments — callers append the section only when non-blank.
 */
fun attachmentsContext(attachments: List<SwarmAttachment>): String {
    if (attachments.isEmpty()) return ""
    return buildString {
        appendLine("ATTACHED DOCUMENTS (mission context — treat as source material)")
        attachments.forEach { a ->
            appendLine()
            appendLine("--- ${a.name} (${a.text.length} chars) ---")
            appendLine(a.text.take(SwarmAttachment.MAX_CHARS_PER_ATTACHMENT))
        }
    }.trimEnd()
}

/**
 * Renders mid-run steering notes (v1.4.0 item 6) as a worker-prompt section.
 * Newest note last. Empty string when there are no notes.
 */
fun steeringContext(notes: List<String>): String {
    if (notes.isEmpty()) return ""
    return buildString {
        appendLine("MANAGER STEERING (new instructions injected mid-run — they override earlier guidance where they conflict)")
        notes.forEachIndexed { i, note ->
            appendLine("${i + 1}. $note")
        }
    }.trimEnd()
}

/**
 * Schedules worker indices into batches of at most [maxWorkers] (v1.4.0
 * item 3). Pure function — the sequential v1.4.0 executor runs one batch
 * element at a time, and the future parallel executor will run each batch
 * concurrently. Invalid caps are coerced to 1..8.
 */
fun batchWorkerIndices(workerCount: Int, maxWorkers: Int): List<List<Int>> {
    val cap = maxWorkers.coerceIn(1, 8)
    if (workerCount <= 0) return emptyList()
    return (0 until workerCount).chunked(cap)
}

/**
 * Normalizes an edited plan (v1.4.0 item 2, approve-with-edits) to exactly
 * one subtask per role: trims extras, pads missing entries with the mission
 * text. Planning can never deadlock the run on a malformed edit.
 */
fun normalizePlan(edited: List<String>, roleIds: List<String>, mission: String): List<String> {
    val cleaned = edited.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return List(roleIds.size) { mission }
    return List(roleIds.size) { i -> cleaned.getOrElse(i) { mission } }
}
