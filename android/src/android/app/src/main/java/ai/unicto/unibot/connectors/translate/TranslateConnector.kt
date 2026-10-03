package ai.unicto.unibot.connectors.translate

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Translate connector — MyMemory free tier, NO key, NO account.
 *
 * 5000 chars/day anonymous. Fine for quick translations in chat.
 */
object TranslateConnector {

    private const val TAG = "TranslateConnector"
    private const val BASE = "https://api.mymemory.translated.net/get"

    private const val PREFS_FILE = "translate_connector"
    private const val KEY_ENABLED = "enabled"

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Translate is disabled. Ask the user to enable it in Settings → Connectors.") :
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

    /** Translate text. [target] like "ur", "es", "fr"; [source] "auto" default. */
    suspend fun translate(context: Context, text: String, target: String, source: String = "auto"): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val q = URLEncoder.encode(text.trim(), "UTF-8")
            if (q.isBlank()) return@withContext ApiResult.Error("Give text to translate.")
            val tgt = target.trim().lowercase().take(5).ifBlank { "en" }
            val src = source.trim().lowercase().take(5).ifBlank { "auto" }
            runCatching {
                http.newCall(
                    Request.Builder().url("$BASE?q=$q&langpair=$src|$tgt").get().build(),
                ).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Translate failed (${resp.code}).")
                    val out = JSONObject(body).optJSONObject("responseData")?.optString("translatedText", "")
                    if (out.isNullOrBlank()) return@withContext ApiResult.Error("Translation came back empty.")
                    ApiResult.Ok(out)
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[translate] ${e.message}")
                ApiResult.Error("Translate failed: ${e.message}")
            }
        }
}
