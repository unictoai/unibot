package ai.unicto.unibot.swarm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 6 — mid-run steering: steer() appends notes while the run is
 * live, and every subsequent worker prompt carries them as a MANAGER
 * STEERING section. Pure prompt-section helpers are covered too.
 */
class SwarmSteeringTest {

    @Test
    fun `steer during planning reaches the worker prompts`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // launch() lands synchronously at PLANNING, so the steer lands
        // before any worker prompt is built.
        assertTrue(engine.steer("Focus on pricing, skip the history section"))

        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals(listOf("Focus on pricing, skip the history section"), final.steeringNotes)

        val workerPrompts = provider.calls.map { it.second }
            .filter { it.startsWith("WORKER SUBTASK") }
        assertTrue(workerPrompts.isNotEmpty())
        workerPrompts.forEach { prompt ->
            assertTrue(prompt.contains("MANAGER STEERING"))
            assertTrue(prompt.contains("Focus on pricing, skip the history section"))
        }
    }

    @Test
    fun `steer is rejected when idle and on empty input`() = runTest {
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("a", "b"))),
            InMemorySwarmCheckpointStore(),
            this,
        )
        assertFalse(engine.steer("anything"))
        engine.launch("Research scooters", SwarmRoles.research)
        assertFalse(engine.steer("   "))
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        // After the terminal state, steering is rejected again.
        assertFalse(engine.steer("too late"))
    }

    @Test
    fun `steering notes are capped and checkpointed`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        engine.launch("Research scooters", SwarmRoles.research)
        repeat(12) { i -> engine.steer("note $i") }

        val state = engine.uiState.value
        assertEquals(10, state.steeringNotes.size)
        assertEquals("note 2", state.steeringNotes.first())
        assertEquals("note 11", state.steeringNotes.last())

        val raw = store.load()!!
        val restored = SwarmCheckpoint.toUiState(SwarmCheckpoint.decode(raw)!!)!!
        assertEquals(state.steeringNotes, restored.steeringNotes)
    }

    @Test
    fun `steeringContext renders newest-last and empty for no notes`() {
        assertEquals("", steeringContext(emptyList()))
        val ctx = steeringContext(listOf("first", "second"))
        assertTrue(ctx.contains("MANAGER STEERING"))
        assertTrue(ctx.indexOf("first") < ctx.indexOf("second"))
    }
}
