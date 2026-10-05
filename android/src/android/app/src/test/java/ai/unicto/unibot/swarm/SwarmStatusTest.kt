package ai.unicto.unibot.swarm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.3.5 unit tests for the nav drawer's swarm status hint: the pure
 * [swarmDrawerHint] lifecycle mapping, the [SwarmStatus] mirror the engine
 * publishes to, and the checkpoint replay that keeps the hint honest before
 * any engine exists in the process. No Compose, no Robolectric — plain
 * `testDebugUnitTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwarmStatusTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        SwarmStatus.resetForTests()
    }

    @After
    fun tearDown() {
        SwarmStatus.resetForTests()
    }

    private fun stateOf(lifecycle: SwarmLifecycle, agentCount: Int): SwarmUiState =
        SwarmUiState(
            lifecycle = lifecycle,
            mission = "m",
            crew = SwarmRoles.research,
            agents = List(agentCount) { i ->
                SwarmAgentState(
                    id = "agent-$i",
                    role = "researcher",
                    displayName = "Researcher",
                    status = SwarmAgentStatus.QUEUED,
                    currentStep = "Queued",
                )
            },
        )

    // ── swarmDrawerHint: pure lifecycle → hint mapping ──────────────────────

    @Test
    fun `idle maps to Idle hint`() {
        assertEquals(SwarmDrawerHint.Idle, swarmDrawerHint(stateOf(SwarmLifecycle.IDLE, 0)))
    }

    @Test
    fun `terminal lifecycles map to Idle hint`() {
        listOf(SwarmLifecycle.DONE, SwarmLifecycle.CANCELLED, SwarmLifecycle.FAILED).forEach { lc ->
            assertEquals(
                "expected Idle hint for $lc",
                SwarmDrawerHint.Idle,
                swarmDrawerHint(stateOf(lc, 3)),
            )
        }
    }

    @Test
    fun `planning maps to an active non-paused hint with the crew size`() {
        assertEquals(
            SwarmDrawerHint.Active(agentCount = 4, paused = false),
            swarmDrawerHint(stateOf(SwarmLifecycle.PLANNING, 4)),
        )
    }

    @Test
    fun `running maps to an active non-paused hint with N agents`() {
        assertEquals(
            SwarmDrawerHint.Active(agentCount = 3, paused = false),
            swarmDrawerHint(stateOf(SwarmLifecycle.RUNNING, 3)),
        )
    }

    @Test
    fun `paused maps to an active paused hint with N agents`() {
        assertEquals(
            SwarmDrawerHint.Active(agentCount = 2, paused = true),
            swarmDrawerHint(stateOf(SwarmLifecycle.PAUSED, 2)),
        )
    }

    // ── SwarmStatus mirror ──────────────────────────────────────────────────

    @Test
    fun `publish drives the hint flow`() {
        SwarmStatus.publish(stateOf(SwarmLifecycle.RUNNING, 3))
        assertEquals(SwarmDrawerHint.Active(3, false), SwarmStatus.hint.value)
        SwarmStatus.publish(stateOf(SwarmLifecycle.PAUSED, 3))
        assertEquals(SwarmDrawerHint.Active(3, true), SwarmStatus.hint.value)
        SwarmStatus.publish(stateOf(SwarmLifecycle.DONE, 3))
        assertEquals(SwarmDrawerHint.Idle, SwarmStatus.hint.value)
    }

    @Test
    fun `engine launch publishes an active hint synchronously`() = runTest(mainDispatcher) {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)
        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // launch() is synchronous up to PLANNING — the mirror must already
        // show the crew, with no dispatcher advance.
        assertEquals(
            SwarmDrawerHint.Active(SwarmRoles.research.roles.size, false),
            SwarmStatus.hint.value,
        )
        // Tear the run down deterministically so the test scope closes clean.
        assertTrue(engine.cancel())
    }

    // ── checkpoint mirror: honest hint before any engine exists ─────────────

    private fun checkpointedStore(lifecycle: SwarmLifecycle, agentCount: Int): InMemorySwarmCheckpointStore {
        val store = InMemorySwarmCheckpointStore()
        val data = SwarmCheckpoint.fromUiState(
            stateOf(lifecycle, agentCount),
            subtasks = List(agentCount) { "subtask $it" },
        ) ?: error("expected a checkpointable state for $lifecycle")
        store.save(SwarmCheckpoint.encode(data))
        return store
    }

    @Test
    fun `checkpoint mirror replays an interrupted run as paused`() {
        SwarmStatus.ensureCheckpointMirror(checkpointedStore(SwarmLifecycle.RUNNING, 3))
        // Restored runs normalize to PAUSED — the drawer shows the crew size.
        assertEquals(SwarmDrawerHint.Active(3, true), SwarmStatus.hint.value)
    }

    @Test
    fun `checkpoint mirror replays a planning run as paused`() {
        SwarmStatus.ensureCheckpointMirror(checkpointedStore(SwarmLifecycle.PLANNING, 2))
        assertEquals(SwarmDrawerHint.Active(2, true), SwarmStatus.hint.value)
    }

    @Test
    fun `checkpoint mirror stays idle with no checkpoint`() {
        SwarmStatus.ensureCheckpointMirror(InMemorySwarmCheckpointStore())
        assertEquals(SwarmDrawerHint.Idle, SwarmStatus.hint.value)
    }

    @Test
    fun `checkpoint mirror ignores corrupt json`() {
        val store = InMemorySwarmCheckpointStore()
        store.save("{not json")
        SwarmStatus.ensureCheckpointMirror(store)
        assertEquals(SwarmDrawerHint.Idle, SwarmStatus.hint.value)
    }

    @Test
    fun `checkpoint mirror never overrides a live engine`() {
        SwarmStatus.publish(stateOf(SwarmLifecycle.RUNNING, 2))
        SwarmStatus.ensureCheckpointMirror(checkpointedStore(SwarmLifecycle.RUNNING, 5))
        assertEquals(SwarmDrawerHint.Active(2, false), SwarmStatus.hint.value)
    }
}
