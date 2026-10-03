package ai.unicto.unibot.connectors.matrix

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Matrix connector (client-server API, password login).
 *
 * Homeserver URL, username and password are used once to obtain an
 * access token; the token (and a generated device id) are stored
 * encrypted on-device and the password is discarded. Supports listing
 * joined rooms, reading recent messages and sending messages.
 */
object MatrixConnector {

    private const val TAG = "MatrixConnector"
    private const val PREFS_FILE = "matrix_connector"

    private const val KEY_HOMESERVER = "homeserver"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_DEVICE = "device_id"

    data class Room(val id: String, val name: String)
    data class Message(
        val sender: String,
        val senderName: String?,
        val body: String,
        val timestampMs: Long,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Matrix is not connected. Sign in with your homeserver, username and password in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_FILE)
                .also { prefsRef = it }
        }

    fun isConnected(context: Context): Boolean =
        prefs(context).getString(KEY_TOKEN, null)?.isNotEmpty() == true

    fun userId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }

    fun homeserver(context: Context): String? =
        prefs(context).getString(KEY_HOMESERVER, null)?.takeIf { it.isNotBlank() }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Password login. On success the access token is stored and the
     * password is never persisted. Returns null on success, or an error.
     */
    suspend fun connect(
        context: Context,
        homeserver: String,
        username: String,
        password: String,
    ): String? = withContext(Dispatchers.IO) {
        var hs = homeserver.trim().trimEnd('/')
        if (hs.isEmpty()) hs = "https://matrix.org"
        if (!hs.matches(Regex("https?://.+"))) return@withContext "That doesn't look like a valid homeserver URL."
        val user = username.trim()
        if (user.isEmpty()) return@withContext "Enter your Matrix username."
        if (password.isEmpty()) return@withContext "Enter your Matrix password."
        val deviceId = "UNIBOT" + UUID.randomUUID().toString().take(8).uppercase()
        val payload = JSONObject()
            .put("type", "m.login.password")
            .put("identifier", JSONObject().put("type", "m.id.user").put("user", user))
            .put("password", password)
            .put("device_id", deviceId)
            .put("initial_device_display_name", "unibot")
            .toString()
        try {
            http.newCall(
                Request.Builder().url("$hs/_matrix/client/v3/login")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 401 || resp.code == 403) {
                    val err = JSONObject(body).optString("error")
                    return@withContext if (err.contains("Unknown", ignoreCase = true))
                        "Login rejected — wrong username or password."
                    else "Login rejected: ${err.ifBlank { "check your username and password" }}."
                }
                if (!resp.isSuccessful) return@withContext "Homeserver answered HTTP ${resp.code}."
                val json = JSONObject(body)
                val token = json.optString("access_token")
                val uid = json.optString("user_id")
                if (token.isBlank() || uid.isBlank()) return@withContext "Unexpected login response."
                prefs(context).edit().apply {
                    putString(KEY_HOMESERVER, hs)
                    putString(KEY_USER_ID, uid)
                    putString(KEY_TOKEN, token)
                    putString(KEY_DEVICE, deviceId)
                }.apply()
                null
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[connect] ${t.message}")
            "Couldn't reach $hs (${t.message ?: "connection failed"})."
        }
    }

    /** Log out (revokes the token server-side) and wipe local state. */
    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val hs = homeserver(context)
        val token = prefs(context).getString(KEY_TOKEN, null)
        if (hs != null && !token.isNullOrEmpty()) {
            runCatching {
                http.newCall(
                    Request.Builder().url("$hs/_matrix/client/v3/logout")
                        .header("Authorization", "Bearer $token")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute().close()
            }
        }
        prefs(context).edit().clear().apply()
    }

    private data class Creds(val hs: String, val token: String)

    private fun creds(context: Context): Creds? {
        val hs = homeserver(context) ?: return null
        val token = prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: return null
        return Creds(hs, token)
    }

    private fun get(c: Creds, path: String): Pair<Int, String> {
        http.newCall(
            Request.Builder().url(c.hs + path)
                .header("Authorization", "Bearer ${c.token}")
                .get().build(),
        ).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun check(code: Int, body: String): String? = when (code) {
        200 -> null
        401, 403 -> "Session expired — sign in again in Settings → Connectors."
        404 -> "Not found."
        else -> JSONObject(body).optString("error").ifBlank { "Homeserver answered HTTP $code." }
    }

    private fun roomName(c: Creds, roomId: String): String {
        // Try the room name state event; fall back to a short id.
        return try {
            val (code, body) = get(c, "/_matrix/client/v3/rooms/${enc(roomId)}/state/m.room.name/")
            if (code == 200) {
                JSONObject(body).optString("name").ifBlank { shortId(roomId) }
            } else shortId(roomId)
        } catch (t: Throwable) {
            shortId(roomId)
        }
    }

    private fun shortId(roomId: String): String =
        roomId.substringBefore(":").take(18).ifBlank { roomId }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Rooms the user has joined, with display names. */
    suspend fun rooms(context: Context): ApiResult<List<Room>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                val (code, body) = get(c, "/_matrix/client/v3/joined_rooms")
                check(code, body)?.let { return@withContext ApiResult.Error(it) }
                val arr = JSONObject(body).optJSONArray("joined_rooms") ?: JSONArray()
                val out = (0 until arr.length()).mapNotNull { i ->
                    val id = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Room(id, roomName(c, id))
                }
                ApiResult.Ok(out)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[rooms] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }

    /** Recent messages in a room (oldest → newest). */
    suspend fun recentMessages(
        context: Context,
        roomId: String,
        limit: Int = 20,
    ): ApiResult<List<Message>> = withContext(Dispatchers.IO) {
        val c = creds(context) ?: return@withContext ApiResult.NotConnected()
        try {
            val (code, body) = get(
                c,
                "/_matrix/client/v3/rooms/${enc(roomId)}/messages?dir=b&limit=${limit.coerceIn(1, 50)}",
            )
            check(code, body)?.let { return@withContext ApiResult.Error(it) }
            val chunk = JSONObject(body).optJSONArray("chunk") ?: JSONArray()
            val out = (0 until chunk.length()).mapNotNull { i ->
                val e = chunk.optJSONObject(i) ?: return@mapNotNull null
                if (e.optString("type") != "m.room.message") return@mapNotNull null
                val content = e.optJSONObject("content") ?: return@mapNotNull null
                val msgBody = content.optString("body").ifBlank { return@mapNotNull null }
                if (content.optString("msgtype") == "m.notice") return@mapNotNull null
                Message(
                    sender = e.optString("sender"),
                    senderName = null,
                    body = msgBody.take(500),
                    timestampMs = e.optLong("origin_server_ts"),
                )
            }.reversed()
            ApiResult.Ok(out)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[messages] ${t.message}")
            ApiResult.Error(t.message ?: "request failed")
        }
    }

    /** Send a plain-text message to a room. */
    suspend fun sendMessage(
        context: Context,
        roomId: String,
        text: String,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val c = creds(context) ?: return@withContext ApiResult.NotConnected()
        val msg = text.trim()
        if (msg.isEmpty()) return@withContext ApiResult.Error("Type a message first.")
        try {
            val txnId = UUID.randomUUID().toString()
            val payload = JSONObject()
                .put("msgtype", "m.text")
                .put("body", msg)
                .toString()
            http.newCall(
                Request.Builder()
                    .url("${c.hs}/_matrix/client/v3/rooms/${enc(roomId)}/send/m.room.message/$txnId")
                    .header("Authorization", "Bearer ${c.token}")
                    .put(payload.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                check(resp.code, body)?.let { return@withContext ApiResult.Error(it) }
                ApiResult.Ok(Unit)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[send] ${t.message}")
            ApiResult.Error(t.message ?: "request failed")
        }
    }
}
