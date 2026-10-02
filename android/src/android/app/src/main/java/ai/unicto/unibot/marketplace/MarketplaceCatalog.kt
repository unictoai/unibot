package ai.unicto.unibot.marketplace

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The agent/skills marketplace catalog (P8).
 *
 * Two sources, both explicit:
 * - **Bundled** (`assets/marketplace/catalog.json`): always available offline,
 *   seeded with the built-in skill(s) and a few example community entries.
 * - **Custom URL** (opt-in): the person pastes a catalog URL and taps Load.
 *   It is fetched exactly then — never automatically, never in the
 *   background — and must be JSON in the bundled catalog's shape.
 *
 * Installing an entry writes it into the existing storage: skills go through
 * [ai.unicto.unibot.data.repository.SkillRepository] (the same path as a
 * manual SKILL.md import), MCP servers through
 * [ai.unicto.unibot.data.repository.MCPRepository].
 */
enum class MarketplaceEntryType { SKILL, MCP }

data class MarketplaceEntry(
    val id: String,
    val type: MarketplaceEntryType,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val tags: List<String>,
    /** True for entries that ship inside the app (e.g. the bundled skill). */
    val bundled: Boolean = false,
    /** Full SKILL.md for [MarketplaceEntryType.SKILL] entries. */
    val skillBody: String? = null,
    /** MCP transport for [MarketplaceEntryType.MCP] entries. */
    val mcpCommand: String? = null,
    val mcpArgs: List<String> = emptyList(),
    val mcpUrl: String? = null,
    val mcpEnv: Map<String, String> = emptyMap(),
    /** Where the entry came from, for display and re-install. */
    val sourceUrl: String? = null,
)

object MarketplaceCatalog {
    private const val TAG = "Marketplace"
    private const val ASSET_PATH = "marketplace/catalog.json"
    private const val PREFS = "unibot_marketplace"
    private const val KEY_CUSTOM_URL = "custom_catalog_url"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** The bundled catalog — offline, always available. */
    fun loadBundled(context: Context): List<MarketplaceEntry> {
        return runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .let { parseCatalog(it, sourceUrl = null) }
        }.onFailure {
            AppLogger.info(TAG, "bundled catalog failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * Fetch a custom catalog. Called ONLY from the explicit "Load catalog"
     * tap in the marketplace UI — never on screen open, never in background.
     */
    suspend fun loadRemote(url: String): List<MarketplaceEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url.trim()).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) throw IllegalStateException("Empty catalog")
            parseCatalog(text, sourceUrl = url.trim())
        }
    }

    fun customCatalogUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_URL, null)?.takeIf { it.isNotBlank() }

    fun setCustomCatalogUrl(context: Context, url: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (url.isNullOrBlank()) remove(KEY_CUSTOM_URL) else putString(KEY_CUSTOM_URL, url.trim())
        }.apply()
    }

    // -- parsing -------------------------------------------------------------------------------

    fun parseCatalog(text: String, sourceUrl: String?): List<MarketplaceEntry> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            parseEntry(arr.optJSONObject(i) ?: return@mapNotNull null, sourceUrl)
        }
    }

    private fun parseEntry(obj: JSONObject, sourceUrl: String?): MarketplaceEntry? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val type = when (obj.optString("type").lowercase()) {
            "skill" -> MarketplaceEntryType.SKILL
            "mcp" -> MarketplaceEntryType.MCP
            else -> return null
        }
        val mcp = obj.optJSONObject("mcp")
        return MarketplaceEntry(
            id = id,
            type = type,
            name = obj.optString("name").ifBlank { id },
            description = obj.optString("description"),
            version = obj.optString("version").ifBlank { "1.0.0" },
            author = obj.optString("author").ifBlank { "community" },
            tags = jsonStrings(obj.optJSONArray("tags")),
            bundled = obj.optBoolean("bundled", false),
            skillBody = obj.optString("skill_body").takeIf { it.isNotBlank() },
            mcpCommand = mcp?.optString("command")?.takeIf { it.isNotBlank() },
            mcpArgs = mcp?.let { jsonStrings(it.optJSONArray("args")) } ?: emptyList(),
            mcpUrl = mcp?.optString("url")?.takeIf { it.isNotBlank() },
            mcpEnv = mcp?.optJSONObject("env")?.let { env ->
                env.keys().asSequence().associateWith { env.optString(it) }
            } ?: emptyMap(),
            sourceUrl = obj.optString("source_url").takeIf { it.isNotBlank() } ?: sourceUrl,
        )
    }

    private fun jsonStrings(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }
}
