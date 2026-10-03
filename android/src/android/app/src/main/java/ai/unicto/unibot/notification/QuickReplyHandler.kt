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
 *
 * 1. [buildReplyAction] adds a RemoteInput "Reply" action to the
 *    notification posted by [BackgroundTaskNotifier]. The action's
 *    PendingIntent targets MainActivity with the SAME
 *    `unibot://session/<id>` data URI a normal tap uses, plus
 *    [ACTION_QUICK_REPLY] so MainActivity can tell the two apart.
 * 2. MainActivity.onNewIntent / onCreate calls [handleIntent] BEFORE the
 *    deep-link handling. It pulls the typed text out of the RemoteInput
 *    results, stashes it in [ChatViewModelStore] addressed to the session,
 *    and dismisses the notification. Navigation then proceeds through the
 *    untouched OpenSession path, so the chat opens exactly as if tapped.
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
        // MainActivity stashes the text before navigating.
        val replyIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_QUICK_REPLY
            data = Uri.parse("unibot://session/$sessionId")
            putExtra(EXTRA_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ("v12g-qr$sessionId").hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
    fun handleIntent(context: Context, intent: Intent?): Boolean {
        if (intent?.action != ACTION_QUICK_REPLY) return false
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?: intent.data?.pathSegments?.getOrNull(0)
            ?: return false

        val results = runCatching { RemoteInput.getResultsFromIntent(intent) }.getOrNull()
        val text = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            ChatViewModelStore.stashPendingQuickReply(sessionId, text)
            AppLogger.info(TAG, "quick reply stashed for session=$sessionId (${text.length}ch)")
        } else {
            // Tapped "Reply" but sent nothing — just open the chat.
            AppLogger.info(TAG, "quick reply with empty text for session=$sessionId — opening chat")
        }
        // The reply is on its way into the chat; the notification has served
        // its purpose. (BackgroundTaskNotifier posts with id session.hashCode().)
        runCatching {
            NotificationManagerCompat.from(context).cancel(sessionId.hashCode())
        }
        return true
    }
}
