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
import ai.unicto.unibot.swarm.SwarmLifecycle
import ai.unicto.unibot.swarm.SwarmUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v1.4.0 item 11: posts a completion notification when a swarm mission
 * reaches DONE or FAILED while the app is backgrounded.
 *
 * Behaviour rules (battery-disciplined: no polling, no wake locks — the
 * notification is a side effect of the terminal transition that already
 * happened, delivered on an IO thread):
 * - Skip silently if the user disabled completion notifications
 *   ([enabled] = false).
 * - Skip silently if the app is currently in foreground — the user is
 *   already looking at the result, no need to interrupt.
 * - Skip silently when POST_NOTIFICATIONS is not granted (Android 13+).
 * - Never fires for user-cancelled runs — only DONE and FAILED reach here
 *   (the engine's terminal hook only fires on those two outcomes).
 * - Tap deep-links into the swarm space via `unibot://swarm` (the
 *   DeepLinkHandler OpenSwarm path) so the user lands on the report.
 *
 * Wired by the swarm destination: the SwarmViewModel's onTerminal hook
 * calls [notifyCompleted] with the terminal UI state.
 */
class SwarmCompletionNotifier(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val isAppForeground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /** Called from the engine's terminal hook (DONE or FAILED). */
    fun notifyCompleted(state: SwarmUiState) {
        if (!enabled()) return
        if (isAppForeground()) return
        if (state.lifecycle != SwarmLifecycle.DONE && state.lifecycle != SwarmLifecycle.FAILED) return

        scope.launch {
            try {
                val failed = state.lifecycle == SwarmLifecycle.FAILED
                val missionTitle = state.mission.trim().takeIf { it.isNotBlank() }
                    ?.let { if (it.length > 60) it.take(60) + "…" else it }
                    ?: context.getString(R.string.notif_swarm_completed_default_title)
                val titleRes = if (failed) {
                    R.string.notif_swarm_failed_title
                } else {
                    R.string.notif_swarm_completed_title
                }
                val title = context.getString(titleRes)
                val body = context.getString(R.string.notif_swarm_completed_body, missionTitle)
                postNotification(title, body, state.mission.hashCode())
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "notifyCompleted failed: ${t.message}")
            }
        }
    }

    private fun postNotification(title: String, body: String, idSeed: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }

        val deepLink = Uri.parse("unibot://swarm")
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
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
            context.getString(R.string.notif_swarm_completed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_swarm_completed_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SwarmNotifier"
        const val CHANNEL_ID = "unibot_swarm_completed"
        private const val NOTIFICATION_ID_BASE = 9100
    }
}
