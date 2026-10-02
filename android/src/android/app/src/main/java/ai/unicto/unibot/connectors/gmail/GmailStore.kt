package ai.unicto.unibot.connectors.gmail

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import org.json.JSONObject

/**
 * Encrypted storage for the Gmail connector's OAuth tokens.
 *
 * Mirrors [ai.unicto.unibot.mcp.oauth.MCPOAuthStore] but scoped to the single
 * Gmail connector. Access/refresh tokens live in EncryptedSharedPreferences —
 * never in plain prefs, logs, or chat history.
 */
object GmailStore {

    private const val TAG = "GmailConnector"
    private const val FILE = "gmail_connector_secrets"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_ACCOUNT = "account_email"

    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    ) {
        /** True when the access token should be refreshed before use (60s skew). */
        fun needsRefresh(nowMs: Long = System.currentTimeMillis()): Boolean =
            expiresAtMs != 0L && nowMs + 60_000 >= expiresAtMs
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
                .also { prefsRef = it }
        }

    fun isConnected(context: Context): Boolean =
        prefs(context).getString(KEY_TOKENS, null)?.isNotEmpty() == true

    fun getTokens(context: Context): StoredTokens? {
        val raw = prefs(context).getString(KEY_TOKENS, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val access = j.optString("access_token", "")
            if (access.isEmpty()) return null
            StoredTokens(
                accessToken = access,
                refreshToken = j.optString("refresh_token", "").ifBlank { null },
                expiresAtMs = j.optLong("expires_at_ms", 0L),
            )
        }.onFailure {
            AppLogger.warning(TAG, "stored Gmail tokens unreadable: ${it.message}")
        }.getOrNull()
    }

    fun setTokens(context: Context, tokens: StoredTokens) {
        val j = JSONObject().apply {
            put("access_token", tokens.accessToken)
            tokens.refreshToken?.let { put("refresh_token", it) }
            put("expires_at_ms", tokens.expiresAtMs)
        }
        prefs(context).edit().putString(KEY_TOKENS, j.toString()).apply()
    }

    fun accountEmail(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT, null)?.takeIf { it.isNotBlank() }

    fun setAccountEmail(context: Context, email: String?) {
        prefs(context).edit().apply {
            if (email.isNullOrBlank()) remove(KEY_ACCOUNT) else putString(KEY_ACCOUNT, email)
        }.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TOKENS).remove(KEY_ACCOUNT).apply()
    }
}
