package ai.unicto.unibot.connectors.weather

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
import java.util.concurrent.TimeUnit

/**
 * Weather connector — Open-Meteo, NO key, NO account.
 *
 * Privacy-friendly: plain HTTPS GETs, no credentials, nothing stored except
 * the enable toggle. Geocoding via Open-Meteo's free geocoding API.
 */
object WeatherConnector {

    private const val TAG = "WeatherConnector"
    private const val GEO = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    private const val PREFS_FILE = "weather_connector"
    private const val KEY_ENABLED = "enabled"

    data class Current(val tempC: Double, val feelsC: Double, val code: Int, val windKph: Double, val humidity: Int)
    data class Day(val date: String, val maxC: Double, val minC: Double, val code: Int, val precipMm: Double)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Weather is disabled. Ask the user to enable it in Settings → Connectors.") :
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

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    fun isConnected(context: Context): Boolean = isEnabled(context)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String): Request =
        Request.Builder().url(url).header("User-Agent", "unibot-android").get().build()

    private fun geocode(place: String): Pair<Double, Double>? {
        val q = java.net.URLEncoder.encode(place, "UTF-8")
        return runCatching {
            http.newCall(get("$GEO?name=$q&count=1")).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val r = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results")
                    ?: return null
                val o = r.optJSONObject(0) ?: return null
                o.optDouble("latitude") to o.optDouble("longitude")
            }
        }.getOrNull()
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"; 1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Foggy"; 51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"; 61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"
        95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    /** Current weather for a place name. */
    suspend fun now(context: Context, place: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val (lat, lon) = geocode(place) ?: return@withContext ApiResult.Error("Place not found: $place")
            runCatching {
                val url = "$FORECAST?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&wind_speed_unit=kmh&timezone=auto"
                http.newCall(get(url)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Weather failed (${resp.code}).")
                    val c = JSONObject(text).optJSONObject("current") ?: JSONObject()
                    val code = c.optInt("weather_code", -1)
                    ApiResult.Ok(
                        "${place.trim()}: ${c.optDouble("temperature_2m", 0.0)}°C " +
                            "(feels ${c.optDouble("apparent_temperature", 0.0)}°C), ${describe(code)}, " +
                            "humidity ${c.optInt("relative_humidity_2m", 0)}%, " +
                            "wind ${c.optDouble("wind_speed_10m", 0.0)} km/h.",
                    )
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[now] ${e.message}")
                ApiResult.Error("Weather failed: ${e.message}")
            }
        }

    /** Multi-day forecast. */
    suspend fun forecast(context: Context, place: String, days: Int = 3): ApiResult<List<Day>> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val (lat, lon) = geocode(place) ?: return@withContext ApiResult.Error("Place not found: $place")
            val n = days.coerceIn(1, 7)
            runCatching {
                val url = "$FORECAST?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=auto&forecast_days=$n"
                http.newCall(get(url)).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Weather failed (${resp.code}).")
                    val d = JSONObject(text).optJSONObject("daily") ?: JSONObject()
                    val dates = d.optJSONArray("dates") ?: d.optJSONArray("time") ?: JSONArray()
                    val maxs = d.optJSONArray("temperature_2m_max") ?: JSONArray()
                    val mins = d.optJSONArray("temperature_2m_min") ?: JSONArray()
                    val codes = d.optJSONArray("weather_code") ?: JSONArray()
                    val prec = d.optJSONArray("precipitation_sum") ?: JSONArray()
                    val out = (0 until dates.length()).map { i ->
                        Day(
                            date = dates.optString(i, ""),
                            maxC = maxs.optDouble(i, 0.0),
                            minC = mins.optDouble(i, 0.0),
                            code = codes.optInt(i, -1),
                            precipMm = prec.optDouble(i, 0.0),
                        )
                    }
                    ApiResult.Ok(out)
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[forecast] ${e.message}")
                ApiResult.Error("Weather forecast failed: ${e.message}")
            }
        }

    /** Human-readable one-liner per day (kept here so tools stay thin). */
    fun formatDay(d: Day): String =
        "${d.date}: ${describe(d.code)}, ${d.minC}–${d.maxC}°C" +
            (if (d.precipMm > 0) ", ${d.precipMm}mm rain" else "")
}
