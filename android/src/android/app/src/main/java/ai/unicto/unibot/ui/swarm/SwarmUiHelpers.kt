package ai.unicto.unibot.ui.swarm

import ai.unicto.unibot.swarm.BEST_EFFORT_NOTE_MARKER
import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmCrewPreset
import ai.unicto.unibot.swarm.SwarmLifecycle

/**
 * [v1.3.0-swarm] Pure, UI-side helpers for the swarm space. No Compose
 * dependencies — every function here is a plain JVM function so it stays
 * unit-testable without Robolectric (see SwarmUiHelpersTest).
 *
 * The engine owns `ai.unicto.unibot.swarm`; this file only *reads* its
 * contract types (lifecycle/status enums, `SwarmRoles` presets/role names)
 * and derives presentation from them — never redefining what the engine
 * owns, so the cards can never misrepresent what launch() actually spawns.
 */

/** Marker the engine prefixes onto an agent's `result`/`detail` when a
 * failed-then-best-effort worker's output stands with a note instead of a
 * clean result. The UI renders this as the explicit "incomplete" terminal
 * state (Kimi's #1 complaint was silent partial failure) — never silently
 * "done". */
const val INCOMPLETE_MARKER = "[incomplete]"

/**
 * Substring of the v1.3.0 engine's best-effort note — the single source of
 * truth lives in the swarm package as [BEST_EFFORT_NOTE_MARKER]; the engine
 * appends `_Note: <marker> (...); one revision was applied and this
 * best-effort output stands._` to `result` when its one bounded revision
 * still didn't pass verification.
 */

/**
 * If [result] or [detail] carries an incomplete marker, returns the note
 * text with the marker stripped; otherwise null. Recognizes both the
 * `[incomplete]` prefix convention and the engine's appended best-effort
 * note. [result] wins when both fields carry a marker.
 */
fun extractIncompleteNote(result: String, detail: String): String? {
    result.stripIncompleteMarker()?.let { return it }
    detail.stripIncompleteMarker()?.let { return it }
    return result.extractEngineBestEffortNote()
}

private fun String.stripIncompleteMarker(): String? {
    val trimmed = trimStart()
    if (!trimmed.startsWith(INCOMPLETE_MARKER)) return null
    return trimmed.removePrefix(INCOMPLETE_MARKER).trim().ifBlank { "(no note provided)" }
}

private fun String.extractEngineBestEffortNote(): String? {
    val markerAt = indexOf(BEST_EFFORT_NOTE_MARKER)
    if (markerAt < 0) return null
    val noteStart = lastIndexOf("_Note:", markerAt).takeIf { it >= 0 } ?: markerAt
    return substring(noteStart).trim().trim('_').trim().ifBlank { "(no note provided)" }
}

/**
 * Display-safe version of a `result`/`detail` string: strips a leading
 * [INCOMPLETE_MARKER] and/or the engine's appended best-effort note (the
 * badge carries that meaning instead).
 */
fun withoutIncompleteMarker(text: String): String {
    val trimmed = text.trimStart()
    val withoutPrefix =
        if (trimmed.startsWith(INCOMPLETE_MARKER)) trimmed.removePrefix(INCOMPLETE_MARKER).trimStart()
        else text
    val markerAt = withoutPrefix.indexOf(BEST_EFFORT_NOTE_MARKER)
    if (markerAt < 0) return withoutPrefix
    val noteStart = withoutPrefix.lastIndexOf("_Note:", markerAt).takeIf { it >= 0 } ?: markerAt
    return withoutPrefix.substring(0, noteStart).trimEnd()
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

/**
 * Canonical display name for a role id. Prefers the engine's
 * [ai.unicto.unibot.swarm.SwarmRoles] display name so the UI can never
 * drift from what the engine actually runs; falls back to humanizing
 * unknown ids ("deep_researcher" -> "Deep Researcher", blank -> "Agent").
 */
fun roleDisplayName(role: String): String =
    ai.unicto.unibot.swarm.SwarmRoles.byId(role)?.displayName
        ?: role.split('_', ' ', '-')
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
    "editor" -> "Polished draft"
    "analyst" -> "Analysis & recommendation"
    "verifier" -> "Verification report"
    else -> "Agent output"
}

/** 850 -> "850", 1_250 -> "1.2k", 2_400_000 -> "2.4M". Locale-fixed: the
 * decimal separator is a literal '.', so a cost bill never changes with
 * the device locale. Truncates (never rounds up): 1_250 is "1.2k".
 * Integer arithmetic throughout — no float-representation surprises. */
fun formatTokens(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    if (tokens < 1_000_000) {
        val k = tokens / 1000
        if (k >= 10) return "${k}k"
        return "$k.${(tokens % 1000) / 100}k"
    }
    val m = tokens / 1_000_000
    if (m >= 10) return "${m}M"
    return "$m.${(tokens % 1_000_000) / 100_000}M"
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
// The three crew presets are ENGINE-OWNED (ai.unicto.unibot.swarm.SwarmRoles)
// — the UI must never redefine their ids, names, descriptions or roles, or
// the cards will misrepresent what launch() actually spawns. "custom" is
// UI-side (all six engine roles; a real crew picker is future work).

val SWARM_PRESETS: List<SwarmCrewPreset> = ai.unicto.unibot.swarm.SwarmRoles.presets +
    SwarmCrewPreset(
        id = "custom",
        name = "Custom",
        description = "All six roles working the mission. A crew picker is coming.",
        roles = ai.unicto.unibot.swarm.SwarmRoles.customRoles().map { it.id },
    )

fun swarmPresetById(id: String): SwarmCrewPreset? = SWARM_PRESETS.firstOrNull { it.id == id }
