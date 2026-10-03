package ai.unicto.unibot.connectors.gtasks

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Tasks connector (same Google OAuth client as Gmail/Drive/Photos —
 * no extra setup beyond connecting).
 *
 * Requires the Tasks API enabled in the Cloud project (unibot-510412);
 * without it calls fail with 403.
 */
object GTasksConnector {

    const val SCOPES = "https://www.googleapis.com/auth/tasks"
    val store = GoogleTokenStore("gtasks")

    private const val TAG = "GTasksConnector"
    private const val BASE = "https://tasks.googleapis.com/tasks/v1"

    data class TaskList(val id: String, val title: String)
    data class Task(val id: String, val title: String, val due: String, val status: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Google Tasks is not connected. Ask the user to connect it in Settings → Connectors.") :
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
        val r = GoogleOAuth.authorize(context, SCOPES, "gtasks")
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

    /** Task lists. */
    suspend fun lists(context: Context): ApiResult<List<TaskList>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(authed("$BASE/users/@me/lists?maxResults=20", t).get().build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Google Tasks error (${resp.code}).")
                    val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        TaskList(o.optString("id", ""), o.optString("title", "(unnamed)"))
                    })
                }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[lists] ${e.message}")
            ApiResult.Error("Google Tasks failed: ${e.message}")
        }
    }

    /** Tasks in a list ("@default" for the default list). */
    suspend fun tasks(context: Context, listId: String = "@default"): ApiResult<List<Task>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val url = "$BASE/lists/${URLEncoder.encode(listId, "UTF-8")}/tasks" +
                    "?maxResults=50&showCompleted=false"
                http.newCall(authed(url, t).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Google Tasks error (${resp.code}).")
                    val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        Task(
                            id = o.optString("id", ""),
                            title = o.optString("title", "(no title)"),
                            due = o.optString("due", "").take(10),
                            status = o.optString("status", ""),
                        )
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[tasks] ${e.message}")
                ApiResult.Error("Google Tasks failed: ${e.message}")
            }
        }

    /** Add a task to a list. */
    suspend fun add(context: Context, title: String, listId: String = "@default", dueRfc3339: String = ""): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val payload = JSONObject().apply {
                    put("title", title)
                    if (dueRfc3339.isNotBlank()) put("due", dueRfc3339)
                }.toString().toRequestBody("application/json".toMediaType())
                val url = "$BASE/lists/${URLEncoder.encode(listId, "UTF-8")}/tasks"
                http.newCall(authed(url, t).post(payload).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Google Tasks error (${resp.code}).")
                    ApiResult.Ok("Task added to Google Tasks.")
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[add] ${e.message}")
                ApiResult.Error("Google Tasks add failed: ${e.message}")
            }
        }
}
