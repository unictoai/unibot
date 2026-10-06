package ai.unicto.unibot.automation

import org.json.JSONObject
import java.util.UUID

/**
 * Item 91 — a user-defined background agent: a named prompt with a pinned
 * model that runs detached (WorkManager) and delivers a callback card when
 * it finishes.
 *
 * Safety contract (explicitly blessed scope):
 *  - [consentGranted] is explicit per-agent opt-in. An agent can NEVER be
 *    enabled or scheduled without it — the scheduler refuses, and the worker
 *    double-checks before running.
 *  - Battery discipline: [batteryNotLow] defaults true; [requireCharging]
 *    and [requireUnmetered] are opt-in per agent. These become WorkManager
 *    [androidx.work.Constraints] so the OS, not us, decides when the radio
 *    and CPU are cheap.
 *  - Killable: every agent maps to one uniquely-named work chain; the
 *    manager screen's kill switch calls [BackgroundAgentScheduler.kill],
 *    which cancels the chain immediately.
 *
 * Persistence: JSON via [toJson]/[fromJson], stored in
 * [BackgroundAgentStore] (SharedPreferences). Additive fields only — old
 * rows read back with defaults.
 */
enum class BackgroundAgentSchedule { MANUAL, INTERVAL, DAILY }

data class BackgroundAgent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    /**
     * Pinned model, same JSON shape as ScheduledTask.modelBinding:
     * `{"type":"group","groupId":"..."}` or `{"type":"entry","entryId":"..."}`.
     * Null → the app default at run time.
     */
    val modelBinding: String? = null,
    val schedule: BackgroundAgentSchedule = BackgroundAgentSchedule.MANUAL,
    /** Minutes between runs when [schedule] is INTERVAL. Clamped ≥ 15 by the scheduler (WorkManager minimum). */
    val intervalMinutes: Int = 60,
    /** Wall-clock fire time when [schedule] is DAILY. */
    val dailyHour: Int = 8,
    val dailyMinute: Int = 0,
    val requireCharging: Boolean = false,
    val requireUnmetered: Boolean = false,
    val batteryNotLow: Boolean = true,
    val enabled: Boolean = false,
    /** Explicit per-agent user consent. Scheduling requires this to be true. */
    val consentGranted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("prompt", prompt)
        if (modelBinding != null) put("modelBinding", modelBinding)
        put("schedule", schedule.name)
        put("intervalMinutes", intervalMinutes)
        put("dailyHour", dailyHour)
        put("dailyMinute", dailyMinute)
        put("requireCharging", requireCharging)
        put("requireUnmetered", requireUnmetered)
        put("batteryNotLow", batteryNotLow)
        put("enabled", enabled)
        put("consentGranted", consentGranted)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): BackgroundAgent = BackgroundAgent(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            prompt = o.optString("prompt", ""),
            modelBinding = if (o.has("modelBinding")) o.optString("modelBinding", null) else null,
            schedule = runCatching {
                BackgroundAgentSchedule.valueOf(o.optString("schedule", "MANUAL"))
            }.getOrDefault(BackgroundAgentSchedule.MANUAL),
            intervalMinutes = o.optInt("intervalMinutes", 60),
            dailyHour = o.optInt("dailyHour", 8),
            dailyMinute = o.optInt("dailyMinute", 0),
            requireCharging = o.optBoolean("requireCharging", false),
            requireUnmetered = o.optBoolean("requireUnmetered", false),
            batteryNotLow = o.optBoolean("batteryNotLow", true),
            enabled = o.optBoolean("enabled", false),
            consentGranted = o.optBoolean("consentGranted", false),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/**
 * One finished (or failed) background run — the data behind the callback
 * card in the manager screen and the completion notification.
 */
data class BackgroundAgentRun(
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val agentName: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val ok: Boolean = false,
    /** First ~300 chars of the agent's final message. */
    val preview: String? = null,
    /** Chat session holding the full result — tappable from the card. */
    val sessionId: String? = null,
    /** Machine-readable failure reason when [ok] is false. */
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("agentId", agentId)
        put("agentName", agentName)
        put("startedAt", startedAt)
        if (finishedAt != null) put("finishedAt", finishedAt)
        put("ok", ok)
        if (preview != null) put("preview", preview)
        if (sessionId != null) put("sessionId", sessionId)
        if (error != null) put("error", error)
    }

    companion object {
        fun fromJson(o: JSONObject): BackgroundAgentRun = BackgroundAgentRun(
            id = o.optString("id", UUID.randomUUID().toString()),
            agentId = o.optString("agentId", ""),
            agentName = o.optString("agentName", ""),
            startedAt = o.optLong("startedAt", System.currentTimeMillis()),
            finishedAt = if (o.has("finishedAt")) o.optLong("finishedAt") else null,
            ok = o.optBoolean("ok", false),
            preview = if (o.has("preview")) o.optString("preview", null) else null,
            sessionId = if (o.has("sessionId")) o.optString("sessionId", null) else null,
            error = if (o.has("error")) o.optString("error", null) else null,
        )
    }
}
