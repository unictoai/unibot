package ai.unicto.unibot.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the tolerant mission-decomposition parser. */
class SwarmPlanParserTest {

    private val roles = listOf("researcher", "verifier")
    private val mission = "Research electric scooters"

    @Test
    fun `well-formed numbered list maps one subtask per role`() {
        val raw = "1. Gather specs and prices\n2. Verify the claims"
        assertEquals(
            listOf("Gather specs and prices", "Verify the claims"),
            parsePlan(raw, roles, mission),
        )
    }

    @Test
    fun `paren and colon markers are accepted`() {
        val raw = "1) First subtask\n2: Second subtask"
        assertEquals(
            listOf("First subtask", "Second subtask"),
            parsePlan(raw, roles, mission),
        )
    }

    @Test
    fun `dash and bullet markers are accepted`() {
        val raw = "- First subtask\n• Second subtask"
        assertEquals(
            listOf("First subtask", "Second subtask"),
            parsePlan(raw, roles, mission),
        )
    }

    @Test
    fun `continuation lines append to the previous item`() {
        val raw = "1. Gather specs\n   and prices from three vendors\n2. Verify the claims"
        assertEquals(
            listOf("Gather specs and prices from three vendors", "Verify the claims"),
            parsePlan(raw, roles, mission),
        )
    }

    @Test
    fun `preamble lines before the first item are ignored`() {
        val raw = "Here is the decomposition:\n1. Gather specs\n2. Verify the claims"
        assertEquals(
            listOf("Gather specs", "Verify the claims"),
            parsePlan(raw, roles, mission),
        )
    }

    @Test
    fun `malformed output falls back to one subtask per role using the mission`() {
        val raw = "I think you should just research this thoroughly, good luck!"
        assertEquals(listOf(mission, mission), parsePlan(raw, roles, mission))
    }

    @Test
    fun `empty output falls back to one subtask per role`() {
        assertEquals(listOf(mission, mission), parsePlan("", roles, mission))
        assertEquals(listOf(mission, mission), parsePlan("   \n  ", roles, mission))
    }

    @Test
    fun `too few items are padded with the mission`() {
        val raw = "1. Only subtask"
        assertEquals(listOf("Only subtask", mission), parsePlan(raw, roles, mission))
    }

    @Test
    fun `extra items are trimmed to the role count`() {
        val raw = "1. One\n2. Two\n3. Three\n4. Four"
        val parsed = parsePlan(raw, roles, mission)
        assertEquals(2, parsed.size)
        assertEquals(listOf("One", "Two"), parsed)
    }

    @Test
    fun `result always has exactly one subtask per role`() {
        val raws = listOf(
            "1. a\n2. b\n3. c",
            "no list at all",
            "",
            "1. only",
        )
        for (raw in raws) {
            assertEquals(
                "parsePlan must return exactly roles.size items for: '$raw'",
                roles.size,
                parsePlan(raw, roles, mission).size,
            )
        }
    }

    @Test
    fun `surrounding quotes are stripped`() {
        val raw = "1. \"Gather specs\"\n2. 'Verify claims'"
        assertEquals(
            listOf("Gather specs", "Verify claims"),
            parsePlan(raw, roles, mission),
        )
    }

    @Test
    fun `realistic model output with bold role prefixes parses`() {
        val raw = "1. **Researcher:** gather specs, prices, reviews\n" +
            "2. **Verifier:** cross-check every factual claim"
        val parsed = parsePlan(raw, roles, mission)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].contains("gather specs"))
        assertTrue(parsed[1].contains("cross-check"))
    }
}
