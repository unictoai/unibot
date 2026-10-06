package ai.unicto.unibot.automation

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

/**
 * Item 91 — SharedPreferences-backed store for [BackgroundAgent] definitions
 * and their [BackgroundAgentRun] callback records. Same small-dataset JSON
 * pattern as [ai.unicto.unibot.scheduled.ScheduledTaskStore]: no Room
 * migration cost, and run records double as the callback cards the manager
 * screen renders.
 */
class BackgroundAgentStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _agents = MutableStateFlow<List<BackgroundAgent>>(emptyList())
    val agents: StateFlow<List<BackgroundAgent>> = _agents.asStateFlow()

    private val _runs = MutableStateFlow<List<BackgroundAgentRun>>(emptyList())
    val runs: StateFlow<List<BackgroundAgentRun>> = _runs.asStateFlow()

    init {
        _agents.value = loadAgents()
        _runs.value = loadRuns()
    }

    fun all(): List<BackgroundAgent> = _agents.value

    fun get(agentId: String): BackgroundAgent? = _agents.value.firstOrNull { it.id == agentId }

    fun upsert(agent: BackgroundAgent) {
        val updated = _agents.value.filter { it.id != agent.id } + agent
        _agents.value = updated.sortedBy { it.createdAt }
        writeAgents(_agents.value)
    }

    fun delete(agentId: String) {
        _agents.value = _agents.value.filter { it.id != agentId }
        writeAgents(_agents.value)
        // Run records are history — keep them so past callback cards survive
        // the agent's deletion.
    }

    /**
     * Enable/disable gate. Enabling REQUIRES explicit consent: callers that
     * flip [enabled] without [BackgroundAgent.consentGranted] get the write
     * applied but the agent stays unscheduled — [BackgroundAgentScheduler]
     * refuses to schedule a non-consented agent, and the UI keeps the switch
     * disabled until the consent checkbox is ticked. This method enforces the
     * same rule at the data layer so no caller can bypass the UI.
     */
    fun setEnabled(agentId: String, enabled: Boolean): BackgroundAgent? {
        val agent = get(agentId) ?: return null
        if (enabled && !agent.consentGranted) {
            AppLogger.warning(TAG, "refusing to enable agent $agentId without consent")
            return agent
        }
        val updated = agent.copy(enabled = enabled)
        upsert(updated)
        return updated
    }

    fun setConsent(agentId: String, granted: Boolean): BackgroundAgent? {
        val agent = get(agentId) ?: return null
        // Revoking consent also disables: a non-consented agent must never run.
        val updated = agent.copy(
            consentGranted = granted,
            enabled = if (granted) agent.enabled else false,
        )
        upsert(updated)
        return updated
    }

    // ── Run records (callback cards) ─────────────────────────────────────

    fun recordRun(run: BackgroundAgentRun) {
        val runs = (listOf(run) + _runs.value).take(MAX_RUNS)
        persistRuns(runs)
    }

    fun updateRun(run: BackgroundAgentRun) {
        val runs = _runs.value.map { if (it.id == run.id) run else it }.take(MAX_RUNS)
        persistRuns(runs)
    }

    private fun persistRuns(runs: List<BackgroundAgentRun>) {
        val arr = JSONArray()
        runs.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_RUNS, arr.toString()).apply()
        _runs.value = runs
    }

    fun runsFor(agentId: String): List<BackgroundAgentRun> =
        _runs.value.filter { it.agentId == agentId }

    fun observeRuns(): Flow<List<BackgroundAgentRun>> = callbackFlow {
        trySend(loadRuns())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_RUNS || key == null) trySend(loadRuns())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // ── Private ──────────────────────────────────────────────────────────

    private fun loadAgents(): List<BackgroundAgent> {
        val raw = prefs.getString(KEY_AGENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { BackgroundAgent.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed agent row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load agents failed: ${t.message}")
            emptyList()
        }
    }

    private fun writeAgents(agents: List<BackgroundAgent>) {
        val arr = JSONArray()
        agents.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_AGENTS, arr.toString()).apply()
    }

    private fun loadRuns(): List<BackgroundAgentRun> {
        val raw = prefs.getString(KEY_RUNS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { BackgroundAgentRun.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed run row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load runs failed: ${t.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BackgroundAgentStore"
        private const val PREFS_NAME = "unibot_background_agents_prefs"
        private const val KEY_AGENTS = "agents_json"
        private const val KEY_RUNS = "runs_json"
        /** Newest-first callback-card history cap. */
        const val MAX_RUNS = 100
    }
}
