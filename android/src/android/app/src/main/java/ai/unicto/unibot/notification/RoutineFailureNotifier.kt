package ai.unicto.unibot.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.unicto.unibot.R
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.scheduled.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts a notification when a scheduled routine run fails.
 *
 * Unlike the completion notifiers, this one fires even when the app is in
 * the foreground: routine failures are rare and actionable, and the user is
 * usually on a different screen when they happen. The tap deep-links to the
 * routine's run history so the failure preview is one tap away.
 *
 * Behaviour rules (same battery discipline as the other notifiers — the
 * notification is a side effect of the recorded run, delivered on IO):
 * - Skip silently when POST_NOTIFICATIONS is not granted (Android 13+).
 * - Never throws — logging only.
 */
class RoutineFailureNotifier(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /** Called after a failed run has been recorded. */
    fun notifyFailed(task: ScheduledTask, preview: String?) {
        scope.launch {
            try {
                val label = task.label.trim().takeIf { it.isNotBlank() }?.take(60)
                    ?: context.getString(R.string.notif_routine_failed_default_title)
                val title = context.getString(R.string.notif_routine_failed_title, label)
                val body = preview?.trim()?.takeIf { it.isNotBlank() }?.take(240)
                    ?: context.getString(R.string.notif_routine_failed_body_default)
                postNotification(title, body, task.id.hashCode())
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyFailed failed: ${t.message}")
            }
        }
    }

    private fun postNotification(title: String, body: String, idSeed: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        // Deep-link to the scheduled tasks screen (the run history is there).
        val deepLink = Uri.parse("unibot://scheduled")
        val launchIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_BASE + (idSeed and 0xFFFF),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_unibot)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

        try {
            nm.notify(NOTIFICATION_ID_BASE + (idSeed and 0xFFFF), notification)
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_routine_failed_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_routine_failed_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RoutineFailureNotifier"
        private const val CHANNEL_ID = "routine_failures"
        private const val NOTIFICATION_ID_BASE = 0x5EED00
    }
}
