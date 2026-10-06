package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for KeyValidator's pure surface: endpoint resolution and the
 * friendly copy for every validation outcome. The live HTTP path is
 * intentionally untested here — it needs a network and a real key.
 */
class KeyValidatorTest {

    // ── endpointFor ─────────────────────────────────────────────────────

    @Test
    fun `openai compatible types hit slash models on their canonical base`() {
        assertEquals(
            "https://api.groq.com/openai/v1/models",
            KeyValidator.endpointFor(ProviderType.groq, null),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            KeyValidator.endpointFor(ProviderType.openAI, null),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            KeyValidator.endpointFor(ProviderType.deepSeek, null),
        )
    }

    @Test
    fun `anthropic uses its models endpoint under v1`() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            KeyValidator.endpointFor(ProviderType.anthropic, null),
        )
    }

    @Test
    fun `gemini uses the v1beta models collection`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            KeyValidator.endpointFor(ProviderType.gemini, null),
        )
    }

    @Test
    fun `base override wins and trailing slashes are trimmed`() {
        assertEquals(
            "https://relay.example.com/v1/models",
            KeyValidator.endpointFor(ProviderType.openAI, "https://relay.example.com/v1/"),
        )
        assertEquals(
            "https://relay.example.com/v1/models",
            KeyValidator.endpointFor(ProviderType.anthropic, "https://relay.example.com"),
        )
    }

    @Test
    fun `unsupported types without a base return null`() {
        assertNull(KeyValidator.endpointFor(ProviderType.unsupported, null))
        assertNull(KeyValidator.endpointFor(ProviderType.antigravity, null))
    }

    // ── friendlyMessage ─────────────────────────────────────────────────

    @Test
    fun `valid message carries the model count`() {
        val msg = KeyValidator.friendlyMessage(KeyValidationResult.Valid(12))
        assertTrue(msg.contains("12"))
        assertTrue(msg.contains("Key works"))
    }

    @Test
    fun `invalid key message is actionable`() {
        val msg = KeyValidator.friendlyMessage(KeyValidationResult.InvalidKey)
        assertTrue(msg.contains("rejected"))
        assertTrue(msg.contains("pasted"))
    }

    @Test
    fun `server error message carries the code not the body`() {
        val msg = KeyValidationResult.ServerError(503)
        assertTrue(KeyValidator.friendlyMessage(msg).contains("503"))
    }

    @Test
    fun `unexpected message carries the code`() {
        val msg = KeyValidator.friendlyMessage(KeyValidationResult.Unexpected(418))
        assertTrue(msg.contains("418"))
    }

    @Test
    fun `network message never blames the key`() {
        val msg = KeyValidator.friendlyMessage(KeyValidationResult.NetworkUnreachable)
        assertTrue(msg.contains("connection"))
    }

    // ── countModels ─────────────────────────────────────────────────────

    @Test
    fun `countModels reads openai style data arrays`() {
        assertEquals(2, KeyValidator.countModels("""{"data":[{"id":"a"},{"id":"b"}]}"""))
    }

    @Test
    fun `countModels reads gemini style models arrays`() {
        assertEquals(3, KeyValidator.countModels("""{"models":[{},{},{}]}"""))
    }

    @Test
    fun `countModels returns minus one on unknown shapes`() {
        assertEquals(-1, KeyValidator.countModels("""{"object":"list"}"""))
        assertEquals(-1, KeyValidator.countModels(null))
        assertEquals(-1, KeyValidator.countModels("not json"))
    }
}
