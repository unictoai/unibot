package ai.unicto.unibot.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import ai.unicto.unibot.util.AppLogger

/**
 * Non-exported receiver for notification quick-replies.
 *
 * [Security] MainActivity is exported (launcher), so it cannot safely
 * accept ACTION_QUICK_REPLY from any app — a forged intent would auto-send
 * attacker text to the agent. This receiver is NOT exported, so only the
 * system (via our PendingIntent) or our own app can trigger it.
 */
class QuickReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickReplyHandler.ACTION_QUICK_REPLY) return

        val sessionId = intent.getStringExtra(QuickReplyHandler.EXTRA_SESSION_ID)
            ?: intent.data?.pathSegments?.getOrNull(0)
            ?: return

        // [Security] A reply is only meaningful for a session that has an
        // outstanding notification. Never accept the synthetic `__new__*`
        // draft ids — those would stash attacker text into a fresh chat.
        if (sessionId.startsWith("__new__")) {
            AppLogger.warning(TAG, "quick reply for synthetic session id rejected")
            return
        }

        val results = runCatching { RemoteInput.getResultsFromIntent(intent) }.getOrNull()
        val text = results?.getCharSequence(QuickReplyHandler.KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()

        if (text.isNotEmpty()) {
            ChatViewModelStore.stashPendingQuickReply(sessionId, text)
            AppLogger.info(TAG, "quick reply stashed for session=$sessionId (${text.length}ch)")
        }

        // Dismiss the notification — the reply is on its way.
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(sessionId.hashCode())
        }

        // Open the chat on the session via the existing deep-link path.
        val openIntent = Intent(context, Class.forName("ai.unicto.unibot.MainActivity")).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("unibot://session/$sessionId")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(openIntent)
    }

    companion object {
        private const val TAG = "QuickReplyReceiver"
    }
}
