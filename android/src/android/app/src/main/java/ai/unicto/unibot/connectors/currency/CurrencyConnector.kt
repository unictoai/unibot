package ai.unicto.unibot.connectors.currency

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Currency connector — frankfurter.app, NO key, NO account.
 *
 * Free ECB-based rates. Only the enable toggle is stored.
 */
object CurrencyConnector {

    private const val TAG = "CurrencyConnector"
    private const val BASE = "https://api.frankfurter.app"

    private const val PREFS_FILE = "currency_connector"
    private const val KEY_ENABLED = "enabled"

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Currency is disabled. Ask the user to enable it in Settings → Connectors.") :
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

    private fun code(v: String): String = v.trim().uppercase().take(3)

    /** Convert an amount. */
    suspend fun convert(context: Context, amount: Double, from: String, to: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val f = code(from); val t = code(to)
            if (f.length != 3 || t.length != 3) return@withContext ApiResult.Error("Use 3-letter codes like USD, PKR, EUR.")
            runCatching {
                http.newCall(
                    Request.Builder().url("$BASE/latest?amount=$amount&from=$f&to=$t").get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Currency failed (${resp.code}).")
                    val rates = JSONObject(text).optJSONObject("rates") ?: JSONObject()
                    val out = rates.optDouble(t, Double.NaN)
                    if (out.isNaN()) return@withContext ApiResult.Error("No rate for $f → $t.")
                    ApiResult.Ok("$amount $f = ${"%.2f".format(out)} $t (ECB reference rate)")
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[convert] ${e.message}")
                ApiResult.Error("Currency convert failed: ${e.message}")
            }
        }

    /** Rates of a base currency against a few majors. */
    suspend fun rates(context: Context, base: String = "USD"): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val b = code(base).ifBlank { "USD" }
            runCatching {
                http.newCall(
                    Request.Builder().url("$BASE/latest?from=$b&to=PKR,EUR,GBP,JPY,INR,AED,SAR,CNY").get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Currency failed (${resp.code}).")
                    val rates = JSONObject(text).optJSONObject("rates") ?: JSONObject()
                    val out = buildString {
                        appendLine("1 $b =")
                        val keys = rates.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            appendLine("  ${"%.2f".format(rates.optDouble(k, 0.0))} $k")
                        }
                    }
                    ApiResult.Ok(out.trim())
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[rates] ${e.message}")
                ApiResult.Error("Currency rates failed: ${e.message}")
            }
        }
}
