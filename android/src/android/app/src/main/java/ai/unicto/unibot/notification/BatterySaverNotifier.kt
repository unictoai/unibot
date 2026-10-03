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

/**
 * [v12-G] The visible indicator for Battery saver mode: a low-priority
 * ONGOING notification that lives exactly as long as the mode is on.
 * Tapping it opens Settings (where the toggle lives). It is posted and
 * removed by [ai.unicto.unibot.ui.settings.BatterySaverStore.setEnabled],
 * so the indicator and the toggle can never disagree.
 *
 * Ongoing (not dismissible by swipe) is deliberate: this is a mode, like
 * a VPN or DND — swiping away the evidence while the behaviour persists
 * is how "placebo" accusations start.
 */
object BatterySaverNotifier {

    private const val TAG = "BatterySaverNotif"
    private const val CHANNEL_ID = "unibot_battery_saver"
    private const val NOTIF_ID = 0xB477E5

    fun show(context: Context) {
        ensureChannel(context)
        val openSettings = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            Intent(Intent.ACTION_VIEW, Uri.parse("unibot://settings")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_unibot)
            .setContentTitle(context.getString(R.string.v12g_battery_saver_notif_title))
            .setContentText(context.getString(R.string.v12g_battery_saver_notif_text))
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS denied — the Settings row still shows the
            // state; the indicator is best-effort.
            AppLogger.info(TAG, "battery-saver indicator denied (notifications off)")
        }
    }

    fun hide(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.v12g_battery_saver_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.v12g_battery_saver_notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
