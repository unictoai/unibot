package ai.unicto.unibot.swarm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 2 — plan-approval gate. The engine parks at PAUSED with the
 * proposed plan after decomposition; approvePlan (optionally with edits)
 * starts the workers, rejectPlan cancels the run. No new lifecycle states.
 */
class SwarmPlanApprovalTest {

    @Test
    fun `gate parks at paused with the proposed plan before any worker runs`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(
            engine.launch(
                "Research scooters",
                SwarmRoles.research,
                SwarmLaunchOptions(requirePlanApproval = true),
            ),
        )
        val gated = engine.uiState.first { it.awaitingApproval }

        assertEquals(SwarmLifecycle.PAUSED, gated.lifecycle)
        assertEquals(listOf("Find facts", "Check facts"), gated.proposedPlan)
        assertFalse(gated.planApproved)
        // No worker ran yet — only the planning call happened.
        assertEquals(0, provider.workerCalls("researcher"))
        assertEquals(0, provider.workerCalls("verifier"))
        assertEquals(1, provider.callsStartingWith("MISSION DECOMPOSITION"))
    }

    @Test
    fun `approvePlan starts workers with the manager plan`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val repo = InMemorySwarmRepository()
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this, repository = repo)

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(requirePlanApproval = true),
        )
        engine.uiState.first { it.awaitingApproval }
        assertTrue(engine.approvePlan(emptyList()))

        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertTrue(final.planApproved)
        assertFalse(final.awaitingApproval)
        assertEquals("Stitched document", final.stitchedResult)
        assertEquals(1, repo.listHistory().size)
        assertEquals(SwarmMissionRecord.OUTCOME_DONE, repo.listHistory()[0].outcome)
    }

    @Test
    fun `approvePlan with edits runs the edited subtasks`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(requirePlanApproval = true),
        )
        engine.uiState.first { it.awaitingApproval }
        assertTrue(engine.approvePlan(listOf("Edited step one", "Edited step two")))

        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        val workerPrompts = provider.calls.map { it.second }
            .filter { it.startsWith("WORKER SUBTASK") && !it.contains("REVISION") }
        assertEquals(2, workerPrompts.size)
        assertTrue(workerPrompts[0].contains("Edited step one"))
        assertTrue(workerPrompts[1].contains("Edited step two"))
    }

    @Test
    fun `approvePlan pads a short edit and trims a long one`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(requirePlanApproval = true),
        )
        engine.uiState.first { it.awaitingApproval }
        // One step for two roles -> padded with the mission; extras trimmed.
        assertTrue(engine.approvePlan(listOf("Only step", "Extra", "Surplus", "More")))

        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        val workerPrompts = provider.calls.map { it.second }
            .filter { it.startsWith("WORKER SUBTASK") && !it.contains("REVISION") }
        assertEquals(2, workerPrompts.size)
        assertTrue(workerPrompts[0].contains("Only step"))
        assertTrue(workerPrompts[1].contains("Extra"))
    }

    @Test
    fun `rejectPlan cancels the run without running workers`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val repo = InMemorySwarmRepository()
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this, repository = repo)

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(requirePlanApproval = true),
        )
        engine.uiState.first { it.awaitingApproval }
        assertTrue(engine.rejectPlan())

        assertEquals(SwarmLifecycle.CANCELLED, engine.uiState.value.lifecycle)
        assertEquals(0, provider.workerCalls("researcher"))
        assertEquals(1, repo.listHistory().size)
        assertEquals(SwarmMissionRecord.OUTCOME_CANCELLED, repo.listHistory()[0].outcome)
    }

    @Test
    fun `approvePlan and rejectPlan are rejected away from the gate`() = runTest {
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("a", "b"))),
            InMemorySwarmCheckpointStore(),
            this,
        )
        // IDLE: nothing to approve.
        assertFalse(engine.approvePlan(listOf("x")))
        assertFalse(engine.rejectPlan())
        // Without the gate the run goes straight through — approvePlan is
        // still illegal mid-run (no awaitingApproval flag).
        engine.launch("Research scooters", SwarmRoles.research)
        assertFalse(engine.approvePlan(listOf("x")))
        assertFalse(engine.rejectPlan())
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
    }

    @Test
    fun `gate state survives a checkpoint round-trip`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(requirePlanApproval = true),
        )
        engine.uiState.first { it.awaitingApproval }
        val raw = store.load()!!
        val restored = SwarmCheckpoint.toUiState(SwarmCheckpoint.decode(raw)!!)!!

        assertTrue(restored.awaitingApproval)
        assertFalse(restored.planApproved)
        assertTrue(restored.requirePlanApproval)
        assertEquals(listOf("Find facts", "Check facts"), restored.proposedPlan)
        assertEquals(SwarmLifecycle.PAUSED, restored.lifecycle)
    }
}
