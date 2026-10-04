package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.ThinkingLevel
import ai.unicto.unibot.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-v1.2.3-groq-cerebras-reasoning-400] Root cause of the user's Groq 400
 * (`'messages.N': for 'role:assistant' the following must be satisfied`).
 *
 * Groq's strict schema validator rejects ANY unknown assistant-message field
 * with 400 (`property 'reasoning_content' is unsupported`); Cerebras likewise
 * 400s (it expects `reasoning` or nothing). Since "Deep Thinking" was on,
 * every assistant turn carried the DeepSeek-style `reasoning_content` echo
 * and every second agent call died. The code already suppressed the echo for
 * Mistral (`forbidReasoningField = isMistral`) but never for these two.
 *
 * `isGroq`/`isCerebras` are `basePath.contains("groq.com"/"cerebras.ai")`,
 * so pointing MockWebServer at a path containing those literals exercises the
 * real production predicate without keys or network.
 */
class GroqCerebrasReasoningFieldTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A reasoning-capable model, so the echo gate would otherwise be ON. */
    private val reasoningModel = LLMModel(
        id = "openai/gpt-oss-120b",
        displayName = "GPT OSS 120B",
        provider = "CPA",
        supportsReasoning = true,
    )

    /** History with a prior assistant turn that captured reasoning — the 400 trigger. */
    private fun historyWithReasoning(): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "first question"),
        LLMMessage(
            LLMMessage.Role.ASSISTANT,
            "first answer",
        ).copy(reasoningContent = "some captured chain of thought"),
        LLMMessage(LLMMessage.Role.USER, "second question"),
    )

    private fun capture(basePath: String): JSONObject {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(ok),
            )
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = reasoningModel,
            basePath = basePath,
        )
        runCatching { runBlocking {
            provider.sendMessageClamped(
                messages = historyWithReasoning(),
                systemPrompt = null,
                maxTokens = 1024,
                temperature = null,
                imageParts = emptyList(),
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.MEDIUM,
            )
        } }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    private fun anyMessageHasReasoning(body: JSONObject): Boolean {
        val msgs = body.getJSONArray("messages")
        for (i in 0 until msgs.length()) {
            if (msgs.getJSONObject(i).has("reasoning_content")) return true
        }
        return false
    }

    @Test
    fun `groq endpoint never sends reasoning_content`() {
        val body = capture(server.url("/groq.com/openai/v1").toString().trimEnd('/'))
        assertFalse(
            "reasoning_content must not be sent to Groq (400 on unknown assistant fields): $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `cerebras endpoint never sends reasoning_content`() {
        val body = capture(server.url("/cerebras.ai/v1").toString().trimEnd('/'))
        assertFalse(
            "reasoning_content must not be sent to Cerebras (400 on unknown assistant fields): $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `groq detection is case-insensitive`() {
        val body = capture(server.url("/API.GROQ.COM/openai/v1").toString().trimEnd('/'))
        assertFalse(
            "uppercase groq.com must still suppress reasoning_content: $body",
            anyMessageHasReasoning(body),
        )
    }

    @Test
    fun `deepseek endpoint still echoes reasoning_content`() {
        // Negative control: DeepSeek REQUIRES the field's presence on
        // multi-turn history (400 if missing), so suppression must stay
        // scoped to the rejecting vendors.
        val body = capture(server.url("/api.deepseek.com/v1").toString().trimEnd('/'))
        assertTrue(
            "reasoning_content should still be echoed for DeepSeek: $body",
            anyMessageHasReasoning(body),
        )
    }
}
