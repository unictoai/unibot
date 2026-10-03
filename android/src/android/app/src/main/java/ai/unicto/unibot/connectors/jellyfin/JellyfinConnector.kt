package ai.unicto.unibot.connectors.jellyfin

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Jellyfin connector (API key).
 *
 * Server URL + API key are stored encrypted on-device and only ever sent
 * to the user's own Jellyfin server. Read-only: libraries, recently added
 * items, and now-playing state.
 */
object JellyfinConnector {

    private const val TAG = "JellyfinConnector"
    private const val PREFS_FILE = "jellyfin_connector"

    private const val KEY_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_USER_ID = "user_id"

    data class Library(val id: String, val name: String, val type: String)
    data class MediaItem(
        val id: String,
        val name: String,
        val type: String,
        val year: Int?,
        val overview: String,
    )
    data class NowPlaying(val sessionUser: String, val title: String, val detail: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Jellyfin is not connected. Add your server URL and API key in Settings → Connectors.") :
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
        prefs(context).getString(KEY_API_KEY, null)?.isNotEmpty() == true

    fun serverUrl(context: Context): String? =
        prefs(context).getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    private fun apiKey(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotEmpty() }

    private fun userId(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun authHeader(key: String): String =
        "MediaBrowser Token=\"$key\", Client=\"unibot\", Version=\"1.2.0\""

    /** Validate the key against /Users/Me; store on success. */
    suspend fun connect(
        context: Context,
        serverUrl: String,
        apiKey: String,
    ): String? = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        if (!base.matches(Regex("https?://.+"))) return@withContext "That doesn't look like a valid http(s):// URL."
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext "Paste your Jellyfin API key."
        try {
            http.newCall(
                Request.Builder().url("$base/Users/Me")
                    .header("Authorization", authHeader(key))
                    .get().build(),
            ).execute().use { resp ->
                if (resp.code == 401) return@withContext "Invalid API key."
                if (!resp.isSuccessful) return@withContext "Server answered HTTP ${resp.code}."
                val uid = JSONObject(resp.body?.string().orEmpty()).optString("Id")
                prefs(context).edit().apply {
                    putString(KEY_URL, base)
                    putString(KEY_API_KEY, key)
                    putString(KEY_USER_ID, uid)
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

    private data class Creds(val base: String, val key: String, val uid: String)

    private fun creds(context: Context): Creds? {
        val base = serverUrl(context) ?: return null
        val key = apiKey(context) ?: return null
        return Creds(base, key, userId(context).orEmpty())
    }

    private fun get(creds: Creds, path: String): Pair<Int, String> {
        http.newCall(
            Request.Builder().url(creds.base + path)
                .header("Authorization", authHeader(creds.key))
                .get().build(),
        ).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun check(code: Int): String? = when (code) {
        200 -> null
        401 -> "Invalid API key — reconnect in Settings → Connectors."
        404 -> "Not found on this server."
        else -> "Server answered HTTP $code."
    }

    /** The user's media libraries (Movies, TV Shows, Music…). */
    suspend fun libraries(context: Context): ApiResult<List<Library>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                val (code, body) = get(c, "/Users/${c.uid.ifBlank { "Me" }}/Views")
                check(code)?.let { return@withContext ApiResult.Error(it) }
                val items = JSONObject(body).optJSONArray("Items") ?: JSONArray()
                ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    Library(
                        id = o.optString("Id"),
                        name = o.optString("Name"),
                        type = o.optString("CollectionType").ifBlank { "mixed" },
                    )
                })
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[libraries] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }

    /** Recently added movies/episodes across libraries. */
    suspend fun recentlyAdded(context: Context, limit: Int = 10): ApiResult<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                val uid = c.uid.ifBlank { "Me" }
                val (code, body) = get(
                    c,
                    "/Users/$uid/Items/Latest?Limit=${limit.coerceIn(1, 30)}" +
                        "&IncludeItemTypes=Movie,Series,Episode,Audio&Fields=Overview,ProductionYear",
                )
                check(code)?.let { return@withContext ApiResult.Error(it) }
                val arr = JSONArray(body)
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    MediaItem(
                        id = o.optString("Id"),
                        name = o.optString("Name"),
                        type = o.optString("Type"),
                        year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                        overview = o.optString("Overview").take(220),
                    )
                })
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[recentlyAdded] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }

    /** What's playing right now on any session. */
    suspend fun nowPlaying(context: Context): ApiResult<List<NowPlaying>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                val (code, body) = get(c, "/Sessions")
                check(code)?.let { return@withContext ApiResult.Error(it) }
                val arr = JSONArray(body)
                val out = mutableListOf<NowPlaying>()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val np = s.optJSONObject("NowPlayingItem") ?: continue
                    out.add(
                        NowPlaying(
                            sessionUser = s.optString("UserName").ifBlank { "Someone" },
                            title = np.optString("Name").ifBlank { "Unknown title" },
                            detail = np.optString("SeriesName").ifBlank {
                                np.optString("Album").ifBlank { np.optString("Type") }
                            },
                        ),
                    )
                }
                ApiResult.Ok(out)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[nowPlaying] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }

    /** Search the library by name. */
    suspend fun search(context: Context, query: String): ApiResult<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            val q = query.trim()
            if (q.isEmpty()) return@withContext ApiResult.Error("Type something to search for.")
            try {
                val uid = c.uid.ifBlank { "Me" }
                val (code, body) = get(
                    c,
                    "/Users/$uid/Items?Recursive=true&SearchTerm=${URLEncoder.encode(q, "UTF-8")}" +
                        "&Limit=15&Fields=Overview,ProductionYear",
                )
                check(code)?.let { return@withContext ApiResult.Error(it) }
                val items = JSONObject(body).optJSONArray("Items") ?: JSONArray()
                ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    MediaItem(
                        id = o.optString("Id"),
                        name = o.optString("Name"),
                        type = o.optString("Type"),
                        year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                        overview = o.optString("Overview").take(220),
                    )
                })
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[search] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }
}
