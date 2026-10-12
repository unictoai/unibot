package ai.unicto.unibot.swarm

/**
 * One-tap swarm PIPELINES (v1.5, stolen from Ruflo's pre-built collaboration
 * templates: ruvnet/ruflo documents `feature` (architect → coder → tester →
 * reviewer), `security` (scanner → analyzer → fixer) and `refactor`
 * (analyzer → planner → refactorer → validator) as fixed ordered pipelines
 * where each stage's output feeds the next).
 *
 * How they differ from crew presets ([SwarmRoles.presets]) and mission
 * templates ([SWARM_TEMPLATES]):
 * - A crew preset is an unordered-capability bag: the manager decomposes the
 *   mission into one subtask per role at run time.
 * - A pipeline is a DETERMINISTIC ordered chain: stage N's brief is fixed
 *   and stage N+1 receives stage N's output as its primary input (an
 *   explicit handoff, not manager-improvised decomposition). The manager
 *   plan call is skipped entirely — the pipeline IS the plan, which is why
 *   the user picks it deliberately in the gallery.
 *
 * Roles are the existing reasoning-only role ids ([SwarmRoles]); no new
 * roles are introduced, so per-role permissions, checkpointing, verify and
 * stitch all work unchanged. A pipeline maps to a synthetic crew preset
 * whose id carries the [PIPELINE_CREW_ID_PREFIX] — the checkpoint already
 * persists crew id + roles, so a pipeline run resumes exactly like a crew
 * run, and the engine detects pipeline mode from the id prefix.
 */
data class PipelineStage(
    /** Existing role id from [SwarmRoles] (planner/researcher/writer/…). */
    val roleId: String,
    /** Short stage label shown on the agent card, e.g. "Gather". */
    val title: String,
    /**
     * What this stage must do. Written as a handoff-aware brief: it may
     * assume the previous stage's output is attached as its primary input
     * (the engine passes it verbatim) and must build on it, not redo it.
     */
    val brief: String,
)

data class SwarmPipeline(
    val id: String,
    val name: String,
    val description: String,
    /** Composer prefill when the user taps the pipeline in the gallery. */
    val missionHint: String,
    val stages: List<PipelineStage>,
) {
    /** The synthetic crew preset the engine actually runs. */
    fun toCrewPreset(): SwarmCrewPreset = SwarmCrewPreset(
        id = PIPELINE_CREW_ID_PREFIX + id,
        name = name,
        description = description,
        roles = stages.map { it.roleId },
    )
}

/** Crew-id prefix marking a run as a pipeline run (see [SwarmPipeline.toCrewPreset]). */
const val PIPELINE_CREW_ID_PREFIX = "pipeline:"

val SWARM_PIPELINES: List<SwarmPipeline> = listOf(
    SwarmPipeline(
        id = "draft-polish",
        name = "Draft & polish",
        description = "Research a topic, draft it, polish it, verify it",
        missionHint = "Research [topic], then write a polished piece on it. " +
            "Replace the bracketed text with your topic before launching.",
        stages = listOf(
            PipelineStage(
                roleId = SwarmRoles.RESEARCHER,
                title = "Gather",
                brief = "Gather the key facts for the mission: names, numbers, " +
                    "dates, and what matters about each. Your output is the " +
                    "raw material the next stage writes from — be thorough " +
                    "and specific, and list what you could not verify.",
            ),
            PipelineStage(
                roleId = SwarmRoles.WRITER,
                title = "Draft",
                brief = "Turn the previous stage's research into a complete " +
                    "draft: strong opening, logical sections with headings, " +
                    "crisp close. Do not re-research — write from the " +
                    "material you were given, and flag any gap you cannot " +
                    "bridge instead of inventing facts.",
            ),
            PipelineStage(
                roleId = SwarmRoles.EDITOR,
                title = "Polish",
                brief = "Polish the previous stage's draft: fix errors, " +
                    "tighten prose, improve structure and consistency. Do " +
                    "not change its meaning or drop facts. Flag any factual " +
                    "claim that looks wrong rather than silently rewriting it.",
            ),
            PipelineStage(
                roleId = SwarmRoles.VERIFIER,
                title = "Verify",
                brief = "Verify the previous stage's polished piece against " +
                    "the mission: is it complete, correct and internally " +
                    "consistent? Report a short verdict — what you checked, " +
                    "issues found (or \"none\") — then PASS or FAIL with the " +
                    "concrete reason.",
            ),
        ),
    ),
    SwarmPipeline(
        id = "find-fix",
        name = "Find & fix",
        description = "Scan for flaws, fix them, verify the result",
        missionHint = "Find the flaws in [the text, plan or argument below], " +
            "fix them, and verify the result. Paste the material at the end " +
            "of this mission before launching.",
        stages = listOf(
            PipelineStage(
                roleId = SwarmRoles.ANALYST,
                title = "Scan",
                brief = "Scan the mission material for flaws: factual errors, " +
                    "logical gaps, contradictions, missing pieces, weak " +
                    "arguments. List each flaw concretely — what is wrong, " +
                    "where, and why it matters. Do not fix anything yet; " +
                    "your output is the fix list the next stage works from.",
            ),
            PipelineStage(
                roleId = SwarmRoles.EDITOR,
                title = "Fix",
                brief = "Fix every flaw the previous stage listed, working " +
                    "through its list in order. Keep what was already good " +
                    "untouched. Where a flaw cannot be fixed from the given " +
                    "material, say so explicitly instead of papering over it.",
            ),
            PipelineStage(
                roleId = SwarmRoles.VERIFIER,
                title = "Verify",
                brief = "Verify the previous stage's fixed version: are all " +
                    "listed flaws actually resolved, and did the fix " +
                    "introduce any new problem? Report what you checked, " +
                    "issues found (or \"none\") — then PASS or FAIL with the " +
                    "concrete reason.",
            ),
        ),
    ),
    SwarmPipeline(
        id = "research-brief",
        name = "Research brief",
        description = "Research a topic, analyze it, land a bottom line",
        missionHint = "Research [topic] and produce an analytical brief: key " +
            "facts, what they mean, and a clear bottom line. Replace the " +
            "bracketed text with your topic before launching.",
        stages = listOf(
            PipelineStage(
                roleId = SwarmRoles.RESEARCHER,
                title = "Gather",
                brief = "Gather the key facts for the mission: names, numbers, " +
                    "dates, and what matters about each. Your output feeds " +
                    "an analyst — prefer specifics over generalities and " +
                    "list what you could not verify.",
            ),
            PipelineStage(
                roleId = SwarmRoles.ANALYST,
                title = "Analyze",
                brief = "Analyze the previous stage's findings: compare " +
                    "options, weigh trade-offs, separate signal from noise. " +
                    "Show your working so it can be audited, then land on a " +
                    "clear bottom line with the 2-3 reasons that carry it.",
            ),
            PipelineStage(
                roleId = SwarmRoles.VERIFIER,
                title = "Verify",
                brief = "Verify the previous stage's brief against the " +
                    "mission: do the facts support the bottom line, is " +
                    "anything material missing, any contradiction? Report " +
                    "what you checked, issues found (or \"none\") — then " +
                    "PASS or FAIL with the concrete reason.",
            ),
        ),
    ),
    SwarmPipeline(
        id = "plan-decide",
        name = "Plan & decide",
        description = "Break a goal into phases, weigh it, recommend",
        missionHint = "Make a plan for [goal]: break it into phases, weigh " +
            "the trade-offs, and land on a clear recommendation. Replace " +
            "the bracketed text with your goal before launching.",
        stages = listOf(
            PipelineStage(
                roleId = SwarmRoles.PLANNER,
                title = "Plan",
                brief = "Break the mission's goal into a clear ordered plan: " +
                    "phases, what each phase must produce, and what \"done\" " +
                    "looks like per phase. Keep it tight and actionable — an " +
                    "analyst will stress-test it next.",
            ),
            PipelineStage(
                roleId = SwarmRoles.ANALYST,
                title = "Stress-test",
                brief = "Stress-test the previous stage's plan: find the " +
                    "weakest assumptions, the biggest risks, and the " +
                    "trade-offs between alternative approaches. Then give a " +
                    "clear recommendation — follow the plan, change it, or " +
                    "drop it — with the reasons that carry it.",
            ),
            PipelineStage(
                roleId = SwarmRoles.VERIFIER,
                title = "Verify",
                brief = "Verify the previous stage's recommendation: is it " +
                    "actually supported by the analysis, is the plan " +
                    "complete and coherent, anything material missing? " +
                    "Report what you checked, issues found (or \"none\") — " +
                    "then PASS or FAIL with the concrete reason.",
            ),
        ),
    ),
)

fun swarmPipelineById(id: String): SwarmPipeline? = SWARM_PIPELINES.firstOrNull { it.id == id }

/**
 * Resolves the pipeline for a run from its synthetic crew id, or null when
 * the run is a normal crew run (or the pipeline no longer exists — the run
 * then degrades gracefully to plain stage briefs via the standard worker
 * prompt).
 */
fun pipelineForCrewId(crewId: String?): SwarmPipeline? {
    if (crewId == null || !crewId.startsWith(PIPELINE_CREW_ID_PREFIX)) return null
    return swarmPipelineById(crewId.removePrefix(PIPELINE_CREW_ID_PREFIX))
}
