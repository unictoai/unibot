package ai.unicto.unibot.privacy

import android.content.Context
import android.util.Log
import ai.unicto.unibot.UnibotApp
import ai.unicto.unibot.data.model.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import java.io.IOException

/**
 * Wave 5 (v1.0) — privacy core. Two jobs, deliberately small and honest:
 *
 * 1. **Traffic log** — a ring buffer of the last 100 outbound hosts, kept
 *    IN MEMORY ONLY. It records the destination host + a coarse category;
 *    never the URL path, query, headers, or body. The log is the raw material
 *    for Settings → Privacy → Network traffic log, and it dies with the
 *    process.
 *
 * 2. **Local-only gate** — when [PrivacyPrefs.localOnly] is on, every
 *    request through a WIRED client (the AI providers, web search, and the
 *    update checker) whose host is not in the allowlist (AI-provider hosts
 *    only) is refused with an IOException. 127.0.0.1/localhost always pass
 *    because the OAuth login dance needs the loopback redirect. Connector
 *    API clients are not wired yet, so connector traffic is not gated —
 *    the UI says exactly that. This is best-effort client-side gating of
 *    this app's own HTTP clients — it does not claim to stop the OS, other
 *    apps, or raw sockets.
 *
 * Wire the interceptors into each OkHttp client via [interceptors]; the
 * DuckDuckGo search client (HttpURLConnection, not OkHttp) calls [record]
 * and [checkAllowed] manually.
 */
object PrivacyNetworkGate {

    private const val TAG = "PrivacyNetworkGate"

    /** Coarse destination category for a logged host. */
    enum class Category {
        LLM, OAUTH, UPDATE, WEB_SEARCH, CONNECTOR, OTHER
    }

    /**
     * One outbound connection: the destination host, its [Category], when it
     * happened (epoch millis), and — [v12-D] — which connector it belongs to,
     * when the host maps to one ([connectorFor]). Null for AI providers,
     * update checks, and anything unattributed. No path, query, headers,
     * or body.
     */
    data class TrafficEntry(
        val host: String,
        val category: Category,
        val timestampMs: Long,
        val connector: String? = null,
    )

    /** Last 100 entries, newest last. In-memory only — never written to disk. */
    private val _log = MutableStateFlow<List<TrafficEntry>>(emptyList())
    val log: StateFlow<List<TrafficEntry>> = _log.asStateFlow()

    private const val MAX_ENTRIES = 100

    /**
     * Master switch, mirrored from [PrivacyPrefs.setLocalOnly] (and seeded
     * on cold start). Read on every request — toggling in Settings takes
     * effect without a restart.
     */
    @Volatile
    var localOnlyEnabled: Boolean = false

    /** Hosts that may connect while local-only mode is on. Guarded by [gateLock]. */
    private val gateLock = Any()
    private val allowlist: MutableSet<String> = mutableSetOf()

    // -- classification -------------------------------------------------------

    /**
     * Host-substring rules, most specific first. Unknown hosts are [Category.OTHER] —
     * the category is a UI label, not a security decision.
     */
    fun classify(host: String): Category {
        val h = host.lowercase()
        return when {
            h.contains("accounts.google.com") || h.contains("oauth2.googleapis.com") -> Category.OAUTH
            h.contains("duckduckgo.com") -> Category.WEB_SEARCH
            h == "api.github.com" || h == "github.com" || h.endsWith(".github.com") -> Category.UPDATE
            h.contains("api.notion.com") || h.contains("reddit.com") || h.contains("api.spotify.com") -> Category.CONNECTOR
            h.contains("openai") || h.contains("anthropic") || h.contains("googleapis.com") ||
                h.contains("openrouter") || h.contains("x.ai") || h.contains("kimi.com") ||
                h.contains("groq.com") || h.contains("deepseek") -> Category.LLM
            else -> Category.OTHER
        }
    }

    // -- recording --------------------------------------------------------------

    /**
     * [v12-D] Which connector a host belongs to, for the per-connector
     * traffic filter. Host-substring rules, most specific first; null when
     * the host isn't a connector's (AI providers, update checks, unknown).
     * Shared hosts get the honest coarse label — e.g. www.googleapis.com
     * serves Drive, YouTube and Calendar, so it's just "Google".
     */
    fun connectorFor(host: String): String? {
        val h = host.lowercase()
        // Specific Google API hosts first — www.googleapis.com serves the
        // Drive / YouTube / Calendar connectors (coarse "Google" label is
        // the honest one for a shared host).
        when {
            h.contains("gmail.googleapis.com") -> return "Gmail"
            h.contains("photoslibrary.googleapis.com") -> return "Google Photos"
            h.contains("tasks.googleapis.com") -> return "Google Tasks"
            h == "www.googleapis.com" -> return "Google"
        }
        // AI-provider hosts are not connectors — never attribute them.
        if (classify(host) == Category.LLM) return null
        return when {
            h.contains("api.notion.com") -> "Notion"
            h.contains("reddit.com") -> "Reddit"
            h.contains("spotify.com") -> "Spotify"
            h.contains("api.telegram.org") -> "Telegram"
            h.contains("discord.com") -> "Discord"
            h.contains("slack.com") -> "Slack"
            h == "api.github.com" || h == "github.com" || h.endsWith(".github.com") -> "GitHub"
            h.contains("gitlab.com") -> "GitLab"
            h.contains("api.dropboxapi.com") || h.contains("dropbox.com") -> "Dropbox"
            h.contains("trello.com") -> "Trello"
            h.contains("api.todoist.com") -> "Todoist"
            h.contains("api.themoviedb.org") -> "TMDB"
            h.contains("api.stackexchange.com") -> "Stack Overflow"
            h.contains("open-meteo.com") -> "Weather"
            h.contains("api.frankfurter.app") -> "Currency"
            h.contains("api.dictionaryapi.dev") -> "Dictionary"
            h.contains("api.mymemory.translated.net") -> "Translate"
            h.contains("gnews.io") -> "GNews"
            h.contains("algolia.com") || h.contains("ycombinator.com") -> "Hacker News"
            h.contains("wikipedia.org") -> "Wikipedia"
            h.contains("graph.microsoft.com") -> "Microsoft 365"
            h.contains("googleapis.com") || h.contains("google.com") -> "Google"
            h.contains("microsoftonline.com") || h.contains("microsoft.com") -> "Microsoft"
            else -> null
        }
    }

    /** Append [host] to the in-memory ring buffer. Never throws. */
    fun record(host: String) {
        try {
            // [v12-D] Attribute the entry to its connector at the logging
            // call-site, so the traffic log can filter per connector.
            val entry = TrafficEntry(host, classify(host), System.currentTimeMillis(), connectorFor(host))
            _log.value = (_log.value + entry).takeLast(MAX_ENTRIES)
        } catch (t: Throwable) {
            Log.w(TAG, "traffic record failed: ${t.message}")
        }
    }

    /** Drop the whole log (the UI's "Clear" button). */
    fun clear() {
        _log.value = emptyList()
    }

    // -- gating -----------------------------------------------------------------

    /**
     * Throws [IOException] when local-only mode is on and [host] is not
     * allowed. Loopback always passes (OAuth login needs the 127.0.0.1
     * redirect), and IPV6 loopback too.
     */
    @Throws(IOException::class)
    fun checkAllowed(host: String) {
        if (!localOnlyEnabled) return
        val normalized = host.lowercase()
        val allowed = normalized == "127.0.0.1" ||
            normalized == "localhost" ||
            normalized == "::1" ||
            synchronized(gateLock) { normalized in allowlist }
        if (!allowed) {
            throw IOException("Blocked by Local-only mode")
        }
    }

    /**
     * The pair to wire into every OkHttp client:
     * `client.addInterceptor(logging).addInterceptor(gate)`.
     *
     * Logging runs first so a blocked request still shows up in the traffic
     * log (the user can see what local-only mode refused). Both interceptors
     * are application interceptors: they see every call, including retries.
     */
    fun interceptors(): Pair<Interceptor, Interceptor> {
        val logging = Interceptor { chain ->
            val request = chain.request()
            record(request.url.host)
            chain.proceed(request)
        }
        val gate = Interceptor { chain ->
            val request = chain.request()
            checkAllowed(request.url.host)
            chain.proceed(request)
        }
        return Pair(logging, gate)
    }

    // -- allowlist ----------------------------------------------------------------

    /** Well-known official hosts per provider type (no custom base URL case). */
    private fun officialHost(type: ProviderType): String? = when (type) {
        ProviderType.anthropic -> "api.anthropic.com"
        ProviderType.gemini -> "generativelanguage.googleapis.com"
        ProviderType.openAI -> "api.openai.com"
        ProviderType.openAIResponses -> "api.openai.com"
        ProviderType.openRouter -> "openrouter.ai"
        ProviderType.xAI -> "api.x.ai"
        ProviderType.kimiCode -> "api.kimi.com"
        // [v1.2-free-tier] Official hosts for the free-tier providers.
        ProviderType.groq -> "api.groq.com"
        ProviderType.cerebras -> "api.cerebras.ai"
        ProviderType.mistral -> "api.mistral.ai"
        ProviderType.githubModels -> "models.github.ai"
        ProviderType.sambaNova -> "api.sambanova.ai"
        ProviderType.nvidiaNim -> "integrate.api.nvidia.com"
        ProviderType.deepSeek -> "api.deepseek.com"
        ProviderType.zai -> "api.z.ai"
        ProviderType.nebius -> "api.studio.nebius.com"
        ProviderType.chutes -> "llm.chutes.ai"
        else -> null
    }

    /**
     * Rebuild the allowlist from the user's configured providers: each
     * instance's custom base-URL host, or its official host when no custom
     * URL is set. Only enabled instances count — a disabled provider's host
     * stays blocked, which is the honest reading of "disabled".
     *
     * If the provider repository is unreachable (safe-mode boot, init
     * failure), the allowlist resets to EMPTY and the failure is logged —
     * local-only mode then blocks everything but loopback rather than
     * failing open.
     */
    fun refreshAllowlist(context: Context) {
        val fresh = try {
            val repo = (context.applicationContext as? UnibotApp)?.providerRepositoryOrNull
                ?: run {
                    Log.w(TAG, "refreshAllowlist: provider repository unreachable — allowlist emptied")
                    return applyFresh(emptySet())
                }
            val instances = repo.config.value.instances
            val hosts = mutableSetOf<String>()
            for (instance in instances) {
                if (!instance.isEnabled) continue
                val customHost = runCatching {
                    instance.effectiveBaseURL?.toHttpUrlOrNull()?.host
                }.getOrNull()
                (customHost ?: officialHost(instance.providerType))?.let { hosts.add(it.lowercase()) }
            }
            hosts
        } catch (t: Throwable) {
            Log.w(TAG, "refreshAllowlist failed (${t.message}) — allowlist emptied", t)
            emptySet()
        }
        applyFresh(fresh)
    }

    private fun applyFresh(hosts: Set<String>) {
        synchronized(gateLock) {
            allowlist.clear()
            allowlist.addAll(hosts)
        }
        Log.i(TAG, "allowlist refreshed: ${hosts.size} host(s) [${hosts.sorted().joinToString()}]")
    }

    /** Snapshot for the dashboard UI ("Local-only allows these hosts: …"). */
    fun allowlistSnapshot(): List<String> = synchronized(gateLock) { allowlist.sorted() }
}
