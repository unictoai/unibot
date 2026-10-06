package ai.unicto.unibot.swarm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.unicto.unibot.provider.LLMProvider
import kotlinx.coroutines.flow.StateFlow

/**
 * Backing ViewModel for the Swarm screen (v1.3.0).
 *
 * Thin lifecycle-aware wrapper around [SwarmEngine]: the engine owns the
 * deterministic lifecycle machine, the run coroutine, and checkpointing;
 * this class just binds it to [viewModelScope] so no coroutine leaks past
 * the screen (cancel() cancels the in-flight run job).
 *
 * Construction: use [factory] — the caller supplies the current
 * [LLMProvider] (the same provider instance the chat session uses, so the
 * swarm bills the user's own key like everything else in unibot).
 *
 * v1.4.0: optional [repository] (settings, custom crews, mission history)
 * and [onTerminal] (completion hook — the completion notification posts
 * from here). Both default from [appContext] when not supplied, and to
 * no-ops when there is no context (unit tests).
 */
class SwarmViewModel(
    provider: LLMProvider,
    checkpointStore: SwarmCheckpointStore? = null,
    appContext: Context? = null,
    repository: SwarmRepository? = null,
    onTerminal: ((SwarmUiState) -> Unit)? = null,
) : ViewModel() {

    private val resolvedContext = appContext?.applicationContext

    private val swarmRepository: SwarmRepository? =
        repository ?: resolvedContext?.let { SharedPrefsSwarmRepository(it) }

    private val engine = SwarmEngine(
        provider = provider,
        checkpointStore = checkpointStore
            ?: SharedPrefsSwarmCheckpointStore(
                resolvedContext
                    ?: error("SwarmViewModel needs a Context when no checkpointStore is supplied"),
            ),
        scope = viewModelScope,
        repository = swarmRepository,
        onTerminal = onTerminal,
    )

    /** The exact contract the Swarm screen renders. */
    val uiState: StateFlow<SwarmUiState> = engine.uiState

    /** v1.4.0: settings / custom crews / history backing the swarm UI. Null in unit tests. */
    val swarmRepositoryOrNull: SwarmRepository? get() = swarmRepository

    /**
     * Start a swarm run. Returns false when a run is already active
     * (launch is only legal from IDLE).
     */
    fun launch(mission: String, preset: SwarmCrewPreset): Boolean =
        engine.launch(mission, preset)

    /**
     * v1.4.0: start a run with launch options (plan-approval gate, worker
     * cap, attachments).
     */
    fun launch(mission: String, preset: SwarmCrewPreset, options: SwarmLaunchOptions): Boolean =
        engine.launch(mission, preset, options)

    /**
     * v1.4.0 item 2: approve the decomposed plan (optionally edited) and
     * start the workers. Only legal at the approval gate.
     */
    fun approvePlan(edited: List<String>): Boolean = engine.approvePlan(edited)

    /** v1.4.0 item 2: reject the decomposed plan; the run is cancelled. */
    fun rejectPlan(): Boolean = engine.rejectPlan()

    /**
     * v1.4.0 item 6: inject a new instruction into the running swarm.
     * Legal while planning, running, or paused.
     */
    fun steer(instruction: String): Boolean = engine.steer(instruction)

    /**
     * Pause between worker steps: the current step finishes, then the run
     * halts at PAUSED with a checkpoint. Returns false outside RUNNING/PLANNING.
     */
    fun pause(): Boolean = engine.pause()

    /** Continue a paused run from the next uncompleted step. */
    fun resume(): Boolean = engine.resume()

    /** Stop the run; partial worker results stay visible. */
    fun cancel(): Boolean = engine.cancel()

    /** Dismiss a finished run (DONE/CANCELLED/FAILED) and return to IDLE. */
    fun dismissResult(): Boolean = engine.dismissResult()

    /**
     * Discard a checkpointed (interrupted) run and return to IDLE. Legal
     * from PAUSED — the resume banner's Discard path (dismissResult()
     * deliberately rejects PAUSED).
     */
    fun discardCheckpoint(): Boolean = engine.discardCheckpoint()

    companion object {
        fun factory(
            context: Context,
            provider: LLMProvider,
            repository: SwarmRepository? = null,
            onTerminal: ((SwarmUiState) -> Unit)? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SwarmViewModel(
                        provider,
                        appContext = context.applicationContext,
                        repository = repository,
                        onTerminal = onTerminal,
                    ) as T
            }
    }
}
