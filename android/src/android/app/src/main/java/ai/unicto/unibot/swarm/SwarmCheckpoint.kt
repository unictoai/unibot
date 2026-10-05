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
) {
    companion object {
        const val CURRENT_VERSION = 1
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
                )
            },
            stitchedResult = state.stitchedResult,
            totalTokens = state.totalTokens,
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
     */
    fun toUiState(data: SwarmCheckpointData): SwarmUiState? {
        val lifecycle = runCatching { SwarmLifecycle.valueOf(data.lifecycle) }.getOrNull()
            ?: return null
        if (lifecycle != SwarmLifecycle.PLANNING &&
            lifecycle != SwarmLifecycle.RUNNING &&
            lifecycle != SwarmLifecycle.PAUSED
        ) return null
        val preset = SwarmRoles.presetById(data.crewId) ?: return null
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
