package ai.unicto.unibot.connectors.gnews

import android.content.Context
import ai.unicto.unibot.connectors.token.TokenStore
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * GNews connector — free API key (100 requests/day on the free tier).
 *
 * Key from gnews.io. Stored encrypted; only ever sent to gnews.io.
 */
object GNewsConnector {

    val store = TokenStore("gnews")

    private const val TAG = "GNewsConnector"
    private const val BASE = "https://gnews.io/api/v4"

    data class Article(val title: String, val source: String, val url: String, val publishedAt: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "News is not connected. Ask the user to add a free GNews API key in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isConnected(context: Context): Boolean = store.isConnected(context)

    private fun authed(context: Context, path: String): String? {
        val k = store.getToken(context) ?: return null
        return "$BASE$path${if (path.contains('?')) "&" else "?"}token=${URLEncoder.encode(k, "UTF-8")}&lang=en&max=10"
    }

    /** Validate a key: non-empty article list on success. */
    suspend fun validate(context: Context, key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder()
                    .url("$BASE/top-headlines?token=${URLEncoder.encode(key.trim(), "UTF-8")}&lang=en&max=1")
                    .get().build(),
            ).execute().use { resp -> if (resp.isSuccessful) "GNews" else null }
        }.getOrNull()
    }

    suspend fun connect(context: Context, key: String): String? {
        val name = validate(context, key) ?: return null
        store.setToken(context, key)
        store.setLabel(context, name)
        return name
    }

    fun disconnect(context: Context) = store.clear(context)

    private fun parse(text: String): List<Article> {
        val arr = runCatching { JSONObject(text).optJSONArray("articles") }.getOrNull() ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title", "")
            if (title.isBlank()) return@mapNotNull null
            Article(
                title = title,
                source = o.optJSONObject("source")?.optString("name", "").orEmpty(),
                url = o.optString("url", ""),
                publishedAt = o.optString("publishedAt", "").take(10),
            )
        }
    }

    /** Top headlines. */
    suspend fun top(context: Context, topic: String = ""): ApiResult<List<Article>> =
        withContext(Dispatchers.IO) {
            val t = topic.trim().lowercase()
            val path = if (t in setOf("world", "nation", "business", "technology", "entertainment", "sports", "science", "health"))
                "/top-headlines?topic=$t" else "/top-headlines"
            val url = authed(context, path) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("News error (${resp.code}).")
                    ApiResult.Ok(parse(text))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[top] ${e.message}")
                ApiResult.Error("News failed: ${e.message}")
            }
        }

    /** Search news. */
    suspend fun search(context: Context, query: String): ApiResult<List<Article>> =
        withContext(Dispatchers.IO) {
            val url = authed(context, "/search?q=${URLEncoder.encode(query, "UTF-8")}&sortby=publishedAt")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("News error (${resp.code}).")
                    ApiResult.Ok(parse(text))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("News search failed: ${e.message}")
            }
        }
}
