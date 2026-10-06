package ai.unicto.unibot.connectors.outlook

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import org.json.JSONObject

/**
 * User-configurable Azure app registration slot for the Outlook connector.
 *
 * unibot ships with no Microsoft client ID (BYOK principle: the user brings
 * their own Azure app registration). The user pastes their Application
 * (client) ID, tenant, and redirect URI once in Settings → Connectors →
 * Outlook → Azure app setup; the connector lights up the moment the slot
 * is filled. Stored in EncryptedSharedPreferences alongside the tokens —
 * the client ID is public, but keeping it with the credential store avoids
 * a second storage path and a second failure mode.
 *
 * Pure JSON round-trip ([toJson]/[fromJson]) is unit-testable without
 * Android; the prefs wrapper below is intentionally thin.
 */
data class OutlookAzureConfig(
    /** Application (client) ID from portal.azure.com. Empty = not set up. */
    val clientId: String = "",
    /** Directory (tenant) ID, or "common"/"organizations"/"consumers". */
    val tenant: String = DEFAULT_TENANT,
    /**
     * Redirect URI registered in the Azure app. The default loopback set
     * (http://127.0.0.1:53712/callback … :53715/callback) is tried first;
     * a custom value here overrides it (e.g. a custom scheme for builds
     * where loopback is blocked).
     */
    val redirectUri: String = "",
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    fun toJson(): String = JSONObject().apply {
        put("client_id", clientId)
        put("tenant", tenant)
        put("redirect_uri", redirectUri)
    }.toString()

    companion object {
        const val DEFAULT_TENANT = "common"
        private const val TAG = "OutlookAzureConfig"

        fun fromJson(raw: String?): OutlookAzureConfig {
            if (raw.isNullOrBlank()) return OutlookAzureConfig()
            return runCatching {
                val j = JSONObject(raw)
                OutlookAzureConfig(
                    clientId = j.optString("client_id", ""),
                    tenant = j.optString("tenant", "").ifBlank { DEFAULT_TENANT },
                    redirectUri = j.optString("redirect_uri", ""),
                )
            }.onFailure {
                AppLogger.warning(TAG, "stored azure config unreadable: ${it.message}")
            }.getOrDefault(OutlookAzureConfig())
        }
    }
}

/** Encrypted persistence for [OutlookAzureConfig]. */
class OutlookAzureConfigStore {

    private val file = "outlook_azure_config"
    private val keyConfig = "azure_config"

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, file)
                .also { prefsRef = it }
        }

    fun load(context: Context): OutlookAzureConfig =
        OutlookAzureConfig.fromJson(prefs(context).getString(keyConfig, null))

    fun save(context: Context, config: OutlookAzureConfig) {
        prefs(context).edit().putString(keyConfig, config.toJson()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(keyConfig).apply()
    }

    fun isConfigured(context: Context): Boolean = load(context).isConfigured
}
