package ai.unicto.unibot.connectors.calendar

import android.content.Context
import ai.unicto.unibot.connectors.google.GoogleOAuth
import ai.unicto.unibot.connectors.google.GoogleTokenStore
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Google Calendar connector (v3).
 * Scopes are sensitive (not restricted): calendar.readonly + calendar.events
 * work for test users in Testing mode; public rollout needs Google verification.
 */
object CalendarConnector {

    const val SCOPES =
        "https://www.googleapis.com/auth/calendar.readonly " +
            "https://www.googleapis.com/auth/calendar.events"
    val store = GoogleTokenStore("calendar")

    private const val TAG = "CalendarConnector"
    private const val BASE = "https://www.googleapis.com/calendar/v3/calendars/primary"

    data class CalEvent(
        val id: String,
        val summary: String,
        val start: String,
        val end: String,
        val location: String,
        val description: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Google Calendar is not connected. Ask the user to connect it in Settings → Connectors.") :
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

    suspend fun authorize(context: Context): GoogleOAuth.Result {
        val r = GoogleOAuth.authorize(context, SCOPES, "calendar")
        if (r is GoogleOAuth.Result.Success) {
            store.setTokens(context, r.tokens)
            store.setAccountEmail(context, r.accountEmail)
        }
        return r
    }

    suspend fun disconnect(context: Context) = GoogleOAuth.disconnect(context, store)

    private suspend fun token(context: Context): String? =
        GoogleOAuth.validAccessToken(context, store)

    private fun authed(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    /** Upcoming events, soonest first. */
    suspend fun listUpcoming(
        context: Context,
        maxResults: Int = 10,
    ): ApiResult<List<CalEvent>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = maxResults.coerceIn(1, 25)
        val now = rfc3339(Date())
        val url = "$BASE/events?timeMin=${android.net.Uri.encode(now)}" +
            "&maxResults=$n&singleEvents=true&orderBy=startTime" +
            "&fields=items(id,summary,start,end,location,description)"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val arr = JSONObject(text).optJSONArray("items") ?: JSONArray()
                ApiResult.Ok((0 until arr.length()).mapNotNull { i ->
                    parseEvent(arr.optJSONObject(i) ?: return@mapNotNull null)
                })
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[list] ${e.message}")
            ApiResult.Error("Calendar list failed: ${e.message}")
        }
    }

    /**
     * Create an event. [start]/[end] accept "yyyy-MM-dd HH:mm" in the device's
     * timezone, or full RFC 3339.
     */
    suspend fun create(
        context: Context,
        summary: String,
        start: String,
        end: String,
        description: String = "",
        location: String = "",
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            val body = JSONObject().apply {
                put("summary", summary)
                put("start", JSONObject().put("dateTime", toRfc3339(start)))
                put("end", JSONObject().put("dateTime", toRfc3339(end)))
                if (description.isNotBlank()) put("description", description)
                if (location.isNotBlank()) put("location", location)
            }.toString().toRequestBody("application/json".toMediaType())
            http.newCall(authed("$BASE/events", t).post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val ev = parseEvent(JSONObject(text))
                ApiResult.Ok(
                    if (ev != null) "Created: ${ev.summary} (${ev.start} → ${ev.end})."
                    else "Event created.",
                )
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[create] ${e.message}")
            ApiResult.Error("Calendar create failed: ${e.message}")
        }
    }

    // -- internals ---------------------------------------------------------------

    private fun parseEvent(j: JSONObject): CalEvent? {
        val id = j.optString("id", "")
        if (id.isEmpty()) return null
        fun whenText(key: String): String {
            val o = j.optJSONObject(key) ?: return ""
            return o.optString("dateTime", "").ifBlank { o.optString("date", "") }
        }
        return CalEvent(
            id = id,
            summary = j.optString("summary", "(no title)"),
            start = whenText("start"),
            end = whenText("end"),
            location = j.optString("location", ""),
            description = j.optString("description", "").take(500),
        )
    }

    private fun rfc3339(d: Date): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(d)
    }

    private fun toRfc3339(input: String): String {
        val s = input.trim()
        if (s.contains("T")) return s // already RFC 3339-ish
        return runCatching {
            val inF = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            inF.timeZone = TimeZone.getDefault()
            rfc3339(inF.parse(s) ?: Date())
        }.getOrDefault(s)
    }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Calendar API error ($code): $detail")
    }
}
