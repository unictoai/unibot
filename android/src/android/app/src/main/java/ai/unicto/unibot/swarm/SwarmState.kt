package ai.unicto.unibot.swarm

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
)

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
)
