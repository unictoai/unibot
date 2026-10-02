package ai.unicto.unibot.connectors.discord

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
 * Discord connector (bot token).
 *
 * The user creates a bot in the Discord Developer Portal, pastes the bot
 * token in Settings → Connectors, and invites the bot to a server. The
 * agent can send messages to channels and read recent channel messages.
 * The token is encrypted on-device and only ever sent to discord.com.
 */
object DiscordConnector {

    val store = TokenStore("discord")

    private const val TAG = "DiscordConnector"
    private const val BASE = "https://discord.com/api/v10"

    data class DiscordMessage(val author: String, val text: String, val timestamp: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Discord is not connected. Ask the user to add a bot token in Settings → Connectors.") :
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

    /** Validate a bot token: returns the bot username, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$BASE/users/@me")
                    .header("Authorization", "Bot ${token.trim()}")
                    .get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string().orEmpty())
                val name = j.optString("username", "")
                if (name.isBlank()) null else name
            }
        }.getOrNull()
    }

    suspend fun connect(context: Context, token: String): String? {
        val username = validate(context, token) ?: return null
        store.setToken(context, token)
        store.setLabel(context, username)
        return username
    }

    fun disconnect(context: Context) = store.clear(context)

    private fun authed(context: Context, url: String): Request.Builder? {
        val t = store.getToken(context) ?: return null
        return Request.Builder().url(url).header("Authorization", "Bot $t")
    }

    /** Read recent messages from a channel. */
    suspend fun readChannel(
        context: Context,
        channelId: String,
        limit: Int = 10,
    ): ApiResult<List<DiscordMessage>> = withContext(Dispatchers.IO) {
        val req = authed(context, "$BASE/channels/$channelId/messages?limit=${limit.coerceIn(1, 30)}")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(req.get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val arr = JSONArray(text)
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val body = o.optString("content", "")
                    if (body.isBlank()) return@mapNotNull null
                    DiscordMessage(
                        author = o.optJSONObject("author")?.optString("username", "?").orEmpty(),
                        text = body,
                        timestamp = o.optString("timestamp", "").take(16).replace("T", " "),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[read] ${e.message}")
            ApiResult.Error("Discord read failed: ${e.message}")
        }
    }

    /** Send a message to a channel. */
    suspend fun send(
        context: Context,
        channelId: String,
        text: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val req = authed(context, "$BASE/channels/$channelId/messages")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            val payload = JSONObject().apply {
                put("content", text.take(2000))
            }.toString().toRequestBody("application/json".toMediaType())
            http.newCall(req.post(payload).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, body)
                ApiResult.Ok("Sent to Discord.")
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[send] ${e.message}")
            ApiResult.Error("Discord send failed: ${e.message}")
        }
    }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Discord API error ($code): $detail")
    }
}
