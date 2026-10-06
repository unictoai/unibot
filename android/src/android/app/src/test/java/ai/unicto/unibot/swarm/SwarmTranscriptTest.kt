package ai.unicto.unibot.swarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 8 — full agent transcripts: the engine records a
 * timestamped step log per agent (worker calls, verifier verdicts,
 * revisions), checkpointed and restored.
 */
class SwarmTranscriptTest {

    @Test
    fun `agents log each step of the worker lifecycle`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        engine.launch("Research scooters", SwarmRoles.research)
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        final.agents.forEach { agent ->
            assertTrue("agent ${agent.id} has log entries", agent.log.isNotEmpty())
            assertTrue(agent.log.any { it.contains("Worker call started") })
            assertTrue(agent.log.any { it.contains("Worker call finished") })
            assertTrue(agent.log.any { it.contains("Verifier verdict: PASS") })
            assertTrue(agent.log.any { it.contains("Step complete") })
            // Entries are timestamped [HH:mm:ss].
            assertTrue(agent.log.all { it.matches(Regex("""\[\d{2}:\d{2}:\d{2}] .*""")) })
        }
    }

    @Test
    fun `failed verification and revision are logged`() = runTest {
        val provider = FakeSwarmProvider(
            standardSwarmHandler(
                plan = listOf("Find facts", "Check facts"),
                verify = { "FAIL: missing concrete numbers" },
            ),
        )
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        engine.launch("Research scooters", SwarmRoles.research)
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        val researcher = final.agents.first { it.role == "researcher" }
        assertTrue(researcher.log.any { it.contains("Verifier verdict: FAIL") })
        assertTrue(researcher.log.any { it.contains("Revision call started") })
        assertTrue(researcher.log.any { it.contains("Revision call finished") })
    }

    @Test
    fun `transcript log survives a checkpoint round-trip`() = runTest {
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts"))),
            store,
            this,
        )
        engine.launch("Research scooters", SwarmRoles.research)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.RUNNING }

        val raw = store.load()!!
        val restored = SwarmCheckpoint.toUiState(SwarmCheckpoint.decode(raw)!!)!!
        val doneAgent = restored.agents.firstOrNull { it.status == SwarmAgentStatus.DONE }
        if (doneAgent != null) {
            assertTrue(doneAgent.log.isNotEmpty())
            assertTrue(doneAgent.log.any { it.contains("Worker call started") })
        } else {
            // No agent finished before the checkpoint — the run only
            // reached RUNNING. The plan entry is logged on every agent.
            assertTrue(restored.agents.all { it.log.any { it.contains("Manager decomposed") } })
        }
    }

    @Test
    fun `log entries are capped per agent`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        engine.launch("Research scooters", SwarmRoles.research)
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        final.agents.forEach { agent ->
            assertTrue(agent.log.size <= 50)
        }
    }

    @Test
    fun `terminal callback fires on done and failed, not on cancel`() = runTest {
        val terminal = mutableListOf<SwarmLifecycle>()
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(
            provider,
            InMemorySwarmCheckpointStore(),
            this,
            onTerminal = { terminal.add(it.lifecycle) },
        )
        engine.launch("Research scooters", SwarmRoles.research)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals(listOf(SwarmLifecycle.DONE), terminal)

        // A cancelled run must not ping: block the worker so the cancel
        // lands deterministically mid-run.
        assertTrue(engine.dismissResult())
        val gate = CompletableDeferred<Unit>()
        val blockingProvider = FakeSwarmProvider(
            standardSwarmHandler(listOf("Find facts", "Check facts")),
            blockRole = "researcher",
            gate = gate,
        )
        val engine2 = SwarmEngine(
            blockingProvider,
            InMemorySwarmCheckpointStore(),
            this,
            onTerminal = { terminal.add(it.lifecycle) },
        )
        engine2.launch("Research scooters", SwarmRoles.research)
        engine2.uiState.first { it.agents[0].status == SwarmAgentStatus.WORKING }
        engine2.cancel()
        gate.complete(Unit)
        engine2.uiState.first { it.lifecycle == SwarmLifecycle.CANCELLED }
        assertEquals(listOf(SwarmLifecycle.DONE), terminal)
    }
}
