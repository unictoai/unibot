package ai.unicto.unibot.connectors.gmail

import android.content.Context
import android.util.Base64
import ai.unicto.unibot.logging.AppLogger
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
 * Minimal Gmail REST client (v1) for the connector.
 *
 * Only what the agent tools need: search/list, read one message, send.
 * Every call resolves a fresh access token via [GmailOAuth] (silent refresh);
 * a null token surfaces as "not connected" instead of throwing.
 */
object GmailApi {

    private const val TAG = "GmailConnector"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    data class MessageSummary(
        val id: String,
        val from: String,
        val subject: String,
        val date: String,
        val snippet: String,
    )

    data class FullMessage(
        val id: String,
        val from: String,
        val to: String,
        val subject: String,
        val date: String,
        val body: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Gmail is not connected. Ask the user to connect it in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun token(context: Context): String? =
        GmailOAuth.validAccessToken(context)

    private fun authed(url: String, accessToken: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    /** Search mail. [query] uses Gmail search syntax (e.g. "from:x newer_than:7d"). */
    suspend fun search(
        context: Context,
        query: String,
        maxResults: Int = 10,
    ): ApiResult<List<MessageSummary>> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val n = maxResults.coerceIn(1, 25)
        val url = "$BASE/messages?q=${android.net.Uri.encode(query)}&maxResults=$n"
        runCatching {
            http.newCall(authed(url, t).get().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val ids = JSONObject(text).optJSONArray("messages") ?: JSONArray()
                val out = mutableListOf<MessageSummary>()
                for (i in 0 until ids.length()) {
                    val id = ids.optJSONObject(i)?.optString("id", "") ?: continue
                    if (id.isEmpty()) continue
                    when (val m = fetchMetadata(t, id)) {
                        is ApiResult.Ok -> out.add(m.value)
                        is ApiResult.Error -> return@withContext m
                        is ApiResult.NotConnected -> return@withContext m
                    }
                }
                ApiResult.Ok(out)
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[search] ${e.message}")
            ApiResult.Error("Gmail search failed: ${e.message}")
        }
    }

    /** Read one message: headers + best-effort plain-text body (truncated). */
    suspend fun read(context: Context, id: String): ApiResult<FullMessage> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            runCatching {
                http.newCall(
                    authed("$BASE/messages/$id?format=full", t).get().build(),
                ).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    ApiResult.Ok(parseFullMessage(JSONObject(text)))
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[read] ${e.message}")
                ApiResult.Error("Gmail read failed: ${e.message}")
            }
        }

    /** Send a plain-text email. */
    suspend fun send(
        context: Context,
        to: String,
        subject: String,
        body: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        runCatching {
            val raw = buildString {
                append("To: ").append(to.trim()).append("\r\n")
                append("Subject: ").append(subject.trim()).append("\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("\r\n")
                append(body)
            }
            val encoded = Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            val payload = JSONObject().put("raw", encoded).toString()
                .toRequestBody("application/json".toMediaType())
            http.newCall(
                authed("$BASE/messages/send", t).post(payload).build(),
            ).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                val sentId = JSONObject(text).optString("id", "")
                ApiResult.Ok(if (sentId.isNotEmpty()) "Sent (id $sentId)." else "Sent.")
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[send] ${e.message}")
            ApiResult.Error("Gmail send failed: ${e.message}")
        }
    }

    // -- internals ---------------------------------------------------------------

    private fun fetchMetadata(accessToken: String, id: String): ApiResult<MessageSummary> {
        http.newCall(
            authed("$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", accessToken)
                .get().build(),
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return apiError(resp.code, text)
            val j = JSONObject(text)
            val headers = j.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
            fun header(name: String): String {
                for (i in 0 until headers.length()) {
                    val h = headers.optJSONObject(i) ?: continue
                    if (h.optString("name", "").equals(name, ignoreCase = true)) {
                        return h.optString("value", "")
                    }
                }
                return ""
            }
            return ApiResult.Ok(
                MessageSummary(
                    id = id,
                    from = header("From"),
                    subject = header("Subject"),
                    date = header("Date"),
                    snippet = j.optString("snippet", ""),
                ),
            )
        }
    }

    private fun parseFullMessage(j: JSONObject): FullMessage {
        val payload = j.optJSONObject("payload")
        val headers = payload?.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String {
            for (i in 0 until headers.length()) {
                val h = headers.optJSONObject(i) ?: continue
                if (h.optString("name", "").equals(name, ignoreCase = true)) {
                    return h.optString("value", "")
                }
            }
            return ""
        }
        val body = extractPlainText(payload).take(12_000)
        return FullMessage(
            id = j.optString("id", ""),
            from = header("From"),
            to = header("To"),
            subject = header("Subject"),
            date = header("Date"),
            body = body.ifBlank { j.optString("snippet", "") },
        )
    }

    /** Walk MIME parts for the first text/plain body; decode base64url. */
    private fun extractPlainText(payload: JSONObject?): String {
        if (payload == null) return ""
        val mime = payload.optString("mimeType", "")
        if (mime.startsWith("text/plain", ignoreCase = true)) {
            val data = payload.optJSONObject("body")?.optString("data", "")
            if (data.isNotEmpty()) return decodeBody(data)
        }
        val parts = payload.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) {
            val found = extractPlainText(parts.optJSONObject(i) ?: continue)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun decodeBody(data: String): String {
        return runCatching {
            val bytes = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP)
            bytes.toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Gmail API error ($code): $detail")
    }
}
