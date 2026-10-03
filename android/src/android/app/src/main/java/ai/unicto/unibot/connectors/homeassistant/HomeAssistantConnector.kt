package ai.unicto.unibot.connectors.homeassistant

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
import java.util.concurrent.TimeUnit

/**
 * Home Assistant connector (long-lived access token).
 *
 * Server URL + token are stored encrypted on-device and only ever sent to
 * the user's own Home Assistant instance. Can read entity states and call
 * light/switch/scene/script services.
 */
object HomeAssistantConnector {

    private const val TAG = "HomeAssistantConnector"
    private const val PREFS_FILE = "homeassistant_connector"

    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"

    data class Entity(
        val id: String,
        val name: String,
        val domain: String,
        val state: String,
        val unit: String?,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Home Assistant is not connected. Add your server URL and long-lived token in Settings → Connectors.") :
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

    fun serverUrl(context: Context): String? =
        prefs(context).getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    private fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Validate the token against /api/; store on success. */
    suspend fun connect(
        context: Context,
        serverUrl: String,
        token: String,
    ): String? = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        if (!base.matches(Regex("https?://.+"))) return@withContext "That doesn't look like a valid http(s):// URL."
        val tok = token.trim()
        if (tok.isEmpty()) return@withContext "Paste your long-lived access token."
        try {
            http.newCall(
                Request.Builder().url("$base/api/")
                    .header("Authorization", "Bearer $tok")
                    .get().build(),
            ).execute().use { resp ->
                if (resp.code == 401) return@withContext "Invalid token."
                if (!resp.isSuccessful) return@withContext "Server answered HTTP ${resp.code}."
                val msg = JSONObject(resp.body?.string().orEmpty()).optString("message")
                if (!msg.contains("API running", ignoreCase = true)) {
                    return@withContext "That doesn't look like a Home Assistant server."
                }
                prefs(context).edit().apply {
                    putString(KEY_URL, base)
                    putString(KEY_TOKEN, tok)
                }.apply()
                null
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[connect] ${t.message}")
            "Couldn't reach $base (${t.message ?: "connection failed"})."
        }
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private data class Creds(val base: String, val token: String)

    private fun creds(context: Context): Creds? {
        val base = serverUrl(context) ?: return null
        val tok = token(context) ?: return null
        return Creds(base, tok)
    }

    private fun get(c: Creds, path: String): Pair<Int, String> {
        http.newCall(
            Request.Builder().url(c.base + path)
                .header("Authorization", "Bearer ${c.token}")
                .get().build(),
        ).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun post(c: Creds, path: String, json: String): Pair<Int, String> {
        http.newCall(
            Request.Builder().url(c.base + path)
                .header("Authorization", "Bearer ${c.token}")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun check(code: Int): String? = when (code) {
        200 -> null
        401 -> "Invalid token — reconnect in Settings → Connectors."
        404 -> "Not found on this server."
        else -> "Server answered HTTP $code."
    }

    private fun parseEntity(o: JSONObject): Entity? {
        val id = o.optString("entity_id").ifBlank { return null }
        val attrs = o.optJSONObject("attributes")
        return Entity(
            id = id,
            name = attrs?.optString("friendly_name").takeIf { !it.isNullOrBlank() } ?: id,
            domain = id.substringBefore("."),
            state = o.optString("state"),
            unit = attrs?.optString("unit_of_measurement")?.takeIf { it.isNotBlank() },
        )
    }

    /** All entities, or only one domain (e.g. "light", "sensor", "switch"). */
    suspend fun entities(
        context: Context,
        domain: String? = null,
    ): ApiResult<List<Entity>> = withContext(Dispatchers.IO) {
        val c = creds(context) ?: return@withContext ApiResult.NotConnected()
        try {
            val (code, body) = get(c, "/api/states")
            check(code)?.let { return@withContext ApiResult.Error(it) }
            val arr = JSONArray(body)
            ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                parseEntity(arr.optJSONObject(i) ?: return@mapNotNull null)
            }.filter { domain.isNullOrBlank() || it.domain == domain })
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[entities] ${t.message}")
            ApiResult.Error(t.message ?: "request failed")
        }
    }

    /** State of one entity (e.g. "sensor.living_room_temperature"). */
    suspend fun state(context: Context, entityId: String): ApiResult<Entity> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                val (code, body) = get(c, "/api/states/${entityId.trim()}")
                check(code)?.let { return@withContext ApiResult.Error(it) }
                parseEntity(JSONObject(body))?.let { ApiResult.Ok(it) }
                    ?: ApiResult.Error("Couldn't parse the entity state.")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[state] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }

    /**
     * Call a service, e.g. service("light", "turn_on", "light.bedroom").
     * Returns null on success, or a user-facing error.
     */
    suspend fun service(
        context: Context,
        domain: String,
        service: String,
        entityId: String? = null,
        data: JSONObject = JSONObject(),
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val c = creds(context) ?: return@withContext ApiResult.NotConnected()
        try {
            if (!entityId.isNullOrBlank()) data.put("entity_id", entityId.trim())
            val (code, _) = post(c, "/api/services/$domain/$service", data.toString())
            check(code)?.let { return@withContext ApiResult.Error(it) }
            ApiResult.Ok(Unit)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[service] ${t.message}")
            ApiResult.Error(t.message ?: "request failed")
        }
    }

    /** Toggle a light or switch on/off. */
    suspend fun toggle(
        context: Context,
        entityId: String,
        on: Boolean,
    ): ApiResult<Unit> {
        val id = entityId.trim()
        val domain = id.substringBefore(".")
        val service = when {
            domain == "light" && on -> "turn_on"
            domain == "light" -> "turn_off"
            domain == "switch" && on -> "turn_on"
            domain == "switch" -> "turn_off"
            domain == "scene" -> "turn_on"
            domain == "script" -> "turn_on"
            else -> "toggle"
        }
        return service(context, domain, service, id)
    }
}
