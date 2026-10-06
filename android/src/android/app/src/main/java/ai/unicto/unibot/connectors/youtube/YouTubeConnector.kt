package ai.unicto.unibot.connectors.youtube

import android.content.Context
import ai.unicto.unibot.connectors.google.GoogleOAuth
import ai.unicto.unibot.connectors.google.GoogleTokenStore
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    const val SCOPES = "https://www.googleapis.com/auth/youtube.readonly " +
        "https://www.googleapis.com/auth/youtube.force-ssl"
    // Watch Later management needs youtube.force-ssl (sensitive, not
    // restricted — same verification class as readonly). Users who
    // connected before this scope was added re-authorize once.
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

    /** The connected channel's most recent uploads (for the creator dashboard). */
    suspend fun recentVideos(
        context: Context,
        maxResults: Int = 5,
    ): ApiResult<List<Video>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = maxResults.coerceIn(1, 10)
        val url = "$BASE/search?part=snippet&forMine=true&type=video&order=date&maxResults=$n"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
                val ids = (0 until items.length()).mapNotNull { i ->
                    items.optJSONObject(i)?.optJSONObject("id")?.optString("videoId", "")
                        ?.takeIf { it.isNotBlank() }
                }
                if (ids.isEmpty()) return@withContext ApiResult.Ok(emptyList())
                // Enrich with statistics in one batched call.
                val vurl = "$BASE/videos?part=snippet,statistics&id=${ids.joinToString(",")}"
                http.newCall(authed(vurl, t).get().build()).execute().use { vresp ->
                    val vtext = vresp.body?.string().orEmpty()
                    if (!vresp.isSuccessful) return@withContext apiError(vresp.code, vtext)
                    val vitems = JSONObject(vtext).optJSONArray("items") ?: JSONArray()
                    ApiResult.Ok((0 until vitems.length()).mapNotNull { i ->
                        val o = vitems.optJSONObject(i) ?: return@mapNotNull null
                        val sn = o.optJSONObject("snippet") ?: return@mapNotNull null
                        val stats = o.optJSONObject("statistics") ?: JSONObject()
                        Video(
                            id = o.optString("id", ""),
                            title = sn.optString("title", ""),
                            channel = sn.optString("channelTitle", ""),
                            publishedAt = sn.optString("publishedAt", "").take(10),
                            views = stats.optString("viewCount", "0"),
                            likes = stats.optString("likeCount", "0"),
                        )
                    })
                }
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[recent] ${e.message}")
            ApiResult.Error("YouTube recent videos failed: ${e.message}")
        }
    }

    // -- Transcripts (no OAuth — public innertube player endpoint) ----------

    /**
     * Fetch a video's caption track as plain text for summarization.
     * Uses YouTube's innertube player API (the same endpoint the web
     * client uses); no OAuth needed and no API quota is consumed. Prefers
     * an English track, falls back to the first available track.
     */
    suspend fun transcript(
        context: Context,
        videoId: String,
        maxChars: Int = 30_000,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        runCatching {
            val playerBody = JSONObject().apply {
                put("videoId", videoId)
                put(
                    "context", JSONObject().put(
                        "client", JSONObject().apply {
                            put("clientName", "ANDROID")
                            put("clientVersion", "20.10.38")
                            put("androidSdkVersion", 34)
                        },
                    ),
                )
            }.toString().toRequestBody("application/json".toMediaType())
            val playerJson = http.newCall(
                Request.Builder()
                    .url("$INNERTUBE_BASE/player?key=$INNERTUBE_KEY")
                    .post(playerBody)
                    .header(
                        "User-Agent",
                        "com.google.android.youtube/20.10.38 (Linux; U; Android 14)",
                    )
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Error(
                        "YouTube player lookup failed (${resp.code}).",
                    )
                }
                JSONObject(resp.body?.string().orEmpty())
            }
            val tracks = playerJson.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks") ?: JSONArray()
            if (tracks.length() == 0) {
                return@withContext ApiResult.Error("No captions available for this video.")
            }
            var chosen: JSONObject? = null
            var fallback: JSONObject? = null
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (fallback == null) fallback = t
                val lang = t.optString("languageCode", "")
                if (lang == "en" || lang.startsWith("en-")) {
                    chosen = t
                    break
                }
            }
            val track = chosen ?: fallback
                ?: return@withContext ApiResult.Error("No captions available for this video.")
            var baseUrl = track.optString("baseUrl", "")
            if (baseUrl.isEmpty()) {
                return@withContext ApiResult.Error("Caption track has no download URL.")
            }
            if (!baseUrl.contains("fmt=")) baseUrl += "&fmt=srv3"
            val xml = http.newCall(Request.Builder().url(baseUrl).get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext ApiResult.Error(
                            "Caption download failed (${resp.code}).",
                        )
                    }
                    resp.body?.string().orEmpty()
                }
            val text = parseTimedText(xml)
            if (text.isBlank()) {
                return@withContext ApiResult.Error("Caption track was empty.")
            }
            val title = playerJson.optJSONObject("videoDetails")
                ?.optString("title", "").orEmpty()
            val shown = if (text.length > maxChars) {
                text.take(maxChars) + "\n[…transcript truncated at $maxChars chars]"
            } else text
            ApiResult.Ok(
                (if (title.isNotBlank()) "Transcript of \"$title\":\n\n" else "") + shown,
            )
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[transcript] ${e.message}")
            ApiResult.Error("Transcript fetch failed: ${e.message}")
        }
    }

    /** timedtext XML → plain lines. Pure — unit-tested. */
    fun parseTimedText(xml: String): String {
        val textRe = Regex("<text[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
        val tagRe = Regex("<[^>]*>")
        val seen = LinkedHashSet<String>()
        for (m in textRe.findAll(xml)) {
            var s = m.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
            s = tagRe.replace(s, " ").replace(Regex("\\s+"), " ").trim()
            if (s.isNotBlank()) seen.add(s)
        }
        return seen.joinToString("\n")
    }

    // -- Watch Later (needs youtube.force-ssl scope) --------------------------

    /** Special playlist id for the user's Watch Later list. */
    const val WATCH_LATER_PLAYLIST_ID = "WL"

    data class WatchLaterItem(
        val playlistItemId: String,
        val videoId: String,
        val title: String,
        val channel: String,
    )

    /** List the user's Watch Later videos, newest-added first. */
    suspend fun watchLaterList(
        context: Context,
        maxResults: Int = 15,
    ): ApiResult<List<WatchLaterItem>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = maxResults.coerceIn(1, 25)
        val url = "$BASE/playlistItems?part=snippet,contentDetails" +
            "&playlistId=$WATCH_LATER_PLAYLIST_ID&maxResults=$n"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
                ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    val sn = o.optJSONObject("snippet") ?: return@mapNotNull null
                    WatchLaterItem(
                        playlistItemId = o.optString("id", ""),
                        videoId = sn.optJSONObject("resourceId")?.optString("videoId", "").orEmpty(),
                        title = sn.optString("title", ""),
                        channel = sn.optString("videoOwnerChannelTitle", ""),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[watchlater-list] ${e.message}")
            ApiResult.Error("Watch Later list failed: ${e.message}")
        }
    }

    /** Add a video to Watch Later. */
    suspend fun watchLaterAdd(
        context: Context,
        videoId: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val payload = JSONObject().apply {
            put("snippet", JSONObject().apply {
                put("playlistId", WATCH_LATER_PLAYLIST_ID)
                put(
                    "resourceId", JSONObject().apply {
                        put("kind", "youtube#video")
                        put("videoId", videoId)
                    },
                )
            })
        }.toString().toRequestBody("application/json".toMediaType())
        runCatching {
            http.newCall(
                authed("$BASE/playlistItems?part=snippet", t).post(payload).build(),
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val title = JSONObject(text).optJSONObject("snippet")?.optString("title").orEmpty()
                ApiResult.Ok(
                    if (title.isNotBlank()) "Added \"$title\" to Watch Later."
                    else "Added to Watch Later.",
                )
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[watchlater-add] ${e.message}")
            ApiResult.Error("Watch Later add failed: ${e.message}")
        }
    }

    /** Remove a video from Watch Later by playlist-item id (from watchLaterList). */
    suspend fun watchLaterRemove(
        context: Context,
        playlistItemId: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val pid = android.net.Uri.encode(playlistItemId)
        runCatching {
            http.newCall(authed("$BASE/playlistItems?id=$pid", t).delete().build())
                .execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 204) {
                        ApiResult.Ok("Removed from Watch Later.")
                    } else {
                        apiError(resp.code, resp.body?.string().orEmpty())
                    }
                }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[watchlater-remove] ${e.message}")
            ApiResult.Error("Watch Later remove failed: ${e.message}")
        }
    }

    private const val INNERTUBE_BASE = "https://www.youtube.com/youtubei/v1"
    /**
     * Public web-client API key embedded in YouTube's own web pages.
     * Only used for the public player/captions lookup — never for
     * account actions, which ride the user's OAuth token.
     */
    private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

}
