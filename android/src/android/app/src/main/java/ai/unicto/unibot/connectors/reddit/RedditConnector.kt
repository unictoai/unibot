package ai.unicto.unibot.connectors.reddit

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Reddit connector — public read access, NO account needed.
 *
 * Privacy-friendly by design: reads public JSON endpoints, sends no
 * credentials, stores nothing sensitive. The enable/disable toggle lives in
 * Settings → Connectors and only controls whether the agent tools are
 * exposed. A descriptive User-Agent is required by Reddit's API rules.
 */
object RedditConnector {

    private const val TAG = "RedditConnector"
    private const val BASE = "https://www.reddit.com"
    private const val USER_AGENT = "android:ai.unicto.unibot:v0.6.0 (by /u/unibot)"

    private const val PREFS_FILE = "reddit_connector"
    private const val KEY_ENABLED = "enabled"

    data class Post(
        val title: String,
        val subreddit: String,
        val score: Int,
        val comments: Int,
        val url: String,
        val selfText: String = "",
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Reddit is disabled. Ask the user to enable it in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_FILE)
                .also { prefsRef = it }
        }

    /** Enabled by default — no account needed, just public reading. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isConnected(context: Context): Boolean = isEnabled(context)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String): Request =
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build()

    /** Search public posts across Reddit. */
    suspend fun search(context: Context, query: String, limit: Int = 10): ApiResult<List<Post>> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val q = URLEncoder.encode(query, "UTF-8")
            val n = limit.coerceIn(1, 25)
            runCatching {
                http.newCall(get("$BASE/search.json?q=$q&limit=$n&sort=relevance")).execute()
                    .use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext ApiResult.Error("Reddit search failed (HTTP ${resp.code}).")
                        }
                        ApiResult.Ok(parseListing(text))
                    }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("Reddit search failed: ${e.message}")
            }
        }

    /** Top posts of a subreddit (hot by default, or top of the day). */
    suspend fun top(
        context: Context,
        subreddit: String,
        sort: String = "hot",
        limit: Int = 10,
    ): ApiResult<List<Post>> = withContext(Dispatchers.IO) {
        if (!isEnabled(context)) return@withContext ApiResult.Disabled()
        val sub = subreddit.trim().removePrefix("r/").replace(Regex("[^A-Za-z0-9_]"), "")
        if (sub.isEmpty()) return@withContext ApiResult.Error("Give a subreddit name, e.g. android.")
        val s = if (sort == "top") "top?t=day" else "hot"
        val n = limit.coerceIn(1, 25)
        runCatching {
            http.newCall(get("$BASE/r/$sub/$s.json?limit=$n")).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Error("r/$sub not found or blocked (HTTP ${resp.code}).")
                }
                ApiResult.Ok(parseListing(text))
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[top] ${e.message}")
            ApiResult.Error("Reddit top failed: ${e.message}")
        }
    }

    private fun parseListing(text: String): List<Post> {
        val children = runCatching {
            JSONObject(text).optJSONObject("data")?.optJSONArray("children")
        }.getOrNull() ?: JSONArray()
        return (0 until children.length()).mapNotNull { i ->
            val d = children.optJSONObject(i)?.optJSONObject("data") ?: return@mapNotNull null
            Post(
                title = d.optString("title", ""),
                subreddit = d.optString("subreddit", ""),
                score = d.optInt("score", 0),
                comments = d.optInt("num_comments", 0),
                url = d.optString("url", ""),
                selfText = d.optString("selftext", "").take(2000),
            )
        }.filter { it.title.isNotBlank() }
    }
}
