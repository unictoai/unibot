package ai.unicto.unibot.profile

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Item 96 — portable profile: settings, skills and presets as one file.
 *
 * The file is a single JSON document ([PortableProfile.toJson]). Secrets are
 * NEVER exported in cleartext: [SecretRedactor] replaces secret-looking
 * values with [REDACTED_MARKER] and records their keys in [redactedKeys], so
 * the importer can ask the user to re-enter (or skip) each one explicitly.
 *
 * Scope of one profile:
 *  - settings: quiet hours, battery-saver pause, and other namespaced app
 *    preferences (secrets redacted).
 *  - skills: every installed skill as its full SKILL.md text.
 *  - presets: prompt-library presets, custom slash commands, question-card
 *    definitions, background agent definitions.
 */
const val REDACTED_MARKER = "__REDACTED__"
const val PROFILE_VERSION = 1

object SecretRedactor {
    private val SECRET_HINTS = listOf(
        "key", "token", "secret", "password", "passwd", "auth",
        "credential", "bearer", "private_key", "apikey", "api_key",
    )

    /** True when a setting key looks like it holds a secret. */
    fun isSecretKey(key: String): Boolean {
        val lower = key.lowercase()
        return SECRET_HINTS.any { it in lower }
    }

    /**
     * Redact [settings]: secret-looking values become [REDACTED_MARKER].
     * Returns the redacted map plus the list of redacted keys (for the
     * importer's explicit-confirmation step).
     */
    fun redact(settings: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val redactedKeys = mutableListOf<String>()
        val out = settings.mapValues { (k, v) ->
            if (isSecretKey(k) && v.isNotBlank() && v != REDACTED_MARKER) {
                redactedKeys.add(k)
                REDACTED_MARKER
            } else v
        }
        return out to redactedKeys
    }
}

data class ProfileSkill(
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    /** Full SKILL.md text (frontmatter + instructions). */
    val skillMd: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("version", version)
        put("skillMd", skillMd)
    }

    companion object {
        fun fromJson(o: JSONObject): ProfileSkill = ProfileSkill(
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            version = o.optString("version", "1.0.0"),
            skillMd = o.optString("skillMd", ""),
        )
    }
}

data class ProfilePromptPreset(
    val name: String,
    val description: String = "",
    val kind: String = "TEXT",
    val content: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("kind", kind)
        put("content", content)
    }

    companion object {
        fun fromJson(o: JSONObject): ProfilePromptPreset = ProfilePromptPreset(
            name = o.optString("name", ""),
            description = o.optString("description", ""),
            kind = o.optString("kind", "TEXT"),
            content = o.optString("content", ""),
        )
    }
}

data class PortableProfile(
    val version: Int = PROFILE_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val deviceLabel: String = "",
    /** Namespaced settings, e.g. "quiet_hours.enabled" → "true". Secrets redacted. */
    val settings: Map<String, String> = emptyMap(),
    /** Keys whose values were redacted on export — need explicit confirmation on import. */
    val redactedKeys: List<String> = emptyList(),
    val skills: List<ProfileSkill> = emptyList(),
    val promptPresets: List<ProfilePromptPreset> = emptyList(),
    /** Raw JSON of custom slash commands (slashcommands package schema). */
    val slashCommandsJson: String = "[]",
    /** Raw JSON of question-card definitions. */
    val questionCardDefsJson: String = "[]",
    /** Raw JSON of background agent definitions. */
    val backgroundAgentsJson: String = "[]",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "unibot-portable-profile")
        put("version", version)
        put("exportedAt", exportedAt)
        put("appVersion", appVersion)
        put("deviceLabel", deviceLabel)
        put("settings", JSONObject(settings))
        put("redactedKeys", JSONArray(redactedKeys))
        put("skills", JSONArray().apply { skills.forEach { put(it.toJson()) } })
        put("promptPresets", JSONArray().apply { promptPresets.forEach { put(it.toJson()) } })
        put("slashCommands", JSONArray(slashCommandsJson))
        put("questionCardDefs", JSONArray(questionCardDefsJson))
        put("backgroundAgents", JSONArray(backgroundAgentsJson))
    }

    companion object {
        fun fromJson(o: JSONObject): PortableProfile? {
            if (o.optString("format") != "unibot-portable-profile") return null
            val settings = mutableMapOf<String, String>()
            o.optJSONObject("settings")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    settings[k] = obj.optString(k, "")
                }
            }
            fun <T> readArray(key: String, fn: (JSONObject) -> T): List<T> = buildList {
                val arr = o.optJSONArray(key) ?: return@buildList
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(fn(it)) }
                }
            }
            return PortableProfile(
                version = o.optInt("version", 1),
                exportedAt = o.optLong("exportedAt", System.currentTimeMillis()),
                appVersion = o.optString("appVersion", ""),
                deviceLabel = o.optString("deviceLabel", ""),
                settings = settings,
                redactedKeys = o.optJSONArray("redactedKeys")?.let { arr ->
                    buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                } ?: emptyList(),
                skills = readArray("skills", ProfileSkill::fromJson),
                promptPresets = readArray("promptPresets", ProfilePromptPreset::fromJson),
                slashCommandsJson = o.optJSONArray("slashCommands")?.toString() ?: "[]",
                questionCardDefsJson = o.optJSONArray("questionCardDefs")?.toString() ?: "[]",
                backgroundAgentsJson = o.optJSONArray("backgroundAgents")?.toString() ?: "[]",
            )
        }

        fun parse(text: String): PortableProfile? = runCatching {
            fromJson(JSONObject(text))
        }.getOrNull()
    }
}

/** Stable export id for the file name. */
fun profileFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        .format(java.util.Date())
    return "unibot-profile-$stamp.json"
}
