package ai.unicto.unibot.connectors.telegram

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
 * Telegram connector (bot token).
 *
 * The user creates a bot with @BotFather, pastes the token in Settings →
 * Connectors, then starts a chat with the bot. The agent can send messages
 * through the bot and read messages sent to it. The token is encrypted
 * on-device and only ever sent to api.telegram.org.
 *
 * Note: this is a *bot* connector — it cannot read the user's personal
 * chats, only conversations with the bot itself.
 */
object TelegramConnector {

    val store = TokenStore("telegram")

    private const val TAG = "TelegramConnector"
    private const val KEY_CHAT_ID = "chat_id"

    data class BotMessage(val chatId: Long, val from: String, val text: String, val date: Long)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Telegram is not connected. Ask the user to add a bot token in Settings → Connectors.") :
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

    private fun base(context: Context): String? =
        store.getToken(context)?.let { "https://api.telegram.org/bot${it.trim()}" }

    /** Validate a bot token: returns the bot username, or null when invalid. */
    suspend fun validate(context: Context, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("https://api.telegram.org/bot${token.trim()}/getMe").get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val j = JSONObject(resp.body?.string().orEmpty())
                if (!j.optBoolean("ok", false)) return@withContext null
                j.optJSONObject("result")?.optString("username", "")?.ifBlank { null }
            }
        }.getOrNull()
    }

    suspend fun connect(context: Context, token: String): String? {
        val username = validate(context, token) ?: return null
        store.setToken(context, token)
        store.setLabel(context, "@$username")
        return "@$username"
    }

    fun disconnect(context: Context) {
        store.clear(context)
        context.getSharedPreferences("telegram_connector_state", Context.MODE_PRIVATE)
            .edit().remove(KEY_CHAT_ID).apply()
    }

    private fun chatId(context: Context): Long =
        context.getSharedPreferences("telegram_connector_state", Context.MODE_PRIVATE)
            .getLong(KEY_CHAT_ID, 0L)

    private fun setChatId(context: Context, id: Long) {
        context.getSharedPreferences("telegram_connector_state", Context.MODE_PRIVATE)
            .edit().putLong(KEY_CHAT_ID, id).apply()
    }

    /**
     * Find the most recent chat the bot has seen (the user must message the
     * bot first). Stores it for [send].
     */
    suspend fun resolveChat(context: Context): ApiResult<Long> = withContext(Dispatchers.IO) {
        val b = base(context) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            http.newCall(Request.Builder().url("$b/getUpdates?limit=20").get().build())
                .execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Telegram error (${resp.code})")
                    val arr = JSONObject(text).optJSONArray("result") ?: JSONArray()
                    var lastId = 0L
                    var lastFrom = ""
                    for (i in 0 until arr.length()) {
                        val msg = arr.optJSONObject(i)?.optJSONObject("message") ?: continue
                        val chat = msg.optJSONObject("chat") ?: continue
                        lastId = chat.optLong("id", 0L)
                        val from = msg.optJSONObject("from")
                        lastFrom = from?.optString("username", "").orEmpty()
                            .ifBlank { from?.optString("first_name", "").orEmpty() }
                    }
                    if (lastId == 0L) {
                        return@withContext ApiResult.Error(
                            "No chats found — message your bot on Telegram first, then try again.",
                        )
                    }
                    setChatId(context, lastId)
                    AppLogger.info(TAG, "[resolveChat] id=$lastId from=$lastFrom")
                    ApiResult.Ok(lastId)
                }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[resolveChat] ${e.message}")
            ApiResult.Error("Telegram resolve chat failed: ${e.message}")
        }
    }

    /** Send a message through the bot to the resolved chat. */
    suspend fun send(context: Context, text: String, chatId: Long = 0L): ApiResult<String> =
        withContext(Dispatchers.IO) {
            val b = base(context) ?: return@withContext ApiResult.NotConnected()
            val target = chatId.takeIf { it != 0L } ?: chatId(context)
            if (target == 0L) {
                return@withContext ApiResult.Error(
                    "No chat selected — use telegram_resolve first (message the bot, then resolve).",
                )
            }
            runCatching {
                val payload = JSONObject().apply {
                    put("chat_id", target)
                    put("text", text.take(4000))
                }.toString().toRequestBody("application/json".toMediaType())
                http.newCall(Request.Builder().url("$b/sendMessage").post(payload).build())
                    .execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val detail = runCatching {
                                JSONObject(body).optString("description", "")
                            }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
                            return@withContext ApiResult.Error("Telegram send failed (${resp.code}): $detail")
                        }
                        ApiResult.Ok("Sent via Telegram.")
                    }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[send] ${e.message}")
                ApiResult.Error("Telegram send failed: ${e.message}")
            }
        }

    /** Recent messages sent TO the bot. */
    suspend fun recentMessages(context: Context, limit: Int = 10): ApiResult<List<BotMessage>> =
        withContext(Dispatchers.IO) {
            val b = base(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(
                    Request.Builder().url("$b/getUpdates?limit=${limit.coerceIn(1, 30)}").get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Telegram error (${resp.code})")
                    val arr = JSONObject(text).optJSONArray("result") ?: JSONArray()
                    val out = mutableListOf<BotMessage>()
                    for (i in 0 until arr.length()) {
                        val msg = arr.optJSONObject(i)?.optJSONObject("message") ?: continue
                        val body = msg.optString("text", "")
                        if (body.isBlank()) continue
                        val chat = msg.optJSONObject("chat") ?: continue
                        val from = msg.optJSONObject("from")
                        out.add(
                            BotMessage(
                                chatId = chat.optLong("id", 0L),
                                from = from?.optString("username", "")?.ifBlank { null }
                                    ?: from?.optString("first_name", "?").orEmpty(),
                                text = body,
                                date = msg.optLong("date", 0L),
                            ),
                        )
                    }
                    ApiResult.Ok(out)
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[recent] ${e.message}")
                ApiResult.Error("Telegram read failed: ${e.message}")
            }
        }
}
