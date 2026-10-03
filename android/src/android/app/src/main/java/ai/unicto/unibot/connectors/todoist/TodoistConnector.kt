package ai.unicto.unibot.connectors.todoist

import android.content.Context
import ai.unicto.unibot.connectors.token.TokenStore
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
 * Todoist connector (personal API token).
 *
 * The user creates a token at todoist.com → Settings → Integrations →
 * Developer. Stored encrypted on-device; only ever sent to api.todoist.com.
 */
object TodoistConnector {

    val store = TokenStore("todoist")

    private const val TAG = "TodoistConnector"
    private const val BASE = "https://api.todoist.com/api/v2"

    data class Task(val id: String, val content: String, val due: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Todoist is not connected. Ask the user to add an API token in Settings → Connectors.") :
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

    private fun authed(context: Context, url: String): Request.Builder? {
        val t = store.getToken(context) ?: return null
        return Request.Builder().url(url).header("Authorization", "Bearer $t")
    }

    /** Validate a token: returns the user's name, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$BASE/user")
                    .header("Authorization", "Bearer ${token.trim()}")
                    .get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string().orEmpty()).optString("name", "Todoist").ifBlank { "Todoist" }
            }
        }.getOrNull()
    }

    suspend fun connect(context: Context, token: String): String? {
        val name = validate(context, token) ?: return null
        store.setToken(context, token)
        store.setLabel(context, name)
        return name
    }

    fun disconnect(context: Context) = store.clear(context)

    /** Open tasks. */
    suspend fun tasks(context: Context): ApiResult<List<Task>> = withContext(Dispatchers.IO) {
        val base = authed(context, "$BASE/tasks") ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(base.get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext ApiResult.Error("Todoist error (${resp.code}).")
                val arr = JSONArray(text)
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Task(
                        id = o.optString("id", ""),
                        content = o.optString("content", "(no title)"),
                        due = o.optJSONObject("due")?.optString("string", "").orEmpty(),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[tasks] ${e.message}")
            ApiResult.Error("Todoist tasks failed: ${e.message}")
        }
    }

    /** Add a task; [dueString] accepts natural language ("tomorrow 9am"). */
    suspend fun add(context: Context, content: String, dueString: String = ""): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val base = authed(context, "$BASE/tasks") ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val payload = JSONObject().apply {
                    put("content", content)
                    if (dueString.isNotBlank()) put("due_string", dueString)
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(base.post(payload).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Todoist error (${resp.code}).")
                    ApiResult.Ok("Task added to Todoist.")
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[add] ${e.message}")
                ApiResult.Error("Todoist add failed: ${e.message}")
            }
        }

    /** Complete a task. */
    suspend fun complete(context: Context, taskId: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val base = authed(context, "$BASE/tasks/$taskId/close")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(
                    base.post("".toRequestBody("application/json".toMediaType())).build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 204) {
                        return@withContext ApiResult.Error("Todoist error (${resp.code}).")
                    }
                    ApiResult.Ok("Task completed.")
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[complete] ${e.message}")
                ApiResult.Error("Todoist complete failed: ${e.message}")
            }
        }
}
