package ai.unicto.unibot.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 42 — JVM tests for the MCP client's pure JSON-RPC layer.
 */
class McpClientTest {

    @Test
    fun `buildRequest sets jsonrpc 2_0 with method and params`() {
        val params = JSONObject().put("a", 1)
        val req = McpClient.buildRequest("tools/list", params)
        assertEquals("2.0", req.getString("jsonrpc"))
        assertEquals("tools/list", req.getString("method"))
        assertEquals(1, req.getJSONObject("params").getInt("a"))
        assertTrue(req.has("id"))
    }

    @Test
    fun `buildInitializeParams carries client info`() {
        val p = McpClient.buildInitializeParams()
        assertEquals("2025-06-18", p.getString("protocolVersion"))
        assertEquals("unibot", p.getJSONObject("clientInfo").getString("name"))
    }

    @Test
    fun `buildToolsCallParams wraps name and arguments`() {
        val args = JSONObject().put("path", "/tmp")
        val p = McpClient.buildToolsCallParams("read_file", args)
        assertEquals("read_file", p.getString("name"))
        assertEquals("/tmp", p.getJSONObject("arguments").getString("path"))
    }

    @Test
    fun `parseToolsList extracts tools and skips malformed`() {
        val result = JSONObject().put("tools", JSONArray().apply {
            put(JSONObject().apply {
                put("name", "search")
                put("description", "Search things")
                put("inputSchema", JSONObject().put("type", "object"))
            })
            put(JSONObject().put("description", "no name — skipped"))
        })
        val tools = McpClient.parseToolsList(result)
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
        assertEquals("Search things", tools[0].description)
    }

    @Test
    fun `parseToolResult flattens text blocks`() {
        val result = JSONObject().put("content", JSONArray().apply {
            put(JSONObject().apply { put("type", "text"); put("text", "hello") })
            put(JSONObject().apply { put("type", "text"); put("text", "world") })
        })
        val r = McpClient.parseToolResult(result)
        assertEquals("hello\nworld", r.text)
        assertFalse(r.isError)
    }

    @Test
    fun `parseToolResult honors isError`() {
        val result = JSONObject().apply {
            put("isError", true)
            put("content", JSONArray().put(
                JSONObject().apply { put("type", "text"); put("text", "boom") },
            ))
        }
        val r = McpClient.parseToolResult(result)
        assertTrue(r.isError)
        assertEquals("boom", r.text)
    }

    @Test
    fun `extractSsePayload unwraps data lines`() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1}\n\ndata: [DONE]\n"
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}", McpClient.extractSsePayload(body))
    }

    @Test
    fun `extractSsePayload passes plain json through`() {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
        assertEquals(body, McpClient.extractSsePayload(body))
    }

    @Test(expected = McpClient.CallError.Protocol::class)
    fun `extractResult throws on error envelope`() {
        val envelope = JSONObject().put("error", JSONObject().apply {
            put("code", -32601)
            put("message", "Method not found")
        })
        McpClient.extractResult(envelope)
    }

    @Test
    fun `extractResult returns result object`() {
        val result = JSONObject().put("tools", JSONArray())
        val envelope = JSONObject().put("result", result)
        assertEquals(0, McpClient.extractResult(envelope).getJSONArray("tools").length())
    }
}
