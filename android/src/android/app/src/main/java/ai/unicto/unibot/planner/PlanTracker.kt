package ai.unicto.unibot.planner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide holder for the currently active agent plan. Producers (today: the
 * swarm, via [SwarmPlanBridge]) publish; the plan UI collects.
 *
 * Kept deliberately thin: it stores the latest plan and applies pure
 * transitions from [AgentPlan]. It never drives execution — pausing,
 * skipping or retrying here only changes the displayed plan unless the
 * producer wires the callback to its engine.
 */
object PlanTracker {
    private val _plan = MutableStateFlow<AgentPlan?>(null)
    val plan: StateFlow<AgentPlan?> = _plan.asStateFlow()

    fun publish(plan: AgentPlan?) {
        _plan.value = plan
    }

    fun clear() {
        _plan.value = null
    }

    /** Apply a pure [AgentPlan] transition to the current plan, if any. */
    fun update(transform: (AgentPlan) -> AgentPlan) {
        _plan.value = _plan.value?.let(transform)
    }
}
