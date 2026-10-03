package ai.unicto.unibot.backup

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.unicto.unibot.R
import ai.unicto.unibot.data.db.AppDatabase
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.FileInputStream
import java.util.Calendar

/**
 * [v12-G] Scheduled LOCAL backups — daily/weekly, on-device only, no cloud.
 *
 * The user toggles this in backup settings (see
 * [ai.unicto.unibot.ui.settings.ScheduledBackupScreen]); [schedule] arms an
 * AlarmManager alarm following the [ai.unicto.unibot.scheduled.ScheduledTaskManager]
 * pattern (exact + allow-while-idle, SecurityException fallback to inexact),
 * and [ScheduledBackupReceiver] runs the export headlessly.
 *
 * Privacy: the export uses [BackupExporter]'s defaults (all categories,
 * credentials included so a restore actually works — same as the manual
 * default) and the package lands either in the app-private backups dir or a
 * user-picked on-device folder (SAF tree). It is never uploaded anywhere;
 * rclone remotes are deliberately not involved.
 *
 * NOTE (manifest): [ScheduledBackupReceiver] must be declared in
 * AndroidManifest.xml for the alarm to fire when the process is dead —
 * Batch G may not touch the manifest, so the declaration is listed in the
 * handoff report. Until it is added, the toggle arms an alarm whose target
 * cannot be delivered while the app is dead.
 */
object ScheduledBackup {

    const val ACTION_RUN = "ai.unicto.unibot.backup.SCHEDULED_RUN"

    private const val TAG = "SchedBackup"
    private const val PREFS_NAME = "v12g_scheduled_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FREQUENCY = "frequency"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_LAST_RUN = "last_run_ms"
    private const val KEY_LAST_OK = "last_ok"
    private const val KEY_LAST_BYTES = "last_bytes"

    private const val CHANNEL_ID = "unibot_scheduled_backup"
    private const val NOTIF_ID_DONE = 0xBAC4

    /** Process-wide IO scope for the headless export (see receiver handoff). */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Frequency { DAILY, WEEKLY }

    data class Config(
        val enabled: Boolean = false,
        val frequency: Frequency = Frequency.DAILY,
        val hour: Int = 3,
        val minute: Int = 0,
        /** SAF tree URI string, or null = app-private backups dir. */
        val treeUri: String? = null,
        val lastRunMs: Long = 0L,
        val lastOk: Boolean = false,
        val lastBytes: Long = 0L,
    )

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            enabled = p.getBoolean(KEY_ENABLED, false),
            frequency = runCatching {
                Frequency.valueOf(p.getString(KEY_FREQUENCY, Frequency.DAILY.name)!!)
            }.getOrDefault(Frequency.DAILY),
            hour = p.getInt(KEY_HOUR, 3),
            minute = p.getInt(KEY_MINUTE, 0),
            treeUri = p.getString(KEY_TREE_URI, null),
            lastRunMs = p.getLong(KEY_LAST_RUN, 0L),
            lastOk = p.getBoolean(KEY_LAST_OK, false),
            lastBytes = p.getLong(KEY_LAST_BYTES, 0L),
        )
    }

    /** Persist [config]; arms/cancels the alarm to match [Config.enabled]. */
    fun save(context: Context, config: Config) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_FREQUENCY, config.frequency.name)
            .putInt(KEY_HOUR, config.hour)
            .putInt(KEY_MINUTE, config.minute)
            .putString(KEY_TREE_URI, config.treeUri)
            .putLong(KEY_LAST_RUN, config.lastRunMs)
            .putBoolean(KEY_LAST_OK, config.lastOk)
            .putLong(KEY_LAST_BYTES, config.lastBytes)
            .apply()
        if (config.enabled) schedule(context, config) else cancel(context)
    }

    fun schedule(context: Context, config: Config = load(context)) {
        val triggerAt = nextTriggerMs(config)
        val pi = pendingIntent(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            AppLogger.info(TAG, "scheduled ${config.frequency} backup at $triggerAt")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back to
            // inexact so the backup still happens eventually (same fallback
            // as ScheduledTaskManager).
            AppLogger.warning(TAG, "exact-alarm denied (${e.message}); falling back to inexact")
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }.onFailure {
                AppLogger.error(TAG, "inexact fallback failed: ${it.message}")
            }
        }
    }

    fun cancel(context: Context) {
        val pi = pendingIntent(context)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
        AppLogger.info(TAG, "scheduled backup cancelled")
    }

    /**
     * Called by the receiver BEFORE dispatching the run: arm the next
     * occurrence first so a kill mid-backup doesn't break the cadence
     * (same ordering as ScheduledTaskManager.rescheduleNext).
     */
    fun rescheduleAfterFire(context: Context) {
        val config = load(context)
        if (!config.enabled) return
        schedule(context, config)
    }

    /**
     * Re-arm after reboot. Called from the BOOT_COMPLETED path (see handoff
     * note) and defensively from the settings screen.
     */
    fun rescheduleIfEnabled(context: Context) {
        if (load(context).enabled) schedule(context)
    }

    /**
     * Headless full local backup. Runs on [scope] (IO); never touches the
     * network. Records to [BackupHistory] and posts a completion
     * notification like the manual flow does.
     */
    suspend fun runNow(context: Context): Boolean {
        val appContext = context.applicationContext
        val config = load(appContext)
        val started = System.currentTimeMillis()
        val history = BackupHistory.get(appContext)
        val cats = BackupCategory.backupable.map { it.key }.sorted()
        var record = BackupHistory.Record(
            backupId = "",
            startedAt = started,
            status = BackupHistory.Status.RUNNING,
            categories = cats,
            encrypted = false,
        )
        history.upsert(record)

        return try {
            val summary = BackupExporter(appContext, AppDatabase.getInstance(appContext))
                .export(BackupExporter.Options())
            val dest = deliverToChosenLocation(appContext, config, summary.packageFile)
            val ok = dest.succeeded
            record = record.copy(
                backupId = summary.backupId,
                finishedAt = System.currentTimeMillis(),
                status = if (ok) BackupHistory.Status.SUCCEEDED else BackupHistory.Status.FAILED,
                totalBytes = summary.totalBytes,
                skippedFiles = summary.skippedFiles,
                packageName = summary.packageFile.name,
                destinations = listOf(dest),
                errorMessage = dest.detail?.takeIf { !ok },
            )
            history.upsert(record)
            save(
                appContext,
                config.copy(
                    lastRunMs = System.currentTimeMillis(),
                    lastOk = ok,
                    lastBytes = summary.totalBytes,
                ),
            )
            postResultNotification(appContext, ok, summary.packageFile.name, destLabel(appContext, config))
            AppLogger.info(TAG, "scheduled backup ${if (ok) "succeeded" else "FAILED"} bytes=${summary.totalBytes}")
            ok
        } catch (t: Throwable) {
            AppLogger.error(TAG, "scheduled backup failed: ${t.message}")
            record = record.copy(
                finishedAt = System.currentTimeMillis(),
                status = BackupHistory.Status.FAILED,
                errorMessage = t.message,
            )
            history.upsert(record)
            save(appContext, config.copy(lastRunMs = System.currentTimeMillis(), lastOk = false))
            postResultNotification(appContext, false, null, destLabel(appContext, config))
            false
        }
    }

    /**
     * The export already lands in the app-private backups dir. When the user
     * picked their own folder, copy the package there (SAF) and remove the
     * app-private copy so the chosen location is the single destination.
     */
    private fun deliverToChosenLocation(
        context: Context,
        config: Config,
        packageFile: File,
    ): BackupHistory.DestinationOutcome {
        val treeString = config.treeUri ?: return BackupHistory.DestinationOutcome(
            name = context.getString(R.string.v12g_sched_backup_dest_private),
            succeeded = true,
            kind = "local",
            path = packageFile.absolutePath,
        )
        val treeUri = runCatching { Uri.parse(treeString) }.getOrNull()
            ?: return BackupHistory.DestinationOutcome(
                name = context.getString(R.string.v12g_sched_backup_dest_private),
                succeeded = false,
                detail = "Saved folder is no longer readable; using app storage instead.",
                kind = "local",
                path = packageFile.absolutePath,
            )
        val copied = copyToTree(context, treeUri, packageFile)
        return if (copied) {
            runCatching { packageFile.delete() }
            BackupHistory.DestinationOutcome(
                name = context.getString(R.string.v12g_sched_backup_destination),
                succeeded = true,
                kind = "local",
                path = treeString,
            )
        } else {
            // SAF write failed — the app-private package is still intact, so
            // the backup exists; say exactly where.
            BackupHistory.DestinationOutcome(
                name = context.getString(R.string.v12g_sched_backup_dest_private),
                succeeded = true,
                detail = "Could not write to the chosen folder; kept in app storage.",
                kind = "local",
                path = packageFile.absolutePath,
            )
        }
    }

    private fun copyToTree(context: Context, treeUri: Uri, src: File): Boolean {
        return runCatching {
            val cr = context.contentResolver
            runCatching {
                cr.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val docUri = DocumentsContract.createDocument(
                cr, treeUri, "application/octet-stream", src.name,
            ) ?: return false
            cr.openOutputStream(docUri)?.use { out ->
                FileInputStream(src).use { inp -> inp.copyTo(out) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun destLabel(context: Context, config: Config): String =
        if (config.treeUri != null) context.getString(R.string.v12g_sched_backup_destination)
        else context.getString(R.string.v12g_sched_backup_dest_private)

    private fun postResultNotification(
        context: Context,
        ok: Boolean,
        packageName: String?,
        destLabel: String,
    ) {
        ensureChannel(context)
        val openSettings = PendingIntent.getActivity(
            context,
            NOTIF_ID_DONE,
            Intent(Intent.ACTION_VIEW, Uri.parse("unibot://settings")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val titleRes = if (ok) R.string.v12g_sched_backup_notif_done_title
        else R.string.v12g_sched_backup_notif_fail_title
        val text = if (ok && packageName != null) {
            context.getString(R.string.v12g_sched_backup_notif_done_text, packageName, destLabel)
        } else {
            context.getString(R.string.v12g_sched_backup_notif_fail_text)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_unibot)
            .setContentTitle(context.getString(titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID_DONE, notification)
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "scheduled-backup result notification denied")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.v12g_sched_backup_notif_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.v12g_sched_backup_notif_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    internal fun nextTriggerMs(config: Config): Long {
        val now = System.currentTimeMillis()
        fun atTime(base: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = base
                set(Calendar.HOUR_OF_DAY, config.hour)
                set(Calendar.MINUTE, config.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
        return if (config.frequency == Frequency.WEEKLY && config.lastRunMs > 0L) {
            // Weekly cadence anchored on the last run, at the chosen time.
            var next = atTime(config.lastRunMs + 7L * 24 * 60 * 60 * 1000)
            while (next <= now) next += 7L * 24 * 60 * 60 * 1000
            next
        } else {
            var next = atTime(now)
            if (next <= now) {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = next
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                next = cal.timeInMillis
            }
            next
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduledBackupReceiver::class.java).apply {
            action = ACTION_RUN
        }
        return PendingIntent.getBroadcast(
            context,
            0x5CA1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
