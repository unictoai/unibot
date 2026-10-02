package ai.unicto.unibot.connectors.spotify

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spotify connector (OAuth 2.0 + PKCE, user's own Spotify account).
 *
 * Agent tools: now playing, play/pause/skip, playlists. Playback commands
 * need an active Spotify device (phone/desktop app open) — the API returns
 * a clear error otherwise.
 */
object SpotifyConnector {

    val store = SpotifyTokenStore()

    private const val TAG = "SpotifyConnector"
    private const val BASE = "https://api.spotify.com/v1"

    data class Track(
        val title: String,
        val artists: String,
        val album: String,
        val isPlaying: Boolean,
        val progressMs: Long = 0,
        val durationMs: Long = 0,
    )

    data class Playlist(val id: String, val name: String, val trackCount: Int)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Spotify is not connected. Ask the user to connect it in Settings → Connectors.") :
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

    fun isConfigured(): Boolean = SpotifyOAuth.isConfigured()

    suspend fun authorize(context: Context): SpotifyOAuth.Result {
        val r = SpotifyOAuth.authorize(context)
        if (r is SpotifyOAuth.Result.Success) {
            store.setTokens(context, r.tokens)
            store.setAccountEmail(context, r.accountEmail)
        }
        return r
    }

    suspend fun disconnect(context: Context) = SpotifyOAuth.disconnect(context, store)

    private suspend fun token(context: Context): String? =
        SpotifyOAuth.validAccessToken(context, store)

    private fun authed(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    /** Currently playing track, or null when nothing is playing. */
    suspend fun nowPlaying(context: Context): ApiResult<Track?> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(authed("$BASE/me/player", t).get().build()).execute().use { resp ->
                if (resp.code == 204) return@withContext ApiResult.Ok(null)
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val j = JSONObject(text)
                val item = j.optJSONObject("item") ?: return@withContext ApiResult.Ok(null)
                val artists = item.optJSONArray("artists")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("name", "")
                    }.filter { it.isNotBlank() }.joinToString(", ")
                }.orEmpty()
                ApiResult.Ok(
                    Track(
                        title = item.optString("name", ""),
                        artists = artists,
                        album = item.optJSONObject("album")?.optString("name", "").orEmpty(),
                        isPlaying = j.optBoolean("is_playing", false),
                        progressMs = j.optLong("progress_ms", 0),
                        durationMs = item.optLong("duration_ms", 0),
                    ),
                )
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[nowPlaying] ${e.message}")
            ApiResult.Error("Spotify now-playing failed: ${e.message}")
        }
    }

    /** Playback command: play, pause, next, previous. */
    suspend fun control(context: Context, command: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            val (method, path) = when (command.lowercase()) {
                "play" -> "PUT" to "$BASE/me/player/play"
                "pause" -> "PUT" to "$BASE/me/player/pause"
                "next" -> "POST" to "$BASE/me/player/next"
                "previous" -> "POST" to "$BASE/me/player/previous"
                else -> return@withContext ApiResult.Error("Unknown command '$command' (play, pause, next, previous).")
            }
            runCatching {
                val builder = authed(path, t)
                val empty = ByteArray(0).toRequestBody(null)
                if (method == "PUT") builder.put(empty) else builder.post(empty)
                http.newCall(builder.build()).execute().use { resp ->
                    if (resp.code == 204 || resp.isSuccessful) {
                        ApiResult.Ok("Spotify: $command sent.")
                    } else {
                        val text = resp.body?.string().orEmpty()
                        if (resp.code == 404) {
                            ApiResult.Error("No active Spotify device — open Spotify on the phone or desktop first.")
                        } else {
                            apiError(resp.code, text)
                        }
                    }
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[control:$command] ${e.message}")
                ApiResult.Error("Spotify $command failed: ${e.message}")
            }
        }

    suspend fun playlists(context: Context): ApiResult<List<Playlist>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(authed("$BASE/me/playlists?limit=30", t).get().build())
                    .execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                        val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
                        ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                            val p = items.optJSONObject(i) ?: return@mapNotNull null
                            Playlist(
                                id = p.optString("id", ""),
                                name = p.optString("name", ""),
                                trackCount = p.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                            )
                        })
                    }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[playlists] ${e.message}")
                ApiResult.Error("Spotify playlists failed: ${e.message}")
            }
        }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Spotify API error ($code): $detail")
    }
}
