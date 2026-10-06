package ai.unicto.unibot.swarm

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 items 7 + 9 + prefs — custom crew builder persistence, mission
 * template gallery validity, and SwarmPrefs behaviour.
 */
class SwarmCustomCrewTest {

    @Test
    fun `custom crew round-trips through the repository`() {
        val repo = InMemorySwarmRepository()
        val crew = SwarmCrewPreset(
            id = "custom-abc",
            name = "My crew",
            description = "Custom crew",
            roles = listOf("planner", "researcher"),
        )
        repo.saveCustomCrew(crew)
        assertEquals(listOf(crew), repo.listCustomCrews())

        // Saving the same id replaces instead of duplicating.
        repo.saveCustomCrew(crew.copy(name = "Renamed"))
        assertEquals(1, repo.listCustomCrews().size)
        assertEquals("Renamed", repo.listCustomCrews()[0].name)

        repo.deleteCustomCrew("custom-abc")
        assertTrue(repo.listCustomCrews().isEmpty())
    }

    @Test
    fun `custom crew runs through the engine like a built-in preset`() = runTest {
        val provider = FakeSwarmProvider(standardSwarmHandler(listOf("Do research", "Write it")))
        val engine = SwarmEngine(provider, InMemorySwarmCheckpointStore(), this)
        val crew = SwarmCrewPreset(
            id = "custom-abc",
            name = "My crew",
            description = "Custom crew",
            roles = listOf("researcher", "writer"),
        )
        assertTrue(engine.launch("Write about scooters", crew))
        val final = engine.uiState.first { it.lifecycle == SwarmLifecycle.DONE }
        assertEquals(2, final.agents.size)
        assertEquals("My crew", final.crew?.name)
    }

    @Test
    fun `checkpoint restores a custom crew that no longer exists`() {
        // Simulate a checkpoint from a custom preset whose id is not a
        // built-in and whose roles were persisted alongside.
        val data = SwarmCheckpointData(
            lifecycle = SwarmLifecycle.PAUSED.name,
            mission = "mission",
            crewId = "custom-deleted",
            subtasks = listOf("s1"),
            agents = emptyList(),
            crewName = "Deleted crew",
            crewRoles = listOf("researcher"),
        )
        val restored = SwarmCheckpoint.toUiState(data)!!
        assertEquals("custom-deleted", restored.crew?.id)
        assertEquals("Deleted crew", restored.crew?.name)
        assertEquals(listOf("researcher"), restored.crew?.roles)
    }

    @Test
    fun `checkpoint still rejects an unknown crew with no roles`() {
        val data = SwarmCheckpointData(
            lifecycle = SwarmLifecycle.PAUSED.name,
            mission = "mission",
            crewId = "nope",
            subtasks = emptyList(),
            agents = emptyList(),
        )
        assertEquals(null, SwarmCheckpoint.toUiState(data))
    }
}

class SwarmTemplatesTest {

    @Test
    fun `gallery has the four expected templates with valid presets`() {
        assertEquals(4, SWARM_TEMPLATES.size)
        val ids = SWARM_TEMPLATES.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("market-research", "trip-plan", "code-review", "literature-scan")))
        SWARM_TEMPLATES.forEach { template ->
            assertTrue("template ${template.id} has a name", template.name.isNotBlank())
            assertTrue("template ${template.id} has a description", template.description.isNotBlank())
            assertTrue("template ${template.id} has a mission", template.mission.isNotBlank())
            assertFalse(
                "template ${template.id} references a real preset",
                SwarmRoles.presetById(template.presetId) == null,
            )
            assertEquals(template, swarmTemplateById(template.id))
        }
    }
}

class SwarmPrefsTest {

    @Test
    fun `prefs round-trip and maxWorkers coerces`() {
        val repo = InMemorySwarmRepository()
        assertEquals(SwarmPrefs(), repo.loadPrefs()) // defaults

        repo.savePrefs(
            SwarmPrefs(
                maxWorkers = 4,
                requirePlanApproval = false,
                completionNotificationsEnabled = false,
            ),
        )
        val loaded = repo.loadPrefs()
        assertEquals(4, loaded.maxWorkers)
        assertFalse(loaded.requirePlanApproval)
        assertFalse(loaded.completionNotificationsEnabled)

        // withMaxWorkers is the coercing setter the UI uses.
        assertEquals(8, SwarmPrefs().withMaxWorkers(99).maxWorkers)
        assertEquals(1, SwarmPrefs().withMaxWorkers(0).maxWorkers)
        assertEquals(4, SwarmPrefs().withMaxWorkers(4).maxWorkers)
    }

    @Test
    fun `history clear wipes records`() {
        val repo = InMemorySwarmRepository()
        repo.recordHistory(
            SwarmMissionRecord(
                id = "m1", mission = "x", crewId = "research", crewName = "Research",
                crewRoles = listOf("researcher"), outcome = SwarmMissionRecord.OUTCOME_DONE,
                totalTokens = 1, agentCount = 1, finishedAtMillis = 1,
            ),
        )
        assertEquals(1, repo.listHistory().size)
        repo.clearHistory()
        assertTrue(repo.listHistory().isEmpty())
    }
}
