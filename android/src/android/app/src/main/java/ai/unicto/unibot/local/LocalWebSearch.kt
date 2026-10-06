package ai.unicto.unibot.local

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** One web search hit: title, text snippet and the real destination URL. */
data class WebResult(
    val title: String,
    val snippet: String,
    val url: String,
)

/**
 * Tiny web-search client for the on-device chat path.
 *
 * Free and keyless: it queries DuckDuckGo's HTML endpoint and parses the
 * result list. No account, no API key, nothing to configure.
 *
 * This is deliberately NOT part of [LlmBackend]: that interface promises
 * zero network I/O on the generate path (the model weights never phone
 * home), and this promise stays intact. Search is a separate, app-level
 * capability — [LocalCapabilities] calls it *before* inference and only
 * when its heuristics (or the user's toggle) say a question needs live
 * info. The user's question text is sent to DuckDuckGo; that is the only
 * disclosure, and the Settings toggle turns it off entirely.
 */
object LocalWebSearch {
    private const val TAG = "LocalWebSearch"
    private const val ENDPOINT = "https://html.duckduckgo.com/html/?q="
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_BODY_CHARS = 200_000

    private val LINK_RE =
        Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val SNIPPET_RE =
        Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val TAG_RE = Regex("<[^>]*>")
    private val UDDG_RE = Regex("[?&]uddg=([^&]+)")

    /**
     * Search the web for [query]. Never throws: any failure (no network,
     * bot-check, parse miss) comes back as an empty list and the caller
     * falls back to the model's built-in knowledge.
     */
    suspend fun search(query: String, maxResults: Int = 4): List<WebResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(ENDPOINT + URLEncoder.encode(query, "UTF-8"))
                // [v1.0-wave5-privacy] HttpURLConnection has no OkHttp
                // interceptors — log + gate manually so the traffic log and
                // Local-only mode cover web search too.
                val privacyGate = ai.unicto.unibot.privacy.PrivacyNetworkGate
                privacyGate.record(url.host)
                privacyGate.checkAllowed(url.host)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                    )
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { r ->
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        var n: Int
                        while (r.read(buf).also { n = it } != -1 && sb.length < MAX_BODY_CHARS) {
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }
                    parseResults(body, maxResults)
                } finally {
                    conn.disconnect()
                }
            }.getOrElse {
                Log.w(TAG, "search failed: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }
        }

    /** Pure HTML → results parser, kept internal so unit tests can drive it. */
    internal fun parseResults(html: String, maxResults: Int): List<WebResult> {
        val links = LINK_RE.findAll(html).toList()
        val snippets = SNIPPET_RE.findAll(html).toList()
        return links.take(maxResults).mapIndexedNotNull { i, m ->
            val href = unescapeHtml(m.groupValues[1])
            val url = realUrl(href)?.let(::stripTrackingParams) ?: return@mapIndexedNotNull null
            val title = cleanText(m.groupValues[2]).take(140)
            val snippet = snippets.getOrNull(i)?.let { cleanText(it.groupValues[1]).take(200) } ?: ""
            if (title.isBlank()) null else WebResult(title, snippet, url)
        }
    }

    /**
     * Privacy: strip marketing/tracking query params before a result URL is
     * handed to the model or rendered in chat. Pure — unit-tested.
     */
    internal fun stripTrackingParams(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val base = url.substring(0, qIdx)
        val fragment = url.substringAfter('#', "")
        val query = url.substring(qIdx + 1).substringBefore('#')
        val kept = query.split('&').filter { param ->
            val name = param.substringBefore('=').lowercase()
            name !in TRACKING_PARAMS && !name.startsWith("utm_")
        }
        val rebuilt = base + (if (kept.isNotEmpty()) "?" + kept.joinToString("&") else "") +
            (if (fragment.isNotEmpty()) "#$fragment" else "")
        return rebuilt
    }

    private val TRACKING_PARAMS = setOf(
        // Google / Meta / Microsoft / misc click IDs
        "gclid", "gbraid", "wbraid", "fbclid", "msclkid", "igshid",
        "mc_cid", "mc_eid", "_ga", "dclid", "yclid", "twclid",
        // common affiliate / ref markers
        "ref", "referrer", "aff_id", "affid", "affiliate_id",
    )

    /** DuckDuckGo wraps destinations as //duckduckgo.com/l/?uddg=<encoded>; unwrap it. */
    private fun realUrl(href: String): String? {
        val withScheme = if (href.startsWith("//")) "https:$href" else href
        val encoded = UDDG_RE.find(withScheme)?.groupValues?.get(1) ?: return withScheme.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun cleanText(html: String): String =
        unescapeHtml(TAG_RE.replace(html, "")).replace(Regex("\\s+"), " ").trim()

    private fun unescapeHtml(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
