package ai.unicto.unibot.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import ai.unicto.unibot.MainActivity
import ai.unicto.unibot.R
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.ui.chat.ChatViewModelStore

/**
 * [v12-G] Notification quick-reply for agent "task completed" notifications.
 *
 * How it routes (no new manifest entries — MainActivity is already
 * singleTask and owns the deep-link path):
 * 1. [buildReplyAction] adds a RemoteInput "Reply" action to the
 *    notification posted by [BackgroundTaskNotifier]. The action's
 *    PendingIntent targets [QuickReplyReceiver] (non-exported) with the
 *    `unibot://session/<id>` data URI, plus [ACTION_QUICK_REPLY].
 * 2. [QuickReplyReceiver] pulls the typed text from RemoteInput results,
 *    stashes it in [ChatViewModelStore], and starts MainActivity via the
 *    deep-link path so the chat opens.
 *
 * [Security] MainActivity is exported for the launcher and must NOT accept
 * ACTION_QUICK_REPLY — any app could forge the intent and auto-send text
 * to the agent. The non-exported QuickReplyReceiver is the only entry point.
 * 3. ChatScreen drains the stash on first composition and calls
 *    `ChatViewModel.sendMessage(text)` — the reply is SENT, not just
 *    prefilled (a notification reply the user still has to press send on
 *    is a broken promise).
 */
object QuickReplyHandler {

    const val ACTION_QUICK_REPLY = "ai.unicto.unibot.notification.QUICK_REPLY"
    const val EXTRA_SESSION_ID = "quick_reply_session_id"

    /** RemoteInput result key — the bundle key the typed text arrives under. */
    private const val KEY_TEXT_REPLY = "key_text_reply"

    private const val TAG = "QuickReply"

    /**
     * The "Reply" action for [BackgroundTaskNotifier] notifications.
     * Kept here (not in the notifier) so the intent contract lives next to
     * the code that reads it.
     */
    fun buildReplyAction(context: Context, sessionId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.v12g_notif_quick_reply))
            .build()

        // Same deep-link data as the content tap: the chat opens through the
        // existing path either way. The action + extra mark it as a reply so
        // QuickReplyReceiver (non-exported) stashes the text before navigating.
        // [Security] Use getBroadcast to a non-exported receiver — MainActivity
        // is exported, so a forged intent there would auto-send attacker text.
        // FLAG_MUTABLE is required for RemoteInput to work on Android 12+.
        val replyIntent = Intent(context, QuickReplyReceiver::class.java).apply {
            action = ACTION_QUICK_REPLY
            data = Uri.parse("unibot://session/$sessionId")
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ("v12g-qr$sessionId").hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            /* icon = */ 0,
            context.getString(R.string.v12g_notif_quick_reply),
            pendingIntent,
        ).addRemoteInput(remoteInput).build()
    }

    /**
     * Called from MainActivity.onNewIntent and onCreate (cold start) before
     * deep-link handling. Returns true when [intent] was a quick-reply
     * intent; the caller should still run the normal deep-link path so the
     * chat opens on the session.
     */
    // [Security] handleIntent was removed — MainActivity (exported) must not
    // accept ACTION_QUICK_REPLY. See QuickReplyReceiver (non-exported).
}
