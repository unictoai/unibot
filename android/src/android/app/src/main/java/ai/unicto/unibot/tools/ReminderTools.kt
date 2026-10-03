package ai.unicto.unibot.tools

import android.content.Context
import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.scheduled.ScheduledRepeatMode
import ai.unicto.unibot.scheduled.ScheduledTask
import ai.unicto.unibot.scheduled.ScheduledTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

/**
 * [v1.0-wave6] Smart reminders as agent tools. `remind_me` accepts natural
 * language ("in 20 minutes", "tomorrow 9am", "every Monday") and creates a
 * real [ScheduledTask] via [ScheduledTaskManager], so reminders fire through
 * the same AlarmManager pipeline as everything else — they survive reboots
 * and Doze, and post a notification with the reminder text.
 *
 * Reminder tasks are plain scheduled tasks whose label starts with
 * [REMINDER_PREFIX]; [list_reminders] / [cancel_reminder] filter on it. No
 * schema change was needed.
 *
 * Privacy: everything is on-device. The reminder text lives in the same
 * SharedPreferences store as other scheduled tasks — never uploaded.
 */
object ReminderTools {
    const val REMIND_NAME = "remind_me"
    const val LIST_NAME = "list_reminders"
    const val CANCEL_NAME = "cancel_reminder"

    /** Label prefix marking a scheduled task as a reminder. */
    const val REMINDER_PREFIX = "Reminder: "

    private const val TAG = "ReminderTools"

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = REMIND_NAME,
            description = "Set a reminder for the user. The reminder fires as a system notification at the requested time, even if the app is closed. " +
                "Accepts natural language for 'when': 'in 20 minutes', 'in 2 hours', 'tomorrow at 9am', 'today 6pm', 'at 7:30', 'every day at 8am', 'every Monday', 'weekdays at 9', 'next Friday at 10'. " +
                "Always confirm what was scheduled in your reply.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary, shown to the user (e.g. 'Set reminder for dentist'). Use the same language as the user."),
                "text" to AgentToolParam("string", "What to remind the user about, e.g. 'call mom' or 'dentist appointment'."),
                "when" to AgentToolParam("string", "When, in natural language: 'in 20 minutes', 'tomorrow at 9am', 'every Monday at 8', 'daily at 7:30'."),
            ),
            required = listOf("tool_title", "text", "when"),
            propertyOrdering = listOf("tool_title", "text", "when"),
        ),
        AgentToolDefinition(
            name = LIST_NAME,
            description = "List the user's active reminders (scheduled notifications). Returns each reminder's text and when it fires.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary, shown to the user."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf("tool_title"),
        ),
        AgentToolDefinition(
            name = CANCEL_NAME,
            description = "Cancel a reminder. Match by text contained in the reminder (e.g. 'dentist' cancels 'Reminder: dentist appointment'). If several match, they are all listed and none is cancelled — ask the user which one.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary, shown to the user."),
                "text" to AgentToolParam("string", "Text to match against the reminder, e.g. 'dentist'."),
            ),
            required = listOf("tool_title", "text"),
            propertyOrdering = listOf("tool_title", "text"),
        ),
    )

    /** Result of parsing natural-language time. */
    data class ParsedSchedule(
        val triggerAtMs: Long,
        val repeatMode: ScheduledRepeatMode,
        val customDays: Set<Int> = emptySet(),
    )

    /**
     * Dependency-free natural-language time parser. Returns null when nothing
     * matched — the caller then asks the model/user to rephrase.
     */
    object ReminderTimeParser {
        private val DAY_IDS = mapOf(
            "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY,
        )

        fun parse(raw: String): ParsedSchedule? {
            val s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            // Order matters: most specific first.
            parseRelative(s)?.let { return it }
            parseEveryDay(s)?.let { return it }
            parseNextWeekday(s)?.let { return it }
            parseTomorrow(s)?.let { return it }
            parseTodayAt(s)?.let { return it }
            parseAtTime(s)?.let { return it }
            return null
        }

        /** "in 20 minutes" / "in 2 hours" / "in 3 days" / "in 1 week". */
        private fun parseRelative(s: String): ParsedSchedule? {
            val m = Regex("""in\s+(\d+)\s*(minute|min|hour|hr|day|week)s?\b""").find(s) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val ms = when (m.groupValues[2]) {
                "minute", "min" -> n * 60_000L
                "hour", "hr" -> n * 3_600_000L
                "day" -> n * 86_400_000L
                "week" -> n * 7 * 86_400_000L
                else -> return null
            }
            return ParsedSchedule(System.currentTimeMillis() + ms, ScheduledRepeatMode.ONCE)
        }

        /** "every day at 8am" / "daily at 7:30". */
        private fun parseEveryDay(s: String): ParsedSchedule? {
            if (!s.contains("every day") && !s.startsWith("daily")) return null
            val t = extractTime(s) ?: return null
            return ParsedSchedule(t, ScheduledRepeatMode.DAILY)
        }

        /** "every Monday [at 8]" / "weekdays at 9". */
        private fun parseNextWeekday(s: String): ParsedSchedule? {
            if (s.contains("weekday")) {
                val t = extractTime(s) ?: defaultMorning()
                return ParsedSchedule(t, ScheduledRepeatMode.WEEKDAYS)
            }
            val m = Regex("""every\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b""").find(s)
                ?: return null
            val day = DAY_IDS[m.groupValues[1]] ?: return null
            val t = extractTime(s) ?: defaultMorning()
            return ParsedSchedule(t, ScheduledRepeatMode.CUSTOM, setOf(day))
        }

        /** "tomorrow at 9am" / "tomorrow 9:30". */
        private fun parseTomorrow(s: String): ParsedSchedule? {
            if (!s.contains("tomorrow")) return null
            val t = extractTime(s) ?: defaultMorning()
            val cal = Calendar.getInstance().apply {
                timeInMillis = t
                add(Calendar.DAY_OF_YEAR, 1)
            }
            return ParsedSchedule(cal.timeInMillis, ScheduledRepeatMode.ONCE)
        }

        /** "today at 6pm" / "today 18:00". */
        private fun parseTodayAt(s: String): ParsedSchedule? {
            if (!s.contains("today")) return null
            val t = extractTime(s) ?: return null
            val now = System.currentTimeMillis()
            val at = if (t <= now) t + 86_400_000L else t
            return ParsedSchedule(at, ScheduledRepeatMode.ONCE)
        }

        /** "at 7:30" / "at 9pm" — today if still ahead, else tomorrow. */
        private fun parseAtTime(s: String): ParsedSchedule? {
            val t = extractTime(s) ?: return null
            val now = System.currentTimeMillis()
            val at = if (t <= now) t + 86_400_000L else t
            return ParsedSchedule(at, ScheduledRepeatMode.ONCE)
        }

        /** Extracts "8", "8:30", "8am", "8:30pm", "20:00" → today at that time. */
        private fun extractTime(s: String): Long? {
            val m = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""").find(s) ?: return null
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            if (minute > 59) return null
            when (m.groupValues[3]) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour > 23) return null
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun defaultMorning(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Human summary for confirmations, e.g. "tomorrow at 09:00" / "daily at 08:00". */
        fun describe(p: ParsedSchedule): String {
            val cal = Calendar.getInstance().apply { timeInMillis = p.triggerAtMs }
            val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            return when (p.repeatMode) {
                ScheduledRepeatMode.ONCE -> {
                    val today = Calendar.getInstance()
                    val day = if (isSameDay(cal, today)) "today"
                    else {
                        today.add(Calendar.DAY_OF_YEAR, 1)
                        if (isSameDay(cal, today)) "tomorrow" else "%tF".format(cal.time)
                    }
                    "$day at $time"
                }
                ScheduledRepeatMode.DAILY -> "daily at $time"
                ScheduledRepeatMode.WEEKDAYS -> "weekdays at $time"
                ScheduledRepeatMode.CUSTOM -> {
                    val days = p.customDays.sorted().joinToString(", ") { dayName(it) }
                    "every $days at $time"
                }
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar): Boolean =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun dayName(dow: Int): String = when (dow) {
            Calendar.SUNDAY -> "Sunday"; Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"; Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"; Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"; else -> ""
        }
    }

    suspend fun executeRemind(argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
            val toolTitle = args.optString("tool_title", REMIND_NAME).ifBlank { REMIND_NAME }
            val text = args.optString("text", "").trim()
            val whenRaw = args.optString("when", "").trim()
            if (text.isEmpty() || whenRaw.isEmpty()) {
                return@withContext ToolExecutionResult(
                    "Error: 'text' and 'when' are both required.", false, toolTitle = toolTitle,
                )
            }
            val parsed = ReminderTimeParser.parse(whenRaw)
                ?: return@withContext ToolExecutionResult(
                    "Couldn't understand when \"$whenRaw\" means. Try 'in 20 minutes', " +
                        "'tomorrow at 9am', 'at 7:30', 'every day at 8am' or 'every Monday'.",
                    false, toolTitle = toolTitle,
                )
            val cal = Calendar.getInstance().apply { timeInMillis = parsed.triggerAtMs }
            val startOfDay = Calendar.getInstance().apply {
                timeInMillis = parsed.triggerAtMs
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val task = ScheduledTask(
                label = REMINDER_PREFIX + text.take(80),
                timeOfDayHour = cal.get(Calendar.HOUR_OF_DAY),
                timeOfDayMinute = cal.get(Calendar.MINUTE),
                repeatMode = parsed.repeatMode,
                customDays = parsed.customDays,
                // ONCE tasks fire on the first matching day at/after the
                // start date — pin it to the parsed day so "tomorrow at 9am"
                // can't slip to today.
                startDateMs = if (parsed.repeatMode == ScheduledRepeatMode.ONCE) startOfDay else null,
                prompt = "This is a scheduled reminder firing now. Tell the user, briefly and warmly: \"$text\". " +
                    "One or two sentences — no preamble about being a reminder system.",
                enabled = true,
            )
            runCatching {
                ScheduledTaskManager(context.applicationContext).create(task)
            }.onFailure { t ->
                AppLogger.error(TAG, "remind_me create failed: ${t.message}")
                return@withContext ToolExecutionResult(
                    "Couldn't schedule the reminder (${t.message}).", false, toolTitle = toolTitle,
                )
            }
            ToolExecutionResult(
                "Reminder set: \"$text\" — ${ReminderTimeParser.describe(parsed)}. " +
                    "It will arrive as a notification; manage it in Routines.",
                true, toolTitle = toolTitle,
            )
        }

    suspend fun executeList(argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", LIST_NAME) }
                .getOrDefault(LIST_NAME).ifBlank { LIST_NAME }
            val reminders = runCatching {
                ScheduledTaskManager(context.applicationContext).list()
                    .filter { it.label.startsWith(REMINDER_PREFIX) && it.enabled }
                    .sortedBy { it.nextTriggerMs() ?: Long.MAX_VALUE }
            }.getOrElse { emptyList() }
            if (reminders.isEmpty()) {
                return@withContext ToolExecutionResult(
                    "No active reminders.", true, toolTitle = toolTitle,
                )
            }
            val lines = reminders.map { t ->
                val next = t.nextTriggerMs()?.let {
                    val c = Calendar.getInstance().apply { timeInMillis = it }
                    "%tF %tR".format(c.time, c.time)
                } ?: "unscheduled"
                "- ${t.label.removePrefix(REMINDER_PREFIX)} (fires: $next)"
            }
            ToolExecutionResult(lines.joinToString("\n"), true, toolTitle = toolTitle)
        }

    suspend fun executeCancel(argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
            val toolTitle = args.optString("tool_title", CANCEL_NAME).ifBlank { CANCEL_NAME }
            val text = args.optString("text", "").trim().lowercase()
            if (text.isEmpty()) {
                return@withContext ToolExecutionResult(
                    "Error: 'text' is required — which reminder should I cancel?",
                    false, toolTitle = toolTitle,
                )
            }
            val manager = ScheduledTaskManager(context.applicationContext)
            val matches = runCatching {
                manager.list().filter {
                    it.label.startsWith(REMINDER_PREFIX) &&
                        it.label.lowercase().contains(text)
                }
            }.getOrElse { emptyList() }
            return@withContext when {
                matches.isEmpty() -> ToolExecutionResult(
                    "No reminder matching \"$text\" found.", true, toolTitle = toolTitle,
                )
                matches.size > 1 -> ToolExecutionResult(
                    "Several reminders match \"$text\":\n" +
                        matches.joinToString("\n") { "- ${it.label.removePrefix(REMINDER_PREFIX)}" } +
                        "\nAsk the user which one to cancel — nothing was cancelled.",
                    true, toolTitle = toolTitle,
                )
                else -> {
                    runCatching { manager.delete(matches.first().id) }
                    ToolExecutionResult(
                        "Cancelled: \"${matches.first().label.removePrefix(REMINDER_PREFIX)}\".",
                        true, toolTitle = toolTitle,
                    )
                }
            }
        }
}
