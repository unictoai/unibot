package ai.unicto.unibot.connectors.hn

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
 * Hacker News connector — Algolia HN API, NO key, NO account.
 */
object HnConnector {

    private const val TAG = "HnConnector"
    private const val BASE = "https://hn.algolia.com/api/v1"

    private const val PREFS_FILE = "hn_connector"
    private const val KEY_ENABLED = "enabled"

    data class Story(val title: String, val points: Int, val comments: Int, val url: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Hacker News is disabled. Ask the user to enable it in Settings → Connectors.") :
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

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    fun isConnected(context: Context): Boolean = isEnabled(context)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String): Request =
        Request.Builder().url(url).header("User-Agent", "unibot-android").get().build()

    private fun parse(text: String): List<Story> {
        val arr = runCatching { JSONObject(text).optJSONArray("hits") }.getOrNull() ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title", "")
            if (title.isBlank()) return@mapNotNull null
            Story(
                title = title,
                points = o.optInt("points", 0),
                comments = o.optInt("num_comments", 0),
                url = o.optString("url", "").ifBlank { "https://news.ycombinator.com/item?id=${o.optString("objectID", "")}" },
            )
        }
    }

    /** Front-page stories. */
    suspend fun top(context: Context, limit: Int = 10): ApiResult<List<Story>> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val n = limit.coerceIn(1, 30)
            runCatching {
                http.newCall(get("$BASE/search?tags=front_page&hitsPerPage=$n")).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Hacker News failed (${resp.code}).")
                    ApiResult.Ok(parse(text))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[top] ${e.message}")
                ApiResult.Error("Hacker News failed: ${e.message}")
            }
        }

    /** Search stories. */
    suspend fun search(context: Context, query: String, limit: Int = 10): ApiResult<List<Story>> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val q = URLEncoder.encode(query, "UTF-8")
            val n = limit.coerceIn(1, 30)
            runCatching {
                http.newCall(get("$BASE/search?query=$q&tags=story&hitsPerPage=$n")).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Hacker News failed (${resp.code}).")
                    ApiResult.Ok(parse(text))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("Hacker News search failed: ${e.message}")
            }
        }
}
