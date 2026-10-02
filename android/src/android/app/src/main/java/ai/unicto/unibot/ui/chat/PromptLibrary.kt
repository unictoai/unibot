package ai.unicto.unibot.ui.chat

import android.content.Context
import ai.unicto.unibot.logging.AppLogger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [P2-prompt-library] User's prompt preset library + composer modes.
 *
 * Two preset kinds:
 * - [PresetKind.TEXT]: a reusable text snippet. Inserted into the composer
 *   via the @-mention picker (see [ChatViewModelMentionExt.selectMention])
 *   or the library screen's "Use in chat".
 * - [PresetKind.MODE]: a one-tap personality overlay. When active, its
 *   [PromptPreset.content] is injected into the agent system prompt as an
 *   extra section (see [ChatViewModelModeExt.activeComposerModeSection]).
 *   "Study Mode" is the built-in MODE; custom modes ("Talk like a terse
 *   engineer") are user-authored MODE presets.
 *
 * Persistence: a single JSON file under `<filesDir>/minis-global/memory/` —
 * the memory-files area — so presets (including custom modes) travel with
 * the memory backup and stay user-editable as plain text. SOUL.md itself is
 * deliberately NOT used: that file is the agent's own personality, while
 * modes are per-chat overlays the user toggles on and off.
 */
@Serializable
enum class PresetKind { TEXT, MODE }

@Serializable
data class PromptPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val kind: PresetKind = PresetKind.TEXT,
    /** TEXT: the snippet inserted into the composer. MODE: the system-prompt addendum. */
    val content: String,
    /** Built-ins (Study Mode) can't be edited or deleted — only toggled/reset. */
    val builtIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

object PromptLibraryStore {
    private const val TAG = "PromptLibrary"
    private const val FILE_NAME = "prompt-library.json"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _presets = MutableStateFlow<List<PromptPreset>>(emptyList())
    val presets: StateFlow<List<PromptPreset>> = _presets.asStateFlow()

    /** Modes only, built-ins first then user customs by creation time. */
    val modes: List<PromptPreset>
        get() = _presets.value.filter { it.kind == PresetKind.MODE }
            .sortedWith(compareBy({ !it.builtIn }, { it.createdAt }))

    /** Text snippets only. */
    val textPresets: List<PromptPreset>
        get() = _presets.value.filter { it.kind == PresetKind.TEXT }
            .sortedWith(compareBy({ !it.builtIn }, { it.createdAt }))

    @Volatile
    private var initialized = false

    // ── Active composer mode (global) ────────────────────────────────
    // The active MODE preset id, or null. Global (not per-session) by
    // design: a mode stays on across chats until the user turns it off,
    // and every surface (slash menu, library screen, system prompt)
    // reads the same flow. Persisted in SharedPreferences so it survives
    // restarts; cleared automatically if the preset is deleted.
    private const val PREFS = "prompt_library_prefs"
    private const val KEY_ACTIVE_MODE = "active_mode_id"

    private val _activeModeId = MutableStateFlow<String?>(null)
    val activeModeId: StateFlow<String?> = _activeModeId.asStateFlow()

    /** Synchronous read of the active MODE preset, or null. */
    fun activeModeNow(): PromptPreset? {
        val id = _activeModeId.value ?: return null
        return _presets.value.firstOrNull { it.id == id && it.kind == PresetKind.MODE }
    }

    /** Set (or clear, with null) the active composer mode. Persists. */
    fun setActiveModeId(context: Context, id: String?) {
        val valid = id?.let { findById(it)?.takeIf { p -> p.kind == PresetKind.MODE }?.id }
        _activeModeId.value = valid
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_MODE, valid)
            .apply()
    }

    private fun libraryFile(context: Context): File =
        File(File(context.filesDir, "minis-global/memory"), FILE_NAME)

    /** Idempotent — call from ChatViewModel init / library screen entry. */
    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        _presets.value = loadFromDisk(context)
        // Restore the persisted active mode; drop it if the preset is gone.
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_MODE, null)
        _activeModeId.value =
            saved?.takeIf { id -> _presets.value.any { it.id == id && it.kind == PresetKind.MODE } }
    }

    fun findById(id: String): PromptPreset? = _presets.value.firstOrNull { it.id == id }

    /** TEXT presets matching the @-mention filter, capped for the picker. */
    fun matchTextPresets(filter: String, limit: Int = 5): List<PromptPreset> {
        val f = filter.trim().lowercase()
        return textPresets
            .filter { f.isEmpty() || it.name.lowercase().contains(f) }
            .take(limit)
    }

    /**
     * Insert or update a preset. Names are sanitized (no '/' — it would break
     * the @-mention entry's basename derivation). Returns the stored preset,
     * or null when validation fails (blank name/content).
     */
    fun upsert(context: Context, preset: PromptPreset): PromptPreset? {
        val name = preset.name.trim().replace('/', ' ').take(60)
        val content = preset.content.trim()
        if (name.isEmpty() || content.isEmpty()) return null
        val stored = preset.copy(name = name, content = content)
        val current = _presets.value.toMutableList()
        val idx = current.indexOfFirst { it.id == stored.id }
        if (idx >= 0) {
            // Built-ins are immutable — refuse edits, keep the original.
            if (current[idx].builtIn) return null
            current[idx] = stored
        } else {
            current.add(stored)
        }
        _presets.value = current
        saveToDisk(context)
        return stored
    }

    /** Delete a user preset. Built-ins are protected — returns false. */
    fun delete(context: Context, id: String): Boolean {
        val current = _presets.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0 || current[idx].builtIn) return false
        current.removeAt(idx)
        _presets.value = current
        saveToDisk(context)
        // Deleting the active mode turns it off.
        if (_activeModeId.value == id) setActiveModeId(context, null)
        return true
    }

    private fun loadFromDisk(context: Context): List<PromptPreset> {
        val file = libraryFile(context)
        val customs = if (file.exists()) {
            runCatching {
                json.decodeFromString(ListSerializer(PromptPreset.serializer()), file.readText())
            }.getOrElse {
                AppLogger.warning(TAG, "parse failed, starting fresh: ${it.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
        // Built-ins are code-owned: always present, never persisted (so a
        // future app update can refresh their wording without a migration).
        // A custom preset that somehow shares a built-in id is dropped.
        val builtInIds = builtInPresets().map { it.id }.toSet()
        return builtInPresets() + customs.filterNot { it.id in builtInIds }
    }

    private fun saveToDisk(context: Context) {
        // Persist customs only — built-ins are re-seeded from code on load.
        val customs = _presets.value.filterNot { it.builtIn }
        try {
            val file = libraryFile(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(PromptPreset.serializer()), customs))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "save failed: ${t.message}")
        }
    }

    /**
     * Code-owned built-ins. The Socratic tutor wording is deliberately
     * compact: it must steer a general agent loop without burning a large
     * static prefix on every turn.
     */
    fun builtInPresets(): List<PromptPreset> = listOf(
        PromptPreset(
            id = "builtin-study-mode",
            name = "Study Mode",
            description = "Socratic tutor — teaches step by step, asks questions",
            kind = PresetKind.MODE,
            content = """
                STUDY MODE — you are a Socratic tutor, not an answer machine.

                - Teach step by step: explain ONE concept at a time, then check understanding before moving on.
                - Prefer questions over answers: when the user asks something, first ask what they already know or how they'd approach it, then guide them to the answer.
                - Never dump a full solution immediately for homework-style problems — build up to it with hints and sub-questions.
                - Use simple language, concrete examples, and short summaries after each step.
                - If the user is stuck, break the problem into smaller pieces rather than giving up the answer.
                - Celebrate correct reasoning briefly; correct mistakes by asking leading questions, not lectures.
                - Keep each reply focused: one idea, one question back to the user.
            """.trimIndent(),
            builtIn = true,
            createdAt = 0,
        ),
        PromptPreset(
            id = "builtin-eli5",
            name = "Explain simply",
            description = "Explain like I'm five",
            kind = PresetKind.TEXT,
            content = "Explain this in very simple terms, like I'm five years old. Use a everyday analogy and keep it short:\n\n",
            builtIn = true,
            createdAt = 0,
        ),
    )
}
