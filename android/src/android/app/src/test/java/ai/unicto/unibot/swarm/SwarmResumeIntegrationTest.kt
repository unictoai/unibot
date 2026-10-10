package ai.unicto.unibot.swarm

import ai.unicto.unibot.data.model.LLMError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for v1.5 swarm survival semantics:
 * - Bug 1: teardown (screen pop) keeps the checkpoint and parks at PAUSED
 *   with canResume=true, so a new engine can resume where the old one stopped.
 * - Bug 2: transient 429s are retried; exhausted retries park at PAUSED
 *   (not FAILED) with the checkpoint intact.
 */
class SwarmResumeIntegrationTest {

    private fun scriptedProvider(): FakeSwarmProvider = FakeSwarmProvider { _, user ->
        when {
            user.startsWith("MISSION DECOMPOSITION") ->
                FakeSwarmResponse("1. Subtask one\n2. Subtask two\n3. Subtask three")
            user.startsWith("VERIFY OUTPUT") -> FakeSwarmResponse("PASS")
            user.startsWith("STITCH RESULTS") -> FakeSwarmResponse("Stitched document.")
            else -> FakeSwarmResponse("Worker output.")
        }
    }

    @Test
    fun `teardown mid-worker keeps checkpoint and parks at PAUSED with canResume`() = runTest {
        val store = InMemorySwarmCheckpointStore()
        val gate = CompletableDeferred<Unit>()
        // Block the first worker so the run is mid-step when we tear down.
        val provider = FakeSwarmProvider(
            handler = { _, user ->
                when {
                    user.startsWith("MISSION DECOMPOSITION") ->
                        FakeSwarmResponse("1. Subtask one\n2. Subtask two\n3. Subtask three")
                    user.startsWith("VERIFY OUTPUT") -> FakeSwarmResponse("PASS")
                    else -> FakeSwarmResponse("Worker output.")
                }
            },
            blockRole = "researcher",
            gate = gate,
        )
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // Wait until the first worker is actually blocked inside its LLM call.
        engine.uiState.first { st ->
            st.agents.any { it.status == SwarmAgentStatus.WORKING }
        }

        // Simulate the Swarm screen being popped: ViewModel.onCleared -> teardown().
        engine.teardown()

        val parked = engine.uiState.first {
            it.lifecycle == SwarmLifecycle.PAUSED && it.canResume
        }
        assertTrue(parked.canResume)
        // Bug 1: the checkpoint must NOT be wiped.
        assertNotNull("teardown must keep the checkpoint", store.load())

        // A new engine (new screen instance) restores the run resumably.
        val engine2 = SwarmEngine(scriptedProvider(), store, this)
        val restored = engine2.uiState.value
        assertEquals(SwarmLifecycle.PAUSED, restored.lifecycle)
        assertTrue(restored.canResume)
        // The first worker never finished, so resume must retry it, not skip it.
        assertTrue(restored.agents.none { it.status == SwarmAgentStatus.DONE })
    }

    @Test
    fun `transient 429 on a worker is retried and the run completes`() = runTest {
        val store = InMemorySwarmCheckpointStore()
        var attempts = 0
        val provider = FakeSwarmProvider { _, user ->
            when {
                user.startsWith("MISSION DECOMPOSITION") ->
                    FakeSwarmResponse("1. Subtask one\n2. Subtask two\n3. Subtask three")
                user.startsWith("VERIFY OUTPUT") -> FakeSwarmResponse("PASS")
                user.startsWith("STITCH RESULTS") -> FakeSwarmResponse("Stitched document.")
                else -> {
                    attempts++
                    // Fail the first worker-call attempt with a 429, succeed on retry.
                    if (attempts == 1) throw LLMError.RateLimited(429)
                    FakeSwarmResponse("Worker output after retry.")
                }
            }
        }
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // Bug 2: one transient 429 must not fail the run — it completes.
        val done = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertTrue(done.stitchedResult.isNotBlank())
        assertTrue("worker should have been attempted twice (fail + retry)", attempts >= 2)
    }

    @Test
    fun `exhausted 429 retries park at PAUSED with checkpoint kept, not FAILED`() = runTest {
        val store = InMemorySwarmCheckpointStore()
        val provider = FakeSwarmProvider { _, _ -> throw LLMError.RateLimited(429) }
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // Bug 2: after 3 failed attempts the run parks at PAUSED (resumable),
        // never FAILED (terminal, checkpoint wiped).
        val parked = engine.uiState.first { it.lifecycle == SwarmLifecycle.PAUSED }
        assertTrue(parked.canResume)
        assertTrue(parked.error?.contains("Resume to retry") == true)
        assertNotNull("exhausted retries must keep the checkpoint", store.load())
        // Three attempts were made (not one, not infinite).
        assertEquals(3, provider.callsStartingWith("MISSION DECOMPOSITION"))
    }
}
