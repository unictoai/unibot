package ai.unicto.unibot.swarm

/**
 * One-tap mission templates (v1.4.0 item 9).
 *
 * Pure data — the UI renders the gallery and prefills the composer with
 * [mission] when the user taps a template. Every template references a
 * built-in crew preset id from [SwarmRoles.presets] so the engine can run
 * it unchanged; the user still reviews (and can edit) the mission text
 * before launching.
 */
data class SwarmTemplate(
    val id: String,
    val name: String,
    val description: String,
    val mission: String,
    val presetId: String,
)

val SWARM_TEMPLATES: List<SwarmTemplate> = listOf(
    SwarmTemplate(
        id = "market-research",
        name = "Market research",
        description = "Size up a market: players, pricing, gaps",
        mission = "Research the market for [product or industry]: who the " +
            "main players are, how they price, what customers complain " +
            "about, and where the gaps are. Replace the bracketed text " +
            "with your topic before launching.",
        presetId = "research",
    ),
    SwarmTemplate(
        id = "trip-plan",
        name = "Trip plan",
        description = "Plan a trip: where, when, what it costs",
        mission = "Plan a trip to [destination] for [dates/duration]: best " +
            "areas to stay, must-see places with rough time needed, local " +
            "transport options, and a realistic daily budget breakdown. " +
            "Replace the bracketed text with your trip details before launching.",
        presetId = "deepdive",
    ),
    SwarmTemplate(
        id = "code-review",
        name = "Code review",
        description = "Review pasted code for bugs and design issues",
        mission = "Review the code I will paste below for bugs, design " +
            "issues, and readability problems. For each finding: what is " +
            "wrong, why it matters, and the smallest fix. Paste the code " +
            "at the end of this mission before launching.",
        presetId = "research",
    ),
    SwarmTemplate(
        id = "literature-scan",
        name = "Literature scan",
        description = "Survey what is known about a topic",
        mission = "Survey the key ideas and findings on [topic]: the " +
            "foundational concepts, where the consensus lies, where experts " +
            "disagree, and the most important open questions. Replace the " +
            "bracketed text with your topic before launching.",
        presetId = "research",
    ),
)

fun swarmTemplateById(id: String): SwarmTemplate? = SWARM_TEMPLATES.firstOrNull { it.id == id }
