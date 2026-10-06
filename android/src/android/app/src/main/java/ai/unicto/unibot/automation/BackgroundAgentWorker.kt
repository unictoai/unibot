package ai.unicto.unibot.automation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.unicto.unibot.R
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.scheduled.ScheduledAgentRunner
import ai.unicto.unibot.scheduled.ScheduledTask
import ai.unicto.unibot.scheduled.ScheduledTaskManager
import ai.unicto.unibot.scheduled.ScheduledTargetMode

/**
 * Item 91 — the WorkManager worker that runs a background agent detached
 * from any UI. Flow:
 *
 *  1. Load the agent; re-verify enabled + explicit consent (the scheduler
 *     checked too — defense in depth, since work can outlive an edit).
 *  2. Record the run start (the manager screen shows it as a running card).
 *  3. Run the prompt through the existing headless agent loop
 *     ([ScheduledAgentRunner], waitForCompletion=true — a Worker may block).
 *  4. Record completion + post the callback notification ("agent finished",
 *     tap → the chat holding the full result).
 *
 * Cancellation (the kill switch) surfaces as coroutine cancellation, which
 * aborts the wait and records the run as failed/cancelled.
 */
class BackgroundAgentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val agentId = inputData.getString(KEY_AGENT_ID)
        if (agentId.isNullOrBlank()) {
            AppLogger.error(TAG, "no agent id in input data")
            return Result.failure()
        }
        val store = BackgroundAgentStore(applicationContext)
        val agent = store.get(agentId)
        if (agent == null) {
            AppLogger.warning(TAG, "agent $agentId gone — not rescheduling")
            return Result.failure()
        }
        // Consent double-check: the user may have revoked it after enqueue.
        if (!agent.enabled || !agent.consentGranted) {
            AppLogger.warning(
                TAG,
                "agent $agentId not enabled/consented at run time — skipping (no retry)",
            )
            return Result.failure()
        }
        if (agent.prompt.isBlank()) {
            AppLogger.warning(TAG, "agent $agentId has a blank prompt — skipping")
            return Result.failure()
        }

        val run = BackgroundAgentRun(
            agentId = agent.id,
            agentName = agent.name,
        )
        store.recordRun(run)

        return try {
            // Reuse the scheduled-task pipeline: it resolves a fresh chat,
            // applies the pinned model binding, runs the agent loop, and
            // posts its own completion plumbing. markFired() no-ops for the
            // synthetic id (not in ScheduledTaskStore) by design.
            val task = ScheduledTask(
                id = "bgagent:${agent.id}:${run.id}",
                label = agent.name,
                timeOfDayHour = 0,
                timeOfDayMinute = 0,
                repeatMode = ai.unicto.unibot.scheduled.ScheduledRepeatMode.ONCE,
                prompt = agent.prompt,
                targetMode = ScheduledTargetMode.NewSession,
                modelBinding = agent.modelBinding,
                enabled = true,
            )
            val sessionId = ScheduledAgentRunner.run(
                applicationContext, task, waitForCompletion = true,
            )
            if (sessionId != null) {
                store.updateRun(run.copy(finishedAt = System.currentTimeMillis(), ok = true, sessionId = sessionId))
                postCallbackNotification(agent, sessionId, ok = true)
                Result.success()
            } else {
                store.updateRun(
                    run.copy(
                        finishedAt = System.currentTimeMillis(),
                        ok = false,
                        error = "The agent could not start (no provider configured or app not ready).",
                    ),
                )
                postCallbackNotification(agent, sessionId = null, ok = false)
                Result.failure()
            }
        } catch (t: Throwable) {
            val cancelled = t is kotlinx.coroutines.CancellationException
            AppLogger.error(TAG, "agent ${agent.id} run failed: ${t.message}")
            store.updateRun(
                run.copy(
                    finishedAt = System.currentTimeMillis(),
                    ok = false,
                    error = if (cancelled) "Stopped by the user." else "Run failed: ${t.message}",
                ),
            )
            if (!cancelled) postCallbackNotification(agent, sessionId = null, ok = false)
            Result.failure()
        }
    }

    private fun postCallbackNotification(agent: BackgroundAgent, sessionId: String?, ok: Boolean) {
        val deepLink = if (sessionId != null) {
            Uri.parse("unibot://session/$sessionId")
        } else {
            Uri.parse("unibot://settings/background_agents")
        }
        val openIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(applicationContext.packageName)
        }
        val notificationId = ("bgagent-cb-${agent.id}").hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (ok) {
            "${ai.unicto.unibot.identity.UnibotIdentity.name(applicationContext)}: ${agent.name} finished"
        } else {
            "${ai.unicto.unibot.identity.UnibotIdentity.name(applicationContext)}: ${agent.name} could not run"
        }
        val text = if (ok) {
            "Tap to open the chat with the full result."
        } else {
            "Tap to open Background agents and check its setup."
        }
        val notification = NotificationCompat.Builder(applicationContext, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_unibot)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (t: Throwable) {
            AppLogger.error(TAG, "callback notification failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "BackgroundAgentWorker"
        const val KEY_AGENT_ID = "agent_id"
    }
}
