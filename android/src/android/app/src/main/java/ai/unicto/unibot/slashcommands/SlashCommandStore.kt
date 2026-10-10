package ai.unicto.unibot.slashcommands

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Item 94 — user-defined slash commands: short `/triggers` that expand to
 * full prompts.
 *
 * A command is a trigger word plus a template. When the user sends
 * `/brief my project`, the composer expands it (via [SlashCommandExpander])
 * to the template with `{args}` replaced by `my project`, `{date}` by
 * today's date and `{time}` by the current time.
 *
 * INTEGRATION NOTE for the chat theme worker:
 *  - Merge [SlashCommandStore.forSlashMenu] entries into
 *    `ChatViewModel.filteredSlashCommands()` (same pattern as the installed
 *    skills rows): tapping fills the composer with `/<trigger> `.
 *  - Call [SlashCommandExpander.expand] on the outgoing text before the
 *    normal send path — a leading `/trigger` becomes the expanded prompt.
 *    Non-matching text passes through untouched.
 *
 * Persistence: SharedPreferences JSON array. Additive fields only.
 */
data class CustomSlashCommand(
    val id: String = UUID.randomUUID().toString(),
    /** Trigger word without the leading slash, e.g. "brief". */
    val trigger: String,
    val description: String = "",
    /** Template with optional {args}, {date}, {time} placeholders. */
    val template: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("trigger", trigger)
        put("description", description)
        put("template", template)
        put("enabled", enabled)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): CustomSlashCommand = CustomSlashCommand(
            id = o.optString("id", UUID.randomUUID().toString()),
            trigger = o.optString("trigger", ""),
            description = o.optString("description", ""),
            template = o.optString("template", ""),
            enabled = o.optBoolean("enabled", true),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )

        /** `My Trigger!` → `my-trigger`. */
        fun sanitizeTrigger(raw: String): String {
            val clean = raw.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return clean.take(32)
        }
    }
}

/** One row for the chat `/` popup. */
data class SlashMenuEntry(
    val id: String,
    val trigger: String,
    val description: String,
)

class SlashCommandStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _commands = MutableStateFlow<List<CustomSlashCommand>>(emptyList())
    val commands: StateFlow<List<CustomSlashCommand>> = _commands.asStateFlow()

    init {
        val stored = load()
        _commands.value = stored + seedDocsCommandIfNeeded(stored)
    }

    /**
     * Seed the built-in `/docs` ("Ask my documents") command once. It becomes
     * a regular stored row afterwards, so the `/` menu, the send-path
     * expansion and the enable/disable UI all treat it like any other
     * command. The [KEY_DOCS_SEEDED] flag keeps a user-deleted `/docs` from
     * resurrecting on every launch; an existing user command already using
     * the `docs` trigger is never overwritten.
     */
    private fun seedDocsCommandIfNeeded(stored: List<CustomSlashCommand>): List<CustomSlashCommand> {
        if (stored.any { it.trigger == DocsSlashCommand.TRIGGER }) return emptyList()
        if (prefs.getBoolean(KEY_DOCS_SEEDED, false)) return emptyList()
        val seeded = DocsSlashCommand.defaultCommand()
        persist(stored + seeded)
        prefs.edit().putBoolean(KEY_DOCS_SEEDED, true).apply()
        return listOf(seeded)
    }

    fun all(): List<CustomSlashCommand> = _commands.value

    fun enabled(): List<CustomSlashCommand> = _commands.value.filter { it.enabled }

    /** Rows for the chat `/` popup (enabled commands only). */
    fun forSlashMenu(): List<SlashMenuEntry> = enabled().map {
        SlashMenuEntry(id = it.id, trigger = it.trigger, description = it.description)
    }

    fun findByTrigger(trigger: String): CustomSlashCommand? =
        enabled().firstOrNull { it.trigger.equals(trigger, ignoreCase = true) }

    /**
     * Insert or update. Returns null when the trigger is blank or collides
     * with a different command (triggers must be unique).
     */
    fun upsert(command: CustomSlashCommand): CustomSlashCommand? {
        val trigger = CustomSlashCommand.sanitizeTrigger(command.trigger)
        if (trigger.isBlank()) return null
        if (_commands.value.any { it.id != command.id && it.trigger == trigger }) return null
        val normalized = command.copy(trigger = trigger)
        _commands.value = (_commands.value.filter { it.id != normalized.id } + normalized)
            .sortedBy { it.trigger }
        persist(_commands.value)
        return normalized
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _commands.value = _commands.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persist(_commands.value)
    }

    fun delete(id: String) {
        _commands.value = _commands.value.filter { it.id != id }
        persist(_commands.value)
    }

    fun observe(): Flow<List<CustomSlashCommand>> = callbackFlow {
        trySend(_commands.value)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_COMMANDS || key == null) trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun load(): List<CustomSlashCommand> {
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { CustomSlashCommand.fromJson(o) }
                        .onSuccess { if (it.trigger.isNotBlank() && it.template.isNotBlank()) add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed slash command: ${it.message}") }
                }
            }.sortedBy { it.trigger }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load slash commands failed: ${t.message}")
            emptyList()
        }
    }

    private fun persist(commands: List<CustomSlashCommand>) {
        val arr = JSONArray()
        commands.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_COMMANDS, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "SlashCommandStore"
        private const val PREFS_NAME = "unibot_slash_commands_prefs"
        private const val KEY_COMMANDS = "commands_json"
        private const val KEY_DOCS_SEEDED = "docs_builtin_seeded"
    }
}

/**
 * Expands a leading `/trigger` into the command's full prompt. Pure logic —
 * unit-tested. Non-command text passes through as null (caller sends it
 * normally).
 */
object SlashCommandExpander {

    data class Expanded(val command: CustomSlashCommand, val prompt: String)

    /**
     * @param input the raw composer text. Matches only a LEADING `/trigger`
     *   (optionally followed by args). Returns null when the text is not a
     *   custom command — including when it names a disabled or unknown
     *   trigger, so built-in `/` commands keep working.
     */
    fun expand(input: String, commands: List<CustomSlashCommand>): Expanded? {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("/")) return null
        val withoutSlash = trimmed.removePrefix("/")
        val spaceIdx = withoutSlash.indexOfFirst { it.isWhitespace() }
        val trigger = if (spaceIdx < 0) withoutSlash else withoutSlash.substring(0, spaceIdx)
        val args = if (spaceIdx < 0) "" else withoutSlash.substring(spaceIdx + 1).trim()
        if (trigger.isBlank()) return null
        val command = commands.firstOrNull { it.enabled && it.trigger.equals(trigger, ignoreCase = true) }
            ?: return null
        return Expanded(command, renderTemplate(command.template, args))
    }

    /** Public so the editor screen can show a live preview. */
    fun renderTemplate(template: String, args: String): String {
        val now = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(now)
        return template
            .replace("{args}", args)
            .replace("{date}", date)
            .replace("{time}", time)
    }
}
