package ai.unicto.unibot.connectors.outlook

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Outlook connector (Microsoft identity platform OAuth 2.0 + PKCE, user's own
 * Microsoft account) over Microsoft Graph.
 *
 * Agent tools: search mail, read mail, send mail. Delegated Mail.Read /
 * Mail.Send permissions are granted by the user at sign-in; no app
 * password or client secret is embedded in the APK.
 */
object OutlookConnector {

    val store = OutlookTokenStore()

    private const val TAG = "OutlookConnector"
    private const val BASE = "https://graph.microsoft.com/v1.0"

    data class MailSummary(
        val id: String,
        val from: String,
        val subject: String,
        val receivedAt: String,
        val snippet: String,
    )

    data class MailMessage(
        val id: String,
        val from: String,
        val to: String,
        val subject: String,
        val receivedAt: String,
        val body: String,
    )

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class NotConnected(val hint: String = "Outlook is not connected. Ask the user to connect it in Settings → Connectors.") :
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

    fun isConfigured(): Boolean = OutlookOAuth.isConfigured()

    suspend fun authorize(context: Context): OutlookOAuth.Result {
        val r = OutlookOAuth.authorize(context)
        if (r is OutlookOAuth.Result.Success) {
            store.setTokens(context, r.tokens)
            store.setAccountEmail(context, r.accountEmail)
        }
        return r
    }

    suspend fun disconnect(context: Context) = OutlookOAuth.disconnect(context, store)

    private suspend fun token(context: Context): String? =
        OutlookOAuth.validAccessToken(context, store)

    private fun authed(url: String, accessToken: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("ConsistencyLevel", "eventual")

    /** Search mail with Graph $search. Returns newest-first summaries. */
    suspend fun searchMail(context: Context, query: String, max: Int): ApiResult<List<MailSummary>> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            val q = URLEncoder.encode("\"$query\"", "UTF-8")
            val top = max.coerceIn(1, 25)
            val url = "$BASE/me/messages?\$search=$q&\$top=$top&\$orderby=receivedDateTime desc"
            runCatching {
                http.newCall(authed(url, t).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    val items = JSONObject(text).optJSONArray("value") ?: JSONArray()
                    ApiResult.Ok((0 until items.length()).mapNotNull { i ->
                        val m = items.optJSONObject(i) ?: return@mapNotNull null
                        MailSummary(
                            id = m.optString("id", ""),
                            from = m.optJSONObject("from")?.optJSONObject("emailAddress")
                                ?.optString("address", "").orEmpty(),
                            subject = m.optString("subject", ""),
                            receivedAt = m.optString("receivedDateTime", ""),
                            snippet = m.optString("bodyPreview", ""),
                        )
                    })
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[searchMail] ${e.message}")
                ApiResult.Error("Outlook mail search failed: ${e.message}")
            }
        }

    /** Read one message (headers + plain-text body, truncated). */
    suspend fun readMail(context: Context, id: String): ApiResult<MailMessage> =
        withContext(Dispatchers.IO) {
            val t = token(context) ?: return@withContext ApiResult.NotConnected()
            val mid = URLEncoder.encode(id, "UTF-8")
            val url = "$BASE/me/messages/$mid?\$select=id,subject,from,toRecipients,receivedDateTime,bodyPreview,body"
            runCatching {
                http.newCall(authed(url, t).get().build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext apiError(resp.code, text)
                    val m = JSONObject(text)
                    val body = m.optJSONObject("body")
                    val rawBody = body?.optString("content", "").orEmpty()
                    val bodyText = if (body?.optString("contentType", "") == "html") {
                        rawBody.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                    } else rawBody
                    ApiResult.Ok(
                        MailMessage(
                            id = m.optString("id", ""),
                            from = m.optJSONObject("from")?.optJSONObject("emailAddress")
                                ?.optString("address", "").orEmpty(),
                            to = (m.optJSONArray("toRecipients")?.let { arr ->
                                (0 until arr.length()).mapNotNull { i ->
                                    arr.optJSONObject(i)?.optJSONObject("emailAddress")
                                        ?.optString("address", "")
                                }.filter { it.isNotBlank() }.joinToString(", ")
                            }).orEmpty(),
                            subject = m.optString("subject", ""),
                            receivedAt = m.optString("receivedDateTime", ""),
                            body = bodyText.take(20_000),
                        ),
                    )
                }
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[readMail] ${e.message}")
                ApiResult.Error("Outlook read failed: ${e.message}")
            }
        }

    /** Send a plain-text email via /me/sendMail. */
    suspend fun sendMail(
        context: Context,
        to: String,
        subject: String,
        body: String,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        val t = token(context) ?: return@withContext ApiResult.NotConnected()
        val payload = JSONObject().apply {
            put("message", JSONObject().apply {
                put("subject", subject)
                put("body", JSONObject().apply {
                    put("contentType", "Text")
                    put("content", body)
                })
                put("toRecipients", JSONArray().apply {
                    put(JSONObject().apply {
                        put("emailAddress", JSONObject().apply { put("address", to) })
                    })
                })
            })
        }.toString()
        runCatching {
            val req = authed("$BASE/me/sendMail", t)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 202 || resp.isSuccessful) {
                    ApiResult.Ok("Email sent to $to.")
                } else {
                    apiError(resp.code, resp.body?.string().orEmpty())
                }
            }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "[sendMail] ${e.message}")
            ApiResult.Error("Outlook send failed: ${e.message}")
        }
    }

    private fun <T> apiError(code: Int, body: String): ApiResult<T> {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
        AppLogger.warning(TAG, "[api] HTTP $code: $detail")
        return ApiResult.Error("Outlook API error ($code): $detail")
    }
}
