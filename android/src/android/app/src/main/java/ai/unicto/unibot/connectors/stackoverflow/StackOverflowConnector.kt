package ai.unicto.unibot.connectors.stackoverflow

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
 * Stack Overflow connector — StackExchange API, NO key, NO account
 * (throttled without a key; fine for occasional searches).
 */
object StackOverflowConnector {

    private const val TAG = "StackOverflowConnector"
    private const val BASE = "https://api.stackexchange.com/2.3"

    private const val PREFS_FILE = "so_connector"
    private const val KEY_ENABLED = "enabled"

    data class Question(val title: String, val score: Int, val answers: Int, val url: String)

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "Stack Overflow is disabled. Ask the user to enable it in Settings → Connectors.") :
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

    /** Search questions by relevance. */
    suspend fun search(context: Context, query: String, limit: Int = 5): ApiResult<List<Question>> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val q = URLEncoder.encode(query, "UTF-8")
            if (q.isBlank()) return@withContext ApiResult.Error("Give a search query.")
            val n = limit.coerceIn(1, 10)
            runCatching {
                val url = "$BASE/search/advanced?order=desc&sort=relevance&q=$q&site=stackoverflow&pagesize=$n&filter=default"
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext ApiResult.Error("Stack Overflow failed (${resp.code}).")
                    val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
                    ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val title = o.optString("title", "")
                        if (title.isBlank()) return@mapNotNull null
                        Question(
                            title = android.text.Html.fromHtml(title, android.text.Html.FROM_HTML_MODE_LEGACY).toString(),
                            score = o.optInt("score", 0),
                            answers = o.optInt("answer_count", 0),
                            url = o.optString("link", ""),
                        )
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[search] ${e.message}")
                ApiResult.Error("Stack Overflow search failed: ${e.message}")
            }
        }
}
