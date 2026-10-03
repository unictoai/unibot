package ai.unicto.unibot.connectors.steam

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.connectors.token.TokenStore
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Steam connector (Web API key + SteamID64).
 *
 * The API key and SteamID64 are stored encrypted on-device and only ever
 * sent to api.steampowered.com. Read-only: player summary, owned games,
 * recently played games.
 */
object SteamConnector {

    private const val TAG = "SteamConnector"
    private const val PREFS_FILE = "steam_connector_extra"
    private const val KEY_STEAM_ID = "steam_id"

    val store = TokenStore("steam")

    data class Player(val name: String, val profileUrl: String, val avatar: String, val status: String)
    data class Game(
        val appId: Long,
        val name: String,
        val playtimeHours: Double,
        val lastPlayedMs: Long,
        val iconUrl: String?,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Steam is not connected. Add your API key and SteamID64 in Settings → Connectors.") :
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

    fun isConnected(context: Context): Boolean = store.isConnected(context)

    fun steamId(context: Context): String? =
        prefs(context).getString(KEY_STEAM_ID, null)?.takeIf { it.isNotBlank() }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Validate the key + SteamID64 against GetPlayerSummaries; store on
     * success. Returns the persona name, or null when invalid.
     */
    suspend fun connect(context: Context, apiKey: String, steamId: String): String? =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            val id = steamId.trim()
            if (key.isEmpty() || !id.matches(Regex("\\d{17}"))) return@withContext null
            val player = fetchPlayer(key, id) ?: return@withContext null
            store.setToken(context, key)
            store.setLabel(context, player.name)
            prefs(context).edit().putString(KEY_STEAM_ID, id).apply()
            player.name
        }

    fun disconnect(context: Context) {
        store.clear(context)
        prefs(context).edit().remove(KEY_STEAM_ID).apply()
    }

    private data class Creds(val key: String, val id: String)

    private fun creds(context: Context): Creds? {
        val key = store.getToken(context) ?: return null
        val id = steamId(context) ?: return null
        return Creds(key, id)
    }

    private fun fetchPlayer(key: String, id: String): Player? {
        return try {
            http.newCall(
                Request.Builder()
                    .url("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/?key=$key&steamids=$id")
                    .get().build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val players = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("response")?.optJSONArray("players") ?: JSONArray()
                if (players.length() == 0) return null
                val p = players.getJSONObject(0)
                val state = p.optInt("personastate", 0)
                Player(
                    name = p.optString("personaname").ifBlank { "Steam user" },
                    profileUrl = p.optString("profileurl"),
                    avatar = p.optString("avatarfull"),
                    status = when (state) {
                        0 -> "Offline"
                        1 -> "Online"
                        2 -> "Busy"
                        3 -> "Away"
                        4 -> "Snooze"
                        5 -> "Looking to trade"
                        6 -> "Looking to play"
                        else -> "Online"
                    },
                )
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[player] ${t.message}")
            null
        }
    }

    /** Player summary for the connected account. */
    suspend fun player(context: Context): ApiResult<Player> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            fetchPlayer(c.key, c.id)?.let { ApiResult.Ok(it) }
                ?: ApiResult.Error("Couldn't load the Steam profile — check the API key and SteamID64.")
        }

    private fun iconUrl(appId: Long, hash: String?): String? =
        hash?.takeIf { it.isNotBlank() }
            ?.let { "https://media.steampowered.com/steamcommunity/public/images/apps/$appId/$it.jpg" }

    /** Owned games, most-played first. */
    suspend fun ownedGames(context: Context, limit: Int = 25): ApiResult<List<Game>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                http.newCall(
                    Request.Builder().url(
                        "https://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/" +
                            "?key=${c.key}&steamid=${c.id}&include_appinfo=true&format=json",
                    ).get().build(),
                ).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        return@withContext ApiResult.Error("Invalid Steam API key — reconnect in Settings → Connectors.")
                    }
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Steam answered HTTP ${resp.code}.")
                    val games = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("response")?.optJSONArray("games") ?: JSONArray()
                    val out = (0 until games.length()).mapNotNull { i ->
                        val g = games.optJSONObject(i) ?: return@mapNotNull null
                        val appId = g.optLong("appid")
                        Game(
                            appId = appId,
                            name = g.optString("name").ifBlank { "App $appId" },
                            playtimeHours = g.optLong("playtime_forever") / 60.0,
                            lastPlayedMs = g.optLong("rtime_last_played") * 1000,
                            iconUrl = iconUrl(appId, g.optString("img_icon_url")),
                        )
                    }.sortedByDescending { it.playtimeHours }.take(limit.coerceIn(1, 100))
                    ApiResult.Ok(out)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[owned] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }

    /** Games played in the last two weeks. */
    suspend fun recentlyPlayed(context: Context): ApiResult<List<Game>> =
        withContext(Dispatchers.IO) {
            val c = creds(context) ?: return@withContext ApiResult.NotConnected()
            try {
                http.newCall(
                    Request.Builder().url(
                        "https://api.steampowered.com/IPlayerService/GetRecentlyPlayedGames/v0001/" +
                            "?key=${c.key}&steamid=${c.id}&format=json",
                    ).get().build(),
                ).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        return@withContext ApiResult.Error("Invalid Steam API key — reconnect in Settings → Connectors.")
                    }
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Steam answered HTTP ${resp.code}.")
                    val games = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("response")?.optJSONArray("games") ?: JSONArray()
                    val out = (0 until games.length()).mapNotNull { i ->
                        val g = games.optJSONObject(i) ?: return@mapNotNull null
                        val appId = g.optLong("appid")
                        Game(
                            appId = appId,
                            name = g.optString("name").ifBlank { "App $appId" },
                            playtimeHours = g.optLong("playtime_2weeks") / 60.0,
                            lastPlayedMs = 0L,
                            iconUrl = iconUrl(appId, g.optString("img_icon_url")),
                        )
                    }
                    ApiResult.Ok(out)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "[recent] ${t.message}")
                ApiResult.Error(t.message ?: "request failed")
            }
        }
}
