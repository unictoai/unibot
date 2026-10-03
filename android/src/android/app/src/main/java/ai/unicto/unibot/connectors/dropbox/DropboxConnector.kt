package ai.unicto.unibot.connectors.dropbox

import android.content.Context
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
 * Dropbox connector (OAuth 2.0 + PKCE, user's own Dropbox account).
 *
 * Read-only: list folders and search files. Tokens encrypted on-device.
 */
object DropboxConnector {

    val store = DropboxTokenStore()

    private const val TAG = "DropboxConnector"
    private const val RPC = "https://api.dropboxapi.com/2"

    data class Entry(val name: String, val path: String, val isFolder: Boolean, val size: Long)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Dropbox is not connected. Ask the user to connect it in Settings → Connectors.") :
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

    fun isConfigured(): Boolean = DropboxOAuth.isConfigured()

    suspend fun authorize(context: Context): DropboxOAuth.Result {
        val r = DropboxOAuth.authorize(context)
        if (r is DropboxOAuth.Result.Success) {
            store.setTokens(context, r.tokens)
            store.setAccountEmail(context, r.accountEmail)
        }
        return r
    }

    suspend fun disconnect(context: Context) = DropboxOAuth.disconnect(context, store)

    private suspend fun token(context: Context): String? =
        DropboxOAuth.validAccessToken(context, store)

    private fun rpc(path: String, accessToken: String, json: JSONObject): Request =
        Request.Builder().url("$RPC$path")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun parseEntries(arr: JSONArray): List<Entry> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val tag = o.optString(".tag", "")
            Entry(
                name = o.optString("name", "(unnamed)"),
                path = o.optString("path_lower", ""),
                isFolder = tag == "folder",
                size = o.optLong("size", 0L),
            )
        }

    /** List a folder ("" = root). */
    suspend fun list(context: Context, path: String = ""): ApiResult<List<Entry>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val payload = JSONObject().apply {
                    put("path", path)
                    put("limit", 50)
                }
                http.newCall(rpc("/files/list_folder", t, payload)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Dropbox error (${resp.code}).")
                    ApiResult.Ok(parseEntries(JSONObject(text).optJSONArray("entries") ?: JSONArray()))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[list] ${e.message}")
                ApiResult.Error("Dropbox list failed: ${e.message}")
            }
        }

    /** Search files by name. */
    suspend fun search(context: Context, query: String): ApiResult<List<Entry>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            if (query.isBlank()) return@withContext ApiResult.Error("Give a search query.")
            runCatching {
                val payload = JSONObject().apply { put("query", query) }
                http.newCall(rpc("/files/search_v2", t, payload)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Dropbox error (${resp.code}).")
                    val matches = JSONObject(text).optJSONArray("matches") ?: JSONArray()
                    val entries = JSONArray()
                    for (i in 0 until matches.length()) {
                        matches.optJSONObject(i)?.optJSONObject("metadata")?.let { entries.put(it) }
                    }
                    ApiResult.Ok(parseEntries(entries))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("Dropbox search failed: ${e.message}")
            }
        }
}
