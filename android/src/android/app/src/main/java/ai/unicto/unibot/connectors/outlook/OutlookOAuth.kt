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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Microsoft identity platform OAuth 2.0 (Authorization Code + PKCE).
 * Mirrors [ai.unicto.unibot.connectors.spotify.SpotifyOAuth]: a public
 * client needs no client secret, so nothing sensitive is embedded in the
 * APK.
 *
 * The Azure app registration is USER-CONFIGURED (BYOK): the user pastes
 * their Application (client) ID, tenant, and redirect URI once in
 * Settings → Connectors → Outlook → Azure app setup
 * ([OutlookAzureConfigStore]). Until the slot is filled [isConfigured]
 * is false and the Connectors screen shows the setup panel instead of a
 * broken connect button.
 *
 * Two redirect modes:
 * - Loopback (default): http://127.0.0.1:53712/callback (…53715 fallback).
 *   Register all four in the Azure app. A tiny localhost server captures
 *   the code — RFC 8252.
 * - Custom scheme: any `unibot://…` URI (e.g. `unibot://oauth/outlook`),
 *   captured by the app's existing intent filter. The code/state arrive via
 *   [onCustomSchemeCallback], which resumes the pending [authorize] call.
 */
object OutlookOAuth {

    private const val TAG = "OutlookOAuth"

    /**
     * The OAuth `state` returned by the provider must be present AND equal to
     * the one we generated. A missing state is rejected — accepting it would
     * let a crafted callback complete a flow the app didn't start.
     */
    internal fun isValidState(returnedState: String?, expectedState: String): Boolean =
        returnedState == expectedState

    val configStore = OutlookAzureConfigStore()

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

    /** True once the user has filled the Azure app slot in settings. */
    fun isConfigured(context: Context): Boolean = configStore.isConfigured(context)

    fun loadConfig(context: Context): OutlookAzureConfig = configStore.load(context)

    fun saveConfig(context: Context, config: OutlookAzureConfig) =
        configStore.save(context, config)

    private fun authEndpoint(tenant: String) =
        "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize"

    private fun tokenEndpoint(tenant: String) =
        "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token"

    /**
     * Pending custom-scheme continuation. Set by [authorize] when the user
     * configured a `unibot://` redirect URI; resumed by
     * [onCustomSchemeCallback] from MainActivity's deep-link path.
     */
    @Volatile
    private var pendingCustomScheme: Continuation<Pair<String, String?>?>? = null

    /**
     * Called from the app's deep-link path for `unibot://oauth/outlook`
     * redirects. Returns true when a pending Outlook authorize consumed it.
     */
    fun onCustomSchemeCallback(code: String, state: String?): Boolean {
        val cont = pendingCustomScheme ?: return false
        pendingCustomScheme = null
        if (code.isBlank()) {
            AppLogger.warning(TAG, "[CustomScheme] no code in redirect")
            cont.resume(null)
        } else {
            cont.resume(code to state)
        }
        return true
    }

    /** Run the full authorize flow. Never throws. Must be called from a coroutine. */
    suspend fun authorize(context: Context): Result {
        val config = configStore.load(context)
        if (!config.isConfigured) {
            return Result.Failed(
                "Outlook needs setup: open Settings → Connectors → Outlook → " +
                    "Azure app setup and paste your Application (client) ID.",
            )
        }
        return withContext(Dispatchers.IO) {
            val pkce = McpPkce.newPkce()
            val tenant = config.tenant.ifBlank { OutlookAzureConfig.DEFAULT_TENANT }
            if (config.redirectUri.isBlank()) {
                authorizeLoopback(context, config, tenant, pkce)
            } else {
                authorizeCustomScheme(context, config, tenant, pkce)
            }
        }
    }

    private suspend fun authorizeLoopback(
        context: Context,
        config: OutlookAzureConfig,
        tenant: String,
        pkce: McpPkce.PkceParams,
    ): Result {
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
                val boundPort = srv.boundPort
                val authUrl = buildAuthUrl(
                    config, tenant, pkce, redirectUri(boundPort),
                )
                CustomTabsIntent.Builder().setShowTitle(true).build()
                    .launchUrl(context, Uri.parse(authUrl))
                AppLogger.info(TAG, "[Authorize] opened Custom Tab (loopback)")
            } ?: return Result.Cancelled

            val (code, state) = callback
            // state must be present AND match (see isValidState).
            if (!isValidState(state, pkce.state)) {
                AppLogger.warning(TAG, "[Authorize] state missing or mismatch")
                return Result.Failed("State mismatch in the sign-in callback.")
            }
            return exchangeCode(
                config, tenant, code,
                redirectUri(server?.boundPort ?: LOOPBACK_PORT), pkce.verifier,
            )
        } finally {
            server?.stop()
        }
    }

    private suspend fun authorizeCustomScheme(
        context: Context,
        config: OutlookAzureConfig,
        tenant: String,
        pkce: McpPkce.PkceParams,
    ): Result {
        val callback = suspendCancellableCoroutine<Pair<String, String?>?> { cont ->
            pendingCustomScheme = cont
            cont.invokeOnCancellation { pendingCustomScheme = null }
            val authUrl = buildAuthUrl(config, tenant, pkce, config.redirectUri)
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(context, Uri.parse(authUrl))
            AppLogger.info(TAG, "[Authorize] opened Custom Tab (custom scheme)")
        } ?: return Result.Cancelled

        val (code, state) = callback
        // state must be present AND match (see isValidState).
        if (!isValidState(state, pkce.state)) {
            AppLogger.warning(TAG, "[Authorize] state missing or mismatch")
            return Result.Failed("State mismatch in the sign-in callback.")
        }
        return exchangeCode(config, tenant, code, config.redirectUri, pkce.verifier)
    }

    private fun redirectUri(port: Int) = "http://127.0.0.1:$port/callback"

    fun loopbackRedirectUris(): List<String> =
        listOf(LOOPBACK_PORT).plus(FALLBACK_PORTS).map { redirectUri(it) }

    private fun buildAuthUrl(
        config: OutlookAzureConfig,
        tenant: String,
        pkce: McpPkce.PkceParams,
        redirectUri: String,
    ): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return buildString {
            append(authEndpoint(tenant))
            append("?client_id=").append(enc(config.clientId))
            append("&response_type=code")
            append("&redirect_uri=").append(enc(redirectUri))
            append("&scope=").append(enc(SCOPES))
            append("&code_challenge=").append(enc(pkce.challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(enc(pkce.state))
        }
    }

    private fun exchangeCode(
        config: OutlookAzureConfig,
        tenant: String,
        code: String,
        redirectUri: String,
        verifier: String,
    ): Result {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val form = "grant_type=authorization_code" +
            "&code=${enc(code)}" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&client_id=${enc(config.clientId)}" +
            "&code_verifier=${enc(verifier)}"
        return postTokenForm(config, tenant, form, "authorize")
    }

    /** Usable access token for [store], refreshing silently when expired. Null = not connected. */
    suspend fun validAccessToken(context: Context, store: OutlookTokenStore): String? =
        withContext(Dispatchers.IO) {
            val stored = store.getTokens(context) ?: return@withContext null
            if (!stored.needsRefresh()) return@withContext stored.accessToken
            val refresh = stored.refreshToken ?: return@withContext null
            val config = configStore.load(context)
            val tenant = config.tenant.ifBlank { OutlookAzureConfig.DEFAULT_TENANT }
            refreshAccessToken(context, config, tenant, store, refresh)
        }

    private fun refreshAccessToken(
        context: Context,
        config: OutlookAzureConfig,
        tenant: String,
        store: OutlookTokenStore,
        refreshToken: String,
    ): String? {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val form = "grant_type=refresh_token" +
            "&refresh_token=${enc(refreshToken)}" +
            "&client_id=${enc(config.clientId)}"
        val request = Request.Builder()
            .url(tokenEndpoint(tenant))
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

    private fun postTokenForm(
        config: OutlookAzureConfig,
        tenant: String,
        form: String,
        op: String,
    ): Result {
        val request = Request.Builder()
            .url(tokenEndpoint(tenant))
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
