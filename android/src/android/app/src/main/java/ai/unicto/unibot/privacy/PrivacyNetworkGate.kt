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
 * Thrown by [checkAllowed] when a request is refused by one of the privacy
 * gates (kill switch, on-device-only mode, or local-only mode). Extends
 * [IOException] so every existing catch site keeps working; the message names
 * the exact gate and the way out, which is what the UI shows the user.
 */
class NetworkBlockedException(message: String) : IOException(message)

/**
 * Wave 5 (v1.0) — privacy core. Two jobs, deliberately small and honest:
 *
 * 1. **Traffic log** — a ring buffer of the last 100 outbound requests,
 *    kept IN MEMORY ONLY. Each entry records metadata only: destination
 *    host, HTTP method, a coarse category, byte counts (from content-length
 *    when the wire reports one), the chat session on screen, and whether a
 *    privacy gate blocked it. Never the URL path, query, headers, or body.
 *    The log is the raw material for Settings → Privacy → Network traffic
 *    log (including the per-session inspector), and it dies with the
 *    process.
 *
 * 2. **Network gates** — when a gate mode is on, every request through a
 *    WIRED client whose host is not permitted is refused with a
 *    [NetworkBlockedException] (an IOException whose message names the gate).
 *    Three modes, strongest first: the kill switch (item 58 — one tap severs
 *    all remote network, toggled from the Quick Settings tile or Settings →
 *    Privacy), on-device-only mode (item 62 — the persistent twin: every
 *    remote host blocked, only on-device models and voice run), and the
 *    original local-only gate (AI-provider hosts only, from the allowlist).
 *    127.0.0.1/localhost always pass because the OAuth login dance needs
 *    the loopback redirect. Connector API clients are not wired yet, so
 *    connector traffic is not gated — the UI says exactly that. This is
 *    best-effort client-side gating of this app's own HTTP clients — it
 *    does not claim to stop the OS, other apps, or raw sockets.
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
     *
     * v1.4.0 privacy items 55/56 extend the entry with request metadata only:
     * [method] (GET/POST/…), [bytesUp]/[bytesDown] from content-length when
     * the wire reports one (0 when chunked/unknown — the UI labels byte
     * counts approximate), [sessionId] of the chat on screen when the request
     * was made (null when none), and [blocked] when a privacy gate refused
     * the request. Still no path, query, headers, or body — ever.
     */
    data class TrafficEntry(
        val host: String,
        val category: Category,
        val timestampMs: Long,
        val connector: String? = null,
        val method: String = "GET",
        val bytesUp: Long = 0L,
        val bytesDown: Long = 0L,
        val sessionId: String? = null,
        val blocked: Boolean = false,
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

    /**
     * v1.4.0 privacy item 58 — kill switch. When true, EVERY remote host is
     * refused (loopback excepted: 127.0.0.1 never leaves the device, and the
     * OAuth login dance needs the loopback redirect). Mirrored from
     * [PrivacyPrefs.setKillSwitch] and seeded on cold start; persisted so a
     * kill the user engaged survives a process restart, and always paired
     * with the on-screen banner while engaged.
     */
    @Volatile
    var killSwitchEngaged: Boolean = false

    /**
     * v1.4.0 privacy item 62 — on-device-only mode. Persistent version of
     * the kill switch's "no network" posture: blocks every remote host, so
     * only on-device models and on-device voice can run. Mirrored from
     * [PrivacyPrefs.setOnDeviceOnly] and seeded on cold start. Unlike
     * [localOnlyEnabled], no provider allowlist applies — AI-provider hosts
     * are blocked too, because nothing may leave the phone.
     */
    @Volatile
    var onDeviceOnlyEnabled: Boolean = false

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
        recordFull(
            TrafficEntry(
                host = host,
                category = classify(host),
                timestampMs = System.currentTimeMillis(),
                connector = connectorFor(host),
                sessionId = currentSessionId(),
            )
        )
    }

    /**
     * Append a fully-populated [TrafficEntry] to the in-memory ring buffer.
     * Never throws. This is the entry point the rich logging interceptor
     * uses; [record] stays for the HttpURLConnection call sites that only
     * know the host.
     */
    fun recordFull(entry: TrafficEntry) {
        try {
            _log.value = (_log.value + entry).takeLast(MAX_ENTRIES)
        } catch (t: Throwable) {
            Log.w(TAG, "traffic record failed: ${t.message}")
        }
    }

    /**
     * The id of the chat currently on screen, for per-session traffic
     * attribution (privacy item 55). Read-only: chat code is never modified
     * from here — when no session is active (or the lookup fails) the entry
     * is simply untagged.
     */
    private fun currentSessionId(): String? = runCatching {
        ai.unicto.unibot.ui.chat.ChatViewModelStore.activeSessionId
    }.getOrNull()

    /** Drop the whole log (the UI's "Clear" button). */
    fun clear() {
        _log.value = emptyList()
    }

    // -- gating -----------------------------------------------------------------

    /**
     * Throws [NetworkBlockedException] when a privacy gate refuses [host].
     * Gate order, strongest first:
     *
     * 1. Kill switch (item 58) — refuses every remote host. One tap in the
     *    Quick Settings tile or Settings → Privacy severs all network
     *    app-wide; requests fail fast with a clear "network killed" state.
     * 2. On-device-only mode (item 62) — refuses every remote host, so only
     *    on-device models and voice can run. The persistent twin of the
     *    kill switch; both share this gate mechanism, not a VPN.
     * 3. Local-only mode — refuses hosts outside the provider allowlist.
     *
     * Loopback (127.0.0.1/localhost/::1) always passes: it never leaves the
     * device, and the OAuth login dance needs the loopback redirect. This is
     * best-effort client-side gating of this app's own HTTP clients — it
     * does not claim to stop the OS, other apps, or raw sockets.
     */
    @Throws(IOException::class)
    fun checkAllowed(host: String) {
        val normalized = host.lowercase()
        if (normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1") return
        if (killSwitchEngaged) {
            throw NetworkBlockedException(
                "Network killed — the kill switch is on. " +
                    "Turn it off in Settings → Privacy to reconnect."
            )
        }
        if (onDeviceOnlyEnabled) {
            throw NetworkBlockedException(
                "Blocked by On-device-only mode — only on-device models and " +
                    "voice can run while it is on."
            )
        }
        if (!localOnlyEnabled) return
        val allowed = synchronized(gateLock) { normalized in allowlist }
        if (!allowed) {
            throw NetworkBlockedException("Blocked by Local-only mode")
        }
    }

    /**
     * The pair to wire into every OkHttp client:
     * `client.addInterceptor(logging).addInterceptor(gate)`.
     *
     * The logging interceptor runs first: it records the attempt (method,
     * host, byte counts, session — metadata only, never bodies), runs the
     * gate check itself so a blocked request is logged WITH its blocked
     * flag, then proceeds. The standalone gate interceptor stays as a
     * second line for the same check — idempotent, and it keeps the
     * long-standing wiring contract intact. Both are application
     * interceptors: they see every call, including retries.
     *
     * Every attempt — allowed, blocked, or failed — also feeds
     * [WeeklyPrivacyReport], which aggregates the on-device weekly report.
     */
    fun interceptors(): Pair<Interceptor, Interceptor> {
        val logging = Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val base = TrafficEntry(
                host = host,
                category = classify(host),
                timestampMs = System.currentTimeMillis(),
                connector = connectorFor(host),
                method = request.method,
                // contentLength() may throw on exotic bodies — never let
                // telemetry break the request it observes.
                bytesUp = runCatching {
                    request.body?.contentLength()?.takeIf { it >= 0 } ?: 0L
                }.getOrDefault(0L),
                sessionId = currentSessionId(),
            )
            try {
                checkAllowed(host)
            } catch (blocked: IOException) {
                recordFull(base.copy(blocked = true))
                WeeklyPrivacyReport.record(host, base.bytesUp, 0L)
                throw blocked
            }
            try {
                val response = chain.proceed(request)
                val bytesDown = response.body?.contentLength()?.takeIf { it >= 0 } ?: 0L
                recordFull(base.copy(bytesDown = bytesDown))
                WeeklyPrivacyReport.record(host, base.bytesUp, bytesDown)
                response
            } catch (e: IOException) {
                // A real network failure, not a gate block: the attempt still
                // happened, so it still belongs in the log and the report.
                recordFull(base)
                WeeklyPrivacyReport.record(host, base.bytesUp, 0L)
                throw e
            }
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
