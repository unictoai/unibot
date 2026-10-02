package ai.unicto.unibot.connectors.google

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import org.json.JSONObject

/**
 * Encrypted per-service token storage for Google connectors (Drive, Calendar…).
 * One service = one scope set = one independent connect/disconnect.
 * Tokens live in EncryptedSharedPreferences — never in plain prefs or logs.
 */
class GoogleTokenStore(val service: String) {

    private val file = "google_connector_$service"
    private val keyTokens = "tokens"
    private val keyAccount = "account_email"

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, file)
                .also { prefsRef = it }
        }

    fun isConnected(context: Context): Boolean =
        prefs(context).getString(keyTokens, null)?.isNotEmpty() == true

    fun getTokens(context: Context): GoogleOAuth.Tokens? {
        val raw = prefs(context).getString(keyTokens, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val access = j.optString("access_token", "")
            if (access.isEmpty()) return null
            GoogleOAuth.Tokens(
                accessToken = access,
                refreshToken = j.optString("refresh_token", "").ifBlank { null },
                expiresAtMs = j.optLong("expires_at_ms", 0L),
            )
        }.onFailure {
            AppLogger.warning(TAG, "'$service' stored tokens unreadable: ${it.message}")
        }.getOrNull()
    }

    fun setTokens(context: Context, tokens: GoogleOAuth.Tokens) {
        val j = JSONObject().apply {
            put("access_token", tokens.accessToken)
            tokens.refreshToken?.let { put("refresh_token", it) }
            put("expires_at_ms", tokens.expiresAtMs)
        }
        prefs(context).edit().putString(keyTokens, j.toString()).apply()
    }

    fun accountEmail(context: Context): String? =
        prefs(context).getString(keyAccount, null)?.takeIf { it.isNotBlank() }

    fun setAccountEmail(context: Context, email: String?) {
        prefs(context).edit().apply {
            if (email.isNullOrBlank()) remove(keyAccount) else putString(keyAccount, email)
        }.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(keyTokens).remove(keyAccount).apply()
    }

    companion object {
        private const val TAG = "GoogleConnector"
    }
}
