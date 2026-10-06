package ai.unicto.unibot.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 89 — the Markdown skill schema: parse is lenient, validate is strict.
 */
class SkillSchemaTest {

    private fun doc(
        name: String = "test-skill",
        description: String = "Does a test thing.",
        version: String = "1.0.0",
        tools: String? = null,
        body: String = "Do the thing.",
    ): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        appendLine("version: $version")
        if (tools != null) appendLine("tools: $tools")
        appendLine("---")
        append(body)
    }

    @Test
    fun `parse reads name description version and body`() {
        val parsed = SkillSchema.parse(doc())!!
        assertEquals("test-skill", parsed.name)
        assertEquals("Does a test thing.", parsed.description)
        assertEquals("1.0.0", parsed.version)
        assertEquals("Do the thing.", parsed.body)
        assertTrue(parsed.tools.isEmpty())
    }

    @Test
    fun `parse handles inline comma tools list`() {
        val parsed = SkillSchema.parse(doc(tools = "web_search, file_read"))!!
        assertEquals(listOf("web_search", "file_read"), parsed.tools)
    }

    @Test
    fun `parse handles yaml list tools`() {
        val content = """
            ---
            name: list-skill
            description: A skill.
            tools:
              - web_search
              - datetime
            ---
            Body here.
        """.trimIndent()
        val parsed = SkillSchema.parse(content)!!
        assertEquals(listOf("web_search", "datetime"), parsed.tools)
        assertEquals("Body here.", parsed.body)
    }

    @Test
    fun `parse returns null without frontmatter`() {
        assertNull(SkillSchema.parse("just some markdown, no frontmatter"))
    }

    @Test
    fun `parse returns null when name is blank`() {
        assertNull(SkillSchema.parse(doc(name = "")))
    }

    @Test
    fun `validate accepts a clean skill`() {
        val (parsed, validation) = SkillSchema.parseAndValidate(doc())!!
        assertNotNull(parsed)
        assertTrue(validation.valid)
        assertTrue(validation.errors.isEmpty())
    }

    @Test
    fun `validate rejects bad names`() {
        for (bad in listOf("A", "UPPER", "has space", "has!bang", "x")) {
            val parsed = SkillSchema.parse(doc(name = bad))!!
            val v = SkillSchema.validate(parsed)
            assertFalse("name $bad should be invalid", v.valid)
        }
    }

    @Test
    fun `validate requires a description`() {
        val parsed = SkillSchema.parse(doc(description = ""))!!
        assertFalse(SkillSchema.validate(parsed).valid)
    }

    @Test
    fun `validate rejects unknown tools`() {
        val parsed = SkillSchema.parse(doc(tools = "web_search, shell_exec, evil_tool"))!!
        val v = SkillSchema.validate(parsed)
        assertFalse(v.valid)
        assertTrue(v.errors.any { it.contains("shell_exec") && it.contains("evil_tool") })
        assertTrue(v.errors.none { it.contains("web_search") })
    }

    @Test
    fun `validate warns on empty body but stays valid`() {
        val parsed = SkillSchema.parse(doc(body = ""))!!
        val v = SkillSchema.validate(parsed)
        assertTrue(v.valid)
        assertTrue(v.warnings.any { it.contains("empty") })
    }

    @Test
    fun `validate warns on unknown frontmatter keys`() {
        val content = doc() .let {
            it.replace("version: 1.0.0", "version: 1.0.0\nmystery: 42")
        }
        val (parsed, v) = SkillSchema.parseAndValidate(content)!!
        assertTrue(v.valid)
        assertTrue(parsed.unknownKeys.contains("mystery"))
        assertTrue(v.warnings.any { it.contains("mystery") })
    }

    @Test
    fun `validate rejects overlong description`() {
        val parsed = SkillSchema.parse(doc(description = "x".repeat(281)))!!
        assertFalse(SkillSchema.validate(parsed).valid)
    }
}
