package ai.unicto.unibot.swarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * v1.5 Bug 1 — application-scoped coroutine scope for swarm runs.
 *
 * Swarm runs must survive the swarm screen being popped: the old code bound
 * the engine to the ViewModel's `viewModelScope`, so popping the screen
 * cancelled the run and wiped its checkpoint. Runs now launch here instead;
 * [SwarmViewModel.onCleared] calls [SwarmEngine.teardown], which parks the
 * run at PAUSED with the checkpoint intact — a fresh engine (next screen
 * open) restores from the checkpoint and offers resume. The engine is
 * deliberately NOT a singleton: the checkpoint is the handoff between the
 * old (torn-down) and new engine instances.
 *
 * Why this doesn't leak: the [SupervisorJob] lives for the whole process
 * lifetime, which is exactly the semantic a "run survives the screen"
 * feature needs — there is no shorter-lived owner that could be kept alive
 * by it. Nothing here references an Activity, ViewModel, or Context, so no
 * UI object can be retained by the scope. When the process dies, the
 * checkpoint (SharedPreferences) is the durable handoff; the scope's jobs
 * die with the process and the next launch restores from disk. A
 * SupervisorJob (not a plain Job) keeps one failed run from cancelling
 * sibling work sharing the scope.
 */
object SwarmAppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
