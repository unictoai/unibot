package ai.unicto.unibot.skills

import ai.unicto.unibot.data.repository.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 89 — the typed, narrow tool surface for Markdown skills.
 */
class SkillToolAdapterTest {

    private fun skill(
        name: String = "test-skill",
        description: String = "Does a test thing.",
        tools: List<String> = emptyList(),
        enabled: Boolean = true,
    ) = SkillRepository.Skill(
        name = name,
        description = description,
        body = "Follow these instructions.",
        isEnabled = enabled,
        tools = tools,
    )

    @Test
    fun `tool name is sanitized and prefixed`() {
        assertEquals("skill_my_skill", SkillToolAdapter.toolNameFor("My Skill!"))
        assertEquals("skill_a_b", SkillToolAdapter.toolNameFor("a--b"))
    }

    @Test
    fun `allowedTools drops anything outside the allowlist`() {
        val allowed = SkillToolAdapter.allowedTools(listOf("web_search", "shell_exec", "FILE_READ"))
        assertEquals(setOf("web_search", "file_read"), allowed)
    }

    @Test
    fun `isToolCallAllowed gates sub-tool calls`() {
        // The skill's own tool is always allowed.
        assertTrue(SkillToolAdapter.isToolCallAllowed("s", listOf("web_search"), "skill_s"))
        // Declared + allowlisted → allowed.
        assertTrue(SkillToolAdapter.isToolCallAllowed("s", listOf("web_search"), "web_search"))
        // Not declared → denied even though it exists in the app.
        assertFalse(SkillToolAdapter.isToolCallAllowed("s", listOf("web_search"), "file_read"))
        // Declared but not allowlisted → denied.
        assertFalse(SkillToolAdapter.isToolCallAllowed("s", listOf("shell_exec"), "shell_exec"))
    }

    @Test
    fun `toToolDefinition builds a single input tool`() {
        val def = SkillToolAdapter.toToolDefinition(skill(tools = listOf("web_search")))!!
        assertEquals("skill_test_skill", def.name)
        assertTrue(def.description.contains("web_search"))
        assertEquals(listOf("input"), def.required)
        assertNotNull(def.parameters["input"])
    }

    @Test
    fun `toToolDefinition returns null for invalid skills`() {
        // Blank description → fails validation → never surfaced as a tool.
        assertNull(SkillToolAdapter.toToolDefinition(skill(description = "")))
    }

    @Test
    fun `definitionsFor only includes enabled valid skills`() {
        val defs = SkillToolAdapter.definitionsFor(
            listOf(
                skill(name = "ok-one"),
                skill(name = "off", enabled = false),
                skill(name = "bad", description = ""),
            ),
        )
        assertEquals(listOf("skill_ok-one"), defs.map { it.name })
    }
}
