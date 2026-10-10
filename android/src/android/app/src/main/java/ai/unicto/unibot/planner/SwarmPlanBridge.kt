package ai.unicto.unibot.planner

import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmLifecycle
import ai.unicto.unibot.swarm.SwarmUiState

/**
 * Derives a general [AgentPlan] from the swarm's UI state so the plan
 * checklist can render any multi-step run, not just swarm internals.
 *
 * Mapping: the engine decomposes the mission into subtasks "one per crew
 * role, in order", and agents are spawned per role in the same order — so
 * step *i* is agent *i*'s work. Step text prefers the decomposed plan
 * ([SwarmUiState.proposedPlan], also index-ordered); when the plan isn't
 * available yet it falls back to the agent's display name.
 */
object SwarmPlanBridge {
    fun toAgentPlan(state: SwarmUiState): AgentPlan? {
        if (state.agents.isEmpty()) return null
        val planTexts = state.proposedPlan
        val steps = state.agents.mapIndexed { index, agent ->
            val text = planTexts.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: agent.displayName.ifBlank { agent.role }
            PlanStep(
                id = agent.id,
                text = text,
                state = when (agent.status) {
                    SwarmAgentStatus.QUEUED -> PlanStepState.PENDING
                    SwarmAgentStatus.WORKING, SwarmAgentStatus.VERIFYING -> PlanStepState.DOING
                    SwarmAgentStatus.DONE -> PlanStepState.DONE
                    SwarmAgentStatus.FAILED -> PlanStepState.FAILED
                },
            )
        }
        val lifecycle = when (state.lifecycle) {
            SwarmLifecycle.IDLE -> return null
            SwarmLifecycle.PLANNING -> PlanLifecycle.RUNNING
            SwarmLifecycle.RUNNING -> PlanLifecycle.RUNNING
            SwarmLifecycle.PAUSED -> PlanLifecycle.PAUSED
            SwarmLifecycle.DONE -> PlanLifecycle.DONE
            SwarmLifecycle.CANCELLED -> PlanLifecycle.CANCELLED
            SwarmLifecycle.FAILED -> PlanLifecycle.FAILED
        }
        val title = state.mission.trim().takeIf { it.isNotBlank() }?.take(80) ?: "Swarm mission"
        return AgentPlan(
            id = "swarm",
            title = title,
            steps = steps,
            lifecycle = lifecycle,
        )
    }
}
