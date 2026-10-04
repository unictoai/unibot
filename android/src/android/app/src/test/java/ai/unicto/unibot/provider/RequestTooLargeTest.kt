package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMError
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.ui.chat.ERROR_KIND_REQUEST_TOO_LARGE
import ai.unicto.unibot.ui.chat.friendlyRequestTooLargeText
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v124-413] HTTP 413 (request too large) handling:
 *  - the error class never leaks the raw "[413]" code as primary text,
 *  - the friendly card text names the model + provider and reassures,
 *  - the Groq seed catalog no longer offers decommissioned model IDs.
 */
class RequestTooLargeTest {

    @Test
    fun `friendly text names model and provider and hides the raw code`() {
        val text = friendlyRequestTooLargeText("GPT OSS 120B", "Groq")
        assertTrue(text.contains("GPT OSS 120B"))
        assertTrue(text.contains("Groq"))
        assertFalse(text.contains("[413]"))
        assertFalse(text.contains("413"))
        assertTrue(text.contains("Your messages are safe"))
    }

    @Test
    fun `friendly text falls back gracefully on blank names`() {
        val text = friendlyRequestTooLargeText("", "")
        assertTrue(text.contains("this model"))
        assertTrue(text.contains("the provider"))
    }

    @Test
    fun `RequestTooLarge is not a ProviderError so group-fallback never silently switches models`() {
        val err: LLMError = LLMError.RequestTooLarge("[413] too big")
        assertFalse(err is LLMError.ProviderError)
        assertFalse(err.isFallbackable)
        assertFalse(err.isRetryable)
        assertEquals("Request too large", err.fallbackReason)
    }

    @Test
    fun `error kind constant is stable`() {
        assertEquals("request_too_large", ERROR_KIND_REQUEST_TOO_LARGE)
    }

    @Test
    fun `Groq catalog contains no decommissioned model IDs`() {
        // qwen3.6-27b retired ~2026-09-14 (renamed to qwen3.8-27b) — it must
        // not be in the seed catalog anymore (see RETIRED_MODEL_IDS).
        val dead = setOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3.6-27b")
        val ids = LLMModel.allGroq.map { it.id }.toSet()
        assertTrue("dead IDs still present: ${ids intersect dead}", (ids intersect dead).isEmpty())
        assertTrue(ids.contains("openai/gpt-oss-120b"))
        assertTrue(ids.contains("openai/gpt-oss-20b"))
        assertTrue(ids.contains("qwen/qwen3.8-27b"))
    }
}
