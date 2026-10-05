package ai.unicto.unibot.swarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the swarm lifecycle state machine — no LLM calls,
 * transitions driven directly through [SwarmEngine.transitionTo].
 */
class SwarmStateMachineTest {

    private fun engine(): Pair<SwarmEngine, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val engine = SwarmEngine(
            FakeSwarmProvider { _, _ -> FakeSwarmResponse("") },
            InMemorySwarmCheckpointStore(),
            scope,
        )
        return engine to scope
    }

    @Test
    fun `full legal transition chain is accepted`() {
        val (engine, scope) = engine()
        try {
            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.RUNNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.PAUSED))
            assertTrue(engine.transitionTo(SwarmLifecycle.RUNNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.DONE))
            assertTrue(engine.transitionTo(SwarmLifecycle.IDLE))
            assertEquals(SwarmLifecycle.IDLE, engine.uiState.value.lifecycle)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancel and fail paths are accepted from active states`() {
        val (engine, scope) = engine()
        try {
            // PLANNING -> CANCELLED -> IDLE
            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.CANCELLED))
            assertTrue(engine.transitionTo(SwarmLifecycle.IDLE))
            // RUNNING -> FAILED -> IDLE
            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.RUNNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.FAILED))
            assertTrue(engine.transitionTo(SwarmLifecycle.IDLE))
            // PAUSED -> CANCELLED
            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.PAUSED))
            assertTrue(engine.transitionTo(SwarmLifecycle.CANCELLED))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `illegal transitions are rejected as no-ops and never crash`() {
        val (engine, scope) = engine()
        try {
            // From IDLE: only PLANNING is legal.
            assertFalse(engine.transitionTo(SwarmLifecycle.RUNNING))
            assertFalse(engine.transitionTo(SwarmLifecycle.DONE))
            assertFalse(engine.transitionTo(SwarmLifecycle.IDLE))
            assertEquals(SwarmLifecycle.IDLE, engine.uiState.value.lifecycle)

            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            // PLANNING cannot skip to DONE or go back to IDLE.
            assertFalse(engine.transitionTo(SwarmLifecycle.DONE))
            assertFalse(engine.transitionTo(SwarmLifecycle.IDLE))
            assertEquals(SwarmLifecycle.PLANNING, engine.uiState.value.lifecycle)

            assertTrue(engine.transitionTo(SwarmLifecycle.RUNNING))
            // RUNNING cannot go back to PLANNING or IDLE.
            assertFalse(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertFalse(engine.transitionTo(SwarmLifecycle.IDLE))
            assertEquals(SwarmLifecycle.RUNNING, engine.uiState.value.lifecycle)

            assertTrue(engine.transitionTo(SwarmLifecycle.PAUSED))
            // PAUSED cannot finish or fail directly.
            assertFalse(engine.transitionTo(SwarmLifecycle.DONE))
            assertFalse(engine.transitionTo(SwarmLifecycle.FAILED))
            assertFalse(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertEquals(SwarmLifecycle.PAUSED, engine.uiState.value.lifecycle)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `launch requires IDLE and second launch is rejected`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("a", "b"))),
            InMemorySwarmCheckpointStore(),
            scope,
        )
        try {
            assertTrue(engine.launch("mission", SwarmRoles.research))
            assertFalse("second launch while active must be rejected", engine.launch("other", SwarmRoles.content))
            assertEquals("mission", engine.uiState.value.mission)
        } finally {
            engine.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `pause resume cancel guards reject wrong states`() {
        val (engine, scope) = engine()
        try {
            assertFalse("pause from IDLE", engine.pause())
            assertFalse("resume from IDLE", engine.resume())
            assertFalse("cancel from IDLE", engine.cancel())
            assertFalse("dismiss from IDLE", engine.dismissResult())

            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertTrue("pause from PLANNING", engine.pause())
            assertFalse("resume needs PAUSED", engine.resume())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `dismissResult only from terminal states`() {
        val (engine, scope) = engine()
        try {
            assertTrue(engine.transitionTo(SwarmLifecycle.PLANNING))
            assertTrue(engine.transitionTo(SwarmLifecycle.RUNNING))
            assertFalse("dismiss from RUNNING", engine.dismissResult())
            assertTrue(engine.transitionTo(SwarmLifecycle.DONE))
            assertTrue("dismiss from DONE", engine.dismissResult())
            assertEquals(SwarmLifecycle.IDLE, engine.uiState.value.lifecycle)
            assertTrue(engine.uiState.value.agents.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
