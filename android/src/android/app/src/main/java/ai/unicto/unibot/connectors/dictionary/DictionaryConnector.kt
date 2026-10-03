package ai.unicto.unibot.connectors.dictionary

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Dictionary connector — dictionaryapi.dev, NO key, NO account.
 */
object DictionaryConnector {

    private const val TAG = "DictionaryConnector"
    private const val BASE = "https://api.dictionaryapi.dev/api/v2/entries/en"

    private const val PREFS_FILE = "dictionary_connector"
    private const val KEY_ENABLED = "enabled"

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Dictionary is disabled. Ask the user to enable it in Settings → Connectors.") :
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

    /** Define a word: part of speech + up to 3 definitions + example. */
    suspend fun define(context: Context, word: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val w = URLEncoder.encode(word.trim().lowercase(), "UTF-8")
            if (w.isBlank()) return@withContext ApiResult.Error("Give a word to define.")
            runCatching {
                http.newCall(
                    Request.Builder().url("$BASE/$w").header("User-Agent", "unibot-android").get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("No definition found for \"$word\".")
                    val entry = JSONArray(text).optJSONObject(0) ?: return@withContext ApiResult.Error("No definition found.")
                    val out = buildString {
                        appendLine("**${entry.optString("word", word)}** ${entry.optString("phonetic", "")}".trim())
                        val meanings = entry.optJSONArray("meanings") ?: JSONArray()
                        var count = 0
                        outer@ for (i in 0 until meanings.length()) {
                            val m = meanings.optJSONObject(i) ?: continue
                            val pos = m.optString("partOfSpeech", "")
                            val defs = m.optJSONArray("definitions") ?: JSONArray()
                            for (j in 0 until defs.length()) {
                                if (count >= 3) break@outer
                                val d = defs.optJSONObject(j) ?: continue
                                val def = d.optString("definition", "")
                                if (def.isBlank()) continue
                                count++
                                appendLine("$count. ($pos) $def")
                                val ex = d.optString("example", "")
                                if (ex.isNotBlank()) appendLine("   e.g. \"$ex\"")
                            }
                        }
                        if (count == 0) appendLine("(no definitions found)")
                    }
                    ApiResult.Ok(out.trim())
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[define] ${e.message}")
                ApiResult.Error("Dictionary failed: ${e.message}")
            }
        }
}
