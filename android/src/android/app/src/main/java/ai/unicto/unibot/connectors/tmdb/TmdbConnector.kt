package ai.unicto.unibot.connectors.tmdb

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
 * TMDB (The Movie Database) connector — free API key.
 *
 * Key from themoviedb.org → Settings → API (v3 auth key). Stored
 * encrypted; only ever sent to api.themoviedb.org.
 */
object TmdbConnector {

    val store = TokenStore("tmdb")

    private const val TAG = "TmdbConnector"
    private const val BASE = "https://api.themoviedb.org/3"

    data class Title(val id: Long, val name: String, val kind: String, val year: String, val rating: Double, val overview: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "TMDB is not connected. Ask the user to add a free API key in Settings → Connectors.") :
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
        return "$BASE$path${if (path.contains('?')) "&" else "?"}api_key=${URLEncoder.encode(k, "UTF-8")}"
    }

    /** Validate a key: returns "TMDB" on success, null when invalid. */
    suspend fun validate(context: Context, key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder()
                    .url("$BASE/configuration?api_key=${URLEncoder.encode(key.trim(), "UTF-8")}")
                    .get().build(),
            ).execute().use { resp -> if (resp.isSuccessful) "TMDB" else null }
        }.getOrNull()
    }

    suspend fun connect(context: Context, key: String): String? {
        val name = validate(context, key) ?: return null
        store.setToken(context, key)
        store.setLabel(context, name)
        return name
    }

    fun disconnect(context: Context) = store.clear(context)

    private fun parseTitles(text: String): List<Title> {
        val arr = runCatching { JSONObject(text).optJSONArray("results") }.getOrNull() ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("title", "").ifBlank { o.optString("name", "") }
            if (name.isBlank()) return@mapNotNull null
            val date = o.optString("release_date", "").ifBlank { o.optString("first_air_date", "") }
            Title(
                id = o.optLong("id", 0),
                name = name,
                kind = o.optString("media_type", "").ifBlank { "movie" },
                year = date.take(4),
                rating = o.optDouble("vote_average", 0.0),
                overview = o.optString("overview", "").take(400),
            )
        }
    }

    /** Search movies + TV. */
    suspend fun search(context: Context, query: String): ApiResult<List<Title>> =
        withContext(Dispatchers.IO) {
            val url = authed(context, "/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&page=1")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("TMDB error (${resp.code}).")
                    ApiResult.Ok(parseTitles(text).take(10))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("TMDB search failed: ${e.message}")
            }
        }

    /** Trending this week. */
    suspend fun trending(context: Context): ApiResult<List<Title>> = withContext(Dispatchers.IO) {
        val url = authed(context, "/trending/all/week?page=1")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext ApiResult.Error("TMDB error (${resp.code}).")
                ApiResult.Ok(parseTitles(text).take(10))
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[trending] ${e.message}")
            ApiResult.Error("TMDB trending failed: ${e.message}")
        }
    }
}
