package ai.unicto.unibot.swarm

import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMStreamChunk
import ai.unicto.unibot.data.model.LLMError
import ai.unicto.unibot.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.IOException

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
    /**
     * v1.4.0: persistence for settings, custom crews and mission history.
     * Null (tests, legacy callers) disables history recording.
     */
    private val repository: SwarmRepository? = null,
    /**
     * v1.4.0: fired once when a run reaches DONE or FAILED (never on
     * user-cancelled runs) — the ViewModel wires the completion
     * notification here. Invoked synchronously on the run coroutine;
     * implementations must not throw (the engine guards the call).
     */
    private val onTerminal: ((SwarmUiState) -> Unit)? = null,
) {
    private val _uiState = MutableStateFlow(SwarmUiState())
    val uiState: StateFlow<SwarmUiState> = _uiState.asStateFlow()

    private val lock = Any()
    private var runJob: Job? = null

    /** Set by pause(); the run loop stops between worker steps, never mid-step. */
    @Volatile
    private var pauseRequested = false

    /**
     * v1.5 Bug 1 — set by teardown() (screen popped). The runSwarm
     * CancellationException handler parks the run at PAUSED and KEEPS the
     * checkpoint when this is set, instead of landing CANCELLED and wiping
     * it. Internal (not private) so the teardown integration tests can
     * observe it.
     */
    @Volatile
    internal var teardownInitiated = false

    /**
     * v1.5 Bug 1 — set by cancel(). Wins the race against teardown(): if the
     * user explicitly cancelled and THEN the screen popped before the
     * coroutine processed the cancellation, the run must still land
     * CANCELLED, not PAUSED. Reset on every fresh launch/resume.
     */
    @Volatile
    internal var userCancelRequested = false

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
    fun launch(mission: String, preset: SwarmCrewPreset): Boolean =
        launch(mission, preset, SwarmLaunchOptions())

    /**
     * Start a run with v1.4.0 launch options (plan-approval gate, worker
     * cap, attachments). Only legal from IDLE.
     */
    fun launch(mission: String, preset: SwarmCrewPreset, options: SwarmLaunchOptions): Boolean {
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
            val safeAttachments = options.attachments
                .take(SwarmAttachment.MAX_ATTACHMENTS)
                .map { it.copy(text = it.text.take(SwarmAttachment.MAX_CHARS_PER_ATTACHMENT)) }
            setUiState(
                SwarmUiState(
                    mission = mission,
                    crew = preset,
                    agents = agents,
                    maxWorkers = options.maxWorkers.coerceIn(1, 8),
                    requirePlanApproval = options.requirePlanApproval,
                    attachments = safeAttachments,
                    // v1.5 bug 5 — token budget for this run; the engine
                    // field lives on SwarmUiState (engine worker's side).
                    tokenBudget = options.tokenBudget,
                ),
            )
            if (!transitionTo(SwarmLifecycle.PLANNING)) return false
            pauseRequested = false
            userCancelRequested = false
            teardownInitiated = false
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
            userCancelRequested = false
            teardownInitiated = false
            _uiState.update { it.copy(canResume = false, error = null) }
            runJob = scope.launch { runSwarm() }
            return true
        }
    }

    /**
     * v1.4.0 item 2 — approve the decomposed plan and start the workers.
     * Legal only from PAUSED with [SwarmUiState.awaitingApproval] set (the
     * plan-approval gate). The plan may be edited first: [edited] is
     * normalized to exactly one subtask per role (trim extras, pad with
     * the mission text) so a malformed edit can never deadlock the run.
     * An empty edit keeps the manager's proposed plan unchanged.
     */
    fun approvePlan(edited: List<String>): Boolean {
        synchronized(lock) {
            val st = _uiState.value
            if (st.lifecycle != SwarmLifecycle.PAUSED || !st.awaitingApproval) {
                println("[swarm] approvePlan rejected: lifecycle is ${st.lifecycle}, awaitingApproval=${st.awaitingApproval}")
                return false
            }
            if (runJob?.isActive == true) {
                println("[swarm] approvePlan rejected: a run is already active")
                return false
            }
            val roleIds = st.crew?.roles.orEmpty()
            val plan = normalizePlan(
                edited.ifEmpty { st.proposedPlan },
                roleIds,
                st.mission,
            )
            subtasks = plan
            _uiState.update {
                it.copy(
                    proposedPlan = plan,
                    awaitingApproval = false,
                    planApproved = true,
                    canResume = false,
                    error = null,
                )
            }
            checkpoint()
            if (!transitionTo(SwarmLifecycle.RUNNING)) return false
            pauseRequested = false
            userCancelRequested = false
            teardownInitiated = false
            runJob = scope.launch { runSwarm() }
            return true
        }
    }

    /**
     * v1.4.0 item 2 — reject the decomposed plan. Routes through cancel():
     * the run lands CANCELLED with nothing executed and the rejection is
     * recorded in the mission history like any other cancelled run.
     */
    fun rejectPlan(): Boolean {
        if (_uiState.value.lifecycle != SwarmLifecycle.PAUSED ||
            !_uiState.value.awaitingApproval
        ) {
            println("[swarm] rejectPlan rejected: not at the approval gate")
            return false
        }
        _uiState.update { it.copy(awaitingApproval = false, proposedPlan = emptyList()) }
        return cancel()
    }

    /**
     * v1.4.0 item 6 — inject a new instruction into a running swarm without
     * restarting it. Legal from RUNNING/PLANNING/PAUSED. The note is
     * appended to [SwarmUiState.steeringNotes] (newest last, capped) and
     * checkpointed; every subsequent worker and revision prompt carries the
     * notes as a MANAGER STEERING section. Steps already finished are not
     * re-run — steering shapes what happens next, it does not rewrite the
     * past.
     */
    fun steer(instruction: String): Boolean {
        val trimmed = instruction.trim()
        if (trimmed.isEmpty()) {
            println("[swarm] steer rejected: empty instruction")
            return false
        }
        val lc = _uiState.value.lifecycle
        if (lc != SwarmLifecycle.RUNNING && lc != SwarmLifecycle.PLANNING &&
            lc != SwarmLifecycle.PAUSED
        ) {
            println("[swarm] steer rejected: lifecycle is $lc")
            return false
        }
        _uiState.update { st ->
            st.copy(steeringNotes = (st.steeringNotes + trimmed).takeLast(MAX_STEERING_NOTES))
        }
        checkpoint()
        println("[swarm] steering note added (${_uiState.value.steeringNotes.size} total)")
        return true
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
            // v1.5 Bug 1 — record the explicit user intent BEFORE cancelling
            // the job, so the CancellationException handler lands CANCELLED
            // even if teardown() (screen pop) fires before the coroutine
            // processes the cancellation.
            userCancelRequested = true
            job = runJob
            if (job == null || job.isCompleted) {
                // Nothing in flight (e.g. a restored PAUSED run): transition now.
                transitionTo(SwarmLifecycle.CANCELLED)
                recordHistory(SwarmMissionRecord.OUTCOME_CANCELLED)
                checkpointStore.clear()
            }
        }
        // Async: the run coroutine's CancellationException handler lands CANCELLED.
        if (job != null && !job.isCompleted) job.cancel()
        return true
    }

    /**
     * v1.5 Bug 1 — the owning screen is going away (called from
     * [SwarmViewModel.onCleared], BEFORE `super.onCleared()`). A run in
     * flight must NOT die with the screen: flag the teardown and cancel the
     * run job; runSwarm's CancellationException handler then parks the run
     * at PAUSED with the checkpoint intact (and `canResume = true`), instead
     * of landing CANCELLED and wiping the checkpoint like the old
     * viewModelScope binding did. A fresh engine instance on the next screen
     * open restores from the checkpoint via the unchanged resume flow.
     * No-op unless a run is active or parked.
     */
    fun teardown() {
        val lc = _uiState.value.lifecycle
        if (lc != SwarmLifecycle.RUNNING && lc != SwarmLifecycle.PLANNING &&
            lc != SwarmLifecycle.PAUSED
        ) return
        teardownInitiated = true
        runJob?.cancel()
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
                // v1.5 Bug 2 — a failed step parks the run at PAUSED (checkpoint
                // kept) instead of failing it and wiping progress.
                val planCall = runStep("Plan") {
                    callLlm(
                        system = MANAGER_SYSTEM,
                        user = planPrompt(st.mission, roleIds, attachmentsContext(st.attachments)),
                        maxTokens = PLAN_MAX_OUTPUT_TOKENS,
                        stepLabel = "plan",
                    )
                } ?: return
                parsePlan(planCall.text, roleIds, st.mission).also {
                    subtasks = it
                    logPlan(it)
                }
            }
            // The plan (and its log entries) must be checkpointed before any
            // transition away from PLANNING, so a restored run never loses it.
            checkpoint()
            // v1.4.0 item 2 — plan-approval gate. The gate is state-driven
            // (requirePlanApproval + planApproved live on the UI state and
            // in the checkpoint), so a restored run re-gates exactly like
            // the original. The lifecycle parks at PAUSED — no new states.
            if (st.requirePlanApproval && !st.planApproved) {
                _uiState.update { it.copy(proposedPlan = finalPlan, awaitingApproval = true) }
                checkpoint()
                transitionTo(SwarmLifecycle.PAUSED)
                checkpoint()
                return
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
                    // v1.5 Bug 2 — a failed step parks at PAUSED (checkpoint
                    // kept); resume re-runs it because it isn't DONE.
                    val stepOk = runStep("Worker ${roleIds[i]}") {
                        runWorkerStep(i, roleIds[i], finalPlan[i])
                    }
                    if (stepOk == null) return
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
                // v1.5 Bug 2 — stitch is idempotent (guarded by the
                // isBlank() check above), so parking + resume re-stitches.
                val stitched = runStep("Stitch") { stitchResults() } ?: return
                _uiState.update { it.copy(stitchedResult = stitched) }
                checkpoint()
            }
            transitionTo(SwarmLifecycle.DONE)
            recordHistory(SwarmMissionRecord.OUTCOME_DONE)
            fireTerminal()
            checkpointStore.clear()
        } catch (e: CancellationException) {
            // v1.5 Bug 1 — three intents, three landings:
            // - userCancelRequested (explicit cancel()): CANCELLED + wipe,
            //   exactly the old behavior.
            // - teardownInitiated (screen popped): PAUSED, checkpoint KEPT,
            //   canResume=true — a fresh engine restores and resumes.
            // - defensive fallback: a bare job.cancel() with no recorded
            //   intent behaves like an explicit cancel.
            when {
                userCancelRequested -> {
                    transitionTo(SwarmLifecycle.CANCELLED)
                    recordHistory(SwarmMissionRecord.OUTCOME_CANCELLED)
                    checkpointStore.clear()
                }
                teardownInitiated -> {
                    transitionTo(SwarmLifecycle.PAUSED)
                    _uiState.update { it.copy(canResume = true, error = null) }
                    // Deliberately NO checkpoint clear and NO history record:
                    // the run is interrupted, not finished.
                    checkpoint()
                }
                else -> {
                    transitionTo(SwarmLifecycle.CANCELLED)
                    recordHistory(SwarmMissionRecord.OUTCOME_CANCELLED)
                    checkpointStore.clear()
                }
            }
            throw e
        } catch (e: SwarmBudgetReachedException) {
            // v1.5 Bug 5 — the token budget (not a failure): park at PAUSED
            // with the checkpoint kept so the user can raise the budget and
            // resume. Caught separately from the generic handler below so a
            // budget stop can never be misreported as a run failure.
            pauseWithError("Token budget reached (${e.used}/${e.budget} tokens). Resume to continue.")
            checkpoint()
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
        recordHistory(SwarmMissionRecord.OUTCOME_FAILED)
        fireTerminal()
        checkpointStore.clear()
    }

    /**
     * v1.5 Bug 2 — park the run at PAUSED with the checkpoint kept and
     * `canResume = true` (vs fail(), which lands FAILED and WIPES the
     * checkpoint). Resume re-runs the failed step because it isn't DONE.
     */
    private fun pauseWithError(message: String) {
        _uiState.update { it.copy(error = message, canResume = true) }
        transitionTo(SwarmLifecycle.PAUSED)
    }

    /**
     * v1.5 Bug 2 — run one step (plan call, worker step, stitch) with
     * failure containment. A step that exhausts its retries (or fails
     * non-transiently, e.g. a 4xx) parks the run via [pauseWithError] and
     * returns null; the caller must `return` from runSwarm when that
     * happens. Returns the block's result on success.
     *
     * CancellationException and SwarmBudgetReachedException are NEVER
     * contained: the former drives cancel()/pause()/teardown(), the latter
     * has its own dedicated handler in runSwarm with its own message.
     */
    private suspend fun <T> runStep(label: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SwarmBudgetReachedException) {
            throw e
        } catch (e: Exception) {
            println("[swarm] $label step failed: ${e.message}")
            pauseWithError("$label failed: ${e.message ?: "unknown error"}. Resume to retry from this step.")
            checkpoint()
            null
        }
    }

    /**
     * v1.4.0 item 4 — record a finished mission in the history. Null-safe:
     * with no repository (tests, legacy callers) this is a no-op. Guarded
     * against double-recording — each terminal path calls it exactly once.
     */
    private fun recordHistory(outcome: String) {
        val repo = repository ?: return
        val st = _uiState.value
        val crew = st.crew ?: return
        runCatching {
            repo.recordHistory(
                SwarmMissionRecord(
                    id = java.util.UUID.randomUUID().toString(),
                    mission = st.mission,
                    crewId = crew.id,
                    crewName = crew.name,
                    crewRoles = crew.roles,
                    outcome = outcome,
                    totalTokens = st.totalTokens,
                    agentCount = st.agents.size,
                    finishedAtMillis = System.currentTimeMillis(),
                ),
            )
        }.onFailure {
            println("[swarm] history record failed: ${it.message}")
        }
    }

    /**
     * v1.4.0 item 11 — notify the terminal hook (the ViewModel posts the
     * completion notification from here). Fires on DONE and FAILED only —
     * a user-cancelled run needs no ping. Never throws into the engine.
     */
    private fun fireTerminal() {
        val cb = onTerminal ?: return
        val snapshot = _uiState.value
        runCatching { cb(snapshot) }.onFailure {
            println("[swarm] onTerminal callback failed: ${it.message}")
        }
    }

    // ─── worker step: execute → verify → (one bounded revision) ────────────

    private suspend fun runWorkerStep(index: Int, roleId: String, subtask: String) {
        val role = SwarmRoles.byId(roleId) ?: SwarmRoles.fallback(roleId)
        val mission = _uiState.value.mission
        // v1.4.0 items 6+10: steering notes and attached documents ride
        // along on every worker and revision prompt, captured fresh per
        // step so a mid-run steer() reaches the very next step.
        val steering = steeringContext(_uiState.value.steeringNotes)
        val docs = attachmentsContext(_uiState.value.attachments)
        updateAgent(index) {
            it.copy(status = SwarmAgentStatus.WORKING, currentStep = "Working", detail = "Running subtask")
        }
        logAgent(index, "Worker call started")
        val prior = priorResults(index)
        var output = callLlm(
            system = role.systemPrompt,
            user = workerPrompt(role, mission, subtask, prior, steering, docs),
            maxTokens = workerMaxOutputTokens,
            stepLabel = "worker:${role.id}",
        )
        addTokens(index, output)
        logAgent(
            index,
            "Worker call finished (${output.promptTokens} prompt + ${output.completionTokens} completion tokens)",
        )

        // Reflector pass (MobileAgent v2): the verifier role checks every
        // worker except itself; the manager checks the verifier and crews
        // without a verifier role.
        updateAgent(index) {
            it.copy(status = SwarmAgentStatus.VERIFYING, currentStep = "Verifying", detail = "Reviewing output")
        }
        val (verdict, verifyCall) = verify(roleId, mission, subtask, output.text)
        addTokens(index, verifyCall)
        logAgent(
            index,
            if (verdict.passed) "Verifier verdict: PASS"
            else "Verifier verdict: FAIL — ${verdict.reason}",
        )

        var note = ""
        when {
            verdict.passed -> { /* nothing to do */ }
            verdict.unclear -> {
                // v1.5 Bug 4 — the verifier's reply was unparseable even
                // after one retry: there is no usable feedback to revise
                // against, so the output stands as-is with an explicit note.
                // Deliberately NOT the best-effort marker — nothing was
                // flagged as wrong; the review itself was unreadable.
                note = "\n\n_Note: verifier reply was unclear; output stands as-is._"
                logAgent(index, "Verifier reply unclear after retry — no revision applied")
            }
            isWeakReason(verdict.reason) -> {
                // v1.5 Bug 7 — a FAIL with a <3-word reason ("bad", "wrong
                // output") is too thin to revise against; treated like an
                // unclear verdict: no revision, output stands with a note.
                logAgent(index, "Weak verifier reason flagged")
                note = "\n\n_Note: verifier reply was unclear; output stands as-is._"
            }
            else -> {
                // ONE bounded revision round — then the best output stands. The
                // revision is not re-verified: that would be an unbounded loop.
                updateAgent(index) {
                    it.copy(
                        status = SwarmAgentStatus.WORKING,
                        currentStep = "Revising",
                        detail = "Applying review feedback (single revision)",
                    )
                }
                logAgent(index, "Revision call started")
                val revised = callLlm(
                    system = role.systemPrompt,
                    user = revisionPrompt(role, mission, subtask, prior, output.text, verdict.reason, steering, docs),
                    maxTokens = workerMaxOutputTokens,
                    stepLabel = "worker:${role.id}:revision",
                )
                addTokens(index, revised)
                logAgent(
                    index,
                    "Revision call finished (${revised.promptTokens} prompt + ${revised.completionTokens} completion tokens)",
                )
                output = revised
                // Shared BEST_EFFORT_NOTE_MARKER: the UI detects this exact
                // substring to render the explicit "incomplete" terminal state.
                note = "\n\n_Note: $BEST_EFFORT_NOTE_MARKER (${verdict.reason}); " +
                    "one revision was applied and this best-effort output stands._"
            }
        }
        val finalText = output.text.trim().ifBlank { "(no output)" } + note
        updateAgent(index) {
            it.copy(status = SwarmAgentStatus.DONE, currentStep = "Done", detail = "", result = finalText)
        }
        logAgent(index, "Step complete — result ${finalText.length} chars")
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
        val first = callLlm(
            system = system,
            user = verifyPrompt(mission, subtask, output),
            maxTokens = VERIFY_MAX_OUTPUT_TOKENS,
            stepLabel = "verify:$workerRoleId",
        )
        val firstVerdict = parseVerify(first.text)
        if (!firstVerdict.unclear) return firstVerdict to first
        // v1.5 Bug 4 — the verifier's reply was unparseable ("Passage is
        // missing a citation" parsed as PASS under the old startsWith
        // check). Retry the verify call ONCE; if it's still unclear the
        // verdict stands as unclear and runWorkerStep skips the revision
        // round. Token counts from both calls are combined.
        println("[swarm] unclear verifier reply; retrying verify call once")
        val second = callLlm(
            system = system,
            user = verifyPrompt(mission, subtask, output),
            maxTokens = VERIFY_MAX_OUTPUT_TOKENS,
            stepLabel = "verify:$workerRoleId:retry",
        )
        val combined = LlmCallResult(
            text = second.text,
            promptTokens = first.promptTokens + second.promptTokens,
            completionTokens = first.completionTokens + second.completionTokens,
            attempts = first.attempts + second.attempts,
        )
        return parseVerify(second.text) to combined
    }

    private suspend fun stitchResults(): String {
        val st = _uiState.value
        val parts = st.agents
            .filter { it.status == SwarmAgentStatus.DONE && it.result.isNotBlank() }
            .joinToString("\n\n") { "## ${it.displayName}\n${it.result}" }
        if (parts.isBlank()) return "(no worker results to stitch)"
        val call = callLlm(
            system = MANAGER_SYSTEM,
            user = stitchPrompt(st.mission, parts, attachmentsContext(st.attachments)),
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
        // v1.5 Bug 5 — token budget guard: refuse BEFORE any provider call so
        // a run can't silently blow past the user's budget mid-step.
        // tokenBudget == 0 disables the guard (unlimited).
        val st0 = _uiState.value
        if (st0.tokenBudget > 0 && st0.totalTokens >= st0.tokenBudget) {
            throw SwarmBudgetReachedException(st0.totalTokens, st0.tokenBudget)
        }
        // v1.5 Bug 2 — bounded retry: one transient network / rate-limit /
        // server failure must not kill the whole run. 3 attempts, 1s/2s/4s
        // backoff. CancellationException is never swallowed (the
        // pause/cancel/teardown machinery depends on it); 4xx auth errors
        // are never retried.
        var attempts = 0
        var lastError: Exception? = null
        while (attempts < MAX_LLM_ATTEMPTS) {
            attempts++
            try {
                return@withContext streamSingleCall(system, user, cap, stepLabel, attempts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SwarmBudgetReachedException) {
                throw e // unreachable (checked above); never retried
            } catch (e: Exception) {
                if (!isSwarmTransientError(e)) {
                    println("[swarm] $stepLabel LLM call failed (not transient): ${e.message}")
                    throw e
                }
                lastError = e
                if (attempts < MAX_LLM_ATTEMPTS) {
                    val backoffMs = RETRY_BASE_DELAY_MS shl (attempts - 1)
                    println(
                        "[swarm] $stepLabel transient failure (${e.message}); " +
                            "retrying in ${backoffMs}ms (attempt $attempts/$MAX_LLM_ATTEMPTS)",
                    )
                    // Cancellable: a cancel/pause/teardown during backoff
                    // throws CancellationException, which propagates.
                    delay(backoffMs)
                }
            }
        }
        println("[swarm] $stepLabel LLM call failed after $attempts attempts: ${lastError?.message}")
        throw lastError ?: IllegalStateException("swarm LLM retry loop exited without a result")
    }

    /** One un-retried streaming LLM call; the retry loop in [callLlm] drives this. */
    private suspend fun streamSingleCall(
        system: String,
        user: String,
        cap: Int,
        stepLabel: String,
        attempts: Int,
    ): LlmCallResult {
        val sb = StringBuilder()
        // v1.5 Bug 6 — MAX-per-field accumulation (see SwarmUsageAccumulator),
        // not a sum over chunks.
        val usage = SwarmUsageAccumulator()
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
                        usage.add(chunk.usage.inputTokens, chunk.usage.outputTokens)
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
        val total = usage.total
        if (total > 0) {
            _uiState.update { it.copy(totalTokens = it.totalTokens + total) }
        }
        return LlmCallResult(
            text = sb.toString(),
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            attempts = attempts,
        )
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

    /**
     * v1.4.0 item 8 — append a timestamped entry to an agent's step log
     * (the full agent transcript). Capped per agent so the checkpoint blob
     * stays small; oldest entries fall off first.
     */
    private fun logAgent(index: Int, message: String) {
        val entry = "[${LOG_TIME_FORMAT.format(java.util.Date())}] $message"
        updateAgent(index) { agent ->
            agent.copy(log = (agent.log + entry).takeLast(MAX_LOG_ENTRIES_PER_AGENT))
        }
    }

    /** v1.4.0 item 8 — log the manager's decomposition onto every agent's transcript. */
    private fun logPlan(plan: List<String>) {
        val entry = "[${LOG_TIME_FORMAT.format(java.util.Date())}] " +
            "Manager decomposed mission into ${plan.size} subtask(s)"
        _uiState.update { st ->
            st.copy(
                agents = st.agents.map { agent ->
                    agent.copy(log = (agent.log + entry).takeLast(MAX_LOG_ENTRIES_PER_AGENT))
                },
            )
        }
    }

    companion object {
        const val WORKER_MAX_OUTPUT_TOKENS_DEFAULT = 4096
        private const val PLAN_MAX_OUTPUT_TOKENS = 2048
        private const val VERIFY_MAX_OUTPUT_TOKENS = 512
        private const val STITCH_MAX_OUTPUT_TOKENS = 8192
        private const val PRIOR_RESULT_CHAR_CAP = 1500

        /** v1.5 Bug 2 — bounded retry: attempts per LLM call. */
        private const val MAX_LLM_ATTEMPTS = 3

        /** v1.5 Bug 2 — backoff base: 1s, 2s, 4s across the 3 attempts. */
        private const val RETRY_BASE_DELAY_MS = 1000L

        /** v1.4.0 item 6 — cap on mid-run steering notes per mission. */
        private const val MAX_STEERING_NOTES = 10

        /** v1.4.0 item 8 — cap on transcript log entries per agent. */
        private const val MAX_LOG_ENTRIES_PER_AGENT = 50

        private val LOG_TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

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

        internal fun planPrompt(mission: String, roleIds: List<String>, docs: String = ""): String {
            val example = roleIds.mapIndexed { i, r -> "${i + 1}. <subtask for $r>" }
                .joinToString("\n")
            return buildString {
                appendLine("MISSION DECOMPOSITION")
                appendLine("Mission: $mission")
                appendLine("Crew roles in order: ${roleIds.joinToString(", ")}")
                if (docs.isNotBlank()) {
                    appendLine()
                    appendLine(docs)
                }
                appendLine("Decompose the mission into exactly ${roleIds.size} ordered subtasks — one per crew ")
                append("role, in the order listed. When attached documents are present, make sure the ")
                appendLine("decomposition covers them.")
                appendLine("Reply with ONLY a numbered list, one subtask per line, like:")
                appendLine(example)
                append("No preamble, no commentary, no extra lines.")
            }
        }

        internal fun workerPrompt(
            role: RoleDef,
            mission: String,
            subtask: String,
            prior: String,
            steering: String = "",
            docs: String = "",
        ): String = buildString {
            appendLine("WORKER SUBTASK")
            appendLine("Role: ${role.displayName} (${role.id})")
            appendLine("Mission: $mission")
            appendLine("Your subtask: $subtask")
            if (docs.isNotBlank()) {
                appendLine()
                appendLine(docs)
            }
            if (prior.isNotBlank()) {
                appendLine()
                appendLine("Results from earlier agents (for context):")
                appendLine(prior)
            }
            if (steering.isNotBlank()) {
                appendLine()
                appendLine(steering)
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
            steering: String = "",
            docs: String = "",
        ): String = buildString {
            appendLine("WORKER SUBTASK — REVISION (single and final attempt)")
            appendLine("Role: ${role.displayName} (${role.id})")
            appendLine("Mission: $mission")
            appendLine("Your subtask: $subtask")
            if (docs.isNotBlank()) {
                appendLine()
                appendLine(docs)
            }
            if (prior.isNotBlank()) {
                appendLine()
                appendLine("Results from earlier agents (for context):")
                appendLine(prior)
            }
            if (steering.isNotBlank()) {
                appendLine()
                appendLine(steering)
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

        internal fun stitchPrompt(mission: String, parts: String, docs: String = ""): String =
            buildString {
                appendLine("STITCH RESULTS")
                appendLine("Mission: $mission")
                if (docs.isNotBlank()) {
                    appendLine(docs)
                }
                appendLine("Worker results:")
                appendLine(parts)
                append("Stitch these into ONE clean, coherent document that answers the mission. Remove ")
                append("duplication, keep headings, preserve key facts and numbers. Reply with only the document.")
            }
    }
}

/** One bounded LLM call: streamed text plus prompt/completion token counts. */
internal data class LlmCallResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    /** v1.5 Bug 2 — how many attempts the call took (1 when no retry fired). */
    val attempts: Int = 1,
)

/**
 * Verifier verdict: pass, or fail with a one-line reason.
 * v1.5 Bug 4 — [unclear] marks a reply the strict parser couldn't classify
 * (neither exactly PASS nor exactly FAIL); it is never treated as a pass.
 */
internal data class VerifyVerdict(
    val passed: Boolean,
    val reason: String,
    val unclear: Boolean = false,
)

/**
 * v1.5 Bug 6 — usage accumulator with MAX-per-field (not SUM) semantics.
 *
 * Why: providers disagree on what a Usage chunk means —
 *  1. Gemini sends CUMULATIVE totals on every chunk (100, 200, …, 500 for a
 *     500-token call); summing them overcounts massively (1500 vs 500).
 *  2. Anthropic sends a start-chunk carrying the input total and a later
 *     delta-chunk carrying the output total ((1000,0) then (0,250));
 *     summing is correct there, and max is too.
 *  3. Most others send a single final chunk with the call total, where
 *     max == sum.
 * MAX-per-field is correct for all three patterns; SUM is correct only for
 * patterns 2 and 3. Provider code is untouched — this is purely the
 * consumer-side interpretation.
 */
internal class SwarmUsageAccumulator {
    var promptTokens: Int = 0
        private set
    var completionTokens: Int = 0
        private set

    fun add(inputTokens: Int, outputTokens: Int) {
        promptTokens = maxOf(promptTokens, inputTokens)
        completionTokens = maxOf(completionTokens, outputTokens)
    }

    val total: Int get() = promptTokens + completionTokens
}

/**
 * v1.5 Bug 5 — thrown by callLlm BEFORE the provider call when the run has
 * already reached its token budget. Caught in runSwarm (not the generic
 * Exception handler): the run parks at PAUSED with the checkpoint kept so
 * the user can raise the budget and resume. tokenBudget == 0 disables the
 * guard.
 */
internal class SwarmBudgetReachedException(val used: Int, val budget: Int) :
    Exception("Token budget reached ($used/$budget tokens)")

/**
 * v1.5 Bug 2 — transient-error classifier for the swarm retry loop.
 *
 * Typed checks first: [LLMError] carries the numeric HTTP status end-to-end
 * (v1.4.0 item 79), so 429 / 5xx / transport failures are recognized without
 * string sniffing. Anything 4xx (InvalidApiKey, OAuthExpired, ProviderError
 * with a 4xx status, …) is NEVER retried — a fresh attempt would fail the
 * same way, and auth failures need the user, not a loop.
 *
 * Heuristic fallback: not every provider surfaces a typed LLMError — some
 * throw raw IOExceptions or plain Exceptions whose message is the only
 * signal. It matches conservatively (word-boundaried status codes, so e.g.
 * "1500 tokens" never matches "500", plus a short list of phrases that
 * overwhelmingly mean "try again"). A false positive costs at most two
 * extra attempts with backoff before the run parks at PAUSED — the loop is
 * bounded, so the heuristic can't spin.
 */
internal fun isSwarmTransientError(e: Throwable): Boolean {
    if (e is LLMError) {
        return e is LLMError.RateLimited || e.isServerError ||
            e.isNetworkError || e is LLMError.TransientError
    }
    if (e is IOException) return true
    val msg = e.message?.lowercase().orEmpty()
    return TRANSIENT_HTTP_CODE_REGEX.containsMatchIn(msg) ||
        TRANSIENT_MESSAGE_HINTS.any { it in msg }
}

private val TRANSIENT_HTTP_CODE_REGEX = Regex("""\b(429|500|502|503|504)\b""")
private val TRANSIENT_MESSAGE_HINTS = listOf(
    "rate limit", "rate_limit", "ratelimit", "too many requests",
    "timeout", "timed out",
    "connection",
)

/**
 * v1.5 Bug 7 — a FAIL reason with fewer than 3 words ("bad", "wrong output")
 * is too thin to revise against; the revision prompt would be guessing.
 * runWorkerStep treats it like an unclear verdict: no revision, output
 * stands with a note.
 */
internal fun isWeakReason(reason: String): Boolean =
    reason.split(Regex("\\s+")).count { it.isNotEmpty() } < 3

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

/**
 * v1.5 Bug 4 — STRICT one-line verifier verdict parser. The old
 * `startsWith("PASS", ignoreCase = true)` accepted "Passage is missing a
 * citation" as a pass. Now: first non-blank line → first whitespace-delimited
 * token → uppercase → must be EXACTLY "PASS" or "FAIL". A single trailing ':'
 * glued to the token ("FAIL: reason") is a separator, not part of the word,
 * so it is stripped before the exact comparison. Anything else ("PASSAGE …",
 * prose, empty) is unclear — never a pass.
 */
internal fun parseVerify(raw: String): VerifyVerdict {
    val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val firstToken = firstLine.split(Regex("\\s+"), limit = 2).getOrNull(0).orEmpty()
    val word = firstToken.trimEnd(':').uppercase()
    return when (word) {
        "PASS" -> VerifyVerdict(passed = true, reason = "")
        "FAIL" -> {
            val reason = firstLine
                .removePrefix(firstToken)
                .trimStart()
                .removePrefix(":")
                .trim()
                .take(300)
            VerifyVerdict(passed = false, reason = reason.ifBlank { "no reason given" })
        }
        else -> VerifyVerdict(passed = false, reason = "unparseable verifier reply", unclear = true)
    }
}
