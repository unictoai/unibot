package ai.unicto.unibot.swarm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 items 3 + 10 — concurrent-agent control (cap 1..8, recorded on the
 * run, scheduling helper for the future parallel executor) and mission
 * attachments (prompt context + size caps).
 */
class SwarmLaunchOptionsTest {

    @Test
    fun `maxWorkers is coerced to 1-8 on launch`() = runTest {
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts"))),
            InMemorySwarmCheckpointStore(),
            this,
        )
        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(maxWorkers = 99),
        )
        assertEquals(8, engine.uiState.value.maxWorkers)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertTrue(engine.dismissResult())

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(maxWorkers = 0),
        )
        assertEquals(1, engine.uiState.value.maxWorkers)
    }

    @Test
    fun `maxWorkers is checkpointed`() = runTest {
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts"))),
            store,
            this,
        )
        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(maxWorkers = 5),
        )
        engine.uiState.first { it.lifecycle == SwarmLifecycle.RUNNING }
        val restored = SwarmCheckpoint.toUiState(SwarmCheckpoint.decode(store.load()!!)!!)!!
        assertEquals(5, restored.maxWorkers)
    }

    @Test
    fun `batchWorkerIndices chunks worker indices by the cap`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), batchWorkerIndices(3, 1))
        assertEquals(
            listOf(listOf(0, 1), listOf(2, 3), listOf(4)),
            batchWorkerIndices(5, 2),
        )
        assertEquals(listOf(listOf(0, 1, 2)), batchWorkerIndices(3, 8))
        assertEquals(emptyList<List<Int>>(), batchWorkerIndices(0, 4))
        // Invalid caps coerce into range instead of crashing the scheduler.
        assertEquals(listOf(listOf(0), listOf(1)), batchWorkerIndices(2, 0))
        assertEquals(listOf(listOf(0, 1)), batchWorkerIndices(2, 99))
    }

    @Test
    fun `attachments reach worker prompts as a documents section`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(
                attachments = listOf(
                    SwarmAttachment(id = "1", name = "prices.txt", text = "Scooter X costs 500."),
                ),
            ),
        )
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals(1, final.attachments.size)

        val workerPrompts = provider.calls.map { it.second }
            .filter { it.startsWith("WORKER SUBTASK") }
        assertTrue(workerPrompts.isNotEmpty())
        workerPrompts.forEach { prompt ->
            assertTrue(prompt.contains("ATTACHED DOCUMENTS"))
            assertTrue(prompt.contains("prices.txt"))
            assertTrue(prompt.contains("Scooter X costs 500."))
        }
        // The plan prompt sees the documents too, so decomposition covers them.
        val planPrompt = provider.calls.map { it.second }
            .first { it.startsWith("MISSION DECOMPOSITION") }
        assertTrue(planPrompt.contains("prices.txt"))
    }

    @Test
    fun `attachments are capped in count and size`() = runTest {
        val engine = SwarmEngine(
            FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts"))),
            InMemorySwarmCheckpointStore(),
            this,
        )
        val big = "x".repeat(SwarmAttachment.MAX_CHARS_PER_ATTACHMENT + 100)
        engine.launch(
            "Research scooters",
            SwarmRoles.research,
            SwarmLaunchOptions(
                attachments = (1..6).map { i ->
                    SwarmAttachment(id = "$i", name = "doc$i.txt", text = big)
                },
            ),
        )
        val state = engine.uiState.value
        assertEquals(SwarmAttachment.MAX_ATTACHMENTS, state.attachments.size)
        assertTrue(state.attachments.all { it.text.length == SwarmAttachment.MAX_CHARS_PER_ATTACHMENT })
    }

    @Test
    fun `attachmentsContext is empty without attachments`() {
        assertEquals("", attachmentsContext(emptyList()))
        val ctx = attachmentsContext(listOf(SwarmAttachment("1", "a.txt", "hello")))
        assertTrue(ctx.contains("a.txt"))
        assertTrue(ctx.contains("hello"))
    }

    @Test
    fun `normalizePlan pads and trims to exactly one subtask per role`() {
        val roles = listOf("researcher", "verifier")
        assertEquals(
            listOf("a", "b"),
            normalizePlan(listOf("a", "b", "c"), roles, "mission"),
        )
        assertEquals(
            listOf("a", "mission"),
            normalizePlan(listOf("a"), roles, "mission"),
        )
        assertEquals(
            listOf("mission", "mission"),
            normalizePlan(emptyList(), roles, "mission"),
        )
        assertEquals(
            listOf("mission", "mission"),
            normalizePlan(listOf(" ", ""), roles, "mission"),
        )
    }
}
