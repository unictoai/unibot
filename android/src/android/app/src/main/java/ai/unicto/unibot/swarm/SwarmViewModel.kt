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
 */
class SwarmViewModel(
    provider: LLMProvider,
    checkpointStore: SwarmCheckpointStore? = null,
    appContext: Context? = null,
) : ViewModel() {

    private val engine = SwarmEngine(
        provider = provider,
        checkpointStore = checkpointStore
            ?: SharedPrefsSwarmCheckpointStore(
                appContext
                    ?: error("SwarmViewModel needs a Context when no checkpointStore is supplied"),
            ),
        scope = viewModelScope,
    )

    /** The exact contract the Swarm screen renders. */
    val uiState: StateFlow<SwarmUiState> = engine.uiState

    /**
     * Start a swarm run. Returns false when a run is already active
     * (launch is only legal from IDLE).
     */
    fun launch(mission: String, preset: SwarmCrewPreset): Boolean =
        engine.launch(mission, preset)

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
        fun factory(context: Context, provider: LLMProvider): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SwarmViewModel(provider, appContext = context.applicationContext) as T
            }
    }
}
