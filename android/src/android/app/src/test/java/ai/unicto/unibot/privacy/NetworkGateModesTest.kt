package ai.unicto.unibot.privacy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Privacy items 58 (kill switch) and 62 (on-device-only mode) — unit tests
 * for the gate ordering in PrivacyNetworkGate.checkAllowed. Pure JVM: the
 * gate flags are plain volatiles, no Android framework involved.
 */
class NetworkGateModesTest {

    @After
    fun tearDown() {
        PrivacyNetworkGate.killSwitchEngaged = false
        PrivacyNetworkGate.onDeviceOnlyEnabled = false
        PrivacyNetworkGate.localOnlyEnabled = false
        PrivacyNetworkGate.clear()
    }

    private fun assertBlocked(host: String, messagePart: String) {
        try {
            PrivacyNetworkGate.checkAllowed(host)
            fail("expected NetworkBlockedException for $host")
        } catch (e: NetworkBlockedException) {
            assertTrue(
                "message should name the gate (got: ${e.message})",
                e.message?.contains(messagePart) == true,
            )
        }
    }

    @Test
    fun `kill switch blocks every remote host`() {
        PrivacyNetworkGate.killSwitchEngaged = true
        assertBlocked("api.openai.com", "kill switch")
        assertBlocked("api.telegram.org", "kill switch")
        assertBlocked("duckduckgo.com", "kill switch")
    }

    @Test
    fun `kill switch still allows loopback`() {
        PrivacyNetworkGate.killSwitchEngaged = true
        PrivacyNetworkGate.checkAllowed("127.0.0.1")
        PrivacyNetworkGate.checkAllowed("localhost")
        PrivacyNetworkGate.checkAllowed("::1")
    }

    @Test
    fun `kill switch works when local-only mode is off`() {
        PrivacyNetworkGate.localOnlyEnabled = false
        PrivacyNetworkGate.killSwitchEngaged = true
        assertBlocked("api.openai.com", "kill switch")
    }

    @Test
    fun `on-device-only mode blocks every remote host including providers`() {
        PrivacyNetworkGate.onDeviceOnlyEnabled = true
        assertBlocked("api.openai.com", "On-device-only")
        assertBlocked("api.telegram.org", "On-device-only")
    }

    @Test
    fun `on-device-only mode still allows loopback`() {
        PrivacyNetworkGate.onDeviceOnlyEnabled = true
        PrivacyNetworkGate.checkAllowed("127.0.0.1")
        PrivacyNetworkGate.checkAllowed("localhost")
    }

    @Test
    fun `kill switch wins over on-device-only mode in message`() {
        PrivacyNetworkGate.killSwitchEngaged = true
        PrivacyNetworkGate.onDeviceOnlyEnabled = true
        assertBlocked("api.openai.com", "kill switch")
    }

    @Test
    fun `local-only mode keeps its original behavior and message`() {
        PrivacyNetworkGate.localOnlyEnabled = true
        // Empty allowlist: everything remote is refused, loopback passes.
        assertBlocked("api.openai.com", "Blocked by Local-only mode")
        PrivacyNetworkGate.checkAllowed("127.0.0.1")
    }

    @Test
    fun `no gate means no blocking`() {
        PrivacyNetworkGate.checkAllowed("api.openai.com")
        PrivacyNetworkGate.checkAllowed("anything.example")
    }

    @Test
    fun `record populates metadata-only entries`() {
        PrivacyNetworkGate.record("api.openai.com")
        val entry = PrivacyNetworkGate.log.value.single()
        assertEquals("api.openai.com", entry.host)
        assertEquals(PrivacyNetworkGate.Category.LLM, entry.category)
        assertEquals("GET", entry.method)
        assertFalse(entry.blocked)
        // Metadata only — sessionId is null off-screen and there is
        // nowhere for a path, query, header, or body to hide.
    }
}
