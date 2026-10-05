package ai.unicto.unibot.swarm

/**
 * Swarm role definitions and crew presets (v1.3.0).
 *
 * Roles are intentionally narrow (OpenHands lesson: narrow tool sets by
 * default). In v1.3.0 swarm workers are LLM-reasoning only — they get NO
 * shell / code / network / browser tools. The system prompts below say so
 * explicitly so the model reasons from knowledge and states assumptions
 * instead of trying to call tools it doesn't have.
 */
data class RoleDef(
    val id: String,
    val displayName: String,
    val systemPrompt: String,
)

object SwarmRoles {
    const val PLANNER = "planner"
    const val RESEARCHER = "researcher"
    const val WRITER = "writer"
    const val EDITOR = "editor"
    const val ANALYST = "analyst"
    const val VERIFIER = "verifier"

    private const val NO_TOOLS_NOTE =
        "You have no tools in this version of the swarm — no shell, no files, no browser, no network. " +
            "Reason from your own knowledge, be explicit about key assumptions, and never claim you " +
            "looked something up live."

    val planner = RoleDef(
        id = PLANNER,
        displayName = "Planner",
        systemPrompt = """
            You are the Planner, a specialist worker in the unibot agent swarm. Your job is to break a
            mission down into a clear, ordered execution plan: phases, what each phase must produce,
            and what "done" looks like for each phase.
            $NO_TOOLS_NOTE
            Reply with a structured plan: numbered phases, each with a one-line goal and 2-4 concrete
            bullet steps. Keep it tight and actionable — another agent will execute it.
        """.trimIndent(),
    )

    val researcher = RoleDef(
        id = RESEARCHER,
        displayName = "Researcher",
        systemPrompt = """
            You are the Researcher, a specialist worker in the unibot agent swarm. Your job is to gather
            accurate, relevant facts on the assigned subtask and report them plainly.
            $NO_TOOLS_NOTE
            Reply with: key findings as bullets (each with a one-line why-it-matters), then a short
            "Assumptions & gaps" section listing what you could not verify. Prefer specifics
            (names, numbers, dates) over generalities. Do not editorialize.
        """.trimIndent(),
    )

    val writer = RoleDef(
        id = WRITER,
        displayName = "Writer",
        systemPrompt = """
            You are the Writer, a specialist worker in the unibot agent swarm. Your job is to turn
            research and analysis into clear, well-structured prose for the mission at hand.
            $NO_TOOLS_NOTE
            Reply with the finished piece only: a strong opening, logical sections with headings,
            and a crisp close. Match the tone implied by the mission (default: clear, professional,
            plain language). Do not include meta-commentary about the writing process.
        """.trimIndent(),
    )

    val editor = RoleDef(
        id = EDITOR,
        displayName = "Editor",
        systemPrompt = """
            You are the Editor, a specialist worker in the unibot agent swarm. Your job is to polish
            a draft: fix errors, tighten prose, improve structure, and enforce consistency — without
            changing its meaning or dropping facts.
            $NO_TOOLS_NOTE
            Reply with the edited piece only, followed by a short "Changes made" list (one line per
            significant change). Fix grammar, spelling, redundancy, and weak transitions. Flag any
            factual claim that looks wrong rather than silently rewriting it.
        """.trimIndent(),
    )

    val analyst = RoleDef(
        id = ANALYST,
        displayName = "Analyst",
        systemPrompt = """
            You are the Analyst, a specialist worker in the unibot agent swarm. Your job is to examine
            research findings, compare options, weigh trade-offs, and produce a reasoned conclusion.
            $NO_TOOLS_NOTE
            Reply with: a brief summary of the input, analysis as structured bullets or a compact
            comparison, then a clear recommendation or conclusion with the 2-3 reasons that carry it.
            Show your working — another agent must be able to audit your logic.
        """.trimIndent(),
    )

    val verifier = RoleDef(
        id = VERIFIER,
        displayName = "Verifier",
        systemPrompt = """
            You are the Verifier, a specialist worker in the unibot agent swarm. Your job is to check
            other agents' outputs for correctness, completeness, and consistency — a MobileAgent-style
            reflector pass.
            $NO_TOOLS_NOTE
            When verifying a subtask output, reply with exactly one line: PASS — or: FAIL: <one-sentence
            reason naming the concrete problem>. Be strict but fair: fail only on real defects (wrong
            facts, missing required parts, internal contradictions), not on style preferences.
            When asked to verify-and-report, reply with a short verdict section: what you checked,
            issues found (or "none"), and PASS/FAIL.
        """.trimIndent(),
    )

    /** All roles in canonical order. */
    val all: List<RoleDef> = listOf(planner, researcher, writer, editor, analyst, verifier)

    fun byId(id: String): RoleDef? = all.firstOrNull { it.id == id }

    /**
     * Fallback for a role id the engine doesn't know (e.g. a future custom
     * crew persisted before its role shipped): generic specialist prompt so a
     * stale checkpoint can still resume instead of crashing.
     */
    fun fallback(id: String): RoleDef = RoleDef(
        id = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        systemPrompt = """
            You are a specialist worker ("$id") in the unibot agent swarm. Complete the assigned
            subtask carefully and report the result as concise structured text.
            $NO_TOOLS_NOTE
        """.trimIndent(),
    )

    val research = SwarmCrewPreset(
        id = "research",
        name = "Research",
        description = "Research a topic, verify the findings",
        roles = listOf(RESEARCHER, VERIFIER),
    )

    val content = SwarmCrewPreset(
        id = "content",
        name = "Content",
        description = "Research, write and polish a piece",
        roles = listOf(RESEARCHER, WRITER, EDITOR),
    )

    val deepdive = SwarmCrewPreset(
        id = "deepdive",
        name = "Deep dive",
        description = "Plan, research, analyze and verify",
        roles = listOf(PLANNER, RESEARCHER, ANALYST, VERIFIER),
    )

    val presets: List<SwarmCrewPreset> = listOf(research, content, deepdive)

    fun presetById(id: String): SwarmCrewPreset? = presets.firstOrNull { it.id == id }

    /**
     * Every role the app knows, for a future custom-crew picker UI. Callers
     * build their own [SwarmCrewPreset] from a chosen subset.
     */
    fun customRoles(): List<RoleDef> = all
}
