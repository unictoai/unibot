package ai.unicto.unibot.connectors.github

import android.content.Context
import android.util.Base64
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
 * GitHub connector (personal access token).
 *
 * The user creates a fine-grained or classic PAT (repo scope for private
 * repos) at github.com/settings/tokens and pastes it in Settings →
 * Connectors. The token is encrypted on-device and only ever sent to
 * api.github.com.
 */
object GitHubConnector {

    val store = TokenStore("github")

    private const val TAG = "GitHubConnector"
    private const val BASE = "https://api.github.com"

    data class Repo(val fullName: String, val description: String, val isPrivate: Boolean)
    data class Issue(val number: Int, val title: String, val state: String, val url: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "GitHub is not connected. Ask the user to add a personal access token in Settings → Connectors.") :
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

    /** Validate a token: returns the login, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$BASE/user")
                    .header("Authorization", "Bearer ${token.trim()}")
                    .header("Accept", "application/vnd.github+json")
                    .get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string().orEmpty()).optString("login", "").ifBlank { null }
            }
        }.getOrNull()
    }

    suspend fun connect(context: Context, token: String): String? {
        val login = validate(context, token) ?: return null
        store.setToken(context, token)
        store.setLabel(context, login)
        return login
    }

    fun disconnect(context: Context) = store.clear(context)

    private fun authed(context: Context, url: String): Request.Builder? {
        val t = store.getToken(context) ?: return null
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json")
    }

    suspend fun listRepos(context: Context): ApiResult<List<Repo>> = withContext(Dispatchers.IO) {
        val req = authed(context, "$BASE/user/repos?per_page=100&sort=updated")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(req.get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val arr = JSONArray(text)
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    Repo(
                        fullName = r.optString("full_name", ""),
                        description = r.optString("description", ""),
                        isPrivate = r.optBoolean("private", false),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[repos] ${e.message}")
            ApiResult.Error("GitHub repos failed: ${e.message}")
        }
    }

    /** Read a file from a repo (owner/name, path). Returns decoded text. */
    suspend fun readFile(context: Context, repo: String, path: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val encPath = path.split("/").joinToString("/") { android.net.Uri.encode(it) }
            val req = authed(context, "$BASE/repos/$repo/contents/$encPath")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(req.get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    val j = JSONObject(text)
                    if (j.optString("type", "") != "file") {
                        return@withContext ApiResult.Error("Not a file: $path")
                    }
                    val content = j.optString("content", "").replace("\n", "")
                    val decoded = runCatching {
                        Base64.decode(content, Base64.DEFAULT).toString(Charsets.UTF_8)
                    }.getOrDefault("")
                    if (decoded.length > 60_000) {
                        ApiResult.Ok("[${decoded.length} chars — showing first 60,000]\n\n" + decoded.take(60_000))
                    } else {
                        ApiResult.Ok(decoded)
                    }
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[read] ${e.message}")
                ApiResult.Error("GitHub read failed: ${e.message}")
            }
        }

    suspend fun listIssues(context: Context, repo: String): ApiResult<List<Issue>> =
        withContext(Dispatchers.IO) {
            val req = authed(context, "$BASE/repos/$repo/issues?per_page=30&state=open")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(req.get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    val arr = JSONArray(text)
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        if (o.has("pull_request")) return@mapNotNull null // skip PRs
                        Issue(
                            number = o.optInt("number", 0),
                            title = o.optString("title", ""),
                            state = o.optString("state", ""),
                            url = o.optString("html_url", ""),
                        )
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[issues] ${e.message}")
                ApiResult.Error("GitHub issues failed: ${e.message}")
            }
        }

    suspend fun createIssue(
        context: Context,
        repo: String,
        title: String,
        body: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val req = authed(context, "$BASE/repos/$repo/issues")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            val payload = JSONObject().apply {
                put("title", title)
                put("body", body)
            }.toString().toRequestBody("application/json".toMediaType())
            http.newCall(req.post(payload).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val j = JSONObject(text)
                ApiResult.Ok("Issue #${j.optInt("number", 0)} created: ${j.optString("html_url", "")}")
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[createIssue] ${e.message}")
            ApiResult.Error("GitHub create issue failed: ${e.message}")
        }
    }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("GitHub API error ($code): $detail")
    }
}
