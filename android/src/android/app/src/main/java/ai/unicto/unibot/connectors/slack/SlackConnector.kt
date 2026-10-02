package ai.unicto.unibot.connectors.slack

import android.content.Context
import ai.unicto.unibot.connectors.token.TokenStore
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Slack connector (bot token, xoxb-…).
 *
 * The user creates a Slack app, installs it to their workspace with the
 * chat:write / channels:history (or groups:history) scopes, and pastes the
 * Bot User OAuth Token in Settings → Connectors. The agent can send
 * messages and read recent channel history. The token is encrypted
 * on-device and only ever sent to slack.com.
 */
object SlackConnector {

    val store = TokenStore("slack")

    private const val TAG = "SlackConnector"
    private const val BASE = "https://slack.com/api"

    data class SlackMessage(val user: String, val text: String, val ts: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Slack is not connected. Ask the user to add a bot token in Settings → Connectors.") :
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

    /** Validate a bot token: returns the bot user name, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("$BASE/auth.test")
                    .header("Authorization", "Bearer ${token.trim()}")
                    .post(FormBody.Builder().build()).build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string().orEmpty())
                if (!j.optBoolean("ok", false)) return@withContext null
                val name = j.optString("user", "")
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
        return Request.Builder().url(url).header("Authorization", "Bearer $t")
    }

    private fun <T> slackError(body: String): ApiResult<T> {
        val err = runCatching { JSONObject(body).optString("error", "") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] $err")
        return ApiResult.Error("Slack API error: $err")
    }

    /** Read recent messages from a channel (channel ID, e.g. C0123456). */
    suspend fun readChannel(
        context: Context,
        channelId: String,
        limit: Int = 10,
    ): ApiResult<List<SlackMessage>> = withContext(Dispatchers.IO) {
        val req = authed(
            context,
            "$BASE/conversations.history?channel=${android.net.Uri.encode(channelId)}" +
                "&limit=${limit.coerceIn(1, 30)}",
        ) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(req.get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext slackError(text)
                val j = JSONObject(text)
                if (!j.optBoolean("ok", false)) return@withContext slackError(text)
                val arr = j.optJSONArray("messages") ?: JSONArray()
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val body = o.optString("text", "")
                    if (body.isBlank() || o.optString("subtype", "") == "channel_join") {
                        return@mapNotNull null
                    }
                    SlackMessage(
                        user = o.optString("user", o.optString("username", "?")),
                        text = body,
                        ts = o.optString("ts", ""),
                    )
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[read] ${e.message}")
            ApiResult.Error("Slack read failed: ${e.message}")
        }
    }

    /** Send a message to a channel (channel ID, e.g. C0123456). */
    suspend fun send(
        context: Context,
        channelId: String,
        text: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val req = authed(context, "$BASE/chat.postMessage")
            ?: return@withContext ApiResult.NotConnected()
        runCatching {
            val form = FormBody.Builder()
                .add("channel", channelId)
                .add("text", text.take(4000))
                .build()
            http.newCall(req.post(form).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext slackError(body)
                if (!JSONObject(body).optBoolean("ok", false)) return@withContext slackError(body)
                ApiResult.Ok("Sent to Slack.")
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[send] ${e.message}")
            ApiResult.Error("Slack send failed: ${e.message}")
        }
    }
}
