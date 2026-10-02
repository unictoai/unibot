package ai.unicto.unibot.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [unibot-connectors] Gmail tools are only exposed when connected.
 */
class GmailToolGatingTest {

    private fun names(
        gmailConnected: Boolean,
        driveConnected: Boolean = false,
        calendarConnected: Boolean = false,
    ): Set<String> = AgentTools.makeAgentTools(
        gmailConnected = gmailConnected,
        driveConnected = driveConnected,
        calendarConnected = calendarConnected,
    ).map { it.name }.toSet()

    @Test
    fun `gmail tools absent when not connected`() {
        val n = names(gmailConnected = false)
        assertFalse(n.contains(GmailTool.SEARCH_NAME))
        assertFalse(n.contains(GmailTool.READ_NAME))
        assertFalse(n.contains(GmailTool.SEND_NAME))
        // Core tools still present.
        assertTrue(n.contains("shell_execute"))
        assertTrue(n.contains("file_read"))
    }

    @Test
    fun `gmail tools present when connected`() {
        val n = names(gmailConnected = true)
        assertTrue(n.contains(GmailTool.SEARCH_NAME))
        assertTrue(n.contains(GmailTool.READ_NAME))
        assertTrue(n.contains(GmailTool.SEND_NAME))
    }

    @Test
    fun `gmail definitions are well formed`() {
        val defs = GmailTool.definitions()
        assertTrue(defs.size == 3)
        val byName = defs.associateBy { it.name }
        assertTrue(byName[GmailTool.SEARCH_NAME]!!.required.contains("query"))
        assertTrue(byName[GmailTool.READ_NAME]!!.required.contains("id"))
        val send = byName[GmailTool.SEND_NAME]!!
        assertTrue(send.required.containsAll(listOf("to", "subject", "body")))
    }

    @Test
    fun `drive and calendar tools gated independently`() {
        val none = names(gmailConnected = false)
        assertFalse(none.contains(DriveTool.SEARCH_NAME))
        assertFalse(none.contains(CalendarTool.LIST_NAME))

        val driveOnly = names(gmailConnected = false, driveConnected = true)
        assertTrue(driveOnly.contains(DriveTool.SEARCH_NAME))
        assertTrue(driveOnly.contains(DriveTool.READ_NAME))
        assertFalse(driveOnly.contains(CalendarTool.LIST_NAME))
        assertFalse(driveOnly.contains(GmailTool.SEARCH_NAME))

        val calOnly = names(gmailConnected = false, calendarConnected = true)
        assertTrue(calOnly.contains(CalendarTool.LIST_NAME))
        assertTrue(calOnly.contains(CalendarTool.CREATE_NAME))
        assertFalse(calOnly.contains(DriveTool.SEARCH_NAME))

        val all = names(gmailConnected = true, driveConnected = true, calendarConnected = true)
        assertTrue(all.contains(GmailTool.SEND_NAME))
        assertTrue(all.contains(DriveTool.READ_NAME))
        assertTrue(all.contains(CalendarTool.CREATE_NAME))
    }
}
