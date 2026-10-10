package ai.unicto.unibot.planner

/**
 * General multi-step plan model. Any agent path that decomposes work into
 * ordered steps (the swarm is the first producer) can publish an [AgentPlan]
 * to [PlanTracker]; the UI renders it as a live checklist.
 *
 * All transitions are pure — they return new instances, never mutate — so the
 * state machine is unit-testable without Android.
 */
enum class PlanStepState { PENDING, DOING, DONE, FAILED, SKIPPED }

enum class PlanLifecycle { RUNNING, PAUSED, DONE, FAILED, CANCELLED }

data class PlanStep(
    val id: String,
    val text: String,
    val state: PlanStepState = PlanStepState.PENDING,
)

data class AgentPlan(
    val id: String,
    val title: String,
    val steps: List<PlanStep>,
    val lifecycle: PlanLifecycle = PlanLifecycle.RUNNING,
) {
    /** Steps finished (done or deliberately skipped) out of the total. */
    val finishedCount: Int
        get() = steps.count { it.state == PlanStepState.DONE || it.state == PlanStepState.SKIPPED }

    val isTerminal: Boolean
        get() = lifecycle == PlanLifecycle.DONE ||
            lifecycle == PlanLifecycle.FAILED ||
            lifecycle == PlanLifecycle.CANCELLED

    private fun mapStep(id: String, transform: (PlanStep) -> PlanStep): AgentPlan =
        copy(steps = steps.map { if (it.id == id) transform(it) else it })

    /** PENDING → DOING. Anything else is left untouched. */
    fun startStep(id: String): AgentPlan =
        mapStep(id) { if (it.state == PlanStepState.PENDING) it.copy(state = PlanStepState.DOING) else it }

    /** DOING → DONE. */
    fun completeStep(id: String): AgentPlan =
        mapStep(id) { if (it.state == PlanStepState.DOING) it.copy(state = PlanStepState.DONE) else it }

    /** DOING → FAILED. */
    fun failStep(id: String): AgentPlan =
        mapStep(id) { if (it.state == PlanStepState.DOING) it.copy(state = PlanStepState.FAILED) else it }

    /** PENDING or DOING → SKIPPED. Done/failed steps stay as they are. */
    fun skipStep(id: String): AgentPlan =
        mapStep(id) {
            if (it.state == PlanStepState.PENDING || it.state == PlanStepState.DOING) {
                it.copy(state = PlanStepState.SKIPPED)
            } else {
                it
            }
        }

    /** FAILED → PENDING, so the step can run again. */
    fun retryStep(id: String): AgentPlan =
        mapStep(id) { if (it.state == PlanStepState.FAILED) it.copy(state = PlanStepState.PENDING) else it }

    /** Rename a step's text. Blank text is ignored. */
    fun editStep(id: String, newText: String): AgentPlan {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return this
        return mapStep(id) { it.copy(text = trimmed) }
    }

    fun pause(): AgentPlan =
        if (lifecycle == PlanLifecycle.RUNNING) copy(lifecycle = PlanLifecycle.PAUSED) else this

    fun resume(): AgentPlan =
        if (lifecycle == PlanLifecycle.PAUSED) copy(lifecycle = PlanLifecycle.RUNNING) else this
}
