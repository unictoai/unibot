package ai.unicto.unibot.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.scheduled.ScheduledAgentRunner
import ai.unicto.unibot.scheduled.ScheduledTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * v0.2.0 P3 — the Tasker / Automate / MacroDroid intent API.
 *
 * Three public broadcasts (documented in Settings → Background →
 * Automation, and in [ai.unicto.unibot.ui.settings.AutomationScreen]):
 *
 *   ai.unicto.unibot.ASK          extra "text" (string)
 *       → opens unibot with the text prefilled in the main chat's
 *         composer. Never auto-sends.
 *   ai.unicto.unibot.NEW_CHAT    (no extras)
 *       → opens a fresh draft chat.
 *   ai.unicto.unibot.RUN_ROUTINE  extra "routine_id" (string)
 *       → runs a scheduled routine headlessly in the background, the
 *         same way its AlarmManager fire does. The routine's id is the
 *         one shown in Goals → Routines (long-press → Run records).
 *
 * Auth: external automation is opt-in (Settings → Background →
 * Automation, default OFF) — see [isExternalAutomationEnabled]. The
 * receiver is exported so Tasker/MacroDroid can reach it, but any
 * installed app can send broadcasts, so nothing is honored until the
 * user enables it. Every action is something the user could already do
 * by tapping the app: ASK never sends without the user reviewing the
 * text; RUN_ROUTINE only runs routines the user already created
 * (disabled routines are skipped), and the agent's own approval gates
 * still apply to whatever the run does.
 */
class TaskerIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // [unibot-audit] Drop external broadcasts unless the user opted in.
        if (!isExternalAutomationEnabled(context)) {
            AppLogger.info(TAG, "external automation disabled — ignored ${intent.action}")
            return
        }
        when (intent.action) {
            ACTION_ASK -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isBlank()) {
                    AppLogger.info(TAG, "ASK with empty text — ignored")
                    return
                }
                launchDeepLink(context, "unibot://ask?text=${Uri.encode(text)}")
            }
            ACTION_NEW_CHAT -> {
                launchDeepLink(context, "unibot://action/new_chat")
            }
            ACTION_RUN_ROUTINE -> {
                val routineId = intent.getStringExtra(EXTRA_ROUTINE_ID).orEmpty()
                if (routineId.isBlank()) {
                    AppLogger.info(TAG, "RUN_ROUTINE without routine_id — ignored")
                    return
                }
                runRoutineHeadless(context, routineId)
            }
            else -> Unit
        }
    }

    private fun launchDeepLink(context: Context, uri: String) {
        val launch = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(launch)
        } catch (t: Throwable) {
            AppLogger.info(TAG, "deep-link launch failed for $uri: ${t.message}")
        }
    }

    /**
     * Headless routine run. Mirrors [ai.unicto.unibot.scheduled.ScheduledTaskAlarmReceiver]:
     * goAsync() + a hard handoff ceiling, waitForCompletion=false — the
     * agent loop runs on ScheduledAgentRunner's app-scoped scope, kept
     * alive by the foreground service. Unlike the alarm path we do NOT
     * reschedule: a manual Tasker trigger must not shift the routine's
     * own AlarmManager schedule.
     */
    private fun runRoutineHeadless(context: Context, routineId: String) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                withTimeout(HANDOFF_TIMEOUT_MS) {
                    val manager = ScheduledTaskManager(appContext)
                    val task = manager.get(routineId) ?: run {
                        AppLogger.info(TAG, "RUN_ROUTINE: routine $routineId not found — ignored")
                        return@withTimeout
                    }
                    if (!task.enabled) {
                        AppLogger.info(TAG, "RUN_ROUTINE: routine $routineId disabled — ignored")
                        return@withTimeout
                    }
                    ScheduledAgentRunner.run(appContext, task, waitForCompletion = false)
                }
            } catch (t: Throwable) {
                AppLogger.error(TAG, "RUN_ROUTINE $routineId failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TaskerIntent"
        private const val HANDOFF_TIMEOUT_MS = 10_000L

        const val ACTION_ASK = "ai.unicto.unibot.ASK"
        const val ACTION_NEW_CHAT = "ai.unicto.unibot.NEW_CHAT"
        const val ACTION_RUN_ROUTINE = "ai.unicto.unibot.RUN_ROUTINE"

        const val EXTRA_TEXT = "text"
        const val EXTRA_ROUTINE_ID = "routine_id"

        // [unibot-audit] External automation is opt-in (default OFF). The
        // receiver is exported so Tasker/MacroDroid can reach it, but any
        // installed app can send these broadcasts — so nothing is honored
        // until the user flips the switch in Settings → Background →
        // Automation. This keeps the feature without censoring it.
        private const val PREFS = "automation_prefs"
        private const val KEY_EXTERNAL_ENABLED = "external_automation_enabled"

        fun isExternalAutomationEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXTERNAL_ENABLED, false)

        fun setExternalAutomationEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EXTERNAL_ENABLED, enabled).apply()
        }
    }
}
