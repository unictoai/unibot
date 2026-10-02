package ai.unicto.unibot.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.unicto.unibot.connectors.whatsapp.WhatsAppConnector
import ai.unicto.unibot.connectors.whatsapp.WhatsAppInbox

/**
 * Opt-in WhatsApp notification reader.
 *
 * PRIVACY — READ THIS:
 * - ON-DEVICE ONLY: captured notifications are appended to the in-memory
 *   [WhatsAppInbox] ring buffer and NEVER leave this phone. Nothing is
 *   logged, persisted to disk, synced, or uploaded — ever.
 * - IN-MEMORY ONLY: the buffer is lost on process death. That is BY
 *   DESIGN, for privacy: no WhatsApp message content survives a restart.
 * - WHATSAPP ONLY: notifications from every other package are ignored.
 * - OPT-IN: the service is inert unless the user both toggles the reader
 *   on in Settings → Connectors AND grants Notification Access in system
 *   settings (the only place Android allows that grant).
 *
 * HONEST SCOPE: there is no official WhatsApp API for personal accounts.
 * This is a notification reader, not a WhatsApp client — it cannot send,
 * receive, or sync anything through WhatsApp itself.
 */
class WhatsAppListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // WhatsApp only — ignore everything else.
        if (sbn.packageName != WhatsAppConnector.PACKAGE) return
        // In-app kill switch: the system only binds this service when the
        // OS-level grant exists, but the user can also flip the reader off
        // in-app (which stops buffering immediately).
        if (!WhatsAppConnector.isListenerWanted(applicationContext)) return
        val extras = sbn.notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank()) return
        // Never logged, never persisted — straight into the in-memory buffer.
        WhatsAppInbox.add(
            WhatsAppInbox.Entry(
                sender = sender.ifBlank { "WhatsApp" },
                text = text,
                receivedAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op: removals do not affect the in-memory buffer.
    }
}
