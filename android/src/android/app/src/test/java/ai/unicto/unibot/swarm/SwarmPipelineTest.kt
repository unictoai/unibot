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
 * v1.5 pipeline tests (one-tap swarm pipelines, Ruflo's pre-built
 * collaboration templates shape): fixed ordered stages with an explicit
 * handoff, no manager decomposition, no plan-approval gate.
 */
class SwarmPipelineTest {

    private val pipeline = swarmPipelineById("draft-polish")!!

    /** Scripted handler: per-stage worker output, PASS verifies, stitched doc. */
    private fun pipelineHandler(): (String?, String) -> FakeSwarmResponse = { _, user ->
        when {
            user.startsWith("PIPELINE STAGE") ->
                FakeSwarmResponse("Stage output from ${roleOfWorkerPrompt(user)}")
            user.startsWith("WORKER SUBTASK") ->
                FakeSwarmResponse("Result from ${roleOfWorkerPrompt(user)}")
            user.startsWith("MISSION DECOMPOSITION") ->
                FakeSwarmResponse("1. Find facts\n2. Check facts")
            user.startsWith("VERIFY OUTPUT") -> FakeSwarmResponse("PASS")
            user.startsWith("STITCH RESULTS") -> FakeSwarmResponse("Stitched document")
            else -> FakeSwarmResponse("unexpected")
        }
    }

    @Test
    fun `pipeline run skips manager decomposition and runs stages in order`() = runTest {
        val provider = FakeSwarmProvider(pipelineHandler())
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launchPipeline("Write about scooters", pipeline, SwarmLaunchOptions()))
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        // No manager plan call — the pipeline IS the plan.
        assertEquals(0, provider.callsStartingWith("MISSION DECOMPOSITION"))
        // 4 stages: worker + verify each, then stitch.
        assertEquals(4, provider.callsStartingWith("PIPELINE STAGE"))
        assertEquals(4, provider.callsStartingWith("VERIFY OUTPUT"))
        assertEquals(1, provider.callsStartingWith("STITCH RESULTS"))
        assertEquals("Stitched document", final.stitchedResult)
        assertTrue(final.agents.all { it.status == SwarmAgentStatus.DONE })

        // Synthetic crew id carries the pipeline marker.
        assertEquals("pipeline:draft-polish", final.crew?.id)
        assertEquals(
            listOf("researcher", "writer", "editor", "verifier"),
            final.crew?.roles,
        )
    }

    @Test
    fun `each stage receives the previous stage output as its primary input`() = runTest {
        val provider = FakeSwarmProvider(pipelineHandler())
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(engine.launchPipeline("Write about scooters", pipeline, SwarmLaunchOptions()))
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        val stageCalls = provider.calls.map { it.second }.filter { it.startsWith("PIPELINE STAGE") }
        assertEquals(4, stageCalls.size)
        // Stage 1 has no previous output; stages 2-4 carry the handoff.
        assertFalse(stageCalls[0].contains("OUTPUT OF THE PREVIOUS STAGE"))
        for (i in 1..3) {
            assertTrue(
                "stage ${i + 1} must carry the handoff section",
                stageCalls[i].contains("OUTPUT OF THE PREVIOUS STAGE"),
            )
        }
        // The handoff is the ACTUAL previous stage output, verbatim.
        assertTrue(stageCalls[1].contains("Stage output from researcher"))
        assertTrue(stageCalls[2].contains("Stage output from writer"))
        assertTrue(stageCalls[3].contains("Stage output from editor"))
        // Stage briefs (not manager subtasks) drive each stage.
        assertTrue(stageCalls[0].contains("Gather the key facts"))
    }

    @Test
    fun `agents carry stage labels instead of plain queued state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeSwarmProvider(pipelineHandler(), blockRole = "writer", gate = gate)
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(engine.launchPipeline("Write about scooters", pipeline, SwarmLaunchOptions()))
        // Researcher finishes; writer is blocked mid-call — inspect labels.
        engine.uiState.first { it.agents[1].status == SwarmAgentStatus.WORKING }
        val mid = engine.uiState.value
        assertEquals("Stage 1/4", mid.agents[0].currentStep)
        assertEquals("Gather", mid.agents[0].detail)
        assertEquals("Stage 2/4", mid.agents[1].currentStep)
        assertEquals("Draft", mid.agents[1].detail)
        assertEquals("Stage 3/4", mid.agents[2].currentStep)
        gate.complete(Unit)
        engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
    }

    @Test
    fun `pipeline run pauses and resumes from checkpoint without replanning`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeSwarmProvider(pipelineHandler(), blockRole = "editor", gate = gate)
        val store = InMemorySwarmCheckpointStore()
        val engine = SwarmEngine(provider, store, this)

        assertTrue(engine.launchPipeline("Write about scooters", pipeline, SwarmLaunchOptions()))
        engine.uiState.first { it.agents[2].status == SwarmAgentStatus.WORKING }
        // Pause: the in-flight editor step finishes, then the run halts.
        assertTrue(engine.pause())
        gate.complete(Unit)
        val paused = engine.uiState.first { it.lifecycle == SwarmLifecycle.PAUSED }
        assertNotNull("checkpoint must be saved at pause", store.load())
        assertEquals(
            "finished stages must stay done",
            3,
            paused.agents.count { it.status == SwarmAgentStatus.DONE },
        )

        // Fresh engine (new screen instance) restores the checkpointed run.
        val engine2 = SwarmEngine(provider, store, this)
        val restored = engine2.uiState.value
        assertEquals("pipeline:draft-polish", restored.crew?.id)
        assertEquals(SwarmLifecycle.PAUSED, restored.lifecycle)
        assertTrue(restored.canResume)

        assertTrue(engine2.resume())
        val done = engine2.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals("Stitched document", done.stitchedResult)
        // Still no manager decomposition on the resumed run, and completed
        // stages were not re-run: exactly 4 stage calls total.
        assertEquals(0, provider.callsStartingWith("MISSION DECOMPOSITION"))
        assertEquals(4, provider.callsStartingWith("PIPELINE STAGE"))
    }

    @Test
    fun `normal crew runs are unaffected by pipeline mode`() = runTest {
        val provider = FakeSwarmProvider(pipelineHandler())
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)

        assertTrue(engine.launch("Research scooters", SwarmRoles.research))
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }

        assertEquals(1, provider.callsStartingWith("MISSION DECOMPOSITION"))
        assertEquals(0, provider.callsStartingWith("PIPELINE STAGE"))
        assertTrue(provider.callsStartingWith("WORKER SUBTASK") > 0)
        assertEquals("research", final.crew?.id)
    }

    @Test
    fun `pipelineForCrewId resolves only pipeline crew ids`() {
        assertEquals(pipeline, pipelineForCrewId("pipeline:draft-polish"))
        assertNull(pipelineForCrewId("research"))
        assertNull(pipelineForCrewId("custom-123"))
        assertNull(pipelineForCrewId("pipeline:no-such-pipeline"))
        assertNull(pipelineForCrewId(null))
        assertNull(pipelineForCrewId(""))
    }

    @Test
    fun `toCrewPreset maps stages to roles with the pipeline id prefix`() {
        val preset = pipeline.toCrewPreset()
        assertEquals("pipeline:draft-polish", preset.id)
        assertEquals("Draft & polish", preset.name)
        assertEquals(
            listOf("researcher", "writer", "editor", "verifier"),
            preset.roles,
        )
    }

    @Test
    fun `all pipelines reference only known roles`() {
        for (p in SWARM_PIPELINES) {
            assertTrue("pipeline ${p.id} must have stages", p.stages.isNotEmpty())
            for (stage in p.stages) {
                assertNotNull(
                    "pipeline ${p.id} references unknown role ${stage.roleId}",
                    SwarmRoles.byId(stage.roleId),
                )
                assertTrue(stage.title.isNotBlank())
                assertTrue(stage.brief.isNotBlank())
            }
            assertTrue(p.missionHint.isNotBlank())
        }
    }

    @Test
    fun `pipelineStagePrompt frames the handoff as primary input`() {
        val role = SwarmRoles.byId(SwarmRoles.WRITER)!!
        val prompt = SwarmEngine.pipelineStagePrompt(
            stageIndex = 1,
            stageCount = 4,
            stageTitle = "Draft",
            role = role,
            mission = "Write about scooters",
            brief = "Turn the research into a draft.",
            previousOutput = "Fact 1. Fact 2.",
            steering = "",
            docs = "",
        )
        assertTrue(prompt.startsWith("PIPELINE STAGE 2 of 4 — Draft"))
        assertTrue(prompt.contains("Role: Writer (writer)"))
        assertTrue(prompt.contains("Your stage brief: Turn the research into a draft."))
        assertTrue(prompt.contains("OUTPUT OF THE PREVIOUS STAGE"))
        assertTrue(prompt.contains("Fact 1. Fact 2."))
    }
}
