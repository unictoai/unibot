package ai.unicto.unibot.swarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 4 — mission history: terminal runs (done / cancelled /
 * failed) are recorded with the crew's role list so any entry can be
 * re-run exactly as it ran, even after its custom preset was deleted.
 */
class SwarmHistoryTest {

    @Test
    fun `completed mission is recorded with outcome and cost`() = runTest {
        val repo = InMemorySwarmRepository()
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts"))),
            InMemorySwarmCheckpointStore(),
            this,
            repository = repo,
        )
        engine.launch("Research scooters", SwarmRoles.research)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        val records = repo.listHistory()
        assertEquals(1, records.size)
        val record = records[0]
        assertEquals("Research scooters", record.mission)
        assertEquals("research", record.crewId)
        assertEquals("Research", record.crewName)
        assertEquals(listOf("researcher", "verifier"), record.crewRoles)
        assertEquals(SwarmMissionRecord.OUTCOME_DONE, record.outcome)
        assertEquals(900, record.totalTokens)
        assertEquals(2, record.agentCount)
        assertTrue(record.finishedAtMillis > 0)
        assertTrue(record.id.isNotBlank())
    }

    @Test
    fun `cancelled mission is recorded once`() = runTest {
        val repo = InMemorySwarmRepository()
        val gate = CompletableDeferred<Unit>()
        val provider = FakeSwarmProvider(
            standardSwarmHandler(listOf("Find facts", "Check facts")),
            blockRole = "researcher",
            gate = gate,
        )
        val engine = SwarmEngine(
            provider,
            InMemorySwarmCheckpointStore(),
            this,
            repository = repo,
        )
        engine.launch("Research scooters", SwarmRoles.research)
        engine.uiState.first { it.agents[0].status == SwarmAgentStatus.WORKING }
        engine.cancel()
        gate.complete(Unit) // let the cancelled step unwind
        engine.uiState.first { it.lifecycle == SwarmLifecycle.CANCELLED }

        val records = repo.listHistory()
        assertEquals(1, records.size)
        assertEquals(SwarmMissionRecord.OUTCOME_CANCELLED, records[0].outcome)
    }

    @Test
    fun `failed mission is recorded`() = runTest {
        val repo = InMemorySwarmRepository()
        val failing = FakeSwarmProvider { _, _ -> throw RuntimeException("boom") }
        val engine = SwarmEngine(failing, InMemorySwarmCheckpointStore(), this, repository = repo)
        engine.launch("Research scooters", SwarmRoles.research)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.FAILED }

        val records = repo.listHistory()
        assertEquals(1, records.size)
        assertEquals(SwarmMissionRecord.OUTCOME_FAILED, records[0].outcome)
    }

    @Test
    fun `no repository means no recording and no crash`() = runTest {
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts"))),
            InMemorySwarmCheckpointStore(),
            this,
            // repository = null: the legacy/test path
        )
        engine.launch("Research scooters", SwarmRoles.research)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        // Nothing to assert — the run simply completed without a store.
    }

    @Test
    fun `history is newest-first and capped`() {
        val repo = InMemorySwarmRepository()
        repeat(55) { i ->
            repo.recordHistory(
                SwarmMissionRecord(
                    id = "m$i",
                    mission = "mission $i",
                    crewId = "research",
                    crewName = "Research",
                    crewRoles = listOf("researcher", "verifier"),
                    outcome = SwarmMissionRecord.OUTCOME_DONE,
                    totalTokens = i,
                    agentCount = 2,
                    finishedAtMillis = i.toLong(),
                ),
            )
        }
        val records = repo.listHistory()
        assertEquals(50, records.size)
        assertEquals("mission 54", records.first().mission)
        assertEquals("mission 5", records.last().mission)
    }
}
