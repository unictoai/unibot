package ai.unicto.unibot.connectors.drive

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
 * Google Drive connector (read-only v1).
 * Scopes are sensitive (not restricted): drive.readonly works for test users
 * in Testing mode; public rollout needs Google verification.
 */
object DriveConnector {

    const val SCOPES = "https://www.googleapis.com/auth/drive.readonly"
    val store = GoogleTokenStore("drive")

    private const val TAG = "DriveConnector"
    private const val BASE = "https://www.googleapis.com/drive/v3"

    data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val modifiedTime: String,
        val size: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Google Drive is not connected. Ask the user to connect it in Settings → Connectors.") :
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
        val r = GoogleOAuth.authorize(context, SCOPES, "drive")
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

    /** Search files by name/content. [query] is free text matched against name. */
    suspend fun search(
        context: Context,
        query: String,
        maxResults: Int = 10,
    ): ApiResult<List<DriveFile>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = maxResults.coerceIn(1, 25)
        // Drive query syntax: name contains / fullText contains; skip trashed.
        val q = "trashed = false and (name contains '${query.replace("'", "\\'")}' " +
            "or fullText contains '${query.replace("'", "\\'")}')"
        val url = "$BASE/files?q=${android.net.Uri.encode(q)}" +
            "&pageSize=$n&orderBy=modifiedTime desc" +
            "&fields=files(id,name,mimeType,modifiedTime,size)"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val arr = JSONObject(text).optJSONArray("files") ?: JSONArray()
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val f = arr.optJSONObject(i) ?: return@mapNotNull null
                    DriveFile(
                        id = f.optString("id", ""),
                        name = f.optString("name", ""),
                        mimeType = f.optString("mimeType", ""),
                        modifiedTime = f.optString("modifiedTime", ""),
                        size = f.optString("size", ""),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[search] ${e.message}")
            ApiResult.Error("Drive search failed: ${e.message}")
        }
    }

    /** Read a file's text. Google Docs/Sheets/Slides are exported as plain text. */
    suspend fun read(context: Context, fileId: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                // First get metadata to decide download vs export.
                val meta = http.newCall(
                    authed("$BASE/files/$fileId?fields=id,name,mimeType,size", t).get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    JSONObject(text)
                }
                val mime = meta.optString("mimeType", "")
                val name = meta.optString("name", "")
                val url = if (mime.startsWith("application/vnd.google-apps.")) {
                    "$BASE/files/$fileId/export?mimeType=text/plain"
                } else {
                    "$BASE/files/$fileId?alt=media"
                }
                http.newCall(authed(url, t).get().build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) {
                        return@withContext apiError(resp.code, bytes.toString(Charsets.UTF_8))
                    }
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.length > 60_000) {
                        ApiResult.Ok("File: $name\n[${text.length} chars — showing first 60,000]\n\n" + text.take(60_000))
                    } else {
                        ApiResult.Ok("File: $name\n\n$text")
                    }
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[read] ${e.message}")
                ApiResult.Error("Drive read failed: ${e.message}")
            }
        }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Drive API error ($code): $detail")
    }
}
