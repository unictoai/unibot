package ai.unicto.unibot.connectors.trello

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Trello connector (API key + token).
 *
 * The user gets both at https://trello.com/app-key ("Power-Up Admin Portal"
 * → generate key, then the Token link). Stored as "key:token" in the
 * encrypted [TokenStore]; only ever sent to api.trello.com.
 */
object TrelloConnector {

    val store = TokenStore("trello")

    private const val TAG = "TrelloConnector"
    private const val BASE = "https://api.trello.com/1"

    data class Board(val id: String, val name: String)
    data class TrelloList(val id: String, val name: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Trello is not connected. Ask the user to add the API key + token in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun creds(context: Context): Pair<String, String>? {
        val raw = store.getToken(context) ?: return null
        val i = raw.indexOf(':')
        if (i <= 0) return null
        return raw.substring(0, i) to raw.substring(i + 1)
    }

    fun isConnected(context: Context): Boolean = creds(context) != null

    private fun auth(context: Context): String? {
        val (k, t) = creds(context) ?: return null
        return "key=${enc(k)}&token=${enc(t)}"
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** Validate key+token: returns the member's full name, or null when invalid. */
    suspend fun validate(context: Context, key: String, token: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url("$BASE/members/me?key=${enc(key.trim())}&token=${enc(token.trim())}")
                        .get().build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    JSONObject(resp.body?.string().orEmpty()).optString("fullName", "Trello")
                        .ifBlank { "Trello" }
                }
            }.getOrNull()
        }

    suspend fun connect(context: Context, key: String, token: String): String? {
        val name = validate(context, key, token) ?: return null
        store.setToken(context, "${key.trim()}:${token.trim()}")
        store.setLabel(context, name)
        return name
    }

    fun disconnect(context: Context) = store.clear(context)

    /** Boards of the member. */
    suspend fun boards(context: Context): ApiResult<List<Board>> = withContext(Dispatchers.IO) {
        val a = auth(context) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(Request.Builder().url("$BASE/members/me/boards?$a&fields=name").get().build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Trello error (${resp.code}).")
                    val arr = JSONArray(text)
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        Board(o.optString("id", ""), o.optString("name", "(unnamed)"))
                    })
                }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[boards] ${e.message}")
            ApiResult.Error("Trello boards failed: ${e.message}")
        }
    }

    /** Lists of a board. */
    suspend fun lists(context: Context, boardId: String): ApiResult<List<TrelloList>> =
        withContext(Dispatchers.IO) {
            val a = auth(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(
                    Request.Builder().url("$BASE/boards/${enc(boardId)}/lists?$a&fields=name").get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Trello error (${resp.code}).")
                    val arr = JSONArray(text)
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        TrelloList(o.optString("id", ""), o.optString("name", "(unnamed)"))
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[lists] ${e.message}")
                ApiResult.Error("Trello lists failed: ${e.message}")
            }
        }

    /** Add a card to a list. */
    suspend fun addCard(context: Context, listId: String, name: String, desc: String = ""): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val a = auth(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                val payload = JSONObject().apply {
                    put("idList", listId)
                    put("name", name)
                    if (desc.isNotBlank()) put("desc", desc)
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(
                    Request.Builder().url("$BASE/cards?$a").post(payload).build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Trello error (${resp.code}).")
                    val shortUrl = runCatching { JSONObject(text).optString("shortUrl", "") }.getOrNull().orEmpty()
                    ApiResult.Ok("Card created${if (shortUrl.isNotBlank()) ": $shortUrl" else "."}")
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[addCard] ${e.message}")
                ApiResult.Error("Trello add card failed: ${e.message}")
            }
        }
}
