package ai.unicto.unibot.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.launch

/**
 * [v12-G] AlarmManager target for scheduled local backups. Follows the
 * [ai.unicto.unibot.scheduled.ScheduledTaskAlarmReceiver] handoff pattern:
 * everything inside onReceive finishes in milliseconds — the export itself
 * runs on [ScheduledBackup.scope], kept alive by a timed PARTIAL_WAKE_LOCK
 * (WAKE_LOCK is already declared for AgentForegroundService).
 *
 * Holding the broadcast for a multi-minute export would ANR (GH#197); the
 * handoff releases it immediately. A kill mid-run leaves a RUNNING
 * [BackupHistory] record that reconciles to FAILED on next launch, and the
 * next alarm was already armed before dispatch, so the cadence self-heals.
 *
 * NOTE: this receiver must be declared in AndroidManifest.xml (Batch G may
 * not touch the manifest — the entry is in the handoff report). Until it is,
 * alarms cannot wake a dead process.
 */
class ScheduledBackupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledBackup.ACTION_RUN) return
        val pending = goAsync()
        val appContext = context.applicationContext
        AppLogger.info(TAG, "scheduled backup alarm fired")
        try {
            // Arm the next occurrence FIRST — a long export must not block
            // next week's fire even if we crash mid-run.
            runCatching { ScheduledBackup.rescheduleAfterFire(appContext) }
                .onFailure { AppLogger.warning(TAG, "rescheduleAfterFire failed: ${it.message}") }

            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unibot:schedbackup").apply {
                setReferenceCounted(false)
                // Timed acquire auto-releases; the export is also bounded by
                // BackupExporter's own serialisation, so 20 min is a ceiling,
                // not a target.
                acquire(WAKE_TIMEOUT_MS)
            }
            ScheduledBackup.scope.launch {
                try {
                    ScheduledBackup.runNow(appContext)
                } catch (t: Throwable) {
                    AppLogger.error(TAG, "scheduled backup run failed: ${t.message}")
                } finally {
                    runCatching { if (lock.isHeld) lock.release() }
                }
            }
        } catch (t: Throwable) {
            AppLogger.error(TAG, "scheduled backup handoff failed: ${t.message}")
        } finally {
            // Handoff done — release the broadcast immediately (GH#197).
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "SchedBackupRecv"
        private const val WAKE_TIMEOUT_MS = 20L * 60 * 1000
    }
}
