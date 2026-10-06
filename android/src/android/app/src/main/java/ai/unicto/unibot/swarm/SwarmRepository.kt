package ai.unicto.unibot.swarm

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-tunable swarm settings (v1.4.0).
 *
 * Persisted as one JSON blob in SharedPreferences (the same established
 * pattern as the swarm checkpoint) so the composer defaults survive
 * restarts. Pure data — no Android dependencies, fully unit-testable.
 */
@Serializable
data class SwarmPrefs(
    /** Worker-parallelism ceiling for new missions, 1..8 (item 3). */
    val maxWorkers: Int = 1,
    /** New missions pause for plan approval by default (item 2). */
    val requirePlanApproval: Boolean = true,
    /** Post a notification when a mission finishes while backgrounded (item 11). */
    val completionNotificationsEnabled: Boolean = true,
) {
    fun withMaxWorkers(value: Int): SwarmPrefs = copy(maxWorkers = value.coerceIn(1, 8))
}

/**
 * One finished mission for the history (v1.4.0 item 4).
 *
 * Stores the crew's role list (not just the preset id) so a custom preset
 * deleted later can still be re-run exactly as it ran.
 */
@Serializable
data class SwarmMissionRecord(
    val id: String,
    val mission: String,
    val crewId: String,
    val crewName: String,
    val crewRoles: List<String>,
    val outcome: String, // "done", "cancelled", "failed"
    val totalTokens: Int,
    val agentCount: Int,
    val finishedAtMillis: Long,
) {
    companion object {
        const val OUTCOME_DONE = "done"
        const val OUTCOME_CANCELLED = "cancelled"
        const val OUTCOME_FAILED = "failed"
    }
}

/**
 * Persistence behind the v1.4.0 swarm additions: settings, custom crews,
 * and mission history. One interface, two implementations —
 * [SharedPrefsSwarmRepository] on device, [InMemorySwarmRepository] in
 * JVM unit tests. History is capped so the blob cannot grow without bound.
 */
interface SwarmRepository {
    fun loadPrefs(): SwarmPrefs
    fun savePrefs(prefs: SwarmPrefs)

    fun listCustomCrews(): List<SwarmCrewPreset>
    fun saveCustomCrew(preset: SwarmCrewPreset)
    fun deleteCustomCrew(id: String)

    fun listHistory(): List<SwarmMissionRecord>
    fun recordHistory(record: SwarmMissionRecord)
    fun clearHistory()
}

/** Production implementation: JSON blobs in SharedPreferences. */
class SharedPrefsSwarmRepository(context: Context) : SwarmRepository {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    override fun loadPrefs(): SwarmPrefs =
        prefs.getString(KEY_PREFS, null)?.let {
            runCatching { json.decodeFromString<SwarmPrefs>(it) }.getOrNull()
        } ?: SwarmPrefs()

    override fun savePrefs(prefs: SwarmPrefs) {
        this.prefs.edit().putString(KEY_PREFS, json.encodeToString(prefs)).apply()
    }

    override fun listCustomCrews(): List<SwarmCrewPreset> =
        prefs.getString(KEY_CUSTOM_CREWS, null)?.let {
            runCatching { json.decodeFromString<List<SwarmCrewPreset>>(it) }.getOrNull()
        }.orEmpty()

    override fun saveCustomCrew(preset: SwarmCrewPreset) {
        val updated = (listCustomCrews().filterNot { it.id == preset.id } + preset)
            .takeLast(MAX_CUSTOM_CREWS)
        prefs.edit().putString(KEY_CUSTOM_CREWS, json.encodeToString(updated)).apply()
    }

    override fun deleteCustomCrew(id: String) {
        val updated = listCustomCrews().filterNot { it.id == id }
        prefs.edit().putString(KEY_CUSTOM_CREWS, json.encodeToString(updated)).apply()
    }

    override fun listHistory(): List<SwarmMissionRecord> =
        prefs.getString(KEY_HISTORY, null)?.let {
            runCatching { json.decodeFromString<List<SwarmMissionRecord>>(it) }.getOrNull()
        }.orEmpty()

    override fun recordHistory(record: SwarmMissionRecord) {
        val updated = (listOf(record) + listHistory()).take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(updated)).apply()
    }

    override fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val PREFS_NAME = "unibot_swarm_v14"
        private const val KEY_PREFS = "prefs_json"
        private const val KEY_CUSTOM_CREWS = "custom_crews_json"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_CUSTOM_CREWS = 20
        private const val MAX_HISTORY = 50
    }
}

/** In-memory implementation for JVM unit tests — no Android needed. */
class InMemorySwarmRepository : SwarmRepository {
    private var prefs = SwarmPrefs()
    private val crews = mutableListOf<SwarmCrewPreset>()
    private val history = mutableListOf<SwarmMissionRecord>()

    override fun loadPrefs(): SwarmPrefs = prefs
    override fun savePrefs(prefs: SwarmPrefs) {
        this.prefs = prefs
    }

    override fun listCustomCrews(): List<SwarmCrewPreset> = crews.toList()
    override fun saveCustomCrew(preset: SwarmCrewPreset) {
        crews.removeAll { it.id == preset.id }
        crews.add(preset)
    }

    override fun deleteCustomCrew(id: String) {
        crews.removeAll { it.id == id }
    }

    override fun listHistory(): List<SwarmMissionRecord> = history.toList()
    override fun recordHistory(record: SwarmMissionRecord) {
        history.add(0, record)
        while (history.size > 50) history.removeLast()
    }

    override fun clearHistory() {
        history.clear()
    }
}
