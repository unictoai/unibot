package ai.unicto.unibot.automation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ai.unicto.unibot.logging.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Item 91 — WorkManager scheduling for background agents. Battery discipline
 * is expressed as [Constraints] so the OS enforces it:
 *  - [BackgroundAgent.batteryNotLow] → setRequiresBatteryNotLow(true)
 *  - [BackgroundAgent.requireCharging] → setRequiresCharging(true)
 *  - [BackgroundAgent.requireUnmetered] → NetworkType.UNMETERED, otherwise
 *    CONNECTED (agents need network for provider calls)
 *
 * Every agent maps to exactly one uniquely-named work chain
 * (`bgagent-<id>`), so [kill] cancels it deterministically and re-scheduling
 * an edited agent replaces the old chain instead of doubling it.
 *
 * [toWorkSpec] is the pure, unit-tested mapping from agent → work plan; the
 * WorkManager builders below are a thin translation of it.
 */
class BackgroundAgentScheduler(private val context: Context) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val store = BackgroundAgentStore(context)

    /**
     * Schedule (or re-schedule) an agent. Refuses agents that are disabled
     * or lack explicit consent — the store enforces the same rule, this is
     * the second gate at the scheduling layer.
     */
    fun schedule(agent: BackgroundAgent): Boolean {
        if (!agent.enabled || !agent.consentGranted) {
            AppLogger.warning(TAG, "refusing to schedule agent ${agent.id}: enabled=${agent.enabled} consent=${agent.consentGranted}")
            return false
        }
        if (agent.prompt.isBlank()) {
            AppLogger.warning(TAG, "refusing to schedule agent ${agent.id}: blank prompt")
            return false
        }
        val spec = toWorkSpec(agent)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (spec.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(spec.batteryNotLow)
            .setRequiresCharging(spec.requireCharging)
            .build()
        val input = androidx.work.Data.Builder()
            .putString(BackgroundAgentWorker.KEY_AGENT_ID, agent.id)
            .build()
        try {
            if (spec.repeatIntervalMinutes != null) {
                val request = PeriodicWorkRequestBuilder<BackgroundAgentWorker>(
                    spec.repeatIntervalMinutes, TimeUnit.MINUTES,
                )
                    .setConstraints(constraints)
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, spec.backoffMinutes, TimeUnit.MINUTES)
                    .addTag(TAG_AGENT)
                    .build()
                workManager.enqueueUniquePeriodicWork(
                    spec.uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request,
                )
            } else {
                val builder = OneTimeWorkRequestBuilder<BackgroundAgentWorker>()
                    .setConstraints(constraints)
                    .setInputData(input)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, spec.backoffMinutes, TimeUnit.MINUTES)
                    .addTag(TAG_AGENT)
                if (spec.initialDelayMinutes > 0) {
                    builder.setInitialDelay(spec.initialDelayMinutes, TimeUnit.MINUTES)
                }
                workManager.enqueueUniqueWork(
                    spec.uniqueName, ExistingWorkPolicy.REPLACE, builder.build(),
                )
            }
            AppLogger.info(TAG, "scheduled agent ${agent.id} (${agent.name}) spec=$spec")
            return true
        } catch (t: Throwable) {
            AppLogger.error(TAG, "schedule failed for agent ${agent.id}: ${t.message}")
            return false
        }
    }

    /** Run once, right now (still gated on consent + constraints). */
    fun runOnce(agentId: String): Boolean {
        val agent = store.get(agentId) ?: return false
        return schedule(agent.copy(schedule = BackgroundAgentSchedule.MANUAL))
    }

    /** Kill switch: cancel the agent's work chain immediately. */
    fun kill(agentId: String) {
        try {
            workManager.cancelUniqueWork(uniqueName(agentId))
            AppLogger.info(TAG, "killed agent $agentId")
        } catch (t: Throwable) {
            AppLogger.error(TAG, "kill failed for agent $agentId: ${t.message}")
        }
    }

    fun killAll() {
        try {
            workManager.cancelAllWorkByTag(TAG_AGENT)
            AppLogger.info(TAG, "killed all background agents")
        } catch (t: Throwable) {
            AppLogger.error(TAG, "killAll failed: ${t.message}")
        }
    }

    /** Re-arm every enabled + consented agent (call on BOOT_COMPLETED). */
    fun rescheduleAll() {
        for (agent in store.all()) {
            if (agent.enabled && agent.consentGranted && agent.schedule != BackgroundAgentSchedule.MANUAL) {
                schedule(agent)
            }
        }
    }

    companion object {
        private const val TAG = "BackgroundAgentScheduler"
        private const val TAG_AGENT = "unibot-background-agent"

        fun uniqueName(agentId: String): String = "bgagent-$agentId"

        /**
         * Pure mapping: agent → work plan. WorkManager's periodic minimum is
         * 15 minutes — shorter intervals are clamped, never rejected, so a
         * user's "every 5 minutes" becomes "every 15" instead of an error.
         */
        fun toWorkSpec(agent: BackgroundAgent): AgentWorkSpec {
            val (repeat, delay) = when (agent.schedule) {
                BackgroundAgentSchedule.MANUAL -> null to 0L
                BackgroundAgentSchedule.INTERVAL ->
                    maxOf(agent.intervalMinutes, 15).toLong() to 0L
                BackgroundAgentSchedule.DAILY -> {
                    // Daily = periodic 24h with an initial delay to the next
                    // wall-clock fire time. WorkManager has no cron; this is
                    // the standard approximation.
                    24L * 60L to minutesUntil(agent.dailyHour, agent.dailyMinute)
                }
            }
            return AgentWorkSpec(
                uniqueName = uniqueName(agent.id),
                requireCharging = agent.requireCharging,
                requireUnmetered = agent.requireUnmetered,
                batteryNotLow = agent.batteryNotLow,
                backoffMinutes = 10L,
                repeatIntervalMinutes = repeat,
                initialDelayMinutes = delay,
            )
        }

        private fun minutesUntil(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
            return (target.timeInMillis - now.timeInMillis) / 60_000L
        }
    }
}

/**
 * The pure work plan for one agent — no AndroidX types, fully unit-testable.
 */
data class AgentWorkSpec(
    val uniqueName: String,
    val requireCharging: Boolean,
    val requireUnmetered: Boolean,
    val batteryNotLow: Boolean,
    val backoffMinutes: Long,
    /** Null → one-shot; non-null → periodic every N minutes. */
    val repeatIntervalMinutes: Long?,
    val initialDelayMinutes: Long,
)
