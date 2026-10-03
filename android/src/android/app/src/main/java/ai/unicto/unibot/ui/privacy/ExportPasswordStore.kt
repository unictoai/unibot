package ai.unicto.unibot.ui.privacy

import android.content.Context
import android.content.SharedPreferences
import ai.unicto.unibot.util.EncryptedPrefsFactory

/**
 * [v12-D] The chat-export password, kept on-device only.
 *
 * Stored via [EncryptedPrefsFactory] (AndroidKeystore-backed
 * EncryptedSharedPreferences, with the same self-healing wipe + plain
 * fallback the rest of the app uses for credentials). The password itself
 * is never logged, never leaves the phone, and is only read on the IO
 * dispatcher at export time.
 *
 * Invariant: protection is enabled IFF a password is stored — [setPassword]
 * writes both, [clear] removes both, so a half-written state can't enable
 * "protection" with no password to protect with.
 */
object ExportPasswordStore {

    private const val FILE = "v12_export_password"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ENABLED = "protection_enabled"

    private fun prefs(context: Context): SharedPreferences =
        EncryptedPrefsFactory.safeCreate(
            context.applicationContext,
            FILE,
        )

    /** True when a password is stored (implies protection is enabled). */
    fun hasPassword(context: Context): Boolean =
        runCatching { prefs(context).contains(KEY_PASSWORD) }.getOrDefault(false)

    /**
     * The password to encrypt with, or null when protection is off / the
     * store is unreadable. Call off the main thread — keystore IO.
     */
    fun getPasswordIfEnabled(context: Context): String? = runCatching {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ENABLED, false)) return@runCatching null
        p.getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Store [password] and enable protection. Returns false when the
     * password is blank or the store write failed.
     */
    fun setPassword(context: Context, password: String): Boolean {
        if (password.isBlank()) return false
        return runCatching {
            prefs(context).edit()
                .putString(KEY_PASSWORD, password)
                .putBoolean(KEY_ENABLED, true)
                .apply()
            true
        }.getOrDefault(false)
    }

    /** Remove the password and disable protection. */
    fun clear(context: Context) {
        runCatching {
            prefs(context).edit()
                .remove(KEY_PASSWORD)
                .putBoolean(KEY_ENABLED, false)
                .apply()
        }
    }
}
