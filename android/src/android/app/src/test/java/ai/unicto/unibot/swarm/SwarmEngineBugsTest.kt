package ai.unicto.unibot.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 swarm-bugfix stream — pure-JVM tests (no coroutines, no Android).
 *
 * Covers Bug 4 (strict verifier parsing), Bug 7 (weak-reason detection) and
 * Bug 6 (MAX-per-field usage accumulation). Retry/teardown integration
 * tests are owned by the separate test worker; these cover the pure
 * functions only.
 */
class SwarmEngineBugsTest {

    // ─── Bug 4: parseVerify strictness ────────────────────────────────────

    @Test
    fun `parseVerify rejects Passage prose as unclear, not a pass`() {
        val v = parseVerify("Passage is missing a citation")
        assertFalse(v.passed)
        assertTrue(v.unclear)
        assertEquals("unparseable verifier reply", v.reason)
    }

    @Test
    fun `parseVerify still accepts a bare PASS`() {
        val v = parseVerify("PASS")
        assertTrue(v.passed)
        assertFalse(v.unclear)
    }

    @Test
    fun `parseVerify accepts lowercase pass`() {
        assertTrue(parseVerify("pass").passed)
    }

    @Test
    fun `parseVerify accepts PASS with trailing commentary`() {
        assertTrue(parseVerify("PASS — output is complete and correct.").passed)
    }

    @Test
    fun `parseVerify parses FAIL with a reason`() {
        val v = parseVerify("FAIL: the output invents pricing numbers")
        assertFalse(v.passed)
        assertFalse(v.unclear)
        assertEquals("the output invents pricing numbers", v.reason)
    }

    @Test
    fun `parseVerify FAIL without colon strips and carries the reason`() {
        val v = parseVerify("FAIL missing required section")
        assertFalse(v.passed)
        assertEquals("missing required section", v.reason)
    }

    @Test
    fun `parseVerify bare FAIL gets the default reason`() {
        val v = parseVerify("FAIL")
        assertFalse(v.passed)
        assertEquals("no reason given", v.reason)
    }

    @Test
    fun `parseVerify caps long reasons at 300 chars`() {
        val v = parseVerify("FAIL: " + "x".repeat(500))
        assertEquals(300, v.reason.length)
    }

    @Test
    fun `parseVerify treats empty input as unclear`() {
        val v = parseVerify("   \n  ")
        assertFalse(v.passed)
        assertTrue(v.unclear)
    }

    @Test
    fun `parseVerify treats non-verdict prose as unclear`() {
        val v = parseVerify("The output looks mostly fine to me.")
        assertFalse(v.passed)
        assertTrue(v.unclear)
    }

    @Test
    fun `parseVerify reads the first non-blank line`() {
        assertTrue(parseVerify("\n  PASS\nsome trailing text").passed)
        val v = parseVerify("\nFAIL: bad\nPASS later")
        assertFalse(v.passed)
        assertEquals("bad", v.reason)
    }

    // ─── Bug 7: weak-reason detection ─────────────────────────────────────

    @Test
    fun `isWeakReason flags one- and two-word reasons`() {
        assertTrue(isWeakReason("bad"))
        assertTrue(isWeakReason("wrong output"))
    }

    @Test
    fun `isWeakReason accepts three or more words`() {
        assertFalse(isWeakReason("no reason given"))
        assertFalse(isWeakReason("the output contradicts the mission statement"))
    }

    @Test
    fun `isWeakReason treats blank as weak`() {
        assertTrue(isWeakReason(""))
        assertTrue(isWeakReason("   "))
    }

    // ─── Bug 6: MAX-per-field usage accumulation ──────────────────────────

    @Test
    fun `gemini-style cumulative chunks take the max, not the sum`() {
        val acc = SwarmUsageAccumulator()
        // Gemini sends cumulative totals per chunk: 100, 200, 300, 400, 500
        // for a 500-token completion. Summing would report 1500.
        listOf(100, 200, 300, 400, 500).forEach { acc.add(0, it) }
        assertEquals(500, acc.completionTokens)
        assertEquals(0, acc.promptTokens)
        assertEquals(500, acc.total)
    }

    @Test
    fun `anthropic-style start plus delta chunks combine correctly`() {
        val acc = SwarmUsageAccumulator()
        acc.add(1000, 0) // start chunk: input total
        acc.add(0, 250) // delta chunk: output total
        assertEquals(1000, acc.promptTokens)
        assertEquals(250, acc.completionTokens)
        assertEquals(1250, acc.total)
    }

    @Test
    fun `single final chunk behaves like the old sum`() {
        val acc = SwarmUsageAccumulator()
        acc.add(800, 120)
        assertEquals(800, acc.promptTokens)
        assertEquals(120, acc.completionTokens)
        assertEquals(920, acc.total)
    }

    @Test
    fun `accumulator never regresses on later smaller chunks`() {
        val acc = SwarmUsageAccumulator()
        acc.add(1000, 250)
        acc.add(0, 0) // e.g. a trailing Finished-adjacent zero chunk
        assertEquals(1000, acc.promptTokens)
        assertEquals(250, acc.completionTokens)
    }
}
