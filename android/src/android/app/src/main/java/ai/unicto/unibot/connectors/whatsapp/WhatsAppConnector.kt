package ai.unicto.unibot.connectors.whatsapp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.service.WhatsAppListenerService
import ai.unicto.unibot.util.EncryptedPrefsFactory

/**
 * WhatsApp connector — HONEST scope only.
 *
 * There is no official WhatsApp API for personal accounts, so this
 * connector deliberately does NOT pretend to be one:
 *
 * - OUTGOING (`whatsapp_share`): opens WhatsApp's share sheet with the
 *   text pre-filled. The user reviews and taps send INSIDE WhatsApp.
 *   Nothing is ever sent silently. No auth, no tokens, always available
 *   when the WhatsApp app is installed.
 * - INCOMING (`whatsapp_recent`): an opt-in [WhatsAppListenerService]
 *   reads WhatsApp notifications ON THIS PHONE ONLY into an in-memory
 *   ring buffer. Nothing is logged, persisted, or uploaded — ever.
 */
object WhatsAppConnector {

    const val PACKAGE = "com.whatsapp"

    private const val TAG = "WhatsAppConnector"
    private const val PREFS_FILE = "whatsapp_connector"
    private const val KEY_LISTENER_WANTED = "listener_wanted"

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_FILE)
                .also { prefsRef = it }
        }

    private fun shareIntent(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            setPackage(PACKAGE)
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * True when WhatsApp is installed (the share sheet has a target).
     *
     * NOTE (Android 11+): without a `<queries>` entry for com.whatsapp,
     * resolveActivity is filtered by package visibility and may report
     * null even when WhatsApp IS installed. The tool treats that as
     * "not installed"; adding the one-line queries entry is the fix.
     */
    fun isWhatsAppInstalled(context: Context): Boolean =
        shareIntent("").resolveActivity(context.packageManager) != null

    /**
     * Open WhatsApp with [text] pre-filled in the share sheet.
     *
     * The user confirms and sends INSIDE WhatsApp — this never sends
     * anything silently. The agent cannot bypass the user's tap.
     */
    fun share(context: Context, text: String): ApiResult<String> {
        val intent = shareIntent(text)
        if (intent.resolveActivity(context.packageManager) == null) {
            return ApiResult.Error("WhatsApp is not installed")
        }
        return try {
            context.startActivity(intent)
            ApiResult.Ok(
                "WhatsApp opened with the message pre-filled. " +
                    "Review and tap send inside WhatsApp — the agent cannot send it for you.",
            )
        } catch (e: ActivityNotFoundException) {
            AppLogger.warning(TAG, "[share] WhatsApp activity missing: ${e.message}")
            ApiResult.Error("WhatsApp is not installed")
        }
    }

    /** In-app opt-in flag for the notification reader (Settings → Connectors). */
    fun isListenerWanted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LISTENER_WANTED, false)

    /**
     * Persist the reader opt-in. Turning it off also wipes the in-memory
     * buffer immediately; the listener service additionally re-checks this
     * flag before buffering anything.
     */
    fun setListenerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LISTENER_WANTED, enabled).apply()
        if (!enabled) WhatsAppInbox.clear()
    }

    /**
     * Open the system Notification Access settings page — the ONLY way
     * Android lets a user grant a NotificationListenerService.
     */
    fun requestAccess(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * True when OUR listener component specifically has Notification
     * Access. Mirrors UnibotNotificationListenerService.isEnabled but
     * matches our component, not just the package — this app hosts two
     * listener services and they are granted independently, so the
     * package-level check (NotificationManagerCompat
     * .getEnabledListenerPackages) would be imprecise here.
     */
    fun isSystemListenerGranted(context: Context): Boolean {
        val target = ComponentName(context, WhatsAppListenerService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.split(':').any { entry ->
            runCatching { ComponentName.unflattenFromString(entry) }.getOrNull() == target
        }
    }

    /**
     * Effective reader state: the user opted in in-app AND the system
     * grant is present. This is what gates `whatsapp_recent`.
     */
    fun isListenerEnabled(context: Context): Boolean =
        isListenerWanted(context) && isSystemListenerGranted(context)

    /** Buffered WhatsApp notifications (in-memory, oldest first). */
    fun recentMessages(): List<WhatsAppInbox.Entry> = WhatsAppInbox.recent()
}
