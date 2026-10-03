package ai.unicto.unibot.share

import android.content.Context
import ai.unicto.unibot.util.EncryptedPrefsFactory

/**
 * [v12-D] Stores the optional chat-export password in AndroidKeystore-backed
 * encrypted storage. While a password is set, [ChatExporter] encrypts every
 * export archive with it (WinZip AES-256).
 */
object ExportPasswordStore {
    private const val PREFS = "export_password_store"
    private const val KEY_PASSWORD = "export_password"

    private fun prefs(context: Context) =
        EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS)

    fun hasPassword(context: Context): Boolean =
        !prefs(context).getString(KEY_PASSWORD, null).isNullOrEmpty()

    /** Returns the password, or null when the user hasn't set one. */
    fun getPasswordIfEnabled(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun setPassword(context: Context, password: String) {
        prefs(context).edit().putString(KEY_PASSWORD, password).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PASSWORD).apply()
    }
}
