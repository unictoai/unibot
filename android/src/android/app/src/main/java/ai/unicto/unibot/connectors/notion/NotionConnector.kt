package ai.unicto.unibot.connectors.notion

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
 * Notion connector (internal integration token).
 *
 * The user creates an integration at notion.so/my-account/integrations,
 * shares pages with it, and pastes the token in Settings → Connectors.
 * The token is encrypted on-device and only ever sent to api.notion.com.
 */
object NotionConnector {

    val store = TokenStore("notion")

    private const val TAG = "NotionConnector"
    private const val BASE = "https://api.notion.com/v1"
    private const val VERSION = "2022-06-28"

    data class Page(val id: String, val title: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Notion is not connected. Ask the user to add an integration token in Settings → Connectors.") :
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

    /** Validate a token: returns the bot name, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$BASE/users/me")
                    .header("Authorization", "Bearer ${token.trim()}")
                    .header("Notion-Version", VERSION)
                    .get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string().orEmpty()).optString("name", "Notion").ifBlank { "Notion" }
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

    private fun authed(context: Context, url: String): Request.Builder? {
        val t = store.getToken(context) ?: return null
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $t")
            .header("Notion-Version", VERSION)
            .header("Content-Type", "application/json")
    }

    /** Search pages/databases shared with the integration. */
    suspend fun search(context: Context, query: String): ApiResult<List<Page>> =
        withContext(Dispatchers.IO) {
            val base = authed(context, "$BASE/search") ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val payload = JSONObject().apply {
                    put("query", query)
                    put("page_size", 10)
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(base.post(payload).build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    val results = JSONObject(text).optJSONArray("results") ?: JSONArray()
                    ApiResult.Ok((0 until results.length()).mapNotNull { i ->
                        val p = results.optJSONObject(i) ?: return@mapNotNull null
                        Page(
                            id = p.optString("id", "").replace("-", ""),
                            title = pageTitle(p),
                        )
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("Notion search failed: ${e.message}")
            }
        }

    /** Read a page's block children as plain text. */
    suspend fun readPage(context: Context, pageId: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val req = authed(context, "$BASE/blocks/${pageId.replace("-", "")}/children?page_size=50")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(req.get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    val results = JSONObject(text).optJSONArray("results") ?: JSONArray()
                    val out = buildString {
                        for (i in 0 until results.length()) {
                            val b = results.optJSONObject(i) ?: continue
                            val type = b.optString("type", "")
                            val data = b.optJSONObject(type) ?: continue
                            val line = richText(data.optJSONArray("rich_text"))
                            if (line.isNotBlank()) appendLine(line)
                        }
                    }
                    ApiResult.Ok(out.ifBlank { "(page is empty)" }.take(30_000))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[read] ${e.message}")
                ApiResult.Error("Notion read failed: ${e.message}")
            }
        }

    /** Append a paragraph block to a page. */
    suspend fun appendBlock(context: Context, pageId: String, text: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val base = authed(context, "$BASE/blocks/${pageId.replace("-", "")}/children")
                ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val payload = JSONObject().apply {
                    put("children", JSONArray().apply {
                        put(JSONObject().apply {
                            put("object", "block")
                            put("type", "paragraph")
                            put("paragraph", JSONObject().apply {
                                put("rich_text", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", JSONObject().apply { put("content", text) })
                                    })
                                })
                            })
                        })
                    })
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(base.patch(payload).build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, body)
                    ApiResult.Ok("Appended to the Notion page.")
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[append] ${e.message}")
                ApiResult.Error("Notion append failed: ${e.message}")
            }
        }

    private fun pageTitle(p: JSONObject): String {
        val props = p.optJSONObject("properties") ?: return p.optString("id", "")
        val keys = props.keys()
        while (keys.hasNext()) {
            val prop = props.optJSONObject(keys.next()) ?: continue
            if (prop.optString("type", "") == "title") {
                val t = richText(prop.optJSONArray("title"))
                if (t.isNotBlank()) return t
            }
        }
        return "(untitled)"
    }

    private fun richText(arr: JSONArray?): String {
        if (arr == null) return ""
        return buildString {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optJSONObject("text")?.optString("content", "")
                if (!t.isNullOrBlank()) append(t)
            }
        }.toString()
    }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching { JSONObject(body).optString("message", "") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Notion API error ($code): $detail")
    }
}
