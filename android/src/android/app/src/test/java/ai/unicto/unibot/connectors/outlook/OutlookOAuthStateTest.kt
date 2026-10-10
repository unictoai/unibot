package ai.unicto.unibot.connectors.outlook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OAuth `state` check must require presence AND equality (no silent accept of a missing state). */
class OutlookOAuthStateTest {

    @Test
    fun `matching state is accepted`() {
        assertTrue(OutlookOAuth.isValidState("abc123", "abc123"))
    }

    @Test
    fun `mismatched state is rejected`() {
        assertFalse(OutlookOAuth.isValidState("attacker", "abc123"))
    }

    @Test
    fun `missing state is rejected`() {
        assertFalse(OutlookOAuth.isValidState(null, "abc123"))
    }

    @Test
    fun `empty state is rejected`() {
        assertFalse(OutlookOAuth.isValidState("", "abc123"))
    }
}
