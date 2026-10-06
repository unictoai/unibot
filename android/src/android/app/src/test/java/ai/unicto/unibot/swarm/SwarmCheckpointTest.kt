package ai.unicto.unibot.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip and resume-semantics tests for swarm checkpointing. */
class SwarmCheckpointTest {

    private fun runningState() = SwarmUiState(
        lifecycle = SwarmLifecycle.RUNNING,
        mission = "Research electric scooters",
        crew = SwarmRoles.research,
        agents = listOf(
            SwarmAgentState(
                id = "agent-0-researcher",
                role = "researcher",
                displayName = "Researcher",
                status = SwarmAgentStatus.DONE,
                currentStep = "Done",
                tokensUsed = 300,
                result = "Specs gathered.",
            ),
            SwarmAgentState(
                id = "agent-1-verifier",
                role = "verifier",
                displayName = "Verifier",
                status = SwarmAgentStatus.WORKING,
                currentStep = "Working",
                detail = "Running subtask",
                tokensUsed = 120,
            ),
        ),
        stitchedResult = "",
        totalTokens = 420,
    )

    @Test
    fun `encode then decode round-trips the checkpoint`() {
        val data = SwarmCheckpoint.fromUiState(
            runningState(),
            listOf("subtask one", "subtask two"),
        )
        assertNotNull(data)
        val decoded = SwarmCheckpoint.decode(SwarmCheckpoint.encode(data!!))
        assertNotNull(decoded)
        assertEquals(data, decoded)
    }

    @Test
    fun `decode rejects corrupt json`() {
        assertNull(SwarmCheckpoint.decode("not json at all"))
        assertNull(SwarmCheckpoint.decode(""))
        assertNull(SwarmCheckpoint.decode("""{"lifecycle": 42}"""))
    }

    @Test
    fun `fromUiState returns null for terminal and idle lifecycles`() {
        val base = runningState()
        assertNull(SwarmCheckpoint.fromUiState(base.copy(lifecycle = SwarmLifecycle.IDLE), emptyList()))
        assertNull(SwarmCheckpoint.fromUiState(base.copy(lifecycle = SwarmLifecycle.DONE), emptyList()))
        assertNull(SwarmCheckpoint.fromUiState(base.copy(lifecycle = SwarmLifecycle.CANCELLED), emptyList()))
        assertNull(SwarmCheckpoint.fromUiState(base.copy(lifecycle = SwarmLifecycle.FAILED), emptyList()))
        assertNotNull(SwarmCheckpoint.fromUiState(base.copy(lifecycle = SwarmLifecycle.PLANNING), emptyList()))
        assertNotNull(SwarmCheckpoint.fromUiState(base.copy(lifecycle = SwarmLifecycle.PAUSED), emptyList()))
    }

    @Test
    fun `toUiState normalizes to paused with canResume`() {
        val data = SwarmCheckpoint.fromUiState(
            runningState().copy(lifecycle = SwarmLifecycle.RUNNING),
            listOf("s1", "s2"),
        )!!
        val restored = SwarmCheckpoint.toUiState(data)
        assertNotNull(restored)
        restored!!
        // Nothing is actually running after process death: PAUSED is honest.
        assertEquals(SwarmLifecycle.PAUSED, restored.lifecycle)
        assertTrue(restored.canResume)
        assertEquals("Research electric scooters", restored.mission)
        assertEquals("research", restored.crew?.id)
        assertEquals(420, restored.totalTokens)
    }

    @Test
    fun `toUiState keeps done agents and requeues mid-step agents`() {
        val data = SwarmCheckpoint.fromUiState(runningState(), listOf("s1", "s2"))!!
        val restored = SwarmCheckpoint.toUiState(data)!!
        val researcher = restored.agents[0]
        assertEquals(SwarmAgentStatus.DONE, researcher.status)
        assertEquals("Specs gathered.", researcher.result)
        assertEquals(300, researcher.tokensUsed)
        val verifier = restored.agents[1]
        // Crashed mid-step: re-run whole, never resume half a step.
        assertEquals(SwarmAgentStatus.QUEUED, verifier.status)
        assertEquals("Queued", verifier.currentStep)
        assertEquals(120, verifier.tokensUsed)
    }

    @Test
    fun `toUiState rejects terminal lifecycles and unknown crews`() {
        val data = SwarmCheckpoint.fromUiState(runningState(), emptyList())!!
        assertNull(SwarmCheckpoint.toUiState(data.copy(lifecycle = "DONE")))
        assertNull(SwarmCheckpoint.toUiState(data.copy(lifecycle = "IDLE")))
        assertNull(SwarmCheckpoint.toUiState(data.copy(lifecycle = "BOGUS")))
        assertNull(SwarmCheckpoint.toUiState(data.copy(crewId = "no-such-preset", crewRoles = emptyList())))
    }

    @Test
    fun `toUiState rejects corrupt agent statuses`() {
        val data = SwarmCheckpoint.fromUiState(runningState(), emptyList())!!
        val corrupt = data.copy(agents = data.agents.map { it.copy(status = "BOGUS") })
        assertNull(SwarmCheckpoint.toUiState(corrupt))
    }

    @Test
    fun `store save load clear round-trips`() {
        val store = InMemorySwarmCheckpointStore()
        assertNull(store.load())
        store.save("""{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", store.load())
        store.clear()
        assertNull(store.load())
    }
}
