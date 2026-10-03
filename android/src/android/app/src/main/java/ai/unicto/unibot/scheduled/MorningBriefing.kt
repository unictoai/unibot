package ai.unicto.unibot.scheduled

import android.content.Context
import ai.unicto.unibot.logging.AppLogger

/**
 * [v1.0-wave6] The pre-built "Morning briefing" routine.
 *
 * [ensureSeeded] creates it once (idempotent — guarded by a prefs flag) as a
 * DISABLED daily 07:30 task, so the user finds it waiting in Routines and
 * just flips the switch. The prompt is written for the agent loop: it tells
 * the model to gather today's weather (web_search), today's calendar events
 * (calendar_list, when connected) and top headlines (rss_latest, when the RSS
 * connector has feeds), then compose a tight briefing. Each source degrades
 * gracefully when its tool is unavailable — the briefing still ships with
 * whatever is there.
 *
 * When the task fires, [ScheduledAgentRunner] runs the prompt headlessly and
 * posts the result as a big-text notification; tapping it opens the chat with
 * the full briefing.
 *
 * Privacy: everything runs on-device; the briefing content is never uploaded
 * anywhere.
 */
object MorningBriefing {
    private const val TAG = "MorningBriefing"
    private const val PREFS = "unibot_morning_briefing"
    private const val KEY_SEEDED = "seeded"

    const val LABEL = "Morning briefing"

    /**
     * Agent prompt template for the briefing. The model fills it from live
     * tools at fire time — no content is pre-baked here.
     */
    const val PROMPT = "Write the user's morning briefing for today. " +
        "Gather: (1) today's weather for the user's city via web_search (skip if unavailable); " +
        "(2) today's calendar events via calendar_list (skip if the calendar is not connected); " +
        "(3) the top 5 tech/world headlines via rss_latest (skip if no feeds). " +
        "Then write a tight briefing with three short sections — Weather, Today, Headlines — " +
        "each 1-3 lines. Friendly tone, no fluff, no preamble about being an AI. " +
        "If a section has no data, drop it silently instead of apologizing."

    /**
     * Idempotent: creates the disabled daily 07:30 briefing task exactly
     * once. Safe to call from anywhere (VM init, boot receiver); never throws.
     */
    fun ensureSeeded(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SEEDED, false)) return
            val manager = ScheduledTaskManager(context.applicationContext)
            val exists = manager.list().any { it.label == LABEL }
            if (!exists) {
                manager.create(
                    ScheduledTask(
                        label = LABEL,
                        timeOfDayHour = 7,
                        timeOfDayMinute = 30,
                        repeatMode = ScheduledRepeatMode.DAILY,
                        prompt = PROMPT,
                        enabled = false,
                    ),
                )
                AppLogger.info(TAG, "seeded disabled daily morning-briefing task")
            }
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }.onFailure { t ->
            AppLogger.warning(TAG, "ensureSeeded failed: ${t.message}")
        }
    }
}
