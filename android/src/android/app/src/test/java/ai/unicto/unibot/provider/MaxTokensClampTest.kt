package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMError
import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.ThinkingLevel
import ai.unicto.unibot.data.model.extractOutputLimit
import ai.unicto.unibot.data.model.isOutputLimitBody
import ai.unicto.unibot.provider.openai.OpenAIProvider
import ai.unicto.unibot.ui.chat.ERROR_KIND_OUTPUT_LIMIT
import ai.unicto.unibot.ui.chat.friendlyOutputLimitText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v126-maxtokens] Root cause of the user's Groq 400
 * (`'max_completion_tokens' must be less than or equal to '16384'`).
 *
 * The models.dev fallback scan assigned ANOTHER provider's output limit to
 * the same model id (aiand's 65536 for `qwen/qwen3.6-27b`, whose real Groq
 * cap is 16384), because refreshed /v1/models entries carry provider="Custom"
 * and the providerKeyMap had no free-tier keys. The fix:
 *  1. providerKeyMap knows the free-tier providers + enrichModels takes a
 *     serving-provider hint,
 *  2. the fallback scan takes the MINIMUM output limit across candidates
 *     (a too-high guess 400s; a too-low guess only caps length),
 *  3. buildRequestBody finally clamps max_tokens/max_completion_tokens to
 *     the model's known limit — the wire can never exceed it,
 *  4. the 400 class maps to a friendly card, never raw JSON.
 */
class MaxTokensClampTest {

    private val qwenGroq = LLMModel(
        id = "qwen/qwen3.6-27b",
        displayName = "Qwen3.6 27B",
        provider = "Groq",
        maxOutputTokens = 16384,
    )

    private fun groqProvider(model: LLMModel) = OpenAIProvider(
        apiKey = "test-key",
        model = model,
        basePath = "https://api.groq.com/openai/v1",
    )

    private fun body(model: LLMModel, maxTokens: Int, level: ThinkingLevel = ThinkingLevel.OFF): JSONObject {
        return groqProvider(model).buildRequestBody(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
            systemPrompt = null,
            maxTokens = maxTokens,
            stream = false,
            temperature = null,
            imageParts = emptyList(),
            thinkingLevel = level,
        )
    }

    // ── Final clamp in buildRequestBody ──────────────────────────────

    @Test
    fun `oversized maxTokens is clamped to the model limit on the wire`() {
        val b = body(qwenGroq, maxTokens = 65536)
        assertEquals(16384, b.getInt("max_completion_tokens"))
    }

    @Test
    fun `maxTokens under the limit passes through untouched`() {
        val b = body(qwenGroq, maxTokens = 4096)
        assertEquals(4096, b.getInt("max_completion_tokens"))
    }

    @Test
    fun `maxTokens exactly at the limit passes through untouched`() {
        val b = body(qwenGroq, maxTokens = 16384)
        assertEquals(16384, b.getInt("max_completion_tokens"))
    }

    @Test
    fun `thinking budget stays strictly below the clamped max`() {
        // QwenDual derives thinking_budget from maxTokens; with the clamp the
        // budget math must see the clamped value, not the oversized input.
        val b = body(qwenGroq, maxTokens = 65536, level = ThinkingLevel.HIGH)
        val maxCompletion = b.getInt("max_completion_tokens")
        assertEquals(16384, maxCompletion)
        val budget = b.optInt("thinking_budget", -1)
        assertTrue("thinking_budget ($budget) must be < max_completion_tokens ($maxCompletion)", budget in 1 until maxCompletion)
    }

    // ── models.dev entry resolution ──────────────────────────────────

    private fun devEntry(output: Int?, effort: List<String>? = null) =
        ModelsDevApi.ModelDevEntry(
            id = "qwen/qwen3.6-27b",
            name = null,
            family = null,
            contextWindow = 131072,
            maxOutputTokens = output,
            reasoning = true,
            interleavedField = null,
            inputModalities = null,
            outputModalities = null,
            reasoningEffortValues = effort,
            releaseDate = null,
            outputCost = null,
        )

    private val fakeRegistry = mapOf(
        // Sorted first — the old scan preferred this wrong-provider entry.
        "aiand" to ModelsDevApi.ProviderEntry("aiand", null, null, mapOf("qwen/qwen3.6-27b" to devEntry(65536))),
        "groq" to ModelsDevApi.ProviderEntry("groq", null, null, mapOf("qwen/qwen3.6-27b" to devEntry(16384))),
    )

    private val customProviderModel = LLMModel("qwen/qwen3.6-27b", "Qwen3.6 27B", "Custom")

    @Test
    fun `provider hint resolves the serving provider's exact entry`() {
        val entry = ModelsDevApi.resolveDevEntry(customProviderModel, fakeRegistry, providerHint = "groq")
        assertNotNull(entry)
        assertEquals(16384, entry!!.maxOutputTokens)
    }

    @Test
    fun `fallback scan takes the minimum output limit across providers`() {
        // No hint and provider="Custom" (what /v1/models refresh produces):
        // aiand says 65536, groq says 16384 → the conservative 16384 wins.
        val entry = ModelsDevApi.resolveDevEntry(customProviderModel, fakeRegistry, providerHint = null)
        assertNotNull(entry)
        assertEquals(16384, entry!!.maxOutputTokens)
    }

    @Test
    fun `unknown hint falls back to the scan instead of failing`() {
        val entry = ModelsDevApi.resolveDevEntry(customProviderModel, fakeRegistry, providerHint = "nope")
        assertNotNull(entry)
        assertEquals(16384, entry!!.maxOutputTokens)
    }

    // ── 400 body matcher ─────────────────────────────────────────────

    private val groq400 = """{'message': "'max_completion_tokens' must be less than or equal to '16384', the maximum for this model.", 'type': 'invalid_request_error'}"""

    @Test
    fun `output-limit 400 body is recognized`() {
        assertTrue(isOutputLimitBody(groq400))
    }

    @Test
    fun `other 400s are not misclassified as output-limit`() {
        assertFalse(isOutputLimitBody("'messages.3': for 'role:assistant' the following must be satisfied"))
        assertFalse(isOutputLimitBody("The model 'x' does not exist"))
        assertFalse(isOutputLimitBody("max_tokens is required"))
    }

    @Test
    fun `output limit is extracted from the body`() {
        assertEquals(16384, extractOutputLimit(groq400))
    }

    @Test
    fun `missing limit extracts as zero`() {
        assertEquals(0, extractOutputLimit("max_completion_tokens must be less than or equal to the model maximum"))
    }

    // ── Friendly card ────────────────────────────────────────────────

    @Test
    fun `friendly text names model and provider, states the cap, hides raw JSON`() {
        val text = friendlyOutputLimitText("Qwen3.6 27B", "Groq", 16384)
        assertTrue(text.contains("Qwen3.6 27B"))
        assertTrue(text.contains("Groq"))
        assertTrue(text.contains("16384"))
        assertFalse(text.contains("max_completion_tokens"))
        assertFalse(text.contains("[400]"))
    }

    @Test
    fun `friendly text falls back gracefully on blank names and zero limit`() {
        val text = friendlyOutputLimitText("", "", 0)
        assertTrue(text.contains("this model"))
        assertTrue(text.contains("the provider"))
        assertFalse(text.contains("max 0"))
    }

    @Test
    fun `OutputLimitExceeded is not a ProviderError and is never retried or group-fallbacked`() {
        val err: LLMError = LLMError.OutputLimitExceeded(16384, "[400] ...")
        assertFalse(err is LLMError.ProviderError)
        assertFalse(err.isRetryable)
        assertFalse(err.isFallbackable)
        assertEquals("Output limit exceeded", err.fallbackReason)
    }

    @Test
    fun `error kind constant is stable`() {
        assertEquals("output_limit_exceeded", ERROR_KIND_OUTPUT_LIMIT)
    }

    // ── Catalog seeds carry true limits ──────────────────────────────

    @Test
    fun `Groq catalog declares true max output tokens`() {
        val byId = LLMModel.allGroq.associateBy { it.id }
        assertEquals(16384, byId["qwen/qwen3.8-27b"]?.maxOutputTokens)
        assertEquals(65536, byId["openai/gpt-oss-120b"]?.maxOutputTokens)
        assertEquals(65536, byId["openai/gpt-oss-20b"]?.maxOutputTokens)
    }

    @Test
    fun `Cerebras gpt-oss-120b declares its true max output tokens`() {
        val byId = LLMModel.allCerebras.associateBy { it.id }
        assertEquals(40960, byId["gpt-oss-120b"]?.maxOutputTokens)
    }
}
