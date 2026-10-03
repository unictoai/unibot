package ai.unicto.unibot.connectors.podcasts

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
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Podcasts connector — no account, no auth, on-device only.
 *
 * Search uses the public iTunes Search API (no key needed). Following a
 * show stores its feed URL in encrypted prefs; episode lists are parsed
 * from the show's RSS feed with Android's XmlPullParser (same pattern as
 * the RSS connector), including `enclosure` audio URLs and
 * `itunes:duration`.
 */
object PodcastsConnector {

    private const val TAG = "PodcastsConnector"
    private const val PREFS_FILE = "podcasts_connector"
    private const val KEY_SUBS = "subscriptions"

    data class Show(
        val feedUrl: String,
        val title: String,
        val artist: String,
        val artwork: String?,
    )

    data class Episode(
        val title: String,
        val published: String,
        val duration: String,
        val audioUrl: String,
        val description: String,
    )

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

    /** "Connected" for a keyless connector = has at least one followed show. */
    fun isConnected(context: Context): Boolean = subscriptions(context).isNotEmpty()

    fun subscriptions(context: Context): List<Show> {
        val raw = prefs(context).getString(KEY_SUBS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val feed = o.optString("feedUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Show(feed, o.optString("title").ifBlank { feed }, o.optString("artist"), o.optString("artwork").ifBlank { null })
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSubscriptions(context: Context, subs: List<Show>) {
        val arr = JSONArray()
        subs.forEach {
            arr.put(JSONObject().put("feedUrl", it.feedUrl).put("title", it.title)
                .put("artist", it.artist).put("artwork", it.artwork.orEmpty()))
        }
        prefs(context).edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    fun subscribe(context: Context, show: Show) {
        val subs = subscriptions(context).toMutableList()
        if (subs.any { it.feedUrl.equals(show.feedUrl, ignoreCase = true) }) return
        subs.add(show)
        saveSubscriptions(context, subs)
    }

    fun unsubscribe(context: Context, feedUrl: String) {
        saveSubscriptions(
            context,
            subscriptions(context).filter { !it.feedUrl.equals(feedUrl, ignoreCase = true) },
        )
    }

    fun isSubscribed(context: Context, feedUrl: String): Boolean =
        subscriptions(context).any { it.feedUrl.equals(feedUrl, ignoreCase = true) }

    /** Search the iTunes podcast directory. */
    suspend fun search(context: Context, query: String): ApiResult<List<Show>> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext ApiResult.Error("Type something to search for.")
            try {
                val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(q, "UTF-8")}" +
                    "&media=podcast&entity=podcast&limit=20"
                http.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", "ai.unicto.unibot/podcasts")
                        .get().build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Search failed (HTTP ${resp.code}).")
                    val results = JSONObject(resp.body?.string().orEmpty())
                        .optJSONArray("results") ?: JSONArray()
                    ApiResult.Ok((0 until results.length()).mapNotNull { i ->
                        val o = results.optJSONObject(i) ?: return@mapNotNull null
                        val feed = o.optString("feedUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Show(
                            feedUrl = feed,
                            title = o.optString("collectionName").ifBlank { o.optString("trackName") },
                            artist = o.optString("artistName"),
                            artwork = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null },
                        )
                    })
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[search] ${t.message}")
                ApiResult.Error(t.message ?: "search failed")
            }
        }

    /** Latest episodes of a show, parsed from its RSS feed. */
    suspend fun episodes(
        context: Context,
        feedUrl: String,
        limit: Int = 15,
    ): ApiResult<List<Episode>> = withContext(Dispatchers.IO) {
        try {
            http.newCall(
                Request.Builder().url(feedUrl)
                    .header("User-Agent", "ai.unicto.unibot/podcasts")
                    .get().build(),
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || text.isBlank()) {
                    return@withContext ApiResult.Error("Couldn't read the feed (HTTP ${resp.code}).")
                }
                ApiResult.Ok(parseEpisodes(text).take(limit.coerceIn(1, 50)))
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[episodes] ${t.message}")
            ApiResult.Error(t.message ?: "fetch failed")
        }
    }

    private fun parseEpisodes(xml: String): List<Episode> {
        val out = mutableListOf<Episode>()
        try {
            val p: XmlPullParser = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(xml.reader())
            var title = ""; var pub = ""; var desc = ""
            var audio = ""; var duration = ""
            var inItem = false
            var tag = ""
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT && out.size < 60) {
                val name = p.name?.lowercase().orEmpty()
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (name == "item" || name == "entry") {
                            inItem = true
                            title = ""; pub = ""; desc = ""; audio = ""; duration = ""
                        } else if (inItem && name == "enclosure") {
                            val u = p.getAttributeValue(null, "url")
                            if (!u.isNullOrBlank() && audio.isBlank()) audio = u
                        }
                        tag = name
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem) {
                            val t = p.text?.trim().orEmpty()
                            if (t.isNotEmpty()) when (tag) {
                                "title" -> title += t
                                "pubdate", "published", "updated" -> if (pub.isBlank()) pub = t
                                "description", "summary" -> if (desc.length < 400) desc += t
                                "duration" -> if (duration.isBlank()) duration = t
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name == "item" || name == "entry") {
                            if (title.isNotBlank()) {
                                out.add(
                                    Episode(
                                        title = title,
                                        published = pub,
                                        duration = duration,
                                        audioUrl = audio,
                                        description = desc.take(300),
                                    ),
                                )
                            }
                            inItem = false
                        }
                        tag = ""
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
