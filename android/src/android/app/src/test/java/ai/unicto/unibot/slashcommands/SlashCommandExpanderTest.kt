package ai.unicto.unibot.slashcommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 94 — custom slash command expansion.
 */
class SlashCommandExpanderTest {

    private val commands = listOf(
        CustomSlashCommand(trigger = "brief", description = "Morning brief", template = "Summarize {args}. Today is {date}."),
        CustomSlashCommand(trigger = "off", template = "x", enabled = false),
    )

    @Test
    fun `expands trigger with args`() {
        val expanded = SlashCommandExpander.expand("/brief my project", commands)!!
        assertEquals("brief", expanded.command.trigger)
        assertTrue(expanded.prompt.startsWith("Summarize my project. Today is 20"))
    }

    @Test
    fun `expands bare trigger with empty args`() {
        val expanded = SlashCommandExpander.expand("/brief", commands)!!
        assertTrue(expanded.prompt.contains("Summarize ."))
    }

    @Test
    fun `non-command text passes through as null`() {
        assertNull(SlashCommandExpander.expand("hello world", commands))
        assertNull(SlashCommandExpander.expand("/unknown thing", commands))
    }

    @Test
    fun `disabled commands do not expand`() {
        assertNull(SlashCommandExpander.expand("/off x", commands))
    }

    @Test
    fun `trigger matching is case-insensitive`() {
        assertNotNull(SlashCommandExpander.expand("/BRIEF x", commands))
    }

    @Test
    fun `leading whitespace before slash still matches`() {
        assertNotNull(SlashCommandExpander.expand("   /brief x", commands))
    }

    @Test
    fun `mid-text slash is not a command`() {
        assertNull(SlashCommandExpander.expand("see /brief for details", commands))
    }

    @Test
    fun `trigger sanitization`() {
        assertEquals("my-trigger", CustomSlashCommand.sanitizeTrigger("My Trigger!"))
        assertEquals("a-b-c", CustomSlashCommand.sanitizeTrigger("a b  c"))
        assertEquals("", CustomSlashCommand.sanitizeTrigger("!!!"))
    }

    @Test
    fun `renderTemplate substitutes date and time`() {
        val rendered = SlashCommandExpander.renderTemplate("{date} {time} {args}", "hi")
        assertTrue(rendered.endsWith(" hi"))
        assertTrue(rendered.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} hi")))
    }
}
