package ai.unicto.unibot.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.4.0 item 80 — tool-call folding summary model.
 *
 * Pins the "Ran N tools · 9s" label contract the chat renderer consumes:
 * pluralization, failure surfacing, duration formatting, and the
 * no-duration omission rule.
 */
class ToolRunSummaryTest {

    @Test
    fun `label for a clean run`() {
        val s = ToolRunFoldSummary(
            listOf(
                ToolCallSummary("file_read", true, 3000),
                ToolCallSummary("shell_execute", true, 6000),
            ),
        )
        assertEquals("Ran 2 tools · 9s", s.label)
    }

    @Test
    fun `singular tool`() {
        val s = ToolRunFoldSummary(listOf(ToolCallSummary("file_read", true, 1500)))
        assertEquals("Ran 1 tool · 1.5s", s.label)
    }

    @Test
    fun `failures surface in the label`() {
        val s = ToolRunFoldSummary(
            listOf(
                ToolCallSummary("file_read", true, 1000),
                ToolCallSummary("shell_execute", false, 2000),
                ToolCallSummary("web_fetch", false, 3000),
            ),
        )
        assertEquals("Ran 3 tools · 2 failed · 6s", s.label)
    }

    @Test
    fun `duration omitted when unknown`() {
        val s = ToolRunFoldSummary(
            listOf(
                ToolCallSummary("file_read", true, 1000),
                ToolCallSummary("shell_execute", true, null),
            ),
        )
        assertEquals("Ran 2 tools", s.label)
        assertNull(s.totalDurationMs)
    }

    @Test
    fun `formatDuration rounds to one decimal`() {
        assertEquals("9s", ToolRunFoldSummary.formatDuration(9000))
        assertEquals("1.5s", ToolRunFoldSummary.formatDuration(1500))
        assertEquals("0.4s", ToolRunFoldSummary.formatDuration(400))
        assertEquals("0s", ToolRunFoldSummary.formatDuration(0))
    }

    @Test
    fun `counts`() {
        val s = ToolRunFoldSummary(
            listOf(
                ToolCallSummary("a", true),
                ToolCallSummary("b", false),
                ToolCallSummary("c", false),
            ),
        )
        assertEquals(3, s.toolCount)
        assertEquals(2, s.failedCount)
    }
}
