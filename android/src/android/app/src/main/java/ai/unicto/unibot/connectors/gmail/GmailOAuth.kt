package ai.unicto.unibot.connectors.gmail

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
 * Google OAuth 2.0 (PKCE, loopback redirect — RFC 8252) for the Gmail connector.
 *
 * Uses the "Desktop app" OAuth client from the unibot Cloud project: installed
 * clients need no client secret, so nothing sensitive is embedded in the APK.
 * The flow reuses the app's existing [OAuthCallbackServer] + Chrome Custom Tab
 * pattern from the MCP OAuth implementation.
 *
 * Scopes: gmail.readonly + gmail.send. Both work for test users while the
 * consent screen is in Testing mode; public rollout needs Google verification.
 */
object GmailOAuth {

    private const val TAG = "GmailConnector"

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
    private const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"

    /** Desktop-app OAuth client in the unibot Cloud project (public value). */
    const val CLIENT_ID =
        "890243358018-g3p3o2le4ikc177bkvbpb54bsanjphoi.apps.googleusercontent.com"

    const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
    const val SCOPE_SEND = "https://www.googleapis.com/auth/gmail.send"
    private const val SCOPES = "$SCOPE_READONLY $SCOPE_SEND"

    private const val LOOPBACK_PORT = 53682
    private val FALLBACK_PORTS = listOf(53683, 53684, 53685)

    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(): Boolean =
        CLIENT_ID.isNotBlank() && !CLIENT_ID.startsWith("GMAIL_OAUTH_CLIENT_ID")

    /**
     * Run the full authorize flow: Custom Tab → loopback callback → code
     * exchange → tokens persisted in [GmailStore]. Never throws.
     * Must be called from a coroutine; the Custom Tab needs a foreground context.
     */
    suspend fun authorize(context: Context): Result {
        if (!isConfigured()) return Result.Failed("Gmail connector is not configured yet.")
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
                    val redirect = redirectUri(srv.boundPort)
                    val authUrl = buildAuthUrl(pkce, redirect)
                    // Same as MCP flow: no NEW_TASK flag, MainActivity is singleTask.
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(context, Uri.parse(authUrl))
                    AppLogger.info(TAG, "[Authorize] opened Custom Tab")
                } ?: return@withContext Result.Cancelled

                val (code, state) = callback
                if (state != null && state != pkce.state) {
                    AppLogger.warning(TAG, "[Authorize] state mismatch")
                    return@withContext Result.Failed("State mismatch in the OAuth callback.")
                }
                val redirect = redirectUri(server?.boundPort ?: LOOPBACK_PORT)
                exchangeCode(context, code, redirect, pkce.verifier)
            } finally {
                server?.stop()
            }
        }
    }

    private fun redirectUri(port: Int) = "http://127.0.0.1:$port/"

    private fun buildAuthUrl(pkce: McpPkce.PkceParams, redirectUri: String): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(enc(CLIENT_ID))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&response_type=code")
            append("&scope=").append(enc(SCOPES))
            append("&code_challenge=").append(enc(pkce.challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(enc(pkce.state))
            append("&access_type=offline")
            append("&prompt=consent")
        }
    }

    private fun exchangeCode(
        context: Context,
        code: String,
        redirectUri: String,
        verifier: String,
    ): Result {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val form = "grant_type=authorization_code" +
            "&code=${enc(code)}" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&client_id=${enc(CLIENT_ID)}" +
            "&code_verifier=${enc(verifier)}"
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.error(TAG, "[Authorize] token exchange HTTP ${resp.code}")
                    return Result.Failed("Google sign-in failed (${resp.code}).")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return Result.Failed("Google returned no access token.")
                val expiresIn = json.optLong("expires_in", 0L)
                val expiresAt = if (expiresIn > 0) {
                    System.currentTimeMillis() + expiresIn * 1000
                } else 0L
                GmailStore.setTokens(
                    context,
                    GmailStore.StoredTokens(
                        accessToken = access,
                        refreshToken = json.optString("refresh_token", "").ifBlank { null },
                        expiresAtMs = expiresAt,
                    ),
                )
                // Best-effort: remember which account was connected.
                GmailStore.setAccountEmail(context, fetchAccountEmail(access))
                AppLogger.info(TAG, "[Authorize] OK (hasRefresh=${json.has("refresh_token")})")
                Result.Success
            }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Authorize] token exchange failed: ${t.message}")
            Result.Failed("Google sign-in failed: ${t.message}")
        }
    }

    private fun fetchAccountEmail(accessToken: String): String? {
        return try {
            val req = Request.Builder()
                .url(USERINFO_ENDPOINT)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty()).optString("email", "").ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Return a usable access token, refreshing silently when expired.
     * Null means not connected (or refresh failed → caller should prompt
     * to reconnect).
     */
    suspend fun validAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val stored = GmailStore.getTokens(context) ?: return@withContext null
        if (!stored.needsRefresh()) return@withContext stored.accessToken
        val refresh = stored.refreshToken ?: return@withContext null
        refreshAccessToken(context, refresh)
    }

    private fun refreshAccessToken(context: Context, refreshToken: String): String? {
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
                    AppLogger.warning(TAG, "[Refresh] HTTP ${resp.code} — treating as disconnected")
                    return null
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return null
                val expiresIn = json.optLong("expires_in", 0L)
                GmailStore.setTokens(
                    context,
                    GmailStore.StoredTokens(
                        accessToken = access,
                        // Google may omit refresh_token on refresh; keep the old one.
                        refreshToken = json.optString("refresh_token", "").ifBlank { refreshToken },
                        expiresAtMs = if (expiresIn > 0) {
                            System.currentTimeMillis() + expiresIn * 1000
                        } else 0L,
                    ),
                )
                access
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[Refresh] failed: ${t.message}")
            null
        }
    }

    /** Disconnect: revoke at Google (best-effort) and wipe local tokens. */
    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        val token = GmailStore.getTokens(context)?.accessToken
        GmailStore.clear(context)
        if (token != null) {
            runCatching {
                val form = "token=${URLEncoder.encode(token, "UTF-8")}"
                val req = Request.Builder()
                    .url(REVOKE_ENDPOINT)
                    .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                http.newCall(req).execute().close()
            }
        }
        AppLogger.info(TAG, "[Disconnect] tokens cleared")
    }
}
