package ai.unicto.unibot.ui.swarm

import ai.unicto.unibot.swarm.SwarmAgentStatus
import ai.unicto.unibot.swarm.SwarmLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [v1.3.0-swarm] Pure-JVM tests for the swarm UI helpers. No Compose, no
 * Robolectric — these run in plain `testDebugUnitTest`.
 */
class SwarmUiHelpersTest {

    // ── Codenames: deterministic ID cards ────────────────────────────────────

    @Test
    fun `codename is deterministic per agent id`() {
        assertEquals(agentCodename("agent-1"), agentCodename("agent-1"))
    }

    @Test
    fun `codename is never blank`() {
        listOf("", "   ", "a", "agent-123", "x".repeat(200)).forEach { id ->
            assertTrue("codename for '$id' must not be blank", agentCodename(id).isNotBlank())
        }
    }

    @Test
    fun `distinct ids usually map to distinct codenames`() {
        val names = (1..8).map { agentCodename("agent-$it") }.toSet()
        // 16-name pool, 8 agents — collisions are possible but unlikely;
        // at minimum more than one name must appear.
        assertTrue("expected variety across 8 ids, got $names", names.size > 1)
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @Test
    fun `every agent status has a non-blank label`() {
        SwarmAgentStatus.entries.forEach { status ->
            assertTrue("${status.name} label blank", status.label().isNotBlank())
        }
    }

    @Test
    fun `every lifecycle has a non-blank label`() {
        SwarmLifecycle.entries.forEach { lifecycle ->
            assertTrue("${lifecycle.name} label blank", lifecycle.label().isNotBlank())
        }
    }

    @Test
    fun `terminal states are DONE and FAILED only`() {
        assertTrue(SwarmAgentStatus.DONE.isTerminal())
        assertTrue(SwarmAgentStatus.FAILED.isTerminal())
        assertTrue(!SwarmAgentStatus.QUEUED.isTerminal())
        assertTrue(!SwarmAgentStatus.WORKING.isTerminal())
        assertTrue(!SwarmAgentStatus.VERIFYING.isTerminal())
    }

    // ── Role names / expected outputs ─────────────────────────────────────────

    @Test
    fun `role display names prefer the engine canon, humanize the rest`() {
        // Engine-canonical names (SwarmRoles) — the UI can never drift.
        assertEquals("Planner", roleDisplayName("planner"))
        assertEquals("Researcher", roleDisplayName("researcher"))
        assertEquals("Analyst", roleDisplayName("analyst"))
        // Unknown ids fall back to humanizing.
        assertEquals("Deep Researcher", roleDisplayName("deep_researcher"))
        assertEquals("Agent", roleDisplayName(""))
        assertEquals("Agent", roleDisplayName("___"))
    }

    @Test
    fun `known roles have a defined output, unknown roles fall back`() {
        assertEquals("Findings with sources", expectedOutput("researcher"))
        assertEquals("Verification report", expectedOutput("VERIFIER"))
        assertEquals("Analysis & recommendation", expectedOutput("analyst"))
        assertEquals("Agent output", expectedOutput("mystery_role"))
    }

    // ── Token formatting ─────────────────────────────────────────────────────

    @Test
    fun `token counts format compactly`() {
        assertEquals("0", formatTokens(0))
        assertEquals("850", formatTokens(850))
        assertEquals("999", formatTokens(999))
        assertEquals("1.0k", formatTokens(1000))
        assertEquals("1.2k", formatTokens(1250))
        assertEquals("15k", formatTokens(15_000))
        assertEquals("2.4M", formatTokens(2_400_000))
    }

    // ── Incomplete marker ────────────────────────────────────────────────────

    @Test
    fun `incomplete marker in result yields the note`() {
        assertEquals(
            "only 3 of 5 sources checked",
            extractIncompleteNote("[incomplete] only 3 of 5 sources checked", ""),
        )
    }

    @Test
    fun `incomplete marker in detail is found when result is clean`() {
        assertEquals(
            "partial",
            extractIncompleteNote("full result", "[incomplete] partial"),
        )
    }

    @Test
    fun `result wins when both carry the marker`() {
        assertEquals(
            "from result",
            extractIncompleteNote("[incomplete] from result", "[incomplete] from detail"),
        )
    }

    @Test
    fun `engine best-effort note is detected as incomplete`() {
        val result = "Some findings here.\n\n_Note: automated review flagged an issue " +
            "(missing sources); one revision was applied and this best-effort output stands._"
        val note = extractIncompleteNote(result, "")
        assertTrue("expected a note, got null", note != null)
        assertTrue("note should name the issue, got: $note", note!!.contains("missing sources"))
    }

    @Test
    fun `engine best-effort note is stripped from display text`() {
        val result = "Some findings here.\n\n_Note: automated review flagged an issue " +
            "(missing sources); one revision was applied and this best-effort output stands._"
        assertEquals("Some findings here.", withoutIncompleteMarker(result))
    }

    @Test
    fun `clean results stay untouched by marker stripping`() {
        assertEquals("done well", withoutIncompleteMarker("done well"))
        assertNull(extractIncompleteNote("done well", "all good"))
        assertNull(extractIncompleteNote("", ""))
    }

    @Test
    fun `bare marker yields a placeholder note, not blank`() {
        assertEquals("(no note provided)", extractIncompleteNote("[incomplete]", ""))
        assertEquals("(no note provided)", extractIncompleteNote("[incomplete]   ", ""))
    }

    @Test
    fun `marker mid-text is not treated as incomplete`() {
        // The marker is a prefix convention — a mention later in the text
        // must not flip the terminal state.
        assertNull(extractIncompleteNote("mentions [incomplete] work elsewhere", ""))
    }

    @Test
    fun `display text strips a leading marker`() {
        assertEquals("only 3 of 5", withoutIncompleteMarker("[incomplete] only 3 of 5"))
        assertEquals("clean text", withoutIncompleteMarker("clean text"))
    }

    // ── Presets ──────────────────────────────────────────────────────────────

    @Test
    fun `contract preset ids exist`() {
        val ids = SWARM_PRESETS.map { it.id }
        assertTrue(ids.contains("research"))
        assertTrue(ids.contains("content"))
        assertTrue(ids.contains("deepdive"))
    }

    @Test
    fun `custom preset uses all six engine roles`() {
        val custom = swarmPresetById("custom")
        assertEquals(
            ai.unicto.unibot.swarm.SwarmRoles.customRoles().map { it.id }.toSet(),
            custom?.roles?.toSet(),
        )
    }

    @Test
    fun `every preset role resolves in the engine`() {
        // Guards against the UI cards misrepresenting what launch() spawns:
        // an unknown role id would silently fall back to a generic prompt.
        SWARM_PRESETS.forEach { preset ->
            preset.roles.forEach { roleId ->
                assertTrue(
                    "preset ${preset.id} role '$roleId' unknown to SwarmRoles",
                    ai.unicto.unibot.swarm.SwarmRoles.byId(roleId) != null,
                )
            }
        }
    }

    @Test
    fun `unknown preset id returns null`() {
        assertNull(swarmPresetById("nope"))
        // sanity: lookup is stable, not order-dependent
        assertNotEquals(
            swarmPresetById("research")?.name,
            swarmPresetById("content")?.name,
        )
    }
}
