package ai.unicto.unibot.connectors.photos

import android.content.Context
import android.net.Uri
import ai.unicto.unibot.connectors.google.GoogleOAuth
import ai.unicto.unibot.connectors.google.GoogleTokenStore
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google Photos connector (read-only v1).
 *
 * Scope is sensitive (not restricted): photoslibrary.readonly works for test
 * users in Testing mode; public rollout needs Google verification.
 *
 * NOTE: the Photos Library API must be enabled in the same Google Cloud
 * project that hosts the OAuth client ("unibot", unibot-510412), alongside
 * the Gmail / YouTube / Drive APIs. Without it every call fails with 403.
 *
 * The API has no free-text search endpoint: [search] lists recent items and
 * filters client-side on filename + description, falling back to the most
 * recent items when nothing matches.
 */
object PhotosConnector {

    const val SCOPES = "https://www.googleapis.com/auth/photoslibrary.readonly"
    val store = GoogleTokenStore("photos")

    private const val TAG = "PhotosConnector"
    private const val BASE = "https://photoslibrary.googleapis.com/v1"

    data class Photo(
        val id: String,
        val filename: String,
        val mimeType: String,
        val baseUrl: String,
        val creationTime: String,
        val description: String = "",
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Google Photos is not connected. Ask the user to connect it in Settings → Connectors.") :
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
        val r = GoogleOAuth.authorize(context, SCOPES, "photos")
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
        return ApiResult.Error("Google Photos API error ($code): $detail")
    }

    private fun parsePhoto(o: JSONObject): Photo? {
        val id = o.optString("id", "")
        val baseUrl = o.optString("baseUrl", "")
        if (id.isEmpty() || baseUrl.isEmpty()) return null
        return Photo(
            id = id,
            filename = o.optString("filename", ""),
            mimeType = o.optString("mimeType", ""),
            baseUrl = baseUrl,
            creationTime = o.optJSONObject("mediaMetadata")?.optString("creationTime", "").orEmpty(),
            description = o.optString("description", ""),
        )
    }

    /**
     * List the user's most recent photos/videos. [max] clamped to 1..50
     * (the API's own pageSize cap).
     */
    suspend fun listRecent(
        context: Context,
        max: Int = 12,
    ): ApiResult<List<Photo>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = max.coerceIn(1, 50)
        val url = "$BASE/mediaItems?pageSize=$n"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val items = JSONObject(text).optJSONArray("mediaItems") ?: JSONArray()
                ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                    parsePhoto(items.optJSONObject(i) ?: return@mapNotNull null)
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[listRecent] ${e.message}")
            ApiResult.Error("Google Photos list failed: ${e.message}")
        }
    }

    /**
     * Pseudo-search: pull a recent window and match [query] against filename
     * and description (case-insensitive). No matches → fall back to the most
     * recent [max] items so the user still gets something visual.
     */
    suspend fun search(
        context: Context,
        query: String,
        max: Int = 6,
    ): ApiResult<List<Photo>> = withContext(Dispatchers.IO) {
        val n = max.coerceIn(1, 12)
        val pool = when (val r = listRecent(context, 50)) {
            is ApiResult.Ok -> r.value
            is ApiResult.NotConnected -> return@withContext r
            is ApiResult.Error -> return@withContext r
        }
        val q = query.trim().lowercase()
        val matched = if (q.isEmpty()) emptyList() else pool.filter {
            it.filename.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        ApiResult.Ok((if (matched.isEmpty()) pool else matched).take(n))
    }

    /**
     * Download a [width]-px thumbnail (baseUrl +=wN-hN) with the bearer token
     * into the app cache dir. Returns the file, or null on failure (never
     * throws). baseUrls are short-lived, so thumbnails are re-fetched per
     * tool call rather than reused across sessions.
     */
    suspend fun downloadThumbnail(
        context: Context,
        photo: Photo,
        width: Int = 512,
    ): File? = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext null
        runCatching {
            val url = "${photo.baseUrl}=w$width-h$width"
            val dir = File(context.cacheDir, "photos").apply { mkdirs() }
            val safeId = photo.id.map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
            val ext = when {
                photo.mimeType.contains("png", ignoreCase = true) -> "png"
                photo.mimeType.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            val out = File(dir, "$safeId.$ext")
            if (out.exists() && out.length() > 0) return@runCatching out
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.warning(TAG, "[thumb] HTTP ${resp.code} for ${photo.id}")
                    return@runCatching null
                }
                val body = resp.body ?: return@runCatching null
                out.outputStream().use { body.byteStream().copyTo(it) }
            }
            if (out.exists() && out.length() > 0) out else null
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[thumb] ${e.message}")
            null
        }
    }

    /** file:// URI string for a cached thumbnail, safe to embed in markdown. */
    fun fileUri(file: File): String = Uri.fromFile(file).toString()
}
