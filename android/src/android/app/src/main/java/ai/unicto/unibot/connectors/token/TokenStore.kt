package ai.unicto.unibot.connectors.token

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.util.EncryptedPrefsFactory

/**
 * Encrypted storage for token-based connectors (GitHub PAT, Telegram bot
 * token, …). The token is the user's own secret, entered by them in
 * Settings → Connectors — it never leaves the device except to the
 * service's own API, and never appears in logs or chat.
 */
class TokenStore(val service: String) {

    private val file = "token_connector_$service"

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, file)
                .also { prefsRef = it }
        }

    fun isConnected(context: Context): Boolean =
        prefs(context).getString("token", null)?.isNotEmpty() == true

    fun getToken(context: Context): String? =
        prefs(context).getString("token", null)?.takeIf { it.isNotEmpty() }

    fun setToken(context: Context, token: String?) {
        prefs(context).edit().apply {
            if (token.isNullOrBlank()) remove("token") else putString("token", token.trim())
        }.apply()
    }

    fun label(context: Context): String? =
        prefs(context).getString("label", null)?.takeIf { it.isNotBlank() }

    fun setLabel(context: Context, label: String?) {
        prefs(context).edit().apply {
            if (label.isNullOrBlank()) remove("label") else putString("label", label)
        }.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove("token").remove("label").apply()
    }
}
