package ai.unicto.unibot.swarm

import kotlinx.serialization.Serializable

/**
 * Swarm UI contract — shared VERBATIM between the swarm engine worker and the
 * swarm UI worker. Do not rename fields or change shapes without coordinating
 * both sides.
 */
enum class SwarmLifecycle { IDLE, PLANNING, RUNNING, PAUSED, DONE, CANCELLED, FAILED }

enum class SwarmAgentStatus { QUEUED, WORKING, VERIFYING, DONE, FAILED }

/**
 * Substring of the best-effort note the engine appends to an agent's
 * `result` after its one bounded revision (see SwarmEngine.runWorkerStep).
 * The UI detects this marker to render the explicit "incomplete" terminal
 * state instead of silently "done". Engine and UI share this const — one
 * source of truth, so the note can never drift between the two sides.
 */
const val BEST_EFFORT_NOTE_MARKER = "automated review flagged an issue"

data class SwarmAgentState(
    val id: String,
    val role: String,
    val displayName: String,
    val status: SwarmAgentStatus,
    val currentStep: String,
    val detail: String = "",
    val tokensUsed: Int = 0,
    val result: String = "",
    /**
     * Step-by-step activity log for the full agent transcript (v1.4.0
     * item 8): worker call start/finish with token counts, verifier
     * verdicts, revisions. Capped by the engine so the checkpoint stays
     * small; the UI renders it in the agent card's expanded section and
     * the transcript export.
     */
    val log: List<String> = emptyList(),
)

@Serializable
data class SwarmCrewPreset(
    val id: String,
    val name: String,
    val description: String,
    val roles: List<String>,
)

data class SwarmUiState(
    val lifecycle: SwarmLifecycle = SwarmLifecycle.IDLE,
    val mission: String = "",
    val crew: SwarmCrewPreset? = null,
    val agents: List<SwarmAgentState> = emptyList(),
    val stitchedResult: String = "",
    val totalTokens: Int = 0,
    val error: String? = null,
    val canResume: Boolean = false,
    /**
     * Plan-approval gate (v1.4.0 item 2). After the manager decomposes the
     * mission, the engine sets [proposedPlan] + [awaitingApproval] and
     * parks at PAUSED — no new lifecycle state. [approvePlan] carries the
     * (optionally edited) plan into the run and flips [planApproved].
     */
    val proposedPlan: List<String> = emptyList(),
    val awaitingApproval: Boolean = false,
    val planApproved: Boolean = false,
    /** Worker-parallelism ceiling for this mission, 1..8 (v1.4.0 item 3). */
    val maxWorkers: Int = 1,
    /**
     * Whether this run pauses for plan approval after decomposition
     * (v1.4.0 item 2). Part of the run state (and the checkpoint) so a
     * restored run re-gates exactly like the original.
     */
    val requirePlanApproval: Boolean = false,
    /** Mid-run steering notes injected via steer() (v1.4.0 item 6). */
    val steeringNotes: List<String> = emptyList(),
    /** Documents attached to the mission (v1.4.0 item 10). */
    val attachments: List<SwarmAttachment> = emptyList(),
)
