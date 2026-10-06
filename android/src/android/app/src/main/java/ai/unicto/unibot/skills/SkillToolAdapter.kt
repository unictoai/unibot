package ai.unicto.unibot.skills

import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.AgentToolParam
import ai.unicto.unibot.data.repository.SkillRepository

/**
 * Surfaces Markdown skills as real agent tools — the typed, narrow surface
 * for item 89. Safety contract:
 *
 *  - A skill becomes exactly ONE tool, `skill_<sanitized-name>`, with a
 *    single free-text `input` parameter. There is no code execution: calling
 *    the tool hands the skill's Markdown instructions (plus the input) to the
 *    agent loop, which follows them like any other system guidance.
 *  - The optional `tools:` frontmatter declares which built-in tools the
 *    skill's run may additionally call. Only names in [SKILL_TOOL_ALLOWLIST]
 *    are honored — [allowedTools] drops anything else, and [SkillSchema]
 *    validation rejects unknown names outright so a community skill can
 *    never silently claim a capability it doesn't have.
 *  - Execution-time gating lives in [isToolCallAllowed]: even if a skill's
 *    instructions ask the model to call something else, the executor must
 *    check every sub-tool call against the skill's declared set.
 */
object SkillToolAdapter {

    /**
     * The only built-ins a Markdown skill may declare. Deliberately narrow:
     * read-only lookups plus reminder creation. No shell, no file writes, no
     * browser automation, no connector sends — those stay behind the app's
     * own approval gates and can never be reached through a skill.
     */
    val SKILL_TOOL_ALLOWLIST: Set<String> = setOf(
        "web_search",
        "file_read",
        "datetime",
        "calculator",
        "reminder_create",
    )

    /** `My Skill!` → `my_skill` — safe for tool-name slots in every provider. */
    fun sanitizeToolName(name: String): String {
        val clean = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        val collapsed = clean.replace(Regex("_+"), "_")
        return if (collapsed.isEmpty()) "unnamed" else collapsed.take(56)
    }

    fun toolNameFor(skillName: String): String = "skill_" + sanitizeToolName(skillName)

    /**
     * The allowlisted subset of [declared] — anything outside the allowlist
     * is dropped. Callers that already ran [SkillSchema.validate] know the
     * declared list is clean; this is the runtime belt-and-braces.
     */
    fun allowedTools(declared: List<String>): Set<String> =
        declared.map { it.lowercase() }.filter { it in SKILL_TOOL_ALLOWLIST }.toSet()

    /**
     * Execution gate: may a run of this skill call [requestedTool]?
     * The skill's own tool is always allowed (it IS the skill); everything
     * else must be in the skill's declared + allowlisted set.
     */
    fun isToolCallAllowed(
        skillName: String,
        declaredTools: List<String>,
        requestedTool: String,
    ): Boolean {
        if (requestedTool == toolNameFor(skillName)) return true
        return requestedTool.lowercase() in allowedTools(declaredTools)
    }

    /**
     * Build the single agent-tool definition for an enabled skill. Null when
     * the skill fails schema validation — invalid skills never reach the
     * model as tools (they still appear in the Skills list with their
     * validation errors shown).
     */
    fun toToolDefinition(skill: SkillRepository.Skill): AgentToolDefinition? {
        // Skill.body holds only the Markdown body; frontmatter lives as
        // fields. Rebuild the full SKILL.md so schema parse + validation see
        // exactly what the on-disk file contains.
        val doc = buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            appendLine("description: ${skill.description}")
            appendLine("version: ${skill.version}")
            if (skill.tools.isNotEmpty()) appendLine("tools: ${skill.tools.joinToString(", ")}")
            appendLine("---")
            append(skill.body)
        }
        val parsed = SkillSchema.parse(doc) ?: return null
        if (!SkillSchema.validate(parsed).valid) return null
        return toToolDefinition(parsed)
    }

    fun toToolDefinition(skill: SkillSchema.ParsedMarkdownSkill): AgentToolDefinition {
        val allowed = allowedTools(skill.tools)
        val desc = buildString {
            append(skill.description)
            append(" Use this skill when the user's request matches: ${skill.name}.")
            if (allowed.isNotEmpty()) {
                append(" While carrying out this skill you may also use: ${allowed.sorted().joinToString(", ")}.")
            } else {
                append(" This skill grants no additional tools — follow its instructions with your built-in capabilities only.")
            }
        }
        return AgentToolDefinition(
            name = toolNameFor(skill.name),
            description = desc.take(900),
            parameters = mapOf(
                "input" to AgentToolParam(
                    type = "string",
                    description = "The user's request, verbatim, for the skill to handle.",
                ),
            ),
            required = listOf("input"),
        )
    }

    /**
     * Tool definitions for every enabled skill that passes validation.
     * Intended to be appended to the agent loop's tool list alongside the
     * prompt-fragment injection (which stays as the discovery path).
     */
    fun definitionsFor(skills: List<SkillRepository.Skill>): List<AgentToolDefinition> =
        skills.filter { it.isEnabled }.mapNotNull { toToolDefinition(it) }
}
