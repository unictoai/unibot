package ai.unicto.unibot.ui.swarm

import ai.unicto.unibot.swarm.SwarmAgentState
import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmAttachment
import ai.unicto.unibot.swarm.SwarmCrewPreset
import ai.unicto.unibot.swarm.SwarmLifecycle
import ai.unicto.unibot.swarm.SwarmUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 items 5 + 8 — mission report and agent transcript Markdown
 * builders. Pure JVM: no Android calls on these paths, so they run as
 * plain unit tests.
 */
class SwarmExportTest {

    private fun sampleState(): SwarmUiState = SwarmUiState(
        lifecycle = SwarmLifecycle.DONE,
        mission = "Research budget ANC headphones",
        crew = SwarmCrewPreset(
            id = "research",
            name = "Research",
            description = "",
            roles = listOf("researcher", "verifier"),
        ),
        agents = listOf(
            SwarmAgentState(
                id = "agent-0-researcher",
                role = "researcher",
                displayName = "Researcher",
                status = SwarmAgentStatus.DONE,
                currentStep = "Done",
                tokensUsed = 1200,
                result = "## Findings\n- Sony WH-1000XM5 is the benchmark.",
                log = listOf(
                    "[10:00:01] Worker call started",
                    "[10:00:05] Worker call finished (800 prompt + 400 completion tokens)",
                    "[10:00:06] Verifier verdict: PASS",
                ),
            ),
            SwarmAgentState(
                id = "agent-1-verifier",
                role = "verifier",
                displayName = "Verifier",
                status = SwarmAgentStatus.DONE,
                currentStep = "Done",
                tokensUsed = 300,
                result = "PASS",
            ),
        ),
        stitchedResult = "# Headphones\nThe Sony WH-1000XM5 wins.",
        totalTokens = 2000,
        proposedPlan = listOf("Find facts", "Check facts"),
        steeringNotes = listOf("Focus on pricing"),
        attachments = listOf(SwarmAttachment("1", "prices.txt", "Sony: 500.")),
    )

    @Test
    fun `mission report contains every section`() {
        val md = buildMissionReportMarkdown(sampleState())
        assertTrue(md.contains("# Swarm mission report"))
        assertTrue(md.contains("Research budget ANC headphones"))
        assertTrue(md.contains("## Crew: Research"))
        assertTrue(md.contains("1. Find facts"))
        assertTrue(md.contains("# Headphones"))
        assertTrue(md.contains("Sony WH-1000XM5 is the benchmark"))
        assertTrue(md.contains("1.2k tokens"))
        assertTrue(md.contains("## Steering notes"))
        assertTrue(md.contains("Focus on pricing"))
        assertTrue(md.contains("prices.txt"))
        assertTrue(md.contains("\$0 spent"))
    }

    @Test
    fun `mission report tolerates an empty state`() {
        val md = buildMissionReportMarkdown(SwarmUiState())
        assertTrue(md.contains("# Swarm mission report"))
        assertTrue(md.contains("(no mission text)"))
        assertFalse(md.contains("## Agent results"))
    }

    @Test
    fun `agent transcript contains the full step log and result`() {
        val state = sampleState()
        val agent = state.agents[0]
        val md = buildAgentTranscriptMarkdown(state, agent)
        assertTrue(md.contains("# Agent transcript"))
        assertTrue(md.contains("Researcher"))
        assertTrue(md.contains("## Step log"))
        assertTrue(md.contains("Worker call started"))
        assertTrue(md.contains("Verifier verdict: PASS"))
        assertTrue(md.contains("Sony WH-1000XM5 is the benchmark"))
        assertTrue(md.contains("## Result"))
    }

    @Test
    fun `incomplete marker is stripped from the result body and surfaced as a note`() {
        val state = sampleState()
        val agent = state.agents[0].copy(
            result = state.agents[0].result +
                "\n\n_Note: automated review flagged an issue (thin sourcing); " +
                "one revision was applied and this best-effort output stands._",
        )
        val md = buildAgentTranscriptMarkdown(state, agent)
        assertTrue(md.contains("> Incomplete:"))
        // The engine's appended note is stripped from the result body...
        val resultSection = md.substringAfter("## Result")
        assertFalse(resultSection.contains("_Note: automated review flagged"))
        // ...and carried once by the incomplete quote instead.
        assertTrue(md.contains("automated review flagged an issue (thin sourcing)"))
    }
}
