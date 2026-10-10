package ai.unicto.unibot.planner

import ai.unicto.unibot.swarm.SwarmAgentState
import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmLifecycle
import ai.unicto.unibot.swarm.SwarmUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The swarm → general plan derivation. Pure Kotlin, no Android. */
class SwarmPlanBridgeTest {

    private fun agent(id: String, status: SwarmAgentStatus) = SwarmAgentState(
        id = id,
        role = "researcher",
        displayName = "Researcher",
        status = status,
        currentStep = "",
    )

    private fun state() = SwarmUiState(
        lifecycle = SwarmLifecycle.RUNNING,
        mission = "Compare notes apps",
        agents = listOf(
            agent("a1", SwarmAgentStatus.DONE),
            agent("a2", SwarmAgentStatus.WORKING),
            agent("a3", SwarmAgentStatus.QUEUED),
            agent("a4", SwarmAgentStatus.FAILED),
        ),
        proposedPlan = listOf("Research A", "Research B", "Research C", "Research D"),
    )

    @Test
    fun `steps map one per agent with plan text`() {
        val plan = SwarmPlanBridge.toAgentPlan(state())!!
        assertEquals(4, plan.steps.size)
        assertEquals("Research A", plan.steps[0].text)
        assertEquals(PlanStepState.DONE, plan.steps[0].state)
        assertEquals(PlanStepState.DOING, plan.steps[1].state)
        assertEquals(PlanStepState.PENDING, plan.steps[2].state)
        assertEquals(PlanStepState.FAILED, plan.steps[3].state)
        assertEquals(1, plan.finishedCount)
    }

    @Test
    fun `lifecycle maps through`() {
        assertEquals(
            PlanLifecycle.PAUSED,
            SwarmPlanBridge.toAgentPlan(state().copy(lifecycle = SwarmLifecycle.PAUSED))!!.lifecycle,
        )
        assertEquals(
            PlanLifecycle.DONE,
            SwarmPlanBridge.toAgentPlan(state().copy(lifecycle = SwarmLifecycle.DONE))!!.lifecycle,
        )
    }

    @Test
    fun `no agents means no plan`() {
        assertNull(SwarmPlanBridge.toAgentPlan(state().copy(agents = emptyList())))
        assertNull(SwarmPlanBridge.toAgentPlan(state().copy(lifecycle = SwarmLifecycle.IDLE)))
    }

    @Test
    fun `missing plan text falls back to agent name`() {
        val plan = SwarmPlanBridge.toAgentPlan(state().copy(proposedPlan = emptyList()))!!
        assertEquals("Researcher", plan.steps[0].text)
    }
}
