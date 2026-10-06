package ai.unicto.unibot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 80 — tool-call folding (integration: perf worker's
 * ToolRunFoldRow contract wired into the chat renderer).
 *
 * Pins foldToolRuns: only maximal runs of >= 2 CONSECUTIVE settled tool
 * rows for the same message collapse into a ToolRunGroup. Streaming /
 * running / pending blocks, single tool calls, and runs split by other
 * rows or a message boundary never fold.
 */
class ToolRunFoldTest {

    private fun toolUse(
        messageId: String,
        blockId: String,
        status: ToolBlockStatus? = ToolBlockStatus.SUCCESS,
        toolName: String = "file_read",
    ) = FlatChatItem.AssistantToolUse(
        messageId = messageId,
        block = AssistantBlock(
            id = blockId,
            kind = "tool_use",
            toolName = toolName,
            toolStatus = status,
            durationMs = 1000,
        ),
        allToolBlocks = emptyList(),
    )

    private fun textRow(messageId: String) = FlatChatItem.AssistantText(
        messageId = messageId,
        block = AssistantBlock(id = "t-$messageId", kind = "text", content = "hi"),
        isStreaming = false,
        messageMarkdown = "hi",
    )

    @Test
    fun `two settled tool rows for one message fold into a group`() {
        val items = listOf(
            textRow("m1"),
            toolUse("m1", "b1"),
            toolUse("m1", "b2"),
        )
        val folded = foldToolRuns(items)
        assertEquals(2, folded.size)
        assertTrue(folded[0] is FlatChatItem.AssistantText)
        val group = folded[1] as FlatChatItem.ToolRunGroup
        assertEquals("m1", group.messageId)
        assertEquals(listOf("b1", "b2"), group.items.map { it.block.id })
        assertEquals("toolrun:m1", group.key)
    }

    @Test
    fun `single tool row never folds`() {
        val items = listOf(textRow("m1"), toolUse("m1", "b1"))
        val folded = foldToolRuns(items)
        assertEquals(items, folded)
    }

    @Test
    fun `running block blocks the fold`() {
        val items = listOf(
            toolUse("m1", "b1"),
            toolUse("m1", "b2", status = ToolBlockStatus.RUNNING),
        )
        assertEquals(items, foldToolRuns(items))
    }

    @Test
    fun `streaming block blocks the fold`() {
        val items = listOf(
            toolUse("m1", "b1"),
            toolUse("m1", "b2", status = ToolBlockStatus.STREAMING),
        )
        assertEquals(items, foldToolRuns(items))
    }

    @Test
    fun `unknown status never folds`() {
        val items = listOf(
            toolUse("m1", "b1"),
            toolUse("m1", "b2", status = null),
        )
        assertEquals(items, foldToolRuns(items))
    }

    @Test
    fun `failed and cancelled blocks are settled and fold`() {
        val items = listOf(
            toolUse("m1", "b1", status = ToolBlockStatus.FAILED),
            toolUse("m1", "b2", status = ToolBlockStatus.CANCELLED),
            toolUse("m1", "b3", status = ToolBlockStatus.TIMEOUT),
        )
        val folded = foldToolRuns(items)
        assertEquals(1, folded.size)
        assertEquals(3, (folded[0] as FlatChatItem.ToolRunGroup).items.size)
    }

    @Test
    fun `text row between tool rows splits the run`() {
        val items = listOf(
            toolUse("m1", "b1"),
            toolUse("m1", "b2"),
            textRow("m1"),
            toolUse("m1", "b3"),
            toolUse("m1", "b4"),
        )
        val folded = foldToolRuns(items)
        assertEquals(3, folded.size)
        assertEquals(2, (folded[0] as FlatChatItem.ToolRunGroup).items.size)
        assertTrue(folded[1] is FlatChatItem.AssistantText)
        assertEquals(2, (folded[2] as FlatChatItem.ToolRunGroup).items.size)
    }

    @Test
    fun `different messages do not fold together`() {
        val items = listOf(
            toolUse("m1", "b1"),
            toolUse("m2", "b2"),
        )
        assertEquals(items, foldToolRuns(items))
    }

    @Test
    fun `empty and singleton lists pass through`() {
        assertEquals(emptyList<FlatChatItem>(), foldToolRuns(emptyList()))
        val single = listOf(textRow("m1"))
        assertEquals(single, foldToolRuns(single))
    }
}
