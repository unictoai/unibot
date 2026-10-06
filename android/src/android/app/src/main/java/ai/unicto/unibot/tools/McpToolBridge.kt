package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import ai.unicto.unibot.data.repository.MCPRepository
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.mcp.McpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * MCP client tool bridge (item 42): plugs user-configured MCP servers in
 * as agent tools.
 *
 * Each enabled HTTP/HTTPS server's tools are exposed under a namespaced
 * name: `mcp_<server>_<tool>` (server id sanitized to [a-z0-9_]). STDIO
 * servers are skipped — they need a local process runner, which the
 * in-guest `unibot-mcp-cli` daemon owns, not the Android client.
 *
 * Tool lists are cached per server ([CACHE_TTL_MS]) so every agent turn
 * doesn't re-run the initialize handshake; sessions are cached alongside
 * so tools/call reuses the negotiated session id. Failures list as zero
 * tools (the server row in Settings → MCP still shows the error) — a
 * dead MCP server must never break the whole agent loop.
 *
 * Pure name-mapping helpers ([toolNameFor], [resolveTool]) are
 * unit-tested.
 */
object McpToolBridge {

    private const val TAG = "McpToolBridge"
    const val PREFIX = "mcp_"

    /** Cache TTL for a server's tool list. */
    const val CACHE_TTL_MS = 5 * 60 * 1000L

    private data class CachedServer(
        val tools: List<McpClient.McpToolInfo>,
        val sessionId: String?,
        val fetchedAt: Long,
    )

    private val cache = mutableMapOf<String, CachedServer>()
    private val cacheMutex = Mutex()

    /** Namespaced agent-tool name for [serverId]/[toolName]. */
    fun toolNameFor(serverId: String, toolName: String): String {
        val s = serverId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifEmpty { "server" }.take(32)
        val t = toolName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifEmpty { "tool" }.take(48)
        return "$PREFIX${s}_$t"
    }

    /** Split a namespaced name back into (serverId-sanitized, toolName-sanitized). */
    fun parseNamespacedName(namespaced: String): Pair<String, String>? {
        if (!namespaced.startsWith(PREFIX)) return null
        val rest = namespaced.removePrefix(PREFIX)
        // server part is at most 32 chars; find the split by re-matching
        // against cached servers at execute time instead — here we return
        // the whole rest and let the caller resolve.
        return rest to ""
    }

    /**
     * Resolve a namespaced tool name against eligible servers:
     * longest server-key match first so "a" doesn't shadow "ab".
     * Returns (server, tool-suffix) — the tool itself is matched against
     * the cached tool list by the caller.
     */
    fun resolveTool(
        namespaced: String,
        servers: List<MCPRepository.MCPServerConfig>,
    ): Pair<MCPRepository.MCPServerConfig, String>? {
        if (!namespaced.startsWith(PREFIX)) return null
        val rest = namespaced.removePrefix(PREFIX)
        return servers.mapNotNull { s ->
            val key = sanitizeServerKey(s.id)
            if (rest.startsWith(key + "_")) s to key else null
        }.maxByOrNull { it.second.length }
            ?.let { (server, key) -> server to rest.removePrefix(key + "_") }
    }

    private fun sanitizeServerKey(serverId: String): String =
        serverId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            .ifEmpty { "server" }.take(32)

    /** Static headers plus the OAuth bearer token when the server has one. */
    private fun authHeaders(
        context: Context,
        server: MCPRepository.MCPServerConfig,
    ): Map<String, String> {
        val headers = server.headers.toMutableMap()
        if (!headers.containsKey("Authorization")) {
            ai.unicto.unibot.mcp.oauth.MCPOAuthStore.tokens(context, server.id)
                ?.accessToken?.takeIf { it.isNotBlank() }?.let {
                    headers["Authorization"] = "Bearer $it"
                }
        }
        return headers
    }

    /** Enabled URL-based (non-stdio) servers. */
    fun eligibleServers(repo: MCPRepository): List<MCPRepository.MCPServerConfig> =
        repo.servers.value.filter { s ->
            s.enabled && !s.isStdio && !s.url.isNullOrBlank() &&
                (s.url.startsWith("http://") || s.url.startsWith("https://"))
        }

    /**
     * Synchronous snapshot of agent tool definitions, built from cached
     * server tool lists only — no I/O. [ChatViewModel.agentTools] reads
     * this on every turn (its contract is "no I/O"); [refresh] populates
     * the cache in the background.
     */
    fun snapshot(): List<AgentToolDefinition> {
        val out = mutableListOf<AgentToolDefinition>()
        // lastServers is empty until the first refresh() ran.
        for ((_, cached) in lastServers) {
            val server = cached.first
            for (tool in cached.second.tools) {
                out.add(toDefinition(server, tool))
            }
        }
        return out
    }

    private val lastServers =
        java.util.concurrent.ConcurrentHashMap<String, Pair<MCPRepository.MCPServerConfig, CachedServer>>()

    /**
     * Refresh cached tool lists for all eligible servers. Suspends on
     * network I/O — call from a background coroutine, never from the
     * agent-turn path.
     */
    suspend fun refresh(context: Context, repo: MCPRepository) {
        for (server in eligibleServers(repo)) {
            val cached = cachedTools(context, server) ?: continue
            lastServers[server.id] = server to cached
        }
        // Drop servers that are no longer eligible.
        val eligibleIds = eligibleServers(repo).map { it.id }.toSet()
        lastServers.keys.retainAll(eligibleIds)
    }

    /**
     * Agent tool definitions for all eligible servers. Never throws —
     * per-server failures yield zero tools for that server.
     *
     * Prefer [snapshot] + [refresh] on the hot path; this convenience
     * wrapper refreshes first and is only for cold callers.
     */
    suspend fun definitions(
        context: Context,
        repo: MCPRepository,
    ): List<AgentToolDefinition> {
        refresh(context, repo)
        return snapshot()
    }

    private suspend fun cachedTools(
        context: Context,
        server: MCPRepository.MCPServerConfig,
    ): CachedServer? {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            cache[server.id]?.let { c ->
                if (now - c.fetchedAt < CACHE_TTL_MS) return c
            }
        }
        val fresh = runCatching {
            val (tools, sessionId) = McpClient.listTools(server.url!!, authHeaders(context, server))
            CachedServer(tools, sessionId, now)
        }.onFailure {
            AppLogger.warning(TAG, "[${server.id}] tools/list failed: ${it.message}")
        }.getOrNull() ?: return null
        cacheMutex.withLock { cache[server.id] = fresh }
        return fresh
    }

    private fun toDefinition(
        server: MCPRepository.MCPServerConfig,
        tool: McpClient.McpToolInfo,
    ): AgentToolDefinition {
        val params = mutableMapOf<String, AgentToolParam>(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user.",
            ),
        )
        // Mirror the MCP inputSchema properties (shallow: name → type).
        val schemaProps = tool.inputSchema.optJSONObject("properties")
        val required = mutableListOf("tool_title")
        if (schemaProps != null) {
            val reqArr = tool.inputSchema.optJSONArray("required")
            val reqSet = (0 until (reqArr?.length() ?: 0))
                .mapNotNull { reqArr?.optString(it) }.toSet()
            val keys = schemaProps.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "tool_title") continue
                val prop = schemaProps.optJSONObject(k) ?: continue
                params[k] = AgentToolParam(
                    prop.optString("type", "string").ifBlank { "string" },
                    prop.optString("description", ""),
                )
                if (k in reqSet) required.add(k)
            }
        }
        val desc = buildString {
            append("[MCP: ${server.id}] ")
            append(tool.description.ifBlank { tool.name })
        }
        return AgentToolDefinition(
            name = toolNameFor(server.id, tool.name),
            description = desc,
            parameters = params,
            required = required,
            propertyOrdering = listOf("tool_title") + params.keys.filter { it != "tool_title" },
        )
    }

    /**
     * Execute a namespaced MCP tool. Resolves the server by matching the
     * sanitized id prefix, then the tool by sanitized name.
     */
    suspend fun execute(
        namespacedName: String,
        argsJson: String,
        context: Context,
        repo: MCPRepository,
    ): ToolExecutionResult {
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", "") }
            .getOrDefault("").ifBlank { namespacedName }
        val resolved = resolveTool(namespacedName, eligibleServers(repo))
        if (resolved == null) {
            return ToolExecutionResult("Error: unknown MCP tool '$namespacedName'", false, toolTitle = toolTitle)
        }
        val (server, toolSuffix) = resolved
        val cached = cachedTools(context, server)
            ?: return ToolExecutionResult(
                "Error: MCP server '${server.id}' is unreachable", false, toolTitle = toolTitle,
            )
        val tool = cached.tools.firstOrNull {
            it.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                .ifEmpty { "tool" }.take(48) == toolSuffix
        } ?: return ToolExecutionResult(
            "Error: MCP tool '$toolSuffix' not found on server '${server.id}'",
            false, toolTitle = toolTitle,
        )
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        args.remove("tool_title")
        return try {
            val result = McpClient.callTool(
                server.url!!, authHeaders(context, server), cached.sessionId, tool.name, args,
            )
            ToolExecutionResult(
                result.text,
                !result.isError,
                toolTitle = toolTitle,
            )
        } catch (e: McpClient.CallError.Unauthorized) {
            ToolExecutionResult(
                "Error: ${e.message}", false, toolTitle = toolTitle,
            )
        } catch (e: McpClient.CallError) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    /** Drop cached tool lists (server edited / toggled). */
    suspend fun invalidate(serverId: String? = null) {
        cacheMutex.withLock {
            if (serverId == null) cache.clear() else cache.remove(serverId)
        }
        if (serverId == null) lastServers.clear() else lastServers.remove(serverId)
    }
}
