package ai.unicto.unibot.provider

import ai.unicto.unibot.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.4.0 item 81 — "No provider configured" becomes a guided fix.
 *
 * Pins the error-text → (action label, route) contract the chat snackbar
 * consumes: exact match only (no substring/regex — "No provider configured
 * for voice" is a different problem), case-insensitive, and null for
 * everything without a guided fix.
 */
class NoProviderGuidanceTest {

    @Test
    fun `no provider configured maps to Set up on the provider list`() {
        val fix = NoProviderGuidance.guidedFixFor("No provider configured")
        assertEquals("Set up", fix?.actionLabel)
        assertEquals(Routes.PROVIDER_LIST, fix?.route)
    }

    @Test
    fun `matching is case-insensitive and trims`() {
        val fix = NoProviderGuidance.guidedFixFor("  NO PROVIDER CONFIGURED\n")
        assertEquals("Set up", fix?.actionLabel)
    }

    @Test
    fun `partial matches do not get the fix`() {
        // A different problem ("for voice") must not offer the generic
        // provider-list fix.
        assertNull(NoProviderGuidance.guidedFixFor("No provider configured for voice"))
        assertNull(NoProviderGuidance.guidedFixFor("No provider"))
    }

    @Test
    fun `null and unrelated errors have no guided fix`() {
        assertNull(NoProviderGuidance.guidedFixFor(null))
        assertNull(NoProviderGuidance.guidedFixFor(""))
        assertNull(NoProviderGuidance.guidedFixFor("Network error: timeout"))
        assertNull(NoProviderGuidance.guidedFixFor("Invalid API key"))
    }
}
