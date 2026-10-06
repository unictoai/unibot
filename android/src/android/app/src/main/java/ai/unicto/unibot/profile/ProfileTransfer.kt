package ai.unicto.unibot.profile

import android.content.Context
import android.os.Build
import ai.unicto.unibot.UnibotApp
import ai.unicto.unibot.automation.BackgroundAgent
import ai.unicto.unibot.automation.BackgroundAgentStore
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.questioncards.QuestionCardDef
import ai.unicto.unibot.questioncards.QuestionCardEngine
import ai.unicto.unibot.questioncards.QuestionCardStore
import ai.unicto.unibot.scheduled.SchedulePolicyStore
import ai.unicto.unibot.slashcommands.CustomSlashCommand
import ai.unicto.unibot.slashcommands.SlashCommandStore
import ai.unicto.unibot.ui.chat.PromptLibraryStore
import ai.unicto.unibot.ui.chat.PresetKind
import ai.unicto.unibot.ui.chat.PromptPreset
import org.json.JSONArray

/**
 * Item 96 — build a [PortableProfile] from on-device state (export) and
 * apply one back (import).
 *
 * Export never writes a secret in cleartext: every setting value whose key
 * looks secret-bearing is replaced with [REDACTED_MARKER] and its key is
 * listed in [PortableProfile.redactedKeys]. Provider API keys are never
 * collected at all — they stay in encrypted storage on this device.
 */
class ProfileExporter(private val context: Context) {

    fun export(): PortableProfile {
        val policy = SchedulePolicyStore(context)
        val qh = policy.quietHours()

        val settings = mutableMapOf(
            "quiet_hours.enabled" to qh.enabled.toString(),
            "quiet_hours.start" to "%02d:%02d".format(qh.startHour, qh.startMinute),
            "quiet_hours.end" to "%02d:%02d".format(qh.endHour, qh.endMinute),
            "battery_saver_pause" to policy.batterySaverPause().toString(),
        )
        val (redactedSettings, redactedKeys) = SecretRedactor.redact(settings)

        val skills = runCatching {
            val app = context.applicationContext as? UnibotApp
            val repo = app?.skillRepository ?: return@runCatching emptyList()
            repo.skills.value.map { skill ->
                val md = runCatching { repo.readSkillFile(skill.id, "SKILL.md") }.getOrNull()
                ProfileSkill(
                    name = skill.name,
                    description = skill.description,
                    version = skill.version,
                    skillMd = md ?: "",
                )
            }
        }.getOrDefault(emptyList())

        val presets = runCatching {
            PromptLibraryStore.ensureInit(context)
            PromptLibraryStore.presets.value
                .filter { !it.builtIn }
                .map {
                    ProfilePromptPreset(
                        name = it.name,
                        description = it.description,
                        kind = it.kind.name,
                        content = it.content,
                    )
                }
        }.getOrDefault(emptyList())

        val slashJson = JSONArray().apply {
            SlashCommandStore(context).all().forEach { put(it.toJson()) }
        }.toString()
        val cardDefsJson = JSONArray().apply {
            QuestionCardStore(context).all()
                .map { QuestionCardDef(it.title, it.description, it.steps) }
                .distinctBy { it.title to it.steps.map { s -> s.id } }
                .forEach { put(it.toJson()) }
        }.toString()
        val agentsJson = JSONArray().apply {
            BackgroundAgentStore(context).all().forEach { put(it.toJson()) }
        }.toString()

        val appVersion = runCatching {
            val pm = context.packageManager
            pm.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")

        return PortableProfile(
            appVersion = appVersion,
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            settings = redactedSettings,
            redactedKeys = redactedKeys,
            skills = skills,
            promptPresets = presets,
            slashCommandsJson = slashJson,
            questionCardDefsJson = cardDefsJson,
            backgroundAgentsJson = agentsJson,
        )
    }
}

data class ImportPlan(
    val profile: PortableProfile,
    /** Redacted secret keys the user must explicitly confirm (re-enter or skip). */
    val secretsRequired: List<String>,
    val summary: String,
)

data class ImportResult(
    val settingsApplied: Int,
    val skillsImported: Int,
    val presetsImported: Int,
    val commandsImported: Int,
    val cardsImported: Int,
    val agentsImported: Int,
    val secretsSet: Int,
)

/**
 * Import side. [plan] parses without writing anything; [apply] writes only
 * after the user reviewed the plan and confirmed every secret separately.
 */
class ProfileImporter(private val context: Context) {

    fun plan(text: String): ImportPlan? {
        val profile = PortableProfile.parse(text) ?: return null
        val summary = buildString {
            appendLine("${profile.skills.size} skills")
            appendLine("${profile.promptPresets.size} prompt presets")
            append(JSONArray(profile.slashCommandsJson).length()).append(" slash commands, ")
            append(JSONArray(profile.questionCardDefsJson).length()).append(" question cards, ")
            append(JSONArray(profile.backgroundAgentsJson).length()).append(" background agents")
            appendLine()
            append("Exported ${java.text.SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.US).format(java.util.Date(profile.exportedAt))}")
            if (profile.deviceLabel.isNotBlank()) append(" from ${profile.deviceLabel}")
            if (profile.appVersion.isNotBlank()) append(" (v${profile.appVersion})")
        }
        return ImportPlan(
            profile = profile,
            secretsRequired = profile.redactedKeys,
            summary = summary.toString(),
        )
    }

    /**
     * @param secretValues redacted key → user-supplied value. A missing or
     *   blank value means "skip this secret" — the setting is left untouched.
     */
    fun apply(plan: ImportPlan, secretValues: Map<String, String> = emptyMap()): ImportResult {
        val profile = plan.profile
        var settingsApplied = 0
        var secretsSet = 0

        // ── Settings ──
        runCatching {
            val policy = SchedulePolicyStore(context)
            val qh = policy.quietHours()
            fun timeOf(key: String, fallbackH: Int, fallbackM: Int): Pair<Int, Int> {
                val raw = profile.settings[key] ?: return fallbackH to fallbackM
                val parts = raw.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: return fallbackH to fallbackM
                val m = parts.getOrNull(1)?.toIntOrNull() ?: return fallbackH to fallbackM
                return h.coerceIn(0, 23) to m.coerceIn(0, 59)
            }
            val (sh, sm) = timeOf("quiet_hours.start", qh.startHour, qh.startMinute)
            val (eh, em) = timeOf("quiet_hours.end", qh.endHour, qh.endMinute)
            policy.setQuietHours(
                qh.copy(
                    enabled = profile.settings["quiet_hours.enabled"]?.toBooleanStrictOrNull() ?: qh.enabled,
                    startHour = sh, startMinute = sm, endHour = eh, endMinute = em,
                ),
            )
            settingsApplied++
            profile.settings["battery_saver_pause"]?.toBooleanStrictOrNull()?.let {
                policy.setBatterySaverPause(it)
                settingsApplied++
            }
            // Secrets: only applied when the user explicitly supplied a value.
            for (key in profile.redactedKeys) {
                val value = secretValues[key]
                if (!value.isNullOrBlank() && value != REDACTED_MARKER) {
                    // Reserved for future secret-bearing settings; currently
                    // there are none in the exported set, so this records the
                    // confirmation without writing anywhere sensitive.
                    AppLogger.info(TAG, "secret confirmed for $key (no secret-bearing settings exported yet)")
                    secretsSet++
                }
            }
        }

        // ── Skills ──
        var skillsImported = 0
        runCatching {
            val app = context.applicationContext as? UnibotApp ?: return@runCatching
            if (!app.subsystemsReady()) return@runCatching
            for (skill in profile.skills) {
                if (skill.skillMd.isBlank()) continue
                val imported = app.skillRepository.importFromContent(
                    skill.skillMd,
                    ai.unicto.unibot.data.repository.SkillRepository.ImportSource.FILE,
                )
                if (imported != null) skillsImported++
            }
        }

        // ── Prompt presets ──
        var presetsImported = 0
        runCatching {
            PromptLibraryStore.ensureInit(context)
            val existingNames = PromptLibraryStore.presets.value.map { it.name }.toSet()
            for (preset in profile.promptPresets) {
                if (preset.name.isBlank() || preset.name in existingNames) continue
                val kind = runCatching { PresetKind.valueOf(preset.kind) }.getOrDefault(PresetKind.TEXT)
                PromptLibraryStore.upsert(
                    context,
                    PromptPreset(name = preset.name, description = preset.description, kind = kind, content = preset.content),
                )
                presetsImported++
            }
        }

        // ── Slash commands ──
        var commandsImported = 0
        runCatching {
            val store = SlashCommandStore(context)
            val arr = JSONArray(profile.slashCommandsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val cmd = runCatching { CustomSlashCommand.fromJson(obj) }.getOrNull() ?: continue
                // Fresh id so an import never clobbers an existing row's identity.
                if (store.upsert(cmd.copy(id = java.util.UUID.randomUUID().toString())) != null) {
                    commandsImported++
                }
            }
        }

        // ── Question card definitions ──
        var cardsImported = 0
        runCatching {
            val store = QuestionCardStore(context)
            val arr = JSONArray(profile.questionCardDefsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val def = runCatching { QuestionCardDef.fromJson(obj) }.getOrNull() ?: continue
                if (def.steps.isEmpty()) continue
                store.upsert(QuestionCardEngine.start(def))
                cardsImported++
            }
        }

        // ── Background agents ──
        var agentsImported = 0
        runCatching {
            val store = BackgroundAgentStore(context)
            val arr = JSONArray(profile.backgroundAgentsJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val agent = runCatching { BackgroundAgent.fromJson(obj) }.getOrNull() ?: continue
                if (agent.prompt.isBlank()) continue
                // Import disabled + unconsented: the user re-grants permission
                // explicitly before an imported agent can ever run.
                store.upsert(
                    agent.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        enabled = false,
                        consentGranted = false,
                    ),
                )
                agentsImported++
            }
        }

        return ImportResult(
            settingsApplied = settingsApplied,
            skillsImported = skillsImported,
            presetsImported = presetsImported,
            commandsImported = commandsImported,
            cardsImported = cardsImported,
            agentsImported = agentsImported,
            secretsSet = secretsSet,
        )
    }

    companion object {
        private const val TAG = "ProfileImporter"
    }
}
