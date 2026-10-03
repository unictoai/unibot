package ai.unicto.unibot.connectors.onedrive

import android.content.Context
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
 * OneDrive connector (Microsoft Graph, OAuth 2.0 + PKCE).
 *
 * Read-only: list folders and search files. Tokens encrypted on-device.
 * Uses the same Azure app registration as the Outlook connector.
 */
object OneDriveConnector {

    val store = OneDriveTokenStore()

    private const val TAG = "OneDriveConnector"
    private const val GRAPH = "https://graph.microsoft.com/v1.0"

    data class Entry(val name: String, val isFolder: Boolean, val size: Long)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "OneDrive is not connected. Ask the user to connect it in Settings → Connectors.") :
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

    fun isConfigured(): Boolean = OneDriveOAuth.isConfigured()

    suspend fun authorize(context: Context): OneDriveOAuth.Result {
        val r = OneDriveOAuth.authorize(context)
        if (r is OneDriveOAuth.Result.Success) {
            store.setTokens(context, r.tokens)
            store.setAccountEmail(context, r.accountEmail)
        }
        return r
    }

    suspend fun disconnect(context: Context) = OneDriveOAuth.disconnect(context, store)

    private suspend fun token(context: Context): String? =
        OneDriveOAuth.validAccessToken(context, store)

    private fun authed(url: String, accessToken: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()

    private fun parseEntries(text: String): List<Entry> {
        val arr = runCatching { JSONObject(text).optJSONArray("value") }.getOrNull() ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "")
            if (name.isBlank()) return@mapNotNull null
            Entry(name = name, isFolder = o.has("folder"), size = o.optLong("size", 0L))
        }
    }

    /** List a folder ("" = root). */
    suspend fun list(context: Context, path: String = ""): ApiResult<List<Entry>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            val seg = if (path.isBlank()) "root" else "root:${path.trim('/')}"
            runCatching {
                http.newCall(
                    authed("$GRAPH/me/drive/$seg/children?\$top=50&\$orderby=name", t),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("OneDrive error (${resp.code}).")
                    ApiResult.Ok(parseEntries(text))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[list] ${e.message}")
                ApiResult.Error("OneDrive list failed: ${e.message}")
            }
        }

    /** Search files by name. */
    suspend fun search(context: Context, query: String): ApiResult<List<Entry>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            if (query.isBlank()) return@withContext ApiResult.Error("Give a search query.")
            runCatching {
                http.newCall(
                    authed(
                        "$GRAPH/me/drive/root/search(q='${URLEncoder.encode(query, "UTF-8")}')?\$top=25",
                        t,
                    ),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("OneDrive error (${resp.code}).")
                    ApiResult.Ok(parseEntries(text))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("OneDrive search failed: ${e.message}")
            }
        }
}
