package ai.unicto.unibot.skills

/**
 * The unibot Markdown skill schema — write a skill in plain Markdown, no code.
 *
 * A skill is one SKILL.md document: a small frontmatter block between `---`
 * lines, then Markdown instructions for the agent.
 *
 * Frontmatter fields:
 *   name:        required. Lowercase letters, digits, `-` and `_`, 2-64 chars.
 *                Becomes the agent tool name `skill_<name>`.
 *   description: required. One or two sentences, max 280 chars. This is what
 *                the model reads when deciding whether to use the skill.
 *   version:     optional semver-ish string, defaults to `1.0.0`.
 *   tools:       optional. Declares which of the narrow built-in tools the
 *                skill may use while running — see [SkillToolAdapter].
 *                Comma-separated (`tools: web_search, file_read`) or a YAML
 *                list. Names outside the allowlist are rejected at validation
 *                time. Omit to run with no extra tools (prompt-only skill).
 *   author:      optional free text, shown in the review screen.
 *
 * The body is the skill's instructions — plain Markdown. There is no code
 * execution surface: a skill can only steer the agent and, when it declares
 * `tools:`, call the allowlisted built-ins. Anything else in frontmatter is
 * ignored (surfaced as a warning) so community files with extra keys still
 * import.
 *
 * Validation is split from parsing on purpose: [parse] is lenient (best
 * effort, like the repository's importer), [validate] is strict and returns
 * every problem found so the editor / gallery review screen can show them
 * before install.
 */
object SkillSchema {

    const val MAX_DESCRIPTION_LENGTH = 280
    const val MAX_BODY_LENGTH = 200_000
    const val MAX_TOOLS_DECLARED = 12

    private val NAME_RE = Regex("^[a-z0-9][a-z0-9\\-_]{0,62}[a-z0-9]$|^[a-z0-9]{2}$")

    data class ParsedMarkdownSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val author: String = "",
        val tools: List<String> = emptyList(),
        val body: String = "",
        /** Frontmatter keys we do not understand — reported as warnings. */
        val unknownKeys: List<String> = emptyList(),
    )

    data class SkillValidation(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    /**
     * Lenient parse: returns null only when the document has no frontmatter
     * or no usable name (same contract as the repository parser).
     */
    fun parse(content: String): ParsedMarkdownSkill? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null
        val lines = trimmed.lines()
        var end = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { end = i; break }
        }
        if (end < 0) return null

        var name = ""
        var description = ""
        var version = "1.0.0"
        var author = ""
        val tools = mutableListOf<String>()
        val unknownKeys = mutableListOf<String>()

        var i = 1
        while (i < end) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon < 0) { i++; continue }
            val key = line.substring(0, colon).trim().lowercase()
            var raw = line.substring(colon + 1).trim()

            // Block scalar (`|` / `>`, with optional chomping indicators).
            val isBlock = raw.startsWith("|") || raw.startsWith(">")
            if (isBlock) {
                val fold = raw.startsWith(">")
                val block = mutableListOf<String>()
                var j = i + 1
                while (j < end) {
                    val next = lines[j]
                    if (next.isEmpty() || next[0].isWhitespace()) block.add(next.trim())
                    else break
                    j++
                }
                raw = if (fold) block.joinToString(" ").trim() else block.joinToString("\n").trim('\n')
                i = j
            } else {
                i++
            }
            // Strip matching quotes.
            val value = raw.removeSurrounding("\"").removeSurrounding("'")

            when (key) {
                "name" -> name = value
                "description" -> description = value
                "version" -> if (value.isNotBlank()) version = value
                "author" -> author = value
                "tools" -> {
                    // Inline comma list, or a YAML list on following lines.
                    if (value.isNotBlank()) {
                        tools += value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    } else {
                        var j = i
                        while (j < end) {
                            val next = lines[j].trim()
                            if (next.startsWith("-")) {
                                tools += next.removePrefix("-").trim().lowercase()
                                j++
                            } else break
                        }
                        i = j
                    }
                }
                else -> if (key.isNotBlank() && key !in unknownKeys) unknownKeys.add(key)
            }
        }

        if (name.isBlank()) return null
        val body = if (end + 1 < lines.size) {
            lines.subList(end + 1, lines.size).joinToString("\n").trim('\n')
        } else ""

        return ParsedMarkdownSkill(
            name = name.trim(),
            description = description.trim(),
            version = version.trim(),
            author = author.trim(),
            tools = tools.distinct(),
            body = body,
            unknownKeys = unknownKeys,
        )
    }

    /**
     * Strict validation. Every problem is reported (no fail-fast) so the UI
     * can show the full list. Unknown `tools:` entries are ERRORS — a skill
     * must never silently gain a capability the author didn't intend, nor
     * claim one that doesn't exist.
     */
    fun validate(skill: ParsedMarkdownSkill): SkillValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!NAME_RE.matches(skill.name)) {
            errors.add(
                "name \"${skill.name}\" is invalid — use 2-64 chars: lowercase " +
                    "letters, digits, '-' and '_' only.",
            )
        }
        if (skill.description.isBlank()) {
            errors.add("description is required — one or two sentences saying what the skill does.")
        } else if (skill.description.length > MAX_DESCRIPTION_LENGTH) {
            errors.add("description is ${skill.description.length} chars — keep it under $MAX_DESCRIPTION_LENGTH.")
        }
        if (!skill.version.matches(Regex("^[0-9A-Za-z][0-9A-Za-z.\\-+]*$"))) {
            errors.add("version \"${skill.version}\" is not a valid version string.")
        }
        if (skill.body.isBlank()) {
            warnings.add("the instructions body is empty — the skill will carry no guidance.")
        }
        if (skill.body.length > MAX_BODY_LENGTH) {
            errors.add("instructions body is too large (${skill.body.length} chars, max $MAX_BODY_LENGTH).")
        }
        if (skill.tools.size > MAX_TOOLS_DECLARED) {
            errors.add("too many tools declared (${skill.tools.size}, max $MAX_TOOLS_DECLARED).")
        }
        val unknownTools = skill.tools.filter { it !in SkillToolAdapter.SKILL_TOOL_ALLOWLIST }
        if (unknownTools.isNotEmpty()) {
            errors.add(
                "unknown tools declared: ${unknownTools.joinToString(", ")} — " +
                    "allowed: ${SkillToolAdapter.SKILL_TOOL_ALLOWLIST.sorted().joinToString(", ")}.",
            )
        }
        for (key in skill.unknownKeys) {
            warnings.add("unrecognized frontmatter key \"$key\" — it will be ignored.")
        }
        return SkillValidation(errors, warnings)
    }

    /** Convenience: parse + validate in one call. Null when unparseable. */
    fun parseAndValidate(content: String): Pair<ParsedMarkdownSkill, SkillValidation>? {
        val parsed = parse(content) ?: return null
        return parsed to validate(parsed)
    }
}
