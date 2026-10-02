package ai.unicto.unibot.connectors.google

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
 * Shared Google OAuth 2.0 (PKCE, loopback redirect — RFC 8252) for the
 * connector family (Drive, Calendar, …).
 *
 * Uses the same "Desktop app" OAuth client as the Gmail connector: installed
 * clients need no client secret, so nothing sensitive is embedded in the APK.
 * The Custom Tab + loopback pattern mirrors the MCP/Google OAuth flows.
 *
 * Each connector keeps its own tokens via [GoogleTokenStore] (one service =
 * one scope set = one approval), so connecting/disconnecting services stays
 * independent.
 */
object GoogleOAuth {

    private const val TAG = "GoogleConnector"

    private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
    private const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"

    /** Desktop-app OAuth client in the unibot Cloud project (public value). */
    const val CLIENT_ID =
        "890243358018-g3p3o2le4ikc177bkvbpb54bsanjphoi.apps.googleusercontent.com"

    private const val LOOPBACK_PORT = 53702
    private val FALLBACK_PORTS = listOf(53703, 53704, 53705)

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

    /**
     * Run the full authorize flow for [scopes] (space-separated). Never throws.
     * Must be called from a coroutine; the Custom Tab needs a foreground context.
     */
    suspend fun authorize(context: Context, scopes: String, service: String): Result {
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
                    val authUrl = buildAuthUrl(pkce, redirectUri(srv.boundPort), scopes)
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(context, Uri.parse(authUrl))
                    AppLogger.info(TAG, "[Authorize] '$service' opened Custom Tab")
                } ?: return@withContext Result.Cancelled

                val (code, state) = callback
                if (state != null && state != pkce.state) {
                    AppLogger.warning(TAG, "[Authorize] '$service' state mismatch")
                    return@withContext Result.Failed("State mismatch in the sign-in callback.")
                }
                exchangeCode(code, redirectUri(server?.boundPort ?: LOOPBACK_PORT), pkce.verifier, service)
            } finally {
                server?.stop()
            }
        }
    }

    private fun redirectUri(port: Int) = "http://127.0.0.1:$port/"

    private fun buildAuthUrl(pkce: McpPkce.PkceParams, redirectUri: String, scopes: String): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return buildString {
            append(AUTH_ENDPOINT)
            append("?client_id=").append(enc(CLIENT_ID))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&response_type=code")
            append("&scope=").append(enc(scopes))
            append("&code_challenge=").append(enc(pkce.challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(enc(pkce.state))
            append("&access_type=offline")
            append("&prompt=consent")
        }
    }

    private fun exchangeCode(code: String, redirectUri: String, verifier: String, service: String): Result {
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
                    AppLogger.error(TAG, "[Authorize] '$service' token exchange HTTP ${resp.code}")
                    return Result.Failed("Google sign-in failed (${resp.code}).")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) return Result.Failed("Google returned no access token.")
                val expiresIn = json.optLong("expires_in", 0L)
                val tokens = Tokens(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token", "").ifBlank { null },
                    expiresAtMs = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                )
                AppLogger.info(TAG, "[Authorize] '$service' OK (hasRefresh=${json.has("refresh_token")})")
                Result.Success(tokens, fetchAccountEmail(access))
            }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Authorize] '$service' token exchange failed: ${t.message}")
            Result.Failed("Google sign-in failed: ${t.message}")
        }
    }

    /** Usable access token for [store], refreshing silently when expired. Null = not connected. */
    suspend fun validAccessToken(context: Context, store: GoogleTokenStore): String? =
        withContext(Dispatchers.IO) {
            val stored = store.getTokens(context) ?: return@withContext null
            if (!stored.needsRefresh()) return@withContext stored.accessToken
            val refresh = stored.refreshToken ?: return@withContext null
            refreshAccessToken(context, store, refresh)
        }

    private fun refreshAccessToken(context: Context, store: GoogleTokenStore, refreshToken: String): String? {
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
                    AppLogger.warning(TAG, "[Refresh] '${store.service}' HTTP ${resp.code}")
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
            AppLogger.warning(TAG, "[Refresh] '${store.service}' failed: ${t.message}")
            null
        }
    }

    /** Disconnect: revoke at Google (best-effort) and wipe local tokens. */
    suspend fun disconnect(context: Context, store: GoogleTokenStore) = withContext(Dispatchers.IO) {
        val token = store.getTokens(context)?.accessToken
        store.clear(context)
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
        AppLogger.info(TAG, "[Disconnect] '${store.service}' tokens cleared")
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
}
