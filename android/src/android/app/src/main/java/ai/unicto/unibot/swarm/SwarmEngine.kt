package ai.unicto.unibot.swarm

import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMStreamChunk
import ai.unicto.unibot.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Swarm orchestrator (v1.3.0) — hierarchical crew, phone-safe by design.
 *
 * Design (from the OSS research in open-source-landscape-2026-10-05):
 * - CrewAI: a manager decomposes the mission; role-based workers execute.
 * - MobileAgent v2: planner/actor/reflector split — the verifier (or the
 *   manager for crews without one) reflects on each worker's output.
 * - LangGraph: deterministic lifecycle + checkpointing after every step.
 * - OpenHands: typed narrow tool sets (workers get NO tools in v1.3.0 — LLM
 *   reasoning only) and per-agent cost accounting from stream usage.
 *
 * Execution is SEQUENTIAL in crew order (battery / quota / memory on a
 * phone); the architecture doesn't preclude parallel workers later.
 *
 * Bounded by construction — no infinite loops possible:
 * - planning: 1 LLM call
 * - per worker step: 1 worker call + 1 verify call + at most 1 revision call
 * - stitching: 1 LLM call
 * Illegal lifecycle transitions are rejected as no-ops (logged, never crash).
 */
class SwarmEngine(
    private val provider: LLMProvider,
    private val checkpointStore: SwarmCheckpointStore,
    private val scope: CoroutineScope,
    private val workerMaxOutputTokens: Int = WORKER_MAX_OUTPUT_TOKENS_DEFAULT,
) {
    private val _uiState = MutableStateFlow(SwarmUiState())
    val uiState: StateFlow<SwarmUiState> = _uiState.asStateFlow()

    private val lock = Any()
    private var runJob: Job? = null

    /** Set by pause(); the run loop stops between worker steps, never mid-step. */
    @Volatile
    private var pauseRequested = false

    /** The manager's decomposed subtasks, one per crew role, in order. */
    @Volatile
    private var subtasks: List<String> = emptyList()

    init {
        restoreCheckpoint()
    }

    // ─── lifecycle state machine ────────────────────────────────────────────

    /**
     * Single choke point for every lifecycle change. Returns false (and logs)
     * for illegal transitions instead of throwing.
     */
    fun transitionTo(target: SwarmLifecycle): Boolean {
        val from = _uiState.value.lifecycle
        val allowed = ALLOWED_TRANSITIONS[from].orEmpty().contains(target)
        if (!allowed) {
            println("[swarm] illegal lifecycle transition $from -> $target ignored")
            return false
        }
        _uiState.update { it.copy(lifecycle = target) }
        // v1.3.5 Mirror every lifecycle change to the app-level SwarmStatus
        // (drawer hint), synchronously — the teardown path that cancels the
        // run when the swarm screen is popped must land here too.
        SwarmStatus.publish(_uiState.value)
        return true
    }

    /** Start a run. Only legal from IDLE. */
    fun launch(mission: String, preset: SwarmCrewPreset): Boolean {
        synchronized(lock) {
            if (_uiState.value.lifecycle != SwarmLifecycle.IDLE) {
                println("[swarm] launch rejected: lifecycle is ${_uiState.value.lifecycle}, must be IDLE")
                return false
            }
            val agents = preset.roles.mapIndexed { i, roleId ->
                val role = SwarmRoles.byId(roleId) ?: SwarmRoles.fallback(roleId)
                SwarmAgentState(
                    id = "agent-$i-$roleId",
                    role = roleId,
                    displayName = role.displayName,
                    status = SwarmAgentStatus.QUEUED,
                    currentStep = "Queued",
                )
            }
            setUiState(SwarmUiState(mission = mission, crew = preset, agents = agents))
            if (!transitionTo(SwarmLifecycle.PLANNING)) return false
            pauseRequested = false
            subtasks = emptyList()
            checkpoint()
            runJob = scope.launch { runSwarm() }
            return true
        }
    }

    /**
     * Request a pause. Takes effect between worker steps: the current step
     * finishes, then the run halts at PAUSED. Legal from RUNNING/PLANNING.
     */
    fun pause(): Boolean {
        val lc = _uiState.value.lifecycle
        if (lc != SwarmLifecycle.RUNNING && lc != SwarmLifecycle.PLANNING) {
            println("[swarm] pause rejected: lifecycle is $lc")
            return false
        }
        pauseRequested = true
        return true
    }

    /** Continue a paused run from the next uncompleted step. Never re-runs DONE steps. */
    fun resume(): Boolean {
        synchronized(lock) {
            if (_uiState.value.lifecycle != SwarmLifecycle.PAUSED) {
                println("[swarm] resume rejected: lifecycle is ${_uiState.value.lifecycle}, must be PAUSED")
                return false
            }
            if (runJob?.isActive == true) {
                println("[swarm] resume rejected: a run is already active")
                return false
            }
            if (!transitionTo(SwarmLifecycle.RUNNING)) return false
            pauseRequested = false
            _uiState.update { it.copy(canResume = false, error = null) }
            runJob = scope.launch { runSwarm() }
            return true
        }
    }

    /**
     * Stop the run. Marks CANCELLED; partial worker results stay visible.
     * Cancels the in-flight coroutine so no work leaks past cancel().
     */
    fun cancel(): Boolean {
        val job: Job?
        synchronized(lock) {
            val lc = _uiState.value.lifecycle
            if (lc != SwarmLifecycle.PLANNING && lc != SwarmLifecycle.RUNNING &&
                lc != SwarmLifecycle.PAUSED
            ) {
                println("[swarm] cancel rejected: lifecycle is $lc")
                return false
            }
            pauseRequested = false
            job = runJob
            if (job == null || job.isCompleted) {
                // Nothing in flight (e.g. a restored PAUSED run): transition now.
                transitionTo(SwarmLifecycle.CANCELLED)
                checkpointStore.clear()
            }
        }
        // Async: the run coroutine's CancellationException handler lands CANCELLED.
        if (job != null && !job.isCompleted) job.cancel()
        return true
    }

    /** Dismiss the finished run and return to IDLE. Legal from DONE/CANCELLED/FAILED. */
    fun dismissResult(): Boolean {
        val from = _uiState.value.lifecycle
        if (from != SwarmLifecycle.DONE && from != SwarmLifecycle.CANCELLED &&
            from != SwarmLifecycle.FAILED
        ) {
            println("[swarm] dismissResult rejected: lifecycle is $from")
            return false
        }
        setUiState(SwarmUiState())
        checkpointStore.clear()
        subtasks = emptyList()
        return true
    }

    /**
     * Discard a checkpointed (interrupted) run and return to IDLE. Legal
     * from PAUSED only — nothing is in flight worth keeping, so the
     * checkpoint is cleared and the state resets for a fresh mission.
     * (dismissResult() covers the terminal DONE/CANCELLED/FAILED states;
     * it deliberately rejects PAUSED, so restored runs need this path.)
     */
    fun discardCheckpoint(): Boolean {
        val job: Job?
        synchronized(lock) {
            if (_uiState.value.lifecycle != SwarmLifecycle.PAUSED) {
                println("[swarm] discardCheckpoint rejected: lifecycle is ${_uiState.value.lifecycle}, must be PAUSED")
                return false
            }
            pauseRequested = false
            checkpointStore.clear()
            subtasks = emptyList()
            setUiState(SwarmUiState())
            job = runJob
        }
        // A live PAUSED run's loop has already returned between steps; belt
        // and suspenders in case a job is somehow still active.
        if (job != null && !job.isCompleted) job.cancel()
        return true
    }

    // ─── run loop ─────────────────────────────────────────────────────────

    private suspend fun runSwarm() {
        try {
            val st = _uiState.value
            val roleIds = st.crew?.roles.orEmpty()
            if (roleIds.isEmpty()) {
                fail("No crew roles for this run")
                return
            }
            // PLANNING — one manager call decomposes the mission. Skipped on
            // resume when the checkpoint already carries the plan.
            val plan = currentPlan()
            val finalPlan = if (plan.size == roleIds.size && plan.all { it.isNotBlank() }) {
                plan
            } else {
                updatePendingAgents { it.copy(currentStep = "Planning", detail = "Manager decomposing mission") }
                val raw = callLlm(
                    system = MANAGER_SYSTEM,
                    user = planPrompt(st.mission, roleIds),
                    maxTokens = PLAN_MAX_OUTPUT_TOKENS,
                    stepLabel = "plan",
                )
                parsePlan(raw.text, roleIds, st.mission).also { subtasks = it }
            }
            if (pauseRequested || !coroutineContext.isActive) {
                transitionTo(SwarmLifecycle.PAUSED)
                checkpoint()
                return
            }
            transitionTo(SwarmLifecycle.RUNNING)
            // RUNNING — workers execute sequentially in crew order.
            for (i in roleIds.indices) {
                coroutineContext.ensureActive()
                if (pauseRequested) {
                    transitionTo(SwarmLifecycle.PAUSED)
                    checkpoint()
                    return
                }
                if (_uiState.value.agents.getOrNull(i)?.status != SwarmAgentStatus.DONE) {
                    runWorkerStep(i, roleIds[i], finalPlan[i])
                }
                checkpoint()
            }
            coroutineContext.ensureActive()
            if (pauseRequested) {
                transitionTo(SwarmLifecycle.PAUSED)
                checkpoint()
                return
            }
            // DONE — one manager call stitches worker results into a document.
            if (_uiState.value.stitchedResult.isBlank()) {
                val stitched = stitchResults()
                _uiState.update { it.copy(stitchedResult = stitched) }
                checkpoint()
            }
            transitionTo(SwarmLifecycle.DONE)
            checkpointStore.clear()
        } catch (e: CancellationException) {
            // cancel(): land CANCELLED, keep partial results visible.
            transitionTo(SwarmLifecycle.CANCELLED)
            checkpointStore.clear()
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Swarm run failed")
        } finally {
            synchronized(lock) {
                // Only clear our own job reference — a successor (resume)
                // may already have replaced it.
                if (coroutineContext[Job] === runJob) runJob = null
            }
        }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(error = message) }
        transitionTo(SwarmLifecycle.FAILED)
        checkpointStore.clear()
    }

    // ─── worker step: execute → verify → (one bounded revision) ────────────

    private suspend fun runWorkerStep(index: Int, roleId: String, subtask: String) {
        val role = SwarmRoles.byId(roleId) ?: SwarmRoles.fallback(roleId)
        val mission = _uiState.value.mission
        updateAgent(index) {
            it.copy(status = SwarmAgentStatus.WORKING, currentStep = "Working", detail = "Running subtask")
        }
        val prior = priorResults(index)
        var output = callLlm(
            system = role.systemPrompt,
            user = workerPrompt(role, mission, subtask, prior),
            maxTokens = workerMaxOutputTokens,
            stepLabel = "worker:${role.id}",
        )
        addTokens(index, output)

        // Reflector pass (MobileAgent v2): the verifier role checks every
        // worker except itself; the manager checks the verifier and crews
        // without a verifier role.
        updateAgent(index) {
            it.copy(status = SwarmAgentStatus.VERIFYING, currentStep = "Verifying", detail = "Reviewing output")
        }
        val (verdict, verifyCall) = verify(roleId, mission, subtask, output.text)
        addTokens(index, verifyCall)

        var note = ""
        if (!verdict.passed) {
            // ONE bounded revision round — then the best output stands. The
            // revision is not re-verified: that would be an unbounded loop.
            updateAgent(index) {
                it.copy(
                    status = SwarmAgentStatus.WORKING,
                    currentStep = "Revising",
                    detail = "Applying review feedback (single revision)",
                )
            }
            val revised = callLlm(
                system = role.systemPrompt,
                user = revisionPrompt(role, mission, subtask, prior, output.text, verdict.reason),
                maxTokens = workerMaxOutputTokens,
                stepLabel = "worker:${role.id}:revision",
            )
            addTokens(index, revised)
            output = revised
            // Shared BEST_EFFORT_NOTE_MARKER: the UI detects this exact
            // substring to render the explicit "incomplete" terminal state.
            note = "\n\n_Note: $BEST_EFFORT_NOTE_MARKER (${verdict.reason}); " +
                "one revision was applied and this best-effort output stands._"
        }
        val finalText = output.text.trim().ifBlank { "(no output)" } + note
        updateAgent(index) {
            it.copy(status = SwarmAgentStatus.DONE, currentStep = "Done", detail = "", result = finalText)
        }
    }

    private suspend fun verify(
        workerRoleId: String,
        mission: String,
        subtask: String,
        output: String,
    ): Pair<VerifyVerdict, LlmCallResult> {
        val crewRoles = _uiState.value.crew?.roles.orEmpty()
        val useVerifierRole =
            workerRoleId != SwarmRoles.VERIFIER && crewRoles.contains(SwarmRoles.VERIFIER)
        val system = if (useVerifierRole) SwarmRoles.verifier.systemPrompt else MANAGER_VERIFY_SYSTEM
        val call = callLlm(
            system = system,
            user = verifyPrompt(mission, subtask, output),
            maxTokens = VERIFY_MAX_OUTPUT_TOKENS,
            stepLabel = "verify:$workerRoleId",
        )
        return parseVerify(call.text) to call
    }

    private suspend fun stitchResults(): String {
        val st = _uiState.value
        val parts = st.agents
            .filter { it.status == SwarmAgentStatus.DONE && it.result.isNotBlank() }
            .joinToString("\n\n") { "## ${it.displayName}\n${it.result}" }
        if (parts.isBlank()) return "(no worker results to stitch)"
        val call = callLlm(
            system = MANAGER_SYSTEM,
            user = stitchPrompt(st.mission, parts),
            maxTokens = STITCH_MAX_OUTPUT_TOKENS,
            stepLabel = "stitch",
        )
        // Never lose work: if the stitch call comes back empty, keep the parts.
        return call.text.trim().ifBlank { parts }
    }

    // ─── one bounded LLM call over the provider's streaming completions ────

    private suspend fun callLlm(
        system: String,
        user: String,
        maxTokens: Int,
        stepLabel: String,
    ): LlmCallResult = withContext(Dispatchers.IO) {
        // [T-android-v129-corrupt-limit] Route the cap through the provider's
        // corruption-proof effectiveMaxOutputTokens — a poisoned saved
        // maxOutputTokens must never reach the wire (Groq 413 lesson).
        val cap = provider.effectiveMaxOutputTokens(provider.model)
            .coerceAtMost(maxTokens)
            .coerceAtLeast(256)
        val sb = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0
        try {
            provider.streamMessage(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, user)),
                systemPrompt = system,
                maxTokens = cap,
                tools = emptyList(), // v1.3.0: workers are reasoning-only, no tools.
            ).collect { chunk ->
                when (chunk) {
                    is LLMStreamChunk.Text -> sb.append(chunk.text)
                    is LLMStreamChunk.Usage -> {
                        promptTokens += chunk.usage.inputTokens
                        completionTokens += chunk.usage.outputTokens
                    }
                    else -> { /* thinking/tool/media chunks: not used by swarm */ }
                }
            }
        } catch (e: CancellationException) {
            throw e // must not be swallowed: cancel()/pause machinery depends on it
        } catch (e: Exception) {
            println("[swarm] $stepLabel LLM call failed: ${e.message}")
            throw e
        }
        val total = promptTokens + completionTokens
        if (total > 0) {
            _uiState.update { it.copy(totalTokens = it.totalTokens + total) }
        }
        LlmCallResult(text = sb.toString(), promptTokens = promptTokens, completionTokens = completionTokens)
    }

    // ─── checkpointing ────────────────────────────────────────────────────

    private fun currentPlan(): List<String> = subtasks

    private fun checkpoint() {
        val data = SwarmCheckpoint.fromUiState(_uiState.value, subtasks) ?: return
        checkpointStore.save(SwarmCheckpoint.encode(data))
    }

    private fun restoreCheckpoint() {
        val raw = checkpointStore.load() ?: return
        val data = SwarmCheckpoint.decode(raw) ?: return
        val restored = SwarmCheckpoint.toUiState(data) ?: return
        subtasks = data.subtasks
        setUiState(restored)
        println("[swarm] restored checkpointed run (mission='${data.mission.take(60)}', canResume=true)")
    }

    // ─── state helpers ────────────────────────────────────────────────────

    /**
     * v1.3.5 Single choke point for replacing the whole UI state.
     * Publishes to the app-level [SwarmStatus] mirror synchronously, so the
     * drawer's status hint can never show a stale run.
     */
    private fun setUiState(newValue: SwarmUiState) {
        _uiState.value = newValue
        SwarmStatus.publish(newValue)
    }

    private fun updateAgent(index: Int, transform: (SwarmAgentState) -> SwarmAgentState) {
        _uiState.update { st ->
            val list = st.agents.toMutableList()
            if (index in list.indices) list[index] = transform(list[index])
            st.copy(agents = list)
        }
    }

    private fun updatePendingAgents(transform: (SwarmAgentState) -> SwarmAgentState) {
        _uiState.update { st ->
            st.copy(agents = st.agents.map {
                if (it.status == SwarmAgentStatus.DONE) it else transform(it)
            })
        }
    }

    private fun addTokens(index: Int, call: LlmCallResult) {
        val total = call.promptTokens + call.completionTokens
        if (total == 0) return
        updateAgent(index) { it.copy(tokensUsed = it.tokensUsed + total) }
    }

    /** Truncated results of completed earlier workers, as context for the next. */
    private fun priorResults(uptoIndex: Int): String {
        val done = _uiState.value.agents.take(uptoIndex)
            .filter { it.status == SwarmAgentStatus.DONE && it.result.isNotBlank() }
        if (done.isEmpty()) return ""
        return done.joinToString("\n\n") {
            "### ${it.displayName}\n${it.result.take(PRIOR_RESULT_CHAR_CAP)}"
        }
    }

    companion object {
        const val WORKER_MAX_OUTPUT_TOKENS_DEFAULT = 4096
        private const val PLAN_MAX_OUTPUT_TOKENS = 2048
        private const val VERIFY_MAX_OUTPUT_TOKENS = 512
        private const val STITCH_MAX_OUTPUT_TOKENS = 8192
        private const val PRIOR_RESULT_CHAR_CAP = 1500

        /**
         * Deterministic lifecycle: IDLE → PLANNING → RUNNING → (PAUSED ↔
         * RUNNING) → DONE / CANCELLED / FAILED; any terminal → IDLE.
         * Everything else is illegal and rejected as a no-op.
         */
        private val ALLOWED_TRANSITIONS: Map<SwarmLifecycle, Set<SwarmLifecycle>> = mapOf(
            SwarmLifecycle.IDLE to setOf(SwarmLifecycle.PLANNING),
            SwarmLifecycle.PLANNING to setOf(
                SwarmLifecycle.RUNNING, SwarmLifecycle.PAUSED,
                SwarmLifecycle.CANCELLED, SwarmLifecycle.FAILED,
            ),
            SwarmLifecycle.RUNNING to setOf(
                SwarmLifecycle.PAUSED, SwarmLifecycle.DONE,
                SwarmLifecycle.CANCELLED, SwarmLifecycle.FAILED,
            ),
            SwarmLifecycle.PAUSED to setOf(SwarmLifecycle.RUNNING, SwarmLifecycle.CANCELLED),
            SwarmLifecycle.DONE to setOf(SwarmLifecycle.IDLE),
            SwarmLifecycle.CANCELLED to setOf(SwarmLifecycle.IDLE),
            SwarmLifecycle.FAILED to setOf(SwarmLifecycle.IDLE),
        )

        const val MANAGER_SYSTEM = "You are the swarm manager inside the unibot Android app. " +
            "You coordinate specialist worker agents (planner, researcher, writer, editor, analyst, verifier). " +
            "Workers have no tools in this version — they reason from knowledge. " +
            "Be decisive, structured, and concise."

        const val MANAGER_VERIFY_SYSTEM = "You are the swarm manager inside the unibot Android app, " +
            "doing a reflector pass over a worker's output. When asked to verify, reply with exactly " +
            "one line: PASS — or: FAIL: <one-sentence reason naming the concrete problem>. " +
            "Be strict but fair: fail only on real defects (wrong facts, missing required parts, " +
            "internal contradictions), not on style preferences."

        internal fun planPrompt(mission: String, roleIds: List<String>): String {
            val example = roleIds.mapIndexed { i, r -> "${i + 1}. <subtask for $r>" }
                .joinToString("\n")
            return "MISSION DECOMPOSITION\n" +
                "Mission: $mission\n" +
                "Crew roles in order: ${roleIds.joinToString(", ")}\n" +
                "Decompose the mission into exactly ${roleIds.size} ordered subtasks — one per crew " +
                "role, in the order listed.\n" +
                "Reply with ONLY a numbered list, one subtask per line, like:\n$example\n" +
                "No preamble, no commentary, no extra lines."
        }

        internal fun workerPrompt(
            role: RoleDef,
            mission: String,
            subtask: String,
            prior: String,
        ): String = buildString {
            appendLine("WORKER SUBTASK")
            appendLine("Role: ${role.displayName} (${role.id})")
            appendLine("Mission: $mission")
            appendLine("Your subtask: $subtask")
            if (prior.isNotBlank()) {
                appendLine()
                appendLine("Results from earlier agents (for context):")
                appendLine(prior)
            }
            appendLine()
            append("Complete ONLY your subtask. Reply with your result as concise structured text ")
            append("(headings/bullets where useful).")
        }

        internal fun revisionPrompt(
            role: RoleDef,
            mission: String,
            subtask: String,
            prior: String,
            previousOutput: String,
            reviewReason: String,
        ): String = buildString {
            appendLine("WORKER SUBTASK — REVISION (single and final attempt)")
            appendLine("Role: ${role.displayName} (${role.id})")
            appendLine("Mission: $mission")
            appendLine("Your subtask: $subtask")
            if (prior.isNotBlank()) {
                appendLine()
                appendLine("Results from earlier agents (for context):")
                appendLine(prior)
            }
            appendLine()
            appendLine("Your previous output FAILED automated review: $reviewReason")
            appendLine("Previous output:")
            appendLine(previousOutput.take(4000))
            appendLine()
            append("Revise and return the corrected result. This is your final attempt — make it count.")
        }

        internal fun verifyPrompt(mission: String, subtask: String, output: String): String =
            "VERIFY OUTPUT\n" +
                "Mission: $mission\n" +
                "Subtask: $subtask\n" +
                "Worker output:\n${output.take(6000)}\n" +
                "Does the output fully and correctly complete the subtask? Reply with exactly one " +
                "line: PASS — or: FAIL: <one-sentence reason>."

        internal fun stitchPrompt(mission: String, parts: String): String =
            "STITCH RESULTS\n" +
                "Mission: $mission\n" +
                "Worker results:\n$parts\n" +
                "Stitch these into ONE clean, coherent document that answers the mission. Remove " +
                "duplication, keep headings, preserve key facts and numbers. Reply with only the document."
    }
}

/** One bounded LLM call: streamed text plus prompt/completion token counts. */
internal data class LlmCallResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
)

/** Verifier verdict: pass, or fail with a one-line reason. */
internal data class VerifyVerdict(val passed: Boolean, val reason: String)

/**
 * Tolerant decomposition parser. Accepts `1.` / `1)` / `1:` / `-` / `*` / `•`
 * items; continuation lines append to the previous item. Falls back to one
 * subtask per role using the mission text when nothing parses (or pads/trims
 * to exactly roleIds.size items) — planning can never deadlock the run.
 */
internal fun parsePlan(raw: String, roleIds: List<String>, mission: String): List<String> {
    val itemStart = Regex("""^\s*(?:\d{1,3}[.):]|\d{1,3}\)|[-*•])\s+(.+?)\s*$""")
    val items = mutableListOf<String>()
    for (line in raw.lines()) {
        val match = itemStart.find(line)
        if (match != null) {
            items.add(match.groupValues[1].trim())
        } else {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && items.isNotEmpty()) {
                items[items.size - 1] = items.last() + " " + trimmed
            }
        }
    }
    val cleaned = items.map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return List(roleIds.size) { mission }
    return List(roleIds.size) { i -> cleaned.getOrElse(i) { mission } }
}

/** Parses a one-line verifier verdict: `PASS` or `FAIL: <reason>`. */
internal fun parseVerify(raw: String): VerifyVerdict {
    val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    if (firstLine.startsWith("PASS", ignoreCase = true)) return VerifyVerdict(true, "")
    val reason = firstLine.replaceFirst(Regex("""(?i)^FAIL\s*:?\s*"""), "").trim().take(300)
    return VerifyVerdict(false, reason.ifBlank { "no reason given" })
}
