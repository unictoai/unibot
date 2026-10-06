package ai.unicto.unibot.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 88 — opt-in crash reports are privacy-safe: whatever the
 * user chooses to send never contains API keys. Pins the scrub shapes and
 * the never-throw / idempotency guarantees of the export boundary.
 */
class SecretScrubberTest {

    @Test
    fun `json credential fields are masked, names kept`() {
        val inText = """{"api_key":"sk-live-ABCDEF123456","model":"gpt-5","access_token":"tok-xyz-1234567890"}"""
        val out = SecretScrubber.scrub(inText)
        assertFalse(out.contains("sk-live-ABCDEF123456"))
        assertFalse(out.contains("tok-xyz-1234567890"))
        assertTrue(out.contains("\"api_key\":\"***\""))
        assertTrue(out.contains("\"access_token\":\"***\""))
        assertTrue(out.contains("\"model\":\"gpt-5\""))
    }

    @Test
    fun `bearer tokens are masked`() {
        val out = SecretScrubber.scrub("Authorization: Bearer gsk_abcdef1234567890XYZ")
        assertFalse(out.contains("gsk_abcdef1234567890XYZ"))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun `provider token shapes are masked`() {
        val inText = "key=sk-ant-abcdefghijklmnop1234 and xai-xYzAbC123456789012 and AIzaSyD-abcdefghijklmnop"
        val out = SecretScrubber.scrub(inText)
        assertFalse(out.contains("sk-ant-abcdefghijklmnop1234"))
        assertFalse(out.contains("xai-xYzAbC123456789012"))
        assertFalse(out.contains("AIzaSyD-abcdefghijklmnop"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `query-string key params are masked`() {
        val out = SecretScrubber.scrub("GET https://api.example.com/v1/models?api_key=SECRET123456&limit=10")
        assertFalse(out.contains("SECRET123456"))
        assertTrue(out.contains("api_key=***"))
        assertTrue(out.contains("limit=10"))
    }

    @Test
    fun `ordinary log text passes through untouched`() {
        val inText = "2026-10-06 10:00:00.123 I/ChatVM: stream finished, 42 messages, model=openai/gpt-oss-120b"
        assertEquals(inText, SecretScrubber.scrub(inText))
        assertFalse(SecretScrubber.containsSecrets(inText))
    }

    @Test
    fun `short lookalikes are not scrubbed`() {
        // "sk-" followed by a short token is not a real key shape.
        val inText = "task sk-1 done"
        assertEquals(inText, SecretScrubber.scrub(inText))
    }

    @Test
    fun `scrub is idempotent`() {
        val inText = """{"api_key":"sk-live-ABCDEF123456"} bearer tok-abcdef1234567890"""
        val once = SecretScrubber.scrub(inText)
        assertEquals(once, SecretScrubber.scrub(once))
    }

    @Test
    fun `containsSecrets detects what scrub masks`() {
        assertTrue(SecretScrubber.containsSecrets("""{"client_secret":"cs-abcdef123456"}"""))
        assertTrue(SecretScrubber.containsSecrets("Authorization: Bearer abcdef1234567890"))
        assertFalse(SecretScrubber.containsSecrets("no secrets here"))
    }
}
