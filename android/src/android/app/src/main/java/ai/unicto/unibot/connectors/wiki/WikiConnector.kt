package ai.unicto.unibot.connectors.wiki

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
 * Wikipedia connector — public REST API, NO key, NO account.
 */
object WikiConnector {

    private const val TAG = "WikiConnector"
    private const val BASE = "https://en.wikipedia.org"

    private const val PREFS_FILE = "wiki_connector"
    private const val KEY_ENABLED = "enabled"

    data class Article(val title: String, val summary: String, val url: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Wikipedia is disabled. Ask the user to enable it in Settings → Connectors.") :
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
        Request.Builder().url(url)
            .header("User-Agent", "unibot-android")
            .header("Accept", "application/json")
            .get().build()

    /** Search article titles. */
    suspend fun search(context: Context, query: String, limit: Int = 5): ApiResult<List<String>> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val q = URLEncoder.encode(query, "UTF-8")
            val n = limit.coerceIn(1, 10)
            runCatching {
                http.newCall(get("$BASE/w/api.php?action=opensearch&search=$q&limit=$n&format=json"))
                    .execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@withContext ApiResult.Error("Wikipedia failed (${resp.code}).")
                        val titles = runCatching { JSONArray(text).optJSONArray(1) }.getOrNull() ?: JSONArray()
                        ApiResult.Ok((0 until titles.length()).map { titles.optString(it, "") }.filter { it.isNotBlank() })
                    }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("Wikipedia search failed: ${e.message}")
            }
        }

    /** Article summary. */
    suspend fun summary(context: Context, title: String): ApiResult<Article> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val t = URLEncoder.encode(title.trim(), "UTF-8").replace("+", "_")
            runCatching {
                http.newCall(get("$BASE/api/rest_v1/page/summary/$t")).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Article not found: $title")
                    val j = JSONObject(text)
                    ApiResult.Ok(
                        Article(
                            title = j.optString("title", title),
                            summary = j.optString("extract", "").take(2000),
                            url = j.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page", "").orEmpty(),
                        ),
                    )
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[summary] ${e.message}")
                ApiResult.Error("Wikipedia summary failed: ${e.message}")
            }
        }
}
