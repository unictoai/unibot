package ai.unicto.unibot.connectors.gitlab

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
 * GitLab connector (personal access token).
 *
 * Token created at gitlab.com → Preferences → Access Tokens (needs at
 * least `read_api`). Stored encrypted; only ever sent to gitlab.com.
 */
object GitLabConnector {

    val store = TokenStore("gitlab")

    private const val TAG = "GitLabConnector"
    private const val BASE = "https://gitlab.com/api/v4"

    data class Project(val id: Long, val name: String, val url: String)
    data class Issue(val id: Long, val title: String, val state: String, val url: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "GitLab is not connected. Ask the user to add a personal access token in Settings → Connectors.") :
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
        return Request.Builder().url(url).header("PRIVATE-TOKEN", t)
    }

    /** Validate a token: returns the username, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$BASE/user")
                    .header("PRIVATE-TOKEN", token.trim())
                    .get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string().orEmpty()).optString("username", "GitLab").ifBlank { "GitLab" }
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

    /** Projects the user is a member of. */
    suspend fun projects(context: Context): ApiResult<List<Project>> = withContext(Dispatchers.IO) {
        val base = authed(context, "$BASE/projects?membership=true&per_page=20&order_by=last_activity_at")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(base.get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext ApiResult.Error("GitLab error (${resp.code}).")
                val arr = JSONArray(text)
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Project(o.optLong("id", 0), o.optString("name", "(unnamed)"), o.optString("web_url", ""))
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[projects] ${e.message}")
            ApiResult.Error("GitLab projects failed: ${e.message}")
        }
    }

    /** Open issues assigned to / authored by the user. */
    suspend fun issues(context: Context, state: String = "opened"): ApiResult<List<Issue>> =
        withContext(Dispatchers.IO) {
            val base = authed(context, "$BASE/issues?scope=all&state=${URLEncoder.encode(state, "UTF-8")}&per_page=20")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(base.get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("GitLab error (${resp.code}).")
                    val arr = JSONArray(text)
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        Issue(o.optLong("id", 0), o.optString("title", "(untitled)"), o.optString("state", ""), o.optString("web_url", ""))
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[issues] ${e.message}")
                ApiResult.Error("GitLab issues failed: ${e.message}")
            }
        }
}
