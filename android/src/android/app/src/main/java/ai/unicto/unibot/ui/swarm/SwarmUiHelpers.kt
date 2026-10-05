package ai.unicto.unibot.ui.swarm

import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmCrewPreset
import ai.unicto.unibot.swarm.SwarmLifecycle

/**
 * [v1.3.0-swarm] Pure, UI-side helpers for the swarm space. No Compose
 * dependencies — every function here is a plain JVM function so it stays
 * unit-testable without Robolectric (see SwarmUiHelpersTest).
 *
 * The engine owns `ai.unicto.unibot.swarm`; this file only *reads* its
 * contract types (statuses, presets) and derives presentation from them.
 */

/** Marker the engine prefixes onto an agent's `result`/`detail` when a
 * failed-then-best-effort worker's output stands with a note instead of a
 * clean result. The UI renders this as the explicit "incomplete" terminal
 * state (Kimi's #1 complaint was silent partial failure) — never silently
 * "done". Integration must confirm the engine emits this exact prefix. */
const val INCOMPLETE_MARKER = "[incomplete]"

/**
 * If [result] or [detail] carries the [INCOMPLETE_MARKER] prefix, returns the
 * note text with the marker stripped; otherwise null. [result] wins when
 * both carry it.
 */
fun extractIncompleteNote(result: String, detail: String): String? {
    val fromResult = result.stripIncompleteMarker()
    if (fromResult != null) return fromResult
    return detail.stripIncompleteMarker()
}

private fun String.stripIncompleteMarker(): String? {
    val trimmed = trimStart()
    if (!trimmed.startsWith(INCOMPLETE_MARKER)) return null
    val note = trimmed.removePrefix(INCOMPLETE_MARKER).trim()
    return note.ifBlank { "(no note provided)" }
}

/**
 * Display-safe version of a `result`/`detail` string: strips a leading
 * [INCOMPLETE_MARKER] (the badge carries that meaning instead).
 */
fun withoutIncompleteMarker(text: String): String {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith(INCOMPLETE_MARKER)) return text
    return trimmed.removePrefix(INCOMPLETE_MARKER).trimStart()
}

/** True when the agent has reached an explicit terminal state. */
fun SwarmAgentStatus.isTerminal(): Boolean =
    this == SwarmAgentStatus.DONE || this == SwarmAgentStatus.FAILED

fun SwarmAgentStatus.label(): String = when (this) {
    SwarmAgentStatus.QUEUED -> "Queued"
    SwarmAgentStatus.WORKING -> "Working"
    SwarmAgentStatus.VERIFYING -> "Verifying"
    SwarmAgentStatus.DONE -> "Done"
    SwarmAgentStatus.FAILED -> "Failed"
}

fun SwarmLifecycle.label(): String = when (this) {
    SwarmLifecycle.IDLE -> "Idle"
    SwarmLifecycle.PLANNING -> "Planning"
    SwarmLifecycle.RUNNING -> "Running"
    SwarmLifecycle.PAUSED -> "Paused"
    SwarmLifecycle.DONE -> "Done"
    SwarmLifecycle.CANCELLED -> "Cancelled"
    SwarmLifecycle.FAILED -> "Failed"
}

/** "planner" -> "Planner", "deep_researcher" -> "Deep Researcher". */
fun roleDisplayName(role: String): String =
    role.split('_', ' ', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        .ifBlank { "Agent" }

/**
 * What each role is expected to hand back — shown on the agent ID card as
 * the "defined output" (Kimi demo pattern: name + role + task + output).
 * Pure presentation; the engine never sees this.
 */
fun expectedOutput(role: String): String = when (role.lowercase()) {
    "planner" -> "Task plan"
    "researcher" -> "Findings with sources"
    "writer" -> "Draft text"
    "verifier" -> "Verification report"
    "critic" -> "Critique notes"
    "editor" -> "Polished draft"
    else -> "Agent output"
}

/** 850 -> "850", 1_250 -> "1.2k", 2_400_000 -> "2.4M". Locale-fixed: a cost
 * bill must not change its decimal separator with the device locale. */
fun formatTokens(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    if (tokens < 1_000_000) {
        val k = tokens / 1000.0
        val text = if (k < 10) String.format(java.util.Locale.US, "%.1f", k) else k.toInt().toString()
        return "${text}k"
    }
    val m = tokens / 1_000_000.0
    val text = if (m < 10) String.format(java.util.Locale.US, "%.1f", m) else m.toInt().toString()
    return "${text}M"
}

// ─── Agent codenames ─────────────────────────────────────────────────────────
// Kimi-demo pattern: named agents ("Oven" the design agent, "Vince" the
// review agent) presented as ID cards feel managed, not chaotic. The pool is
// UI-side and deterministic: the same agent id always maps to the same name
// within a run (stable across recompositions and config changes), with no
// engine changes needed.

private val CODENAME_POOL = listOf(
    "Oven", "Vince", "Mabel", "Juno", "Pip", "Sable",
    "Tansy", "Rook", "Wren", "Bram", "Liv", "Sol",
    "Ash", "Cedar", "Dune", "Ember",
)

/** Deterministic codename for an agent id. Never blank. */
fun agentCodename(agentId: String): String {
    val pool = CODENAME_POOL
    if (agentId.isBlank()) return pool[0]
    val index = (agentId.hashCode() and Int.MAX_VALUE) % pool.size
    return pool[index]
}

// ─── Crew presets ────────────────────────────────────────────────────────────
// Preset ids are contract: "research" / "content" / "deepdive". "custom" is
// UI-side (all six roles; a real crew picker is future work).

private val ALL_SIX_ROLES = listOf("planner", "researcher", "writer", "verifier", "critic", "editor")

val SWARM_PRESETS: List<SwarmCrewPreset> = listOf(
    SwarmCrewPreset(
        id = "research",
        name = "Research",
        description = "Deep multi-source research, cross-checked before delivery.",
        roles = listOf("planner", "researcher", "researcher", "verifier", "writer"),
    ),
    SwarmCrewPreset(
        id = "content",
        name = "Content",
        description = "Drafts that read clean — written, edited, then verified.",
        roles = listOf("planner", "writer", "editor", "verifier"),
    ),
    SwarmCrewPreset(
        id = "deepdive",
        name = "Deep dive",
        description = "The full crew: research, critique, verify, then write.",
        roles = ALL_SIX_ROLES,
    ),
    SwarmCrewPreset(
        id = "custom",
        name = "Custom",
        description = "All six roles working the mission. A crew picker is coming.",
        roles = ALL_SIX_ROLES,
    ),
)

fun swarmPresetById(id: String): SwarmCrewPreset? = SWARM_PRESETS.firstOrNull { it.id == id }
