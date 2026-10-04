package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMError
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.extractModelId
import ai.unicto.unibot.data.model.isModelNotFoundBody
import ai.unicto.unibot.ui.chat.ERROR_KIND_MODEL_NOT_FOUND
import ai.unicto.unibot.ui.chat.friendlyModelNotFoundText
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v125-model-filter] Chat model picker hygiene + dead-model 404
 * handling:
 *  - TTS/STT/guard/moderation/embedding ids are never offered as chat models
 *    (case-insensitive), whether they come from seeds or live /v1/models,
 *  - known-retired ids are hard-excluded even when still listed upstream,
 *  - an HTTP 404 whose body says the model is gone maps to
 *    LLMError.ModelNotFound (not ProviderError — never a bare Retry pill,
 *    never a silent group fallback),
 *  - the 404 card text names the model + provider and hides the raw code.
 */
class ChatModelFilterTest {

    // ---------- isChatModel ----------

    @Test
    fun `non-chat modalities are excluded, case-insensitive`() {
        val nonChat = listOf(
            "whisper-large-v3-turbo",            // Groq STT
            "WHISPER-LARGE-V3",                  // uppercase
            "orpheus-english",                   // Canopy Labs TTS
            "Orpheus-Arabic",
            "meta-llama/llama-prompt-guard-2-86m", // guard
            "meta-llama/llama-prompt-guard-2-22m",
            "openai/gpt-oss-safeguard-20b",      // safeguard
            "tts-1", "openai/tts-1-hd",           // tts
            "stt-whisper-small",                  // stt
            "transcribe-v2",                      // transcribe
            "text-embedding-3-small",             // embedding
            "nomic-embed-text-v1",
        )
        for (id in nonChat) {
            assertFalse("expected non-chat model to be filtered: $id", ChatModelFilter.isChatModel(id))
        }
    }

    @Test
    fun `retired ids are hard-excluded even when still listed upstream`() {
        for (id in listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")) {
            assertTrue(ChatModelFilter.isRetired(id))
            assertFalse(ChatModelFilter.isChatModel(id))
            assertFalse(ChatModelFilter.isChatModel(id.uppercase()))
        }
        assertTrue(ChatModelFilter.RETIRED_MODEL_IDS["llama-3.3-70b-versatile"]!!.contains("2026-08-16"))
    }

    @Test
    fun `legit chat models pass`() {
        val chat = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            // qwen/qwen3.6-27b retired ~2026-09-14 (see RETIRED_MODEL_IDS);
            // its replacement qwen/qwen3.8-27b is the legit entry now.
            "qwen/qwen3.8-27b",
            "deepseek-chat",
            "moonshotai/kimi-k2.5",
            "z-ai/glm-4.7",
        )
        for (id in chat) {
            assertTrue("expected chat model to pass: $id", ChatModelFilter.isChatModel(id))
            assertFalse(ChatModelFilter.isRetired(id))
        }
    }

    @Test
    fun `filterChatModels keeps only chat models`() {
        val models = listOf(
            LLMModel("openai/gpt-oss-120b", "GPT OSS 120B", "Groq"),
            LLMModel("whisper-large-v3-turbo", "Whisper", "Groq"),
            LLMModel("llama-3.3-70b-versatile", "Llama 3.3", "Groq"),
            LLMModel("deepseek-chat", "DeepSeek", "DeepSeek"),
        )
        val kept = ChatModelFilter.filterChatModels(models).map { it.id }
        assertEquals(listOf("openai/gpt-oss-120b", "deepseek-chat"), kept)
    }

    // ---------- 404 body classification ----------

    @Test
    fun `groq model-gone 404 body is classified as ModelNotFound`() {
        val body = """{"error":{"message":"The model 'llama-3.3-70b-versatile' does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}"""
        assertTrue(isModelNotFoundBody(body))
        assertEquals("llama-3.3-70b-versatile", extractModelId(body))
    }

    @Test
    fun `bare endpoint 404 is NOT classified as model-gone`() {
        assertFalse(isModelNotFoundBody("Not found: /v1/chat/completions"))
        assertFalse(isModelNotFoundBody("""{"error":"resource not found"}"""))
    }

    @Test
    fun `ModelNotFound is not retryable and not fallbackable`() {
        val err = LLMError.ModelNotFound("llama-3.3-70b-versatile", "detail")
        assertFalse(err is LLMError.ProviderError)
        assertFalse(err.isFallbackable)
        assertFalse(err.isRetryable)
        assertEquals("Model not available", err.fallbackReason)
        assertEquals("llama-3.3-70b-versatile", err.modelId)
    }

    // ---------- friendly 404 card ----------

    @Test
    fun `friendly text names model and provider and hides the raw code`() {
        val text = friendlyModelNotFoundText("llama-3.3-70b-versatile", "Groq")
        assertTrue(text.contains("llama-3.3-70b-versatile"))
        assertTrue(text.contains("Groq"))
        assertFalse(text.contains("[404]"))
        assertFalse(text.contains("404"))
    }

    @Test
    fun `friendly text falls back gracefully on blank names`() {
        val text = friendlyModelNotFoundText("", "")
        assertTrue(text.contains("this model"))
        assertTrue(text.contains("the provider"))
    }

    @Test
    fun `error kind constant is stable`() {
        assertEquals("model_not_found", ERROR_KIND_MODEL_NOT_FOUND)
    }

    // ---------- v128 filter gaps ----------

    @Test
    fun `embedding rerank ocr moderation image-gen ids are excluded`() {
        val nonChat = listOf(
            "cohere-embed-v3-multilingual",       // embed
            "text-embedding-ada-002",             // embed
            "llama-3.2-nv-rerankqa-1b-v2",        // rerank
            "mistral-ocr-latest",                 // ocr
            "text-moderation-latest",             // moderat
            "omni-moderation-latest",
            "dall-e-3",                           // dall
            "imagen-4.0-generate-001",            // imagen
            "flux-1-schnell",                     // flux
            "sora-2",                             // sora
        )
        for (id in nonChat) {
            assertFalse("expected non-chat model to be filtered: $id", ChatModelFilter.isChatModel(id))
        }
    }

    @Test
    fun `gpt-image-2 survives the filter via explicit carve-out`() {
        // Specially routed through the Codex image_generation tool — it must
        // remain selectable even though image-gen patterns got stricter.
        assertTrue(ChatModelFilter.isChatModel("gpt-image-2"))
        assertTrue(ChatModelFilter.isChatModel("openai/gpt-image-2"))
    }

    @Test
    fun `retired qwen3_6-27b is excluded`() {
        assertFalse(ChatModelFilter.isChatModel("qwen/qwen3.6-27b"))
        assertTrue(ChatModelFilter.isRetired("qwen/qwen3.6-27b"))
        // Its replacement stays selectable.
        assertTrue(ChatModelFilter.isChatModel("qwen/qwen3.8-27b"))
    }

    @Test
    fun `ordinary chat models still pass`() {
        val chat = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3.8-27b",
            "allam-2-7b",
            "gpt-5.5",
            "claude-opus-4-6",
        )
        for (id in chat) {
            assertTrue("expected chat model to pass the filter: $id", ChatModelFilter.isChatModel(id))
        }
    }
}
