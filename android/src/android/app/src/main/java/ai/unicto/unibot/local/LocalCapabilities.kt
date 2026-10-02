package ai.unicto.unibot.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * App-side capabilities for on-device chat — the answer to "the model I
 * downloaded doesn't use our app capabilities".
 *
 * A small local model can't do tool-calling reliably, so the app does the
 * capable part itself and hands the model plain text:
 *
 *  - the current date/time is always added to the system prompt (the model
 *    otherwise has no idea what "today" is);
 *  - [phoneStateLine] answers "what's my battery"-style questions;
 *  - [LocalWebSearch] fetches live web results when [needsWebSearch] says
 *    the question needs current info, and they are prepended to the prompt
 *    in a clearly labeled block.
 *
 * Privacy: [LlmBackend]'s zero-network promise is untouched — this runs in
 * [ai.unicto.unibot.ui.chat.ChatViewModel.runLocalTurn] *before* inference,
 * never inside the backend. The only network disclosure is the question
 * text sent to DuckDuckGo when a search triggers, and the Settings toggle
 * ([isWebSearchEnabled]) turns that off entirely.
 */
object LocalCapabilities {
    private const val PREFS = "local_capabilities_prefs"
    private const val KEY_WEB_SEARCH = "web_search_enabled"

    fun isWebSearchEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WEB_SEARCH, true)

    fun setWebSearchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WEB_SEARCH, enabled)
            .apply()
    }

    /** e.g. "Current date and time: Friday, October 2, 2026, 4:45 PM." */
    fun currentDateTimeLine(now: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.getDefault())
        fmt.timeZone = TimeZone.getDefault()
        return "Current date and time: ${fmt.format(Date(now))}."
    }

    private val SEARCH_PATTERNS = listOf(
        Regex("\\bwhen\\b.{0,40}\\b(will|is|does)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwho\\s+won\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwho\\s+is\\s+the\\s+(current|new|latest)\\b", RegexOption.IGNORE_CASE),
        Regex("\\blatest\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnews\\b", RegexOption.IGNORE_CASE),
        Regex("\\bright\\s+now\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcurrent\\b", RegexOption.IGNORE_CASE),
        Regex("\\btoday'?s\\b", RegexOption.IGNORE_CASE),
        Regex("\\bprice\\b", RegexOption.IGNORE_CASE),
        Regex("\\bhow\\s+much\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcost\\s+of\\b", RegexOption.IGNORE_CASE),
        Regex("\\brelease\\s+date\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcoming\\s+out\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwill\\s+release\\b", RegexOption.IGNORE_CASE),
        Regex("\\bscore\\b", RegexOption.IGNORE_CASE),
        Regex("\\bweather\\b", RegexOption.IGNORE_CASE),
        Regex("\\bstock\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(2024|2025|2026|2027)\\b"),
    )

    /**
     * Conservative heuristic: does this message look like it needs live
     * info? Small models can't do tool-calls, so the app decides. False
     * negatives are fine (the user can rephrase); false positives just cost
     * a few seconds.
     */
    fun needsWebSearch(text: String): Boolean =
        SEARCH_PATTERNS.any { it.containsMatchIn(text) }

    /**
     * "what's my battery" → "Phone state: battery 29% (not charging).",
     * else null. No permission needed for the sticky battery intent.
     */
    fun phoneStateLine(context: Context, text: String): String? {
        if (!Regex("\\bbattery\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return null
        val pct = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "Phone state: battery $pct%${if (charging) " (charging)" else " (not charging)"}."
    }

    /** Pure formatter for the injected search block (unit-testable). */
    fun buildSearchBlock(results: List<WebResult>): String = buildString {
        appendLine("Live web search results (fetched just now):")
        results.forEachIndexed { i, r ->
            appendLine("${i + 1}. ${r.title}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
            appendLine("   Source: ${r.url}")
        }
        append(
            "Answer using these results. If they don't contain the answer, " +
                "say so honestly instead of guessing.",
        )
    }

    /**
     * Build the (systemPrompt, prompt) pair for a local turn: date/time
     * always in, phone state and web results when triggered. Suspends only
     * when a web search actually runs.
     */
    suspend fun augment(
        context: Context,
        systemPrompt: String?,
        userText: String,
    ): Pair<String?, String> {
        val appContext = context.applicationContext
        val sys = buildString {
            val base = systemPrompt?.takeIf { it.isNotBlank() }
            if (base != null) append(base)
            if (isNotEmpty()) append("\n\n")
            append(currentDateTimeLine())
        }
        val extras = StringBuilder()
        phoneStateLine(appContext, userText)?.let { extras.appendLine(it) }
        if (isWebSearchEnabled(appContext) && needsWebSearch(userText)) {
            val results = runCatching { LocalWebSearch.search(userText) }.getOrDefault(emptyList())
            if (results.isNotEmpty()) {
                extras.appendLine(buildSearchBlock(results))
            }
        }
        val prompt = if (extras.isEmpty()) userText else "${extras}\nUser question: $userText"
        return sys to prompt
    }
}
