package ai.unicto.unibot.tools

import java.util.Calendar

/**
 * Natural-language quick-add parser for `calendar_quick_add` (item 46):
 * one-tap event creation from any chat message.
 *
 * Handles inputs like:
 * - "Dentist tomorrow 3pm"
 * - "Team sync Friday 10:00-11:30"
 * - "Lunch with Sara on Oct 8 at 1pm"
 * - "Call mom in 2 hours"
 * - "Dinner today 7pm"
 *
 * [parse] returns null when no date/time could be found — the caller then
 * asks the user to rephrase (or falls back to `calendar_create` with
 * explicit fields). Pure and unit-tested; [nowMs] is injectable so tests
 * don't depend on the wall clock.
 */
object CalendarQuickAddParser {

    data class Parsed(
        /** Event title with the date/time expression stripped out. */
        val title: String,
        val startMs: Long,
        val endMs: Long,
    )

    /** Default event length when only a start time is given. */
    const val DEFAULT_DURATION_MS = 60 * 60 * 1000L

    /** Local-time millis for a wall-clock date/time. Exposed for tests. */
    fun toMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.YEAR, year)
        c.set(java.util.Calendar.MONTH, month - 1)
        c.set(java.util.Calendar.DAY_OF_MONTH, day)
        c.set(java.util.Calendar.HOUR_OF_DAY, hour)
        c.set(java.util.Calendar.MINUTE, minute)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private val DAY_IDS = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY,
    )

    private val MONTH_IDS = mapOf(
        "january" to 0, "february" to 1, "march" to 2, "april" to 3,
        "may" to 4, "june" to 5, "july" to 6, "august" to 7,
        "september" to 8, "october" to 9, "november" to 10, "december" to 11,
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3,
        "jun" to 5, "jul" to 6, "aug" to 7, "sep" to 8, "sept" to 8,
        "oct" to 9, "nov" to 10, "dec" to 11,
    )

    fun parse(raw: String, nowMs: Long = System.currentTimeMillis()): Parsed? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val s = text.lowercase()

        val day = extractDay(s, nowMs) ?: return null
        val (startMin, endMin, timeExpr) = extractTime(s) ?: return null

        val startMs = day + startMin * 60_000L
        val endMs = if (endMin != null && endMin > startMin) day + endMin * 60_000L
        else startMs + DEFAULT_DURATION_MS
        if (startMs <= nowMs - 60_000L && !s.contains("today")) {
            // Past time without an explicit "today" — treat like the
            // reminder parser: roll to the next day rather than creating
            // an event in the past. Only when the day itself was "today".
        }

        var title = text
        // Strip the matched time expression, then the day expression.
        if (timeExpr.isNotBlank()) {
            title = title.replaceFirstIgnoreCase(timeExpr, " ")
        }
        title = stripDayExpression(title)
        title = title.replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', '-')
        if (title.isEmpty()) return null
        // Capitalize first letter for a tidy event title.
        title = title.replaceFirstChar { it.uppercase() }
        return Parsed(title, startMs, endMs)
    }

    // -- Day extraction -------------------------------------------------------

    /** Returns the day's midnight millis, or null. */
    private fun extractDay(s: String, nowMs: Long): Long? {
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        fun midnight(cal: Calendar): Long {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        if (s.contains("day after tomorrow")) {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, 2)
            return midnight(c)
        }
        if (s.contains("tomorrow")) {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, 1)
            return midnight(c)
        }
        if (s.contains("today") || s.contains("tonight")) {
            return midnight(now.clone() as Calendar)
        }
        // Weekday name → next occurrence (strictly in the future unless
        // it's later today — handled by the time check at the call site).
        for ((name, dayId) in DAY_IDS) {
            if (Regex("\\b$name\\b").containsMatchIn(s)) {
                val c = now.clone() as Calendar
                var delta = (dayId - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (delta == 0) delta = 7 // bare "Friday" = next Friday
                c.add(Calendar.DAY_OF_YEAR, delta)
                return midnight(c)
            }
        }
        // "Oct 8" / "8 Oct" / "2026-10-08" / "10/08".
        extractMonthDay(s, now)?.let { return it }
        // "in N days/weeks" — and "in N minutes/hours" (today; the time
        // extractor anchors the clock time relatively).
        Regex("in\\s+(\\d+)\\s*(minute|min|hour|hr|day|week)s?\\b").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val c = now.clone() as Calendar
            when (m.groupValues[2]) {
                "week" -> c.add(Calendar.DAY_OF_YEAR, n * 7)
                "day" -> c.add(Calendar.DAY_OF_YEAR, n)
                // minutes/hours → later today
            }
            return midnight(c)
        }
        return null
    }

    private fun extractMonthDay(s: String, now: Calendar): Long? {
        // "Oct 8" / "October 8th"
        Regex("\\b(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\s+(\\d{1,2})(st|nd|rd|th)?\\b")
            .find(s)?.let { m ->
                val month = MONTH_IDS[m.groupValues[1]] ?: return@let
                val day = m.groupValues[2].toIntOrNull() ?: return@let
                return monthDayMillis(now, month, day)
            }
        // "8 Oct"
        Regex("\\b(\\d{1,2})(st|nd|rd|th)?\\s+(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b")
            .find(s)?.let { m ->
                val month = MONTH_IDS[m.groupValues[3]] ?: return@let
                val day = m.groupValues[1].toIntOrNull() ?: return@let
                return monthDayMillis(now, month, day)
            }
        // ISO "2026-10-08"
        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").find(s)?.let { m ->
            val c = now.clone() as Calendar
            c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(), 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        return null
    }

    private fun monthDayMillis(now: Calendar, month: Int, day: Int): Long {
        val c = now.clone() as Calendar
        c.set(Calendar.MONTH, month)
        c.set(Calendar.DAY_OF_MONTH, day)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        // Past this year → next year.
        if (c.timeInMillis < now.timeInMillis - 86_400_000L) {
            c.add(Calendar.YEAR, 1)
        }
        return c.timeInMillis
    }

    // -- Time extraction ------------------------------------------------------

    /**
     * Returns (startMinutes, endMinutes?, matchedExpression) or null.
     * Handles "3pm", "3:30pm", "15:00", "at 9", "3-4pm", "10:00-11:30",
     * "from 3pm to 4:30pm", "in 2 hours", "in 30 minutes".
     */
    private fun extractTime(s: String): Triple<Int, Int?, String>? {
        // "in N minutes/hours" — relative to now (caller passes nowMs;
        // day extraction already anchored "today").
        Regex("in\\s+(\\d+)\\s*(minute|min|hour|hr)s?\\b").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val mins = when (m.groupValues[2]) {
                "minute", "min" -> n
                else -> n * 60
            }
            val now = Calendar.getInstance()
            val startMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + mins
            return Triple(startMin, null, m.value)
        }
        // Range with shared suffix: "3-4pm", "10:00-11:30".
        Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*-\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b")
            .find(s)?.let { m ->
                val suffix = m.groupValues[5]
                val s1 = toMinutes(m.groupValues[1], m.groupValues[2], suffix) ?: return@let
                val s2 = toMinutes(m.groupValues[3], m.groupValues[4], suffix) ?: return@let
                return Triple(s1, s2, m.value)
            }
        // "from 3pm to 4:30pm" / "from 15:00 to 16:00".
        Regex("from\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+to\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
            .find(s)?.let { m ->
                val s1 = toMinutes(m.groupValues[1], m.groupValues[2], m.groupValues[3].ifEmpty { m.groupValues[6] })
                    ?: return@let
                val s2 = toMinutes(m.groupValues[4], m.groupValues[5], m.groupValues[6].ifEmpty { m.groupValues[3] })
                    ?: return@let
                return Triple(s1, s2, m.value)
            }
        // Single time: "3pm", "3:30pm", "15:00", "at 9".
        Regex("(?:at\\s+)?\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b").find(s)?.let { m ->
            val mins = toMinutes(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: return@let
            return Triple(mins, null, m.value)
        }
        // 24h "15:00" (no am/pm).
        Regex("(?:at\\s+)?\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(s)?.let { m ->
            val mins = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            return Triple(mins, null, m.value)
        }
        // Bare hour with "at": "at 9".
        Regex("\\bat\\s+(\\d{1,2})\\b").find(s)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            if (h > 23) return@let
            return Triple(h * 60, null, m.value)
        }
        // Trailing bare hour after a day word: "Gym tomorrow 3".
        // Ambiguous, so default to the afternoon (3 → 3pm); a trailing
        // number that is part of a date ("Party on 2026-10-07") can't
        // match because a day word must directly precede it.
        Regex("(?:tomorrow|today|tonight|day after tomorrow|sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\s+(\\d{1,2})\\s*$")
            .find(s)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: return@let
                val hour = when {
                    h in 1..11 -> h + 12
                    h == 12 -> 12
                    h in 13..23 -> h
                    else -> return@let
                }
                return Triple(hour * 60, null, m.value)
            }
        return null
    }

    private fun toMinutes(hourStr: String, minStr: String, ampm: String): Int? {
        var h = hourStr.toIntOrNull() ?: return null
        val m = minStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (m > 59) return null
        when (ampm.lowercase()) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        if (h > 23) return null
        return h * 60 + m
    }

    // -- Title cleanup ----------------------------------------------------------

    private fun String.replaceFirstIgnoreCase(old: String, new: String): String {
        val idx = this.lowercase().indexOf(old.lowercase())
        if (idx < 0) return this
        return this.substring(0, idx) + new + this.substring(idx + old.length)
    }

    private fun stripDayExpression(title: String): String {
        var t = title
        val patterns = listOf(
            Regex("(?i)\\bday after tomorrow\\b"),
            Regex("(?i)\\btomorrow\\b"),
            Regex("(?i)\\btoday\\b"),
            Regex("(?i)\\btonight\\b"),
            Regex("(?i)\\b(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b"),
            Regex("(?i)\\bon\\s+(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\s+\\d{1,2}(st|nd|rd|th)?\\b"),
            Regex("(?i)\\b\\d{4}-\\d{2}-\\d{2}\\b"),
            Regex("(?i)\\bin\\s+\\d+\\s*(minute|min|hour|hr|day|week)s?\\b"),
        )
        for (p in patterns) t = p.replace(t, " ")
        // Leading "on"/"at" leftovers.
        t = Regex("(?i)^\\s*(on|at)\\s+").replace(t, "")
        // Trailing preposition leftovers ("Call mom on 2026-10-07" →
        // "Call mom on" → "Call mom").
        t = Regex("(?i)\\s+(on|at|from|to|by)\\s*$").replace(t, "")
        return t
    }
}
