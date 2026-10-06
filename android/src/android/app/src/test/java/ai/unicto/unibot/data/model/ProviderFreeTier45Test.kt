package ai.unicto.unibot.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 45 — the three new free-tier providers are registered with
 * conservative defaults and behave like the rest of the registry.
 */
class ProviderFreeTier45Test {

    @Test
    fun `new providers are marked free tier`() {
        assertTrue(ProviderType.pollinations.isFreeTier)
        assertTrue(ProviderType.huggingFace.isFreeTier)
        assertTrue(ProviderType.aionLabs.isFreeTier)
    }

    @Test
    fun `new providers are usable`() {
        assertTrue(ProviderType.pollinations.isUsable)
        assertTrue(ProviderType.huggingFace.isUsable)
        assertTrue(ProviderType.aionLabs.isUsable)
    }

    @Test
    fun `new providers have base urls`() {
        assertEquals("https://text.pollinations.ai/openai", ProviderType.pollinations.defaultBaseUrl)
        assertEquals("https://router.huggingface.co/v1", ProviderType.huggingFace.defaultBaseUrl)
        assertEquals("https://api.aionlabs.ai/v1", ProviderType.aionLabs.defaultBaseUrl)
    }

    @Test
    fun `new providers have seed models with sane output caps`() {
        for (t in listOf(ProviderType.pollinations, ProviderType.huggingFace, ProviderType.aionLabs)) {
            val models = t.builtInModels
            assertTrue("$t has no seed models", models.isNotEmpty())
            for (m in models) {
                val cap = m.maxOutputTokens ?: 0
                assertTrue("$t/${m.id} output cap missing", cap > 0)
                assertTrue("$t/${m.id} output cap too large", cap <= 8192)
            }
        }
    }

    @Test
    fun `seed models are visible in the global list`() {
        assertTrue(LLMModel.allModels.any { it.id == "openai" })
        assertTrue(LLMModel.allModels.any { it.id.startsWith("meta-llama/") })
        assertTrue(LLMModel.allModels.any { it.id.startsWith("aion-3.0") })
    }

    @Test
    fun `existing providers unchanged`() {
        assertFalse(ProviderType.openAI.isFreeTier)
        assertTrue(ProviderType.openAI.isUsable)
    }
}
