package ai.unicto.unibot.connectors.outlook

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import ai.unicto.unibot.auth.OAuthCallbackServer
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.mcp.oauth.McpPkce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Microsoft identity platform OAuth 2.0 (Authorization Code + PKCE, loopback
 * redirect — RFC 8252). Mirrors
 * [ai.unicto.unibot.connectors.spotify.SpotifyOAuth]: a public client needs
 * no client secret, so nothing sensitive is embedded in the APK.
 *
 * SETUP (one time, by the app publisher): register an app at
 * portal.azure.com (single-tenant or multitenant "native / mobile app"),
 * add `http://127.0.0.1:53712/callback` (and 53713–53715) as Redirect URIs,
 * enable the delegated Mail.Read, Mail.Send and Calendars.Read permissions,
 * and put the issued Application (client) ID into [CLIENT_ID]. Until then
 * [isConfigured] is false and the Connectors screen shows a "needs setup"
 * state instead of a broken connect button.
 */
object OutlookOAuth {

    private const val TAG = "OutlookOAuth"

    private const val AUTH_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"

    /**
     * TODO: register an app at portal.azure.com and paste the Application
     * (client) ID here. The app must be a public/native client with Redirect
     * URIs http://127.0.0.1:53712/callback … :53715/callback registered.
     */
    const val CLIENT_ID = ""

    fun isConfigured(): Boolean = CLIENT_ID.isNotBlank()

    private const val LOOPBACK_PORT = 53712
    private val FALLBACK_PORTS = listOf(53713, 53714, 53715)

    const val SCOPES = "offline_access Mail.Read Mail.Send Calendars.Read"

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    ) {
        fun needsRefresh(nowMs: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs != 0L && nowMs + 60_000 >= expiresAtMs
    }

    sealed class Result {
        data class Success(val tokens: Tokens, val accountEmail: String?) : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Run the full authorize flow. Never throws. Must be called from a coroutine. */
    suspend fun authorize(context: Context): Result {
        if (!isConfigured()) {
            return Result.Failed(
                "Outlook needs setup: register an app at portal.azure.com " +
                    "and set CLIENT_ID in OutlookOAuth.kt",
            )
        }
        return withContext(Dispatchers.IO) {
            val pkce = McpPkce.newPkce()
            var server: OAuthCallbackServer? = null
            try {
                val callback = suspendCancellableCoroutine<Pair<String, String?>?> { cont ->
                    val srv = OAuthCallbackServer(LOOPBACK_PORT, FALLBACK_PORTS) { code, state ->
                        if (cont.isActive) cont.resume(code to state)
                    }
                    server = srv
                    srv.onExternalCancel = { if (cont.isActive) cont.resume(null) }
                    srv.start()
                    cont.invokeOnCancellation {
                        srv.stop()
                        server = null
                    }
                    val authUrl = buildAuthUrl(pkce, redirectUri(srv.boundPort))
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(context, Uri.parse(authUrl))
                    AppLogger.info(TAG, "[Authorize] opened Custom Tab")
                } ?: return@withContext Result.Cancelled

                val (code, state) = callback
                if (state != null && state != pkce.state) {
                    AppLogger.warning(TAG, "[Authorize] state mismatch")
                    return@withContext Result.Failed("State mismatch in the sign-in callback.")
                }
                exchangeCode(code, redirectUri(server?.boundPort ?: LOOPBACK_PORT), pkce.verifier)
            } finally {
                server?.stop()
            }
        }
    }

    private fun redirectUri(port: Int) = "http://127.0.0.1:$port/callback"

    private fun buildAuthUrl(pkce: McpPkce.PkceParams, redirectUri: String): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(enc(CLIENT_ID))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&scope=").append(enc(SCOPES))
            append("&code_challenge=").append(enc(pkce.challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(enc(pkce.state))
        }
    }

    private fun exchangeCode(code: String, redirectUri: String, verifier: String): Result {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val form = "grant_type=authorization_code" +
            "&code=${enc(code)}" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&client_id=${enc(CLIENT_ID)}" +
            "&code_verifier=${enc(verifier)}"
        return postTokenForm(form, "authorize")
    }

    /** Usable access token for [store], refreshing silently when expired. Null = not connected. */
    suspend fun validAccessToken(context: Context, store: OutlookTokenStore): String? =
        withContext(Dispatchers.IO) {
            val stored = store.getTokens(context) ?: return@withContext null
            if (!stored.needsRefresh()) return@withContext stored.accessToken
            val refresh = stored.refreshToken ?: return@withContext null
            refreshAccessToken(context, store, refresh)
        }

    private fun refreshAccessToken(
        context: Context,
        store: OutlookTokenStore,
        refreshToken: String,
    ): String? {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val form = "grant_type=refresh_token" +
            "&refresh_token=${enc(refreshToken)}" +
            "&client_id=${enc(CLIENT_ID)}"
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.warning(TAG, "[Refresh] HTTP ${resp.code}")
                    return null
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return null
                val expiresIn = json.optLong("expires_in", 0L)
                store.setTokens(
                    context,
                    Tokens(
                        accessToken = access,
                        refreshToken = json.optString("refresh_token", "").ifBlank { refreshToken },
                        expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                    ),
                )
                access
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[Refresh] failed: ${t.message}")
            null
        }
    }

    private fun postTokenForm(form: String, op: String): Result {
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.error(TAG, "[$op] token HTTP ${resp.code}")
                    return Result.Failed("Outlook sign-in failed (${resp.code}).")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return Result.Failed("Outlook returned no access token.")
                val expiresIn = json.optLong("expires_in", 0L)
                val tokens = Tokens(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token", "").ifBlank { null },
                    expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                )
                AppLogger.info(TAG, "[$op] OK (hasRefresh=${json.has("refresh_token")})")
                Result.Success(tokens, fetchAccountEmail(access))
            }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[$op] failed: ${t.message}")
            Result.Failed("Outlook sign-in failed: ${t.message}")
        }
    }

    /** Disconnect: revoke is best-effort here — always wipe local tokens. */
    suspend fun disconnect(context: Context, store: OutlookTokenStore) =
        withContext(Dispatchers.IO) {
            store.clear(context)
            AppLogger.info(TAG, "[Disconnect] tokens cleared")
        }

    private fun fetchAccountEmail(accessToken: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body?.string().orEmpty())
                j.optString("mail", "").ifBlank { j.optString("userPrincipalName", "") }
                    .ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
