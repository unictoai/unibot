package ai.unicto.unibot.tools

import ai.unicto.unibot.data.repository.MCPRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 42 — tool-name namespacing must be stable, reversible, and
 * injective: the bridge maps model-visible `mcp_<server>_<tool>` names
 * back to (serverId, toolName) pairs.
 */
class McpToolBridgeTest {

    @Test
    fun `toolNameFor prefixes and sanitizes`() {
        assertEquals("mcp_notes_read_file", McpToolBridge.toolNameFor("notes", "read_file"))
        // Uppercase, spaces, punctuation are folded to lowercase snake.
        assertEquals(
            "mcp_my_server_my_tool_2",
            McpToolBridge.toolNameFor("My Server!", "My Tool 2"),
        )
    }

    @Test
    fun `toolNameFor is injective across different servers`() {
        val a = McpToolBridge.toolNameFor("alpha", "read")
        val b = McpToolBridge.toolNameFor("beta", "read")
        assertTrue(a != b)
    }

    @Test
    fun `resolveTool finds server and tool suffix`() {
        val servers = listOf(
            MCPRepository.MCPServerConfig(id = "obsidian", url = "https://x"),
            MCPRepository.MCPServerConfig(id = "a", url = "https://y"),
            MCPRepository.MCPServerConfig(id = "ab", url = "https://z"),
        )
        val (server, suffix) = McpToolBridge.resolveTool(
            McpToolBridge.toolNameFor("obsidian", "append_note"), servers,
        )!!
        assertEquals("obsidian", server.id)
        assertEquals("append_note", suffix)
    }

    @Test
    fun `resolveTool prefers longest server key`() {
        val servers = listOf(
            MCPRepository.MCPServerConfig(id = "a", url = "https://y"),
            MCPRepository.MCPServerConfig(id = "ab", url = "https://z"),
        )
        val (server, suffix) = McpToolBridge.resolveTool("mcp_ab_read", servers)!!
        assertEquals("ab", server.id)
        assertEquals("read", suffix)
    }

    @Test
    fun `resolveTool rejects non-prefixed and unknown names`() {
        val servers = listOf(MCPRepository.MCPServerConfig(id = "obsidian", url = "https://x"))
        assertEquals(null, McpToolBridge.resolveTool("gmail_search", servers))
        assertEquals(null, McpToolBridge.resolveTool("mcp_nosuchserver_read", servers))
    }
}
