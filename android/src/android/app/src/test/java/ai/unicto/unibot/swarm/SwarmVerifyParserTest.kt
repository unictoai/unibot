package ai.unicto.unibot.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the one-line verifier verdict parser. */
class SwarmVerifyParserTest {

    @Test
    fun `plain PASS passes`() {
        val verdict = parseVerify("PASS")
        assertTrue(verdict.passed)
    }

    @Test
    fun `lowercase pass passes`() {
        assertTrue(parseVerify("pass").passed)
    }

    @Test
    fun `PASS with trailing commentary passes`() {
        assertTrue(parseVerify("PASS — output is complete and correct.").passed)
    }

    @Test
    fun `FAIL with reason fails and carries the reason`() {
        val verdict = parseVerify("FAIL: the output invents pricing numbers")
        assertFalse(verdict.passed)
        assertEquals("the output invents pricing numbers", verdict.reason)
    }

    @Test
    fun `FAIL without colon fails`() {
        val verdict = parseVerify("FAIL missing required section")
        assertFalse(verdict.passed)
        assertEquals("missing required section", verdict.reason)
    }

    @Test
    fun `bare FAIL fails with default reason`() {
        val verdict = parseVerify("FAIL")
        assertFalse(verdict.passed)
        assertTrue(verdict.reason.isNotBlank())
    }

    @Test
    fun `empty output fails`() {
        assertFalse(parseVerify("").passed)
        assertFalse(parseVerify("   ").passed)
    }

    @Test
    fun `non-verdict prose fails`() {
        assertFalse(parseVerify("The output looks mostly fine to me.").passed)
    }

    @Test
    fun `only the first non-blank line is considered`() {
        assertTrue(parseVerify("\n  PASS\nsome trailing text").passed)
        val verdict = parseVerify("\nFAIL: bad\nPASS later")
        assertFalse(verdict.passed)
        assertEquals("bad", verdict.reason)
    }
}
