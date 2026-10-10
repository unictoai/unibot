package ai.unicto.unibot.swarm

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Swarm checkpointing (v1.3.0) — LangGraph lesson: persist the run after every
 * step so it survives process death and can resume mid-flight.
 *
 * The checkpoint is a single JSON blob in SharedPreferences (the established
 * pattern in `data/`, e.g. FastModePrefs — a whole DataStore/Room table for
 * one blob would be pure ceremony). The engine behind it is pure Kotlin behind
 * [SwarmCheckpointStore], so JVM unit tests exercise the real encode/decode +
 * resume path with an in-memory store — no Robolectric in this module.
 */
@Serializable
data class SwarmAgentCheckpoint(
    val id: String,
    val role: String,
    val displayName: String,
    val status: String, // SwarmAgentStatus.name
    val currentStep: String,
    val detail: String = "",
    val tokensUsed: Int = 0,
    val result: String = "",
    /** v1.4.0 item 8 — the agent's step-by-step transcript log. */
    val log: List<String> = emptyList(),
)

/**
 * v1.4.0: an attached document as checkpointed (content is size-capped at
 * attach time, so the blob stays small).
 */
@Serializable
data class SwarmAttachmentCheckpoint(
    val id: String,
    val name: String,
    val text: String,
)

@Serializable
data class SwarmCheckpointData(
    val version: Int = CURRENT_VERSION,
    val lifecycle: String, // SwarmLifecycle.name at checkpoint time
    val mission: String,
    val crewId: String,
    /** The manager's decomposed subtasks, one per crew role, in order. */
    val subtasks: List<String> = emptyList(),
    val agents: List<SwarmAgentCheckpoint> = emptyList(),
    /** Stitched final document, when the run reached the stitch step. */
    val stitchedResult: String = "",
    val totalTokens: Int = 0,
    /** v1.4.0 item 7 — lets a deleted custom preset still resume. */
    val crewName: String = "",
    val crewRoles: List<String> = emptyList(),
    /** v1.4.0 item 2 — plan-approval gate state. */
    val proposedPlan: List<String> = emptyList(),
    val planApproved: Boolean = false,
    val awaitingApproval: Boolean = false,
    val requirePlanApproval: Boolean = false,
    /** v1.4.0 item 3 — worker-parallelism ceiling for the mission. */
    val maxWorkers: Int = 1,
    /** v1.4.0 item 6 — mid-run steering notes. */
    val steeringNotes: List<String> = emptyList(),
    /** v1.4.0 item 10 — attached documents. */
    val attachments: List<SwarmAttachmentCheckpoint> = emptyList(),
    /**
     * v1.5 Bug 5 — the run's token budget. Defaulted so pre-v1.5 checkpoint
     * blobs (which lack the field) still decode; [Json.ignoreUnknownKeys]
     * already covers the reverse direction.
     */
    val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

object SwarmCheckpoint {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(data: SwarmCheckpointData): String = json.encodeToString(data)

    /** Returns null for corrupt/foreign JSON — never throws. */
    fun decode(raw: String): SwarmCheckpointData? =
        runCatching { json.decodeFromString<SwarmCheckpointData>(raw) }.getOrNull()

    /**
     * Snapshot the live UI state into checkpoint form. Returns null when the
     * lifecycle is terminal or idle — nothing resumable to persist.
     */
    fun fromUiState(state: SwarmUiState, subtasks: List<String>): SwarmCheckpointData? {
        if (state.lifecycle != SwarmLifecycle.PLANNING &&
            state.lifecycle != SwarmLifecycle.RUNNING &&
            state.lifecycle != SwarmLifecycle.PAUSED
        ) return null
        val crewId = state.crew?.id ?: return null
        return SwarmCheckpointData(
            lifecycle = state.lifecycle.name,
            mission = state.mission,
            crewId = crewId,
            subtasks = subtasks,
            agents = state.agents.map {
                SwarmAgentCheckpoint(
                    id = it.id,
                    role = it.role,
                    displayName = it.displayName,
                    status = it.status.name,
                    currentStep = it.currentStep,
                    detail = it.detail,
                    tokensUsed = it.tokensUsed,
                    result = it.result,
                    log = it.log.takeLast(50),
                )
            },
            stitchedResult = state.stitchedResult,
            totalTokens = state.totalTokens,
            crewName = state.crew?.name.orEmpty(),
            crewRoles = state.crew?.roles.orEmpty(),
            proposedPlan = state.proposedPlan,
            planApproved = state.planApproved,
            awaitingApproval = state.awaitingApproval,
            requirePlanApproval = state.requirePlanApproval,
            maxWorkers = state.maxWorkers,
            steeringNotes = state.steeringNotes,
            attachments = state.attachments.map {
                SwarmAttachmentCheckpoint(id = it.id, name = it.name, text = it.text)
            },
            tokenBudget = state.tokenBudget,
        )
    }

    /**
     * Rebuild UI state from a checkpoint. Returns null when there is nothing
     * resumable (terminal lifecycle, unknown preset, corrupt enums).
     *
     * Resume semantics: the lifecycle is normalized to PAUSED — nothing is
     * actually running after process death, so claiming RUNNING would lie.
     * [SwarmUiState.canResume] is set, and the engine continues from the next
     * uncompleted step. Any agent that was mid-step (WORKING/VERIFYING/QUEUED)
     * goes back to QUEUED: a crashed step is re-run whole, never resumed
     * half-way; completed (DONE) steps keep their results and are never
     * re-run.
     *
     * v1.4.0: a checkpoint whose preset id is not a built-in (a custom crew
     * since deleted, or a crew from a newer app version) is reconstructed
     * from the stored crew name + roles, so the run can still resume.
     * Approval-gate state (proposed plan, awaiting flag) is restored so the
     * user lands back at the review step, not mid-run.
     */
    fun toUiState(data: SwarmCheckpointData): SwarmUiState? {
        val lifecycle = runCatching { SwarmLifecycle.valueOf(data.lifecycle) }.getOrNull()
            ?: return null
        if (lifecycle != SwarmLifecycle.PLANNING &&
            lifecycle != SwarmLifecycle.RUNNING &&
            lifecycle != SwarmLifecycle.PAUSED
        ) return null
        val preset = SwarmRoles.presetById(data.crewId)
            ?: data.crewRoles.takeIf { it.isNotEmpty() }?.let { roles ->
                SwarmCrewPreset(
                    id = data.crewId,
                    name = data.crewName.ifBlank { data.crewId },
                    description = "Restored custom crew",
                    roles = roles,
                )
            } ?: return null
        val agents = data.agents.mapNotNull { a ->
            val status = runCatching { SwarmAgentStatus.valueOf(a.status) }.getOrNull()
                ?: return null
            val (restoredStatus, restoredStep) = if (status == SwarmAgentStatus.DONE) {
                status to a.currentStep
            } else {
                SwarmAgentStatus.QUEUED to "Queued"
            }
            SwarmAgentState(
                id = a.id,
                role = a.role,
                displayName = a.displayName,
                status = restoredStatus,
                currentStep = restoredStep,
                detail = "",
                tokensUsed = a.tokensUsed,
                result = a.result,
                log = a.log,
            )
        }
        return SwarmUiState(
            lifecycle = SwarmLifecycle.PAUSED,
            mission = data.mission,
            crew = preset,
            agents = agents,
            stitchedResult = data.stitchedResult,
            totalTokens = data.totalTokens,
            canResume = true,
            proposedPlan = data.proposedPlan,
            awaitingApproval = data.awaitingApproval,
            planApproved = data.planApproved,
            requirePlanApproval = data.requirePlanApproval,
            maxWorkers = data.maxWorkers.coerceIn(1, 8),
            steeringNotes = data.steeringNotes,
            attachments = data.attachments.map {
                SwarmAttachment(id = it.id, name = it.name, text = it.text)
            },
            tokenBudget = data.tokenBudget,
        )
    }
}

/** Persistence behind the checkpoint — SharedPreferences on device, in-memory in tests. */
interface SwarmCheckpointStore {
    fun save(json: String)
    fun load(): String?
    fun clear()
}

/** Production store: single JSON blob in SharedPreferences (FastModePrefs pattern). */
class SharedPrefsSwarmCheckpointStore(context: Context) : SwarmCheckpointStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )

    override fun save(json: String) {
        prefs.edit().putString(KEY_JSON, json).apply()
    }

    override fun load(): String? = prefs.getString(KEY_JSON, null)

    override fun clear() {
        prefs.edit().remove(KEY_JSON).apply()
    }

    companion object {
        private const val PREFS_NAME = "unibot_swarm_checkpoint"
        private const val KEY_JSON = "checkpoint_json"
    }
}
