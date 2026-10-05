package ai.unicto.unibot.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the role/preset contract the Swarm screen implements against. */
class SwarmRolesTest {

    @Test
    fun `preset ids are exactly the contracted ones`() {
        assertEquals("research", SwarmRoles.research.id)
        assertEquals("content", SwarmRoles.content.id)
        assertEquals("deepdive", SwarmRoles.deepdive.id)
        assertEquals(listOf("research", "content", "deepdive"), SwarmRoles.presets.map { it.id })
    }

    @Test
    fun `preset names descriptions and role order`() {
        assertEquals("Research", SwarmRoles.research.name)
        assertEquals("Research a topic, verify the findings", SwarmRoles.research.description)
        assertEquals(listOf("researcher", "verifier"), SwarmRoles.research.roles)

        assertEquals("Content", SwarmRoles.content.name)
        assertEquals("Research, write and polish a piece", SwarmRoles.content.description)
        assertEquals(listOf("researcher", "writer", "editor"), SwarmRoles.content.roles)

        assertEquals("Deep dive", SwarmRoles.deepdive.name)
        assertEquals("Plan, research, analyze and verify", SwarmRoles.deepdive.description)
        assertEquals(
            listOf("planner", "researcher", "analyst", "verifier"),
            SwarmRoles.deepdive.roles,
        )
    }

    @Test
    fun `role ids and display names`() {
        val expected = mapOf(
            "planner" to "Planner",
            "researcher" to "Researcher",
            "writer" to "Writer",
            "editor" to "Editor",
            "analyst" to "Analyst",
            "verifier" to "Verifier",
        )
        for ((id, displayName) in expected) {
            val role = SwarmRoles.byId(id)
            assertNotNull("role $id", role)
            assertEquals(displayName, role!!.displayName)
            assertTrue("role $id needs a system prompt", role.systemPrompt.isNotBlank())
        }
        assertNull(SwarmRoles.byId("nope"))
    }

    @Test
    fun `customRoles exposes every known role for a future crew picker`() {
        assertEquals(
            listOf("planner", "researcher", "writer", "editor", "analyst", "verifier"),
            SwarmRoles.customRoles().map { it.id },
        )
    }

    @Test
    fun `presetById resolves all presets`() {
        for (preset in SwarmRoles.presets) {
            assertEquals(preset, SwarmRoles.presetById(preset.id))
        }
        assertNull(SwarmRoles.presetById("nope"))
    }

    @Test
    fun `every preset role resolves to a known role`() {
        for (preset in SwarmRoles.presets) {
            for (roleId in preset.roles) {
                assertNotNull("preset ${preset.id} references unknown role $roleId", SwarmRoles.byId(roleId))
            }
        }
    }
}
