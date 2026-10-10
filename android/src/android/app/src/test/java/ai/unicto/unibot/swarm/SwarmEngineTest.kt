package ai.unicto.unibot.swarm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end engine tests with a scripted fake provider — hermetic, no network.
 * The engine's [kotlinx.coroutines.CoroutineScope] is the test scope.
 */
class SwarmEngineTest {

    @Test
    fun `full run completes with stitched result and accounted cost`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Find facts", "Check facts")))
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        assertEquals(2, final.agents.size)
        assertTrue(final.agents.all { it.status == SwarmAgentStatus.DONE })
        assertTrue(final.agents.all { it.result.isNotBlank() })
        assertEquals("Stitched document", final.stitchedResult)
        assertFalse(final.canResume)

        // Cost: 6 calls (plan, worker+verify x2, stitch) x 150 tokens.
        assertEquals(900, final.totalTokens)
        // Per-agent: worker + verify each. Plan/stitch go to the total only.
        assertEquals(300, final.agents[0].tokensUsed)
        assertEquals(300, final.agents[1].tokensUsed)

        // Terminal run clears the checkpoint.
        assertNull(store.load())

        // Call order: plan, worker, verify, worker, verify, stitch.
        val kinds = provider.calls.map { (_, user) ->
            when {
                user.startsWith("MISSION DECOMPOSITION") -> "plan"
                user.startsWith("WORKER SUBTASK") -> "worker:${roleOfWorkerPrompt(user)}"
                user.startsWith("VERIFY OUTPUT") -> "verify"
                user.startsWith("STITCH RESULTS") -> "stitch"
                else -> "unknown"
            }
        }
        assertEquals(
            listOf("plan", "worker:researcher", "verify", "worker:verifier", "verify", "stitch"),
            kinds,
        )
    }

    @Test
    fun `malformed plan falls back to one subtask per role and still completes`() = runTest {
        val provider = FakeSwarmProvider({ _, user ->
            when {
                user.startsWith("MISSION DECOMPOSITION") ->
                    FakeSwarmResponse("Here are some thoughts with no list whatsoever.")
                user.startsWith("WORKER SUBTASK") ->
                    FakeSwarmResponse("Result from ${roleOfWorkerPrompt(user)}")
                user.startsWith("VERIFY OUTPUT") -> FakeSwarmResponse("PASS")
                user.startsWith("STITCH RESULTS") -> FakeSwarmResponse("Stitched document")
                else -> FakeSwarmResponse("unexpected")
            }
        })
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals(2, final.agents.size)
        assertTrue(final.agents.all { it.status == SwarmAgentStatus.DONE })
        assertEquals("Stitched document", final.stitchedResult)
    }

    @Test
    fun `pause mid-run then resume continues from next step without re-running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeSwarmProvider(
            standardSwarmHandler(listOf("Find facts", "Check facts")),
            blockRole = "verifier",
            gate = gate,
        )
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // The verifier's worker call is blocked mid-step.
        engine.uiState.first { it.agents[1].status == SwarmAgentStatus.WORKING }
        assertTrue(engine.pause())
        // The in-flight step finishes, then the run halts at PAUSED.
        gate.complete(Unit)
        val paused = engine.uiState.first { it.lifecycle == SwarmLifecycle.PAUSED }
        assertEquals(SwarmAgentStatus.DONE, paused.agents[1].status)
        assertTrue("no stitching while paused", paused.stitchedResult.isBlank())
        assertNotNull("checkpoint must be saved at pause", store.load())

        val researcherCalls = provider.workerCalls("researcher")
        val verifierCalls = provider.workerCalls("verifier")
        assertTrue(engine.resume())
        val done = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals("Stitched document", done.stitchedResult)
        assertEquals("researcher must not re-run", researcherCalls, provider.workerCalls("researcher"))
        assertEquals("verifier must not re-run", verifierCalls, provider.workerCalls("verifier"))
    }

    @Test
    fun `verifier failure triggers exactly one revision then best output stands`() = runTest {
        var researcherAttempts = 0
        val provider = FakeSwarmProvider({ _, user ->
            when {
                user.startsWith("MISSION DECOMPOSITION") ->
                    FakeSwarmResponse("1. Find facts\n2. Check facts")
                user.startsWith("WORKER SUBTASK") -> {
                    val role = roleOfWorkerPrompt(user)
                    if (role == "researcher") {
                        researcherAttempts++
                        val isRevision = user.contains("REVISION")
                        FakeSwarmResponse(if (isRevision) "FINAL findings" else "DRAFT findings")
                    } else {
                        FakeSwarmResponse("Verifier report")
                    }
                }
                user.startsWith("VERIFY OUTPUT") -> {
                    val output = user.substringAfter("Worker output:").trim()
                    // Fails even the revision output: proves the retry bound is
                    // structural (no re-verify loop), not luck.
                    FakeSwarmResponse(if (output.contains("findings")) "FAIL: not good enough" else "PASS")
                }
                user.startsWith("STITCH RESULTS") -> FakeSwarmResponse("Stitched document")
                else -> FakeSwarmResponse("unexpected")
            }
        })
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        val done = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        assertEquals(
            "researcher attempted exactly twice: initial + one bounded revision",
            2,
            researcherAttempts,
        )
        assertEquals(
            "exactly one verify per worker — the revision is never re-verified",
            2,
            provider.callsStartingWith("VERIFY OUTPUT"),
        )
        val researcher = done.agents[0]
        assertEquals(SwarmAgentStatus.DONE, researcher.status)
        assertTrue(researcher.result.contains("FINAL findings"))
        assertTrue(
            "best output stands with a note",
            researcher.result.contains("automated review flagged"),
        )
    }

    @Test
    fun `cancel stops the run, keeps partial results, clears checkpoint`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeSwarmProvider(
            standardSwarmHandler(listOf("Find facts", "Check facts")),
            blockRole = "researcher",
            gate = gate,
        )
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        engine.uiState.first { it.agents[0].status == SwarmAgentStatus.WORKING }
        assertTrue(engine.cancel())
        gate.complete(Unit) // let the cancelled step unwind
        val cancelled = engine.uiState.first { it.lifecycle == SwarmLifecycle.CANCELLED }

        assertEquals("Research scooters", cancelled.mission)
        assertEquals("partial results stay visible", 2, cancelled.agents.size)
        assertNull("checkpoint cleared on cancel", store.load())
        assertTrue(engine.dismissResult())
        assertEquals(SwarmLifecycle.IDLE, engine.uiState.value.lifecycle)
    }

    @Test
    fun `checkpoint restore resumes from next uncompleted step without re-running done work`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeSwarmProvider(
            standardSwarmHandler(listOf("Find facts", "Check facts")),
            blockRole = "verifier",
            gate = gate,
        )
        val store = InMemorySwarmCheckpointStore()
        val engine1 = SwarmEngine(provider, store, this)

        assertTrue(engine1.launch("Research scooters", SwarmRoles.research))
        engine1.uiState.first { it.agents[0].status == SwarmAgentStatus.DONE }
        assertTrue(engine1.pause())
        gate.complete(Unit)
        engine1.uiState.first { it.lifecycle == SwarmLifecycle.PAUSED }

        // Simulate process death: brand-new engine, same store and provider.
        val engine2 = SwarmEngine(provider, store, this)
        val restored = engine2.uiState.value
        assertEquals(SwarmLifecycle.PAUSED, restored.lifecycle)
        assertTrue(restored.canResume)
        assertEquals("Research scooters", restored.mission)
        assertEquals(SwarmAgentStatus.DONE, restored.agents[0].status)
        assertTrue(restored.agents[0].result.isNotBlank())

        val researcherCallsBefore = provider.workerCalls("researcher")
        val verifierCallsBefore = provider.workerCalls("verifier")
        assertTrue(engine2.resume())
        val done = engine2.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals("Stitched document", done.stitchedResult)
        assertEquals(
            "completed researcher step must not re-run",
            researcherCallsBefore,
            provider.workerCalls("researcher"),
        )
        assertEquals(
            "completed verifier step must not re-run",
            verifierCallsBefore,
            provider.workerCalls("verifier"),
        )
    }

    @Test
    fun `provider failure parks the run at PAUSED with checkpoint kept instead of failing`() = runTest {
        val store = InMemorySwarmCheckpointStore()
        val provider = FakeSwarmProvider({ _, _ -> throw IllegalStateException("boom") })
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        // v1.5 Bug 2 — the run parks at PAUSED with the checkpoint KEPT (not
        // wiped) and canResume=true, so Resume retries the failed step.
        val parked = engine.uiState.first { it.lifecycle == SwarmLifecycle.PAUSED }
        assertTrue(parked.error?.contains("boom") == true)
        assertTrue(parked.canResume)
        assertNotNull(store.load())
        // Resume retries the failed step (the plan call) rather than failing.
        val planCallsBefore = provider.callsStartingWith("MISSION DECOMPOSITION")
        assertTrue(engine.resume())
        val parked2 = engine.uiState.first {
            it.lifecycle == SwarmLifecycle.PAUSED &&
                provider.callsStartingWith("MISSION DECOMPOSITION") > planCallsBefore
        }
        assertTrue(parked2.canResume)
        assertNotNull(store.load())
    }
}
