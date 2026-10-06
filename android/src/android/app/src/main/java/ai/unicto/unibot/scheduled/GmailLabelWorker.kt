package ai.unicto.unibot.scheduled

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.unicto.unibot.connectors.gmail.GmailLabelApplier
import ai.unicto.unibot.connectors.gmail.GmailLabelRuleStore
import ai.unicto.unibot.logging.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Item 43 — background applier for Gmail auto-label rules.
 *
 * Battery-disciplined by construction:
 * - WorkManager periodic work (2h interval — the minimum useful cadence
 *   for "incoming mail" without waking the radio constantly).
 * - Constraints: network CONNECTED + battery not low. No charging
 *   requirement (labels are cheap), no unmetered requirement (a few KB
 *   of Gmail API calls).
 * - User-toggleable: the worker no-ops (Result.success, no retry) when
 *   the master toggle is off, and [cancel] removes the schedule entirely.
 * - Bounded work: rules only scan the last 24h (see
 *   [ai.unicto.unibot.connectors.gmail.GmailLabelRule.LOOKBACK_HOURS])
 *   and label at most 50 messages per rule per run.
 *
 * Never throws out of doWork: per-rule failures are logged and the run
 * still succeeds so one bad rule can't poison the schedule.
 */
class GmailLabelWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = GmailLabelRuleStore()
        if (!store.isAutoLabelEnabled(applicationContext)) {
            return Result.success()
        }
        val report = GmailLabelApplier.applyAll(applicationContext)
        AppLogger.info(
            TAG,
            "run done: ${report.labeledTotal} labeled across ${report.rulesRun} rule(s)",
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "GmailLabelWorker"
        private const val UNIQUE_NAME = "gmail_auto_label"
        private const val INTERVAL_HOURS = 2L

        /**
         * Battery-disciplined constraints: connected network + battery not
         * low. No unmetered/charging requirement — a label run is a
         * handful of small API calls.
         */
        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Schedule (or re-schedule) the 2-hourly run. Idempotent. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GmailLabelWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
            AppLogger.info(TAG, "scheduled every ${INTERVAL_HOURS}h")
        }

        /** Remove the schedule entirely. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_NAME)
            AppLogger.info(TAG, "schedule cancelled")
        }
    }
}
