package ai.unicto.unibot.mcp

import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP client (item 42) — Streamable HTTP transport (MCP 2025-06-18,
 * superseding the legacy SSE transport). Connects to user-configured
 * server URLs, runs the initialize handshake, lists tools, and calls
 * them — the results are exposed to the agent loop as namespaced tools
 * via [ai.unicto.unibot.tools.McpToolBridge].
 *
 * JSON-RPC message building ([buildRequest]) and response parsing
 * ([parseToolsList], [parseToolResult], [extractSsePayload]) are pure and
 * unit-tested; only [McpTransport] touches the network.
 *
 * Auth: servers carry their own headers (e.g. Authorization) in
 * [MCPServerConfig.headers]. A 401/403 response surfaces as a
 * [CallError.Unauthorized] so the caller can route the user to the
 * existing MCP OAuth flow ([ai.unicto.unibot.mcp.oauth.MCPOAuthController]).
 *
 * Privacy: only the tool name + arguments the model chose are sent, to
 * the server URL the user configured. Nothing else leaves the phone.
 */
object McpClient {

    private const val TAG = "McpClient"

    /** Protocol versions we speak, newest first. */
    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val FALLBACK_PROTOCOL_VERSION = "2025-03-26"

    const val SESSION_HEADER = "mcp-session-id"

    private val idSeq = AtomicLong(1)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    data class McpToolInfo(
        val name: String,
        val description: String,
        val inputSchema: JSONObject,
    )

    data class ToolCallResult(
        val text: String,
        val isError: Boolean,
    )

    sealed class CallError(message: String) : Exception(message) {
        class Unauthorized(msg: String) : CallError(msg)
        class Protocol(msg: String) : CallError(msg)
        class Transport(msg: String) : CallError(msg)
    }

    // -- Pure JSON-RPC builders (unit-tested) ---------------------------------

    fun buildRequest(method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", idSeq.getAndIncrement())
            put("method", method)
            if (params != null) put("params", params)
        }

    fun buildInitializeParams(): JSONObject = JSONObject().apply {
        put("protocolVersion", PROTOCOL_VERSION)
        put(
            "capabilities", JSONObject().apply {
                put("tools", JSONObject())
            },
        )
        put(
            "clientInfo", JSONObject().apply {
                put("name", "unibot")
                put("version", "1.4.0")
            },
        )
    }

    fun buildToolsCallParams(name: String, arguments: JSONObject): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }

    /** Parse a tools/list result object → tool infos. Skips malformed entries. */
    fun parseToolsList(result: JSONObject): List<McpToolInfo> {
        val arr = result.optJSONArray("tools") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = t.optString("name", "")
            if (name.isBlank()) return@mapNotNull null
            McpToolInfo(
                name = name,
                description = t.optString("description", ""),
                inputSchema = t.optJSONObject("inputSchema") ?: JSONObject(),
            )
        }
    }

    /** Parse a tools/call result object → flattened text. */
    fun parseToolResult(result: JSONObject): ToolCallResult {
        val isError = result.optBoolean("isError", false)
        val content = result.optJSONArray("content")
        val text = if (content != null) {
            (0 until content.length()).mapNotNull { i ->
                val block = content.optJSONObject(i) ?: return@mapNotNull null
                when (block.optString("type", "")) {
                    "text" -> block.optString("text", "")
                    "resource" -> {
                        val r = block.optJSONObject("resource")
                        r?.optString("text", "")?.takeIf { it.isNotBlank() }
                            ?: r?.optString("uri", "")
                    }
                    else -> "[${block.optString("type", "unknown")} content omitted]"
                }
            }.filter { it.isNotBlank() }.joinToString("\n")
        } else {
            result.toString(2)
        }
        return ToolCallResult(text.ifBlank { "(empty result)" }, isError)
    }

    /**
     * Unwrap a Streamable-HTTP SSE response body: concatenate `data:` lines
     * (ignoring comments / event fields); a plain JSON body passes through.
     */
    fun extractSsePayload(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith(":" ) && !trimmed.startsWith("event:") && !trimmed.startsWith("data:")) {
            return trimmed
        }
        return trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .joinToString("\n")
    }

    /** Pull the JSON-RPC result / error out of a response envelope. */
    fun extractResult(envelope: JSONObject): JSONObject {
        envelope.optJSONObject("error")?.let { err ->
            throw CallError.Protocol(
                "MCP error ${err.optInt("code", -1)}: ${err.optString("message", "unknown")}",
            )
        }
        return envelope.optJSONObject("result")
            ?: throw CallError.Protocol("MCP response had no result: ${envelope.toString().take(300)}")
    }

    // -- Transport --------------------------------------------------------------

    /**
     * Run initialize + tools/list against [url]. Returns the tools and the
     * session id (null when the server is stateless).
     */
    suspend fun listTools(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<List<McpToolInfo>, String?> = withContext(Dispatchers.IO) {
        val session = Handshake(url, headers).initialize()
        val tools = rpc(url, headers, session, buildRequest("tools/list"))
        parseToolsList(extractResult(tools)) to session
    }

    /** Call one tool. [sessionId] from [listTools] (may be null). */
    suspend fun callTool(
        url: String,
        headers: Map<String, String> = emptyMap(),
        sessionId: String?,
        toolName: String,
        arguments: JSONObject,
    ): ToolCallResult = withContext(Dispatchers.IO) {
        val envelope = rpc(
            url, headers, sessionId,
            buildRequest("tools/call", buildToolsCallParams(toolName, arguments)),
        )
        parseToolResult(extractResult(envelope))
    }

    private inner class Handshake(
        private val url: String,
        private val headers: Map<String, String>,
    ) {
        /** Full initialize handshake; returns the session id (may be null). */
        suspend fun initialize(): String? {
            // Try the current protocol version, then the previous one.
            val versions = listOf(PROTOCOL_VERSION, FALLBACK_PROTOCOL_VERSION)
            var lastError: Exception? = null
            for (version in versions) {
                try {
                    val params = buildInitializeParams()
                    params.put("protocolVersion", version)
                    val (envelope, sessionId) = postRpc(url, headers, null, buildRequest("initialize", params))
                    val result = extractResult(envelope)
                    val negotiated = result.optString("protocolVersion", version)
                    AppLogger.info(TAG, "[initialize] negotiated=$negotiated session=${sessionId != null}")
                    // Mandatory initialized notification (no id).
                    val notif = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/initialized")
                    }
                    runCatching { postRpc(url, headers, sessionId, notif) }
                    return sessionId
                } catch (e: CallError.Protocol) {
                    lastError = e
                    AppLogger.warning(TAG, "[initialize] $version failed: ${e.message}")
                }
            }
            throw lastError ?: CallError.Protocol("MCP initialize failed")
        }
    }

    private suspend fun rpc(
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        request: JSONObject,
    ): JSONObject = postRpc(url, headers, sessionId, request).first

    /**
     * One JSON-RPC POST. Returns (envelope, sessionId). Throws [CallError].
     * Never logs headers or bodies.
     */
    private fun postRpc(
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        request: JSONObject,
    ): Pair<JSONObject, String?> {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        if (sessionId != null) builder.header(SESSION_HEADER, sessionId)
        for ((k, v) in headers) {
            if (v.isNotBlank()) builder.header(k, v)
        }
        val call = http.newCall(
            builder.post(request.toString().toRequestBody("application/json".toMediaType())).build(),
        )
        return try {
            call.execute().use { resp ->
                val newSession = resp.header(SESSION_HEADER)
                val code = resp.code
                if (code == 401 || code == 403) {
                    throw CallError.Unauthorized("MCP server rejected the request ($code) — re-authorize in Settings → MCP.")
                }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw CallError.Transport("MCP HTTP $code")
                }
                if (raw.isBlank()) {
                    // Notification-style empty 202 — synthesize an empty result.
                    return JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("result", JSONObject())
                    } to (newSession ?: sessionId)
                }
                val payload = extractSsePayload(raw)
                val envelope = runCatching { JSONObject(payload) }.getOrElse {
                    throw CallError.Protocol("MCP returned non-JSON")
                }
                envelope to (newSession ?: sessionId)
            }
        } catch (e: CallError) {
            throw e
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[rpc] ${t.message}")
            throw CallError.Transport("MCP request failed: ${t.message}")
        }
    }
}
