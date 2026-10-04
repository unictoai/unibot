package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMError
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.provider.anthropic.AnthropicProvider
import ai.unicto.unibot.provider.gemini.GeminiProvider
import ai.unicto.unibot.provider.openai.OpenAIProvider
import ai.unicto.unibot.ui.chat.friendlyOutputLimitText
import ai.unicto.unibot.ui.chat.friendlyRequestTooLargeText
import ai.unicto.unibot.ui.chat.resolveCardProviderName
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v127-provider-name] [T-android-v127-413-output-limit]
 *
 * Two confusion-generators from the user's v1.2.6 screenshots (Groq):
 *  1. The friendly cards said "on OpenAI" / "OpenAI's free tier" for requests
 *     that went to his Groq instance — the card used LLMProvider.name (the
 *     provider TYPE) instead of the instance's user-visible label.
 *  2. A 413 whose body was really about the output-token budget (Groq answers
 *     413, not 400, for oversized max_completion_tokens) rendered the
 *     "conversation too long" card on a one-word chat — it must map to
 *     OutputLimitExceeded instead.
 */
class CardAccuracyTest {

    private val qwenGroq = LLMModel(
        id = "qwen/qwen3.6-27b",
        displayName = "Qwen3.6 27B",
        provider = "Groq",
        maxOutputTokens = 16384,
    )

    private fun openAIProvider() = OpenAIProvider(
        apiKey = "test-key",
        model = qwenGroq,
        basePath = "https://api.groq.com/openai/v1",
    )

    private fun anthropicProvider() = AnthropicProvider(apiKey = "test-key")

    private fun geminiProvider() = GeminiProvider(apiKey = "test-key")

    // ── Bug 1: instance label wins over provider type name ──────────────

    @Test
    fun `card uses the instance label Groq not the type name OpenAI`() {
        assertEquals("Groq", resolveCardProviderName("Groq", "OpenAI"))
    }

    @Test
    fun `card falls back to the type name when the instance label is blank`() {
        assertEquals("OpenAI", resolveCardProviderName("", "OpenAI"))
        assertEquals("OpenAI", resolveCardProviderName(null, "OpenAI"))
        assertEquals("OpenAI", resolveCardProviderName("   ", "OpenAI"))
    }

    @Test
    fun `card falls back to generic text when both names are blank`() {
        assertEquals("the provider", resolveCardProviderName("", ""))
        assertEquals("the provider", resolveCardProviderName(null, null))
    }

    @Test
    fun `friendly 413 card names the Groq instance`() {
        val text = friendlyRequestTooLargeText("GPT OSS 20B", resolveCardProviderName("Groq", "OpenAI"))
        assertTrue(text.contains("Groq"))
        assertFalse(text.contains("OpenAI"))
    }

    @Test
    fun `friendly output-limit card names the Groq instance`() {
        val text = friendlyOutputLimitText("Qwen3.8-27B", resolveCardProviderName("Groq", "OpenAI"), 16384)
        assertTrue(text.contains("Groq"))
        assertFalse(text.contains("OpenAI"))
    }

    // ── Bug 2: 413 with token language → OutputLimitExceeded ─────────────

    private val tokenBody =
        """{"error":{"message":"'max_completion_tokens' must be less than or equal to '16384', the maximum for this model.","type":"invalid_request_error"}}"""
    private val plainBody = """{"error":{"message":"Request too large: conversation history exceeds the model's context window."}}"""

    @Test
    fun `openai 413 with token body maps to OutputLimitExceeded`() {
        val err = openAIProvider().mapHttpError(413, tokenBody)
        assertTrue(err is LLMError.OutputLimitExceeded)
        assertEquals(16384, (err as LLMError.OutputLimitExceeded).limit)
        assertFalse(err is LLMError.ProviderError)
    }

    @Test
    fun `openai 413 without token language stays RequestTooLarge`() {
        val err = openAIProvider().mapHttpError(413, plainBody)
        assertTrue(err is LLMError.RequestTooLarge)
    }

    @Test
    fun `anthropic 413 with token body maps to OutputLimitExceeded`() {
        val err = anthropicProvider().mapHttpError(413, tokenBody)
        assertTrue(err is LLMError.OutputLimitExceeded)
    }

    @Test
    fun `anthropic 413 without token language stays RequestTooLarge`() {
        val err = anthropicProvider().mapHttpError(413, plainBody)
        assertTrue(err is LLMError.RequestTooLarge)
    }

    @Test
    fun `gemini 413 with token body maps to OutputLimitExceeded`() {
        val err = geminiProvider().mapHttpError(413, tokenBody)
        assertTrue(err is LLMError.OutputLimitExceeded)
    }

    @Test
    fun `gemini 413 without token language stays RequestTooLarge`() {
        val err = geminiProvider().mapHttpError(413, plainBody)
        assertTrue(err is LLMError.RequestTooLarge)
    }

    @Test
    fun `OutputLimitExceeded is neither retryable nor fallbackable`() {
        val err: LLMError = LLMError.OutputLimitExceeded(16384, "[413] ...")
        assertFalse(err.isRetryable)
        assertFalse(err.isFallbackable)
        assertFalse(err is LLMError.ProviderError)
    }
}
