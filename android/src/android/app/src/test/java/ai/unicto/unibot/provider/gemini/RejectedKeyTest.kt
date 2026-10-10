package ai.unicto.unibot.provider.gemini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rejected API key must not be hidden behind the built-in catalog —
 * otherwise the UI implies a bad key works.
 */
class RejectedKeyTest {

    @Test
    fun `rejected api key is not masked`() {
        assertTrue(GeminiModelsApi.isRejectedKey(isOAuth = false, code = 401))
        assertTrue(GeminiModelsApi.isRejectedKey(isOAuth = false, code = 403))
    }

    @Test
    fun `transient failures keep the fallback`() {
        assertFalse(GeminiModelsApi.isRejectedKey(isOAuth = false, code = 429))
        assertFalse(GeminiModelsApi.isRejectedKey(isOAuth = false, code = 500))
        assertFalse(GeminiModelsApi.isRejectedKey(isOAuth = false, code = 200))
    }

    @Test
    fun `oauth keeps the fallback even on 403`() {
        // A 403 on OAuth usually means the token lacks the scope, not that
        // the credential is bad — the built-in list keeps those users working.
        assertFalse(GeminiModelsApi.isRejectedKey(isOAuth = true, code = 403))
        assertFalse(GeminiModelsApi.isRejectedKey(isOAuth = true, code = 401))
    }
}
