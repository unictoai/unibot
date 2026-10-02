package ai.unicto.unibot.connectors.rss

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

/**
 * RSS / Atom feeds connector — no account, no auth.
 *
 * The user adds feed URLs in Settings → Connectors; they are stored
 * on-device only and fetched only when the user asks for headlines.
 * Parsing uses Android's XmlPullParser (RSS 2.0 `<item>` and Atom `<entry>`),
 * no new dependencies.
 */
object RssConnector {

    private const val TAG = "RssConnector"
    private const val PREFS_FILE = "rss_connector"
    private const val KEY_FEEDS = "feeds"

    /** Seeded on first use; the user can add/remove freely. */
    private val DEFAULT_FEEDS = listOf(
        "https://news.ycombinator.com/rss",
        "https://techcrunch.com/feed/",
        "https://feeds.bbci.co.uk/news/technology/rss.xml",
    )

    data class Headline(val title: String, val link: String, val published: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_FILE)
                .also { prefsRef = it }
        }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun feeds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_FEEDS, null)
        if (raw == null) {
            setFeeds(context, DEFAULT_FEEDS)
            return DEFAULT_FEEDS
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        }.getOrDefault(DEFAULT_FEEDS)
    }

    fun setFeeds(context: Context, feeds: List<String>) {
        val arr = JSONArray()
        feeds.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_FEEDS, arr.toString()).apply()
    }

    fun addFeed(context: Context, url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val current = feeds(context).toMutableList()
        if (current.any { it.equals(u, ignoreCase = true) }) return true
        current.add(u)
        setFeeds(context, current)
        return true
    }

    fun removeFeed(context: Context, url: String) {
        setFeeds(context, feeds(context).filter { !it.equals(url, ignoreCase = true) })
    }

    fun isConnected(context: Context): Boolean = true

    /** Latest headlines from one feed (or all feeds when [feedUrl] is null). */
    suspend fun latest(
        context: Context,
        feedUrl: String?,
        limit: Int = 10,
    ): ApiResult<List<Headline>> = withContext(Dispatchers.IO) {
        val targets = if (feedUrl.isNullOrBlank()) feeds(context) else listOf(feedUrl.trim())
        if (targets.isEmpty()) {
            return@withContext ApiResult.Error("No RSS feeds added. Add some in Settings → Connectors → RSS feeds.")
        }
        val all = mutableListOf<Headline>()
        val errors = mutableListOf<String>()
        for (url in targets) {
            when (val r = fetchFeed(url)) {
                is ApiResult.Ok -> all.addAll(r.value)
                is ApiResult.Error -> errors.add("$url: ${r.message}")
            }
            if (all.size >= limit * targets.size.coerceAtMost(3)) break
        }
        if (all.isEmpty()) {
            return@withContext ApiResult.Error(
                "Couldn't read any feed." + if (errors.isNotEmpty()) " ${errors.first()}" else "",
            )
        }
        ApiResult.Ok(all.take(limit.coerceIn(1, 30)))
    }

    private fun fetchFeed(url: String): ApiResult<List<Headline>> {
        return try {
            http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "ai.unicto.unibot/rss")
                    .get().build(),
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || text.isBlank()) {
                    return ApiResult.Error("HTTP ${resp.code}")
                }
                ApiResult.Ok(parse(text))
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[fetch] $url: ${t.message}")
            ApiResult.Error(t.message ?: "fetch failed")
        }
    }

    /** Parse RSS 2.0 items and Atom entries into headlines. */
    private fun parse(xml: String): List<Headline> {
        val out = mutableListOf<Headline>()
        try {
            val p: XmlPullParser = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(xml.reader())
            var title = ""; var link = ""; var pub = ""
            var inItem = false
            var textTag = ""
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT && out.size < 40) {
                val name = p.name?.lowercase().orEmpty()
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (name == "item" || name == "entry") {
                            inItem = true; title = ""; link = ""; pub = ""
                        } else if (inItem && name == "link") {
                            // Atom puts the URL in href=""
                            val href = p.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) link = href
                        }
                        textTag = name
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem) {
                            val t = p.text?.trim().orEmpty()
                            if (t.isNotEmpty()) {
                                when (textTag) {
                                    "title" -> title += t
                                    "link" -> if (link.isBlank()) link += t
                                    "pubdate", "published", "updated" ->
                                        if (pub.isBlank()) pub = t
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "item" || name == "entry") {
                            if (title.isNotBlank()) out.add(Headline(title, link, pub))
                            inItem = false
                        }
                        textTag = ""
                    }
                }
                event = p.next()
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[parse] ${t.message}")
        }
        return out
    }
}
