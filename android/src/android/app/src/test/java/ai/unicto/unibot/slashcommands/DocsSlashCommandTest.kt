package ai.unicto.unibot.slashcommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Built-in `/docs` ("Ask my documents") slash command.
 */
class DocsSlashCommandTest {

    @Test
    fun `default command has the docs trigger and description`() {
        val cmd = DocsSlashCommand.defaultCommand()
        assertEquals("docs", cmd.trigger)
        assertEquals(DocsSlashCommand.BUILTIN_ID, cmd.id)
        assertTrue(cmd.enabled)
        assertTrue(cmd.description.isNotBlank())
    }

    @Test
    fun `template carries args date and time placeholders`() {
        val template = DocsSlashCommand.TEMPLATE
        assertTrue(template.contains("{args}"))
        assertTrue(template.contains("file:line"))
    }

    @Test
    fun `docs expands through the normal expander path`() {
        val commands = listOf(DocsSlashCommand.defaultCommand())
        val expanded = SlashCommandExpander.expand("/docs my meeting notes", commands)
        assertNotNull(expanded)
        assertTrue(expanded!!.prompt.contains("my meeting notes"))
        assertTrue(expanded.prompt.contains("file:line"))
        // No raw placeholders leak into the sent prompt.
        assertTrue(!expanded.prompt.contains("{args}"))
    }

    @Test
    fun `bare docs trigger expands with empty args`() {
        val commands = listOf(DocsSlashCommand.defaultCommand())
        val expanded = SlashCommandExpander.expand("/docs", commands)
        assertNotNull(expanded)
        assertTrue(!expanded!!.prompt.contains("{args}"))
    }
}
