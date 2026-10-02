package ai.unicto.unibot.connectors.youtube

import android.content.Context
import ai.unicto.unibot.connectors.google.GoogleOAuth
import ai.unicto.unibot.connectors.google.GoogleTokenStore
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouTube connector (read-only v1).
 * Scope is sensitive (not restricted): youtube.readonly works for test
 * users in Testing mode; public rollout needs Google verification.
 * NOTE: the YouTube Data API v3 must be enabled in the Cloud project.
 */
object YouTubeConnector {

    const val SCOPES = "https://www.googleapis.com/auth/youtube.readonly"
    val store = GoogleTokenStore("youtube")

    private const val TAG = "YouTubeConnector"
    private const val BASE = "https://www.googleapis.com/youtube/v3"

    data class Video(
        val id: String,
        val title: String,
        val channel: String,
        val publishedAt: String,
        val views: String = "",
        val likes: String = "",
    )

    data class ChannelStats(
        val title: String,
        val subscribers: String,
        val videos: String,
        val views: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "YouTube is not connected. Ask the user to connect it in Settings → Connectors.") :
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

    suspend fun authorize(context: Context): GoogleOAuth.Result {
        val r = GoogleOAuth.authorize(context, SCOPES, "youtube")
        if (r is GoogleOAuth.Result.Success) {
            store.setTokens(context, r.tokens)
            store.setAccountEmail(context, r.accountEmail)
        }
        return r
    }

    suspend fun disconnect(context: Context) = GoogleOAuth.disconnect(context, store)

    private suspend fun token(context: Context): String? =
        GoogleOAuth.validAccessToken(context, store)

    private fun authed(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("YouTube API error ($code): $detail")
    }

    /** Search public videos. */
    suspend fun search(
        context: Context,
        query: String,
        maxResults: Int = 5,
    ): ApiResult<List<Video>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = maxResults.coerceIn(1, 15)
        val url = "$BASE/search?part=snippet&type=video&maxResults=$n" +
            "&q=${android.net.Uri.encode(query)}"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
                ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    val sn = o.optJSONObject("snippet") ?: return@mapNotNull null
                    Video(
                        id = o.optJSONObject("id")?.optString("videoId", "").orEmpty(),
                        title = sn.optString("title", ""),
                        channel = sn.optString("channelTitle", ""),
                        publishedAt = sn.optString("publishedAt", "").take(10),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[search] ${e.message}")
            ApiResult.Error("YouTube search failed: ${e.message}")
        }
    }

    /** The connected user's own channel stats. */
    suspend fun myChannel(context: Context): ApiResult<ChannelStats> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val url = "$BASE/channels?part=snippet,statistics&mine=true"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val item = JSONObject(text).optJSONArray("items")?.optJSONObject(0)
                    ?: return@withContext ApiResult.Error("No YouTube channel found on this account.")
                val stats = item.optJSONObject("statistics") ?: JSONObject()
                ApiResult.Ok(
                    ChannelStats(
                        title = item.optJSONObject("snippet")?.optString("title", "").orEmpty(),
                        subscribers = stats.optString("subscriberCount", "0"),
                        videos = stats.optString("videoCount", "0"),
                        views = stats.optString("viewCount", "0"),
                    ),
                )
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[channel] ${e.message}")
            ApiResult.Error("YouTube channel lookup failed: ${e.message}")
        }
    }

    /** Details + stats for one video. */
    suspend fun video(context: Context, videoId: String): ApiResult<Video> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val url = "$BASE/videos?part=snippet,statistics&id=${android.net.Uri.encode(videoId)}"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val item = JSONObject(text).optJSONArray("items")?.optJSONObject(0)
                    ?: return@withContext ApiResult.Error("Video not found: $videoId")
                val sn = item.optJSONObject("snippet") ?: JSONObject()
                val stats = item.optJSONObject("statistics") ?: JSONObject()
                ApiResult.Ok(
                    Video(
                        id = videoId,
                        title = sn.optString("title", ""),
                        channel = sn.optString("channelTitle", ""),
                        publishedAt = sn.optString("publishedAt", "").take(10),
                        views = stats.optString("viewCount", "0"),
                        likes = stats.optString("likeCount", "0"),
                    ),
                )
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[video] ${e.message}")
            ApiResult.Error("YouTube video lookup failed: ${e.message}")
        }
    }
}
