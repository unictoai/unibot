package ai.unicto.unibot.swarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The nav drawer's live status line for the Swarm entry — a pure function of
 * the runtime state, so it is unit-testable without Compose or Android.
 *
 * - [Idle]: nothing to show — the row reads "Idle", the way Devices reads
 *   "Off".
 * - [Active]: a run is live — the row reads "N agents" while planning,
 *   running, or paused (checkpointed).
 */
sealed interface SwarmDrawerHint {
    data object Idle : SwarmDrawerHint
    data class Active(val agentCount: Int, val paused: Boolean) : SwarmDrawerHint
}

/**
 * Maps a [SwarmUiState] to the drawer hint. Only a live run — planning,
 * running, or paused — shows an agent count; every other lifecycle (idle,
 * done, cancelled, failed) is [SwarmDrawerHint.Idle].
 */
fun swarmDrawerHint(state: SwarmUiState): SwarmDrawerHint = when (state.lifecycle) {
    SwarmLifecycle.PLANNING,
    SwarmLifecycle.RUNNING -> SwarmDrawerHint.Active(agentCount = state.agents.size, paused = false)
    SwarmLifecycle.PAUSED -> SwarmDrawerHint.Active(agentCount = state.agents.size, paused = true)
    SwarmLifecycle.IDLE,
    SwarmLifecycle.DONE,
    SwarmLifecycle.CANCELLED,
    SwarmLifecycle.FAILED -> SwarmDrawerHint.Idle
}

/**
 * v1.3.5 App-level mirror of the swarm runtime state, for surfaces outside
 * the swarm screen — the nav drawer's Swarm entry.
 *
 * The engine publishes every state change here ([SwarmEngine] calls [publish]
 * on each mutation, synchronously — no coroutine hop, so even the teardown
 * path that cancels the run when the swarm screen is popped lands its
 * terminal state in the mirror). Before any engine exists in this process
 * (fresh start), [ensureCheckpointMirror] replays a checkpointed
 * (interrupted) run so the hint is honest from the first frame: a killed
 * app's run restores as PAUSED, and the drawer shows its agent count until
 * the user resumes or discards it in the swarm space.
 */
object SwarmStatus {
    private val _hint = MutableStateFlow<SwarmDrawerHint>(SwarmDrawerHint.Idle)
    val hint: StateFlow<SwarmDrawerHint> = _hint.asStateFlow()

    /** True once an engine has published in this process — it then owns the hint. */
    @Volatile
    private var engineSeen = false

    internal fun publish(state: SwarmUiState) {
        engineSeen = true
        _hint.value = swarmDrawerHint(state)
    }

    /**
     * Replays a checkpointed run into the hint. A no-op when an engine has
     * already published (a live engine owns the truth — its on-disk
     * checkpoint would decode as PAUSED even mid-run) or when there is no
     * resumable checkpoint. Safe to call from the drawer's LaunchedEffect;
     * the checkpoint read is a single SharedPreferences get plus a
     * null-safe JSON decode.
     */
    fun ensureCheckpointMirror(store: SwarmCheckpointStore) {
        if (engineSeen) return
        val raw = store.load() ?: return
        val restored = SwarmCheckpoint.decode(raw)?.let(SwarmCheckpoint::toUiState) ?: return
        _hint.value = swarmDrawerHint(restored)
    }

    /** Test-only reset — the singleton would otherwise leak state between tests. */
    internal fun resetForTests() {
        engineSeen = false
        _hint.value = SwarmDrawerHint.Idle
    }
}
