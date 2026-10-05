package ai.unicto.unibot.swarm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wiring tests for [SwarmViewModel]: it exposes the engine's [SwarmUiState]
 * and delegates launch/pause/resume/cancel/dismissResult. Uses an in-memory
 * checkpoint store — no Android Context needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwarmViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `launch runs the swarm to done through the viewmodel`() = runTest(mainDispatcher) {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val vm = SwarmViewModel(provider, InMemorySwarmCheckpointStore())

        assertTrue(vm.launch("Research scooters", SwarmRoles.research))
        repeat(400) {
            advanceUntilIdle()
            if (vm.uiState.value.lifecycle == SwarmLifecycle.DONE) return@repeat
            delay(25)
        }
        val final = vm.uiState.value
        assertEquals(SwarmLifecycle.DONE, final.lifecycle)
        assertEquals("Stitched document", final.stitchedResult)
        assertEquals(2, final.agents.size)
        assertTrue(final.agents.all { it.status == SwarmAgentStatus.DONE })

        assertTrue(vm.dismissResult())
        assertEquals(SwarmLifecycle.IDLE, vm.uiState.value.lifecycle)
    }

    @Test
    fun `pause and resume delegate to the engine`() = runTest(mainDispatcher) {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val vm = SwarmViewModel(provider, InMemorySwarmCheckpointStore())

        assertTrue(vm.launch("Research scooters", SwarmRoles.research))
        // Let planning finish and the run start.
        repeat(200) {
            advanceUntilIdle()
            if (vm.uiState.value.lifecycle == SwarmLifecycle.RUNNING) return@repeat
            delay(25)
        }
        assertEquals(SwarmLifecycle.RUNNING, vm.uiState.value.lifecycle)
        assertTrue(vm.pause())
        repeat(400) {
            advanceUntilIdle()
            if (vm.uiState.value.lifecycle == SwarmLifecycle.PAUSED) return@repeat
            delay(25)
        }
        assertEquals(SwarmLifecycle.PAUSED, vm.uiState.value.lifecycle)
        assertTrue(vm.resume())
        repeat(400) {
            advanceUntilIdle()
            if (vm.uiState.value.lifecycle == SwarmLifecycle.DONE) return@repeat
            delay(25)
        }
        assertEquals(SwarmLifecycle.DONE, vm.uiState.value.lifecycle)
    }

    @Test
    fun `cancel delegates to the engine`() = runTest(mainDispatcher) {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val vm = SwarmViewModel(provider, InMemorySwarmCheckpointStore())

        assertTrue(vm.launch("Research scooters", SwarmRoles.research))
        repeat(200) {
            advanceUntilIdle()
            if (vm.uiState.value.lifecycle == SwarmLifecycle.RUNNING) return@repeat
            delay(25)
        }
        assertTrue(vm.cancel())
        repeat(400) {
            advanceUntilIdle()
            if (vm.uiState.value.lifecycle == SwarmLifecycle.CANCELLED) return@repeat
            delay(25)
        }
        assertEquals(SwarmLifecycle.CANCELLED, vm.uiState.value.lifecycle)
    }

    @Test
    fun `discardCheckpoint clears a restored interrupted run and returns to IDLE`() = runTest(mainDispatcher) {
        val store = InMemorySwarmCheckpointStore()
        store.save(
            SwarmCheckpoint.encode(
                SwarmCheckpointData(
                    lifecycle = SwarmLifecycle.RUNNING.name,
                    mission = "Interrupted mission",
                    crewId = SwarmRoles.research.id,
                ),
            ),
        )
        val vm = SwarmViewModel(FakeSwarmProvider({ _, _ -> FakeSwarmResponse("") }), store)
        // Restore normalizes a checkpointed run to PAUSED with canResume.
        assertEquals(SwarmLifecycle.PAUSED, vm.uiState.value.lifecycle)
        assertTrue(vm.uiState.value.canResume)
        // dismissResult() must NOT work here — this is the resume-banner caveat.
        assertTrue(!vm.dismissResult())
        assertTrue(vm.discardCheckpoint())
        assertEquals(SwarmLifecycle.IDLE, vm.uiState.value.lifecycle)
        assertEquals("checkpoint cleared", null, store.load())
    }
}
