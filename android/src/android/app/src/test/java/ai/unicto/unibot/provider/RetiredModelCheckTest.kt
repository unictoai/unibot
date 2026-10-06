package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for RetiredModelCheck.flagRetired — the pure comparison of seed
 * ids against a models.dev registry. Registry entries are built by hand so
 * no network or catalog parsing is involved.
 */
class RetiredModelCheckTest {

    private fun entry(vararg ids: String) =
        ModelsDevApi.ProviderEntry(
            id = "test",
            name = "Test",
            api = null,
            models = ids.associateWith {
                ModelsDevApi.ModelDevEntry(
                    id = it,
                    name = null,
                    family = null,
                    contextWindow = null,
                    maxOutputTokens = null,
                    reasoning = null,
                    interleavedField = null,
                    inputModalities = null,
                    outputModalities = null,
                    reasoningEffortValues = null,
                    declaresNoEffortTiers = false,
                    releaseDate = null,
                    outputCost = null,
                )
            },
        )

    private fun seed(id: String, provider: String) = LLMModel(id, id, provider)

    @Test
    fun `ids present in the catalog are not flagged`() {
        val flagged = RetiredModelCheck.flagRetired(
            listOf(seed("gpt-4o", "OpenAI"), seed("claude-opus-4-1", "Anthropic")),
            mapOf("openai" to entry("gpt-4o", "gpt-4o-mini"), "anthropic" to entry("claude-opus-4-1")),
        )
        assertTrue(flagged.isEmpty())
    }

    @Test
    fun `ids missing from the catalog are flagged`() {
        val flagged = RetiredModelCheck.flagRetired(
            listOf(seed("gpt-9", "OpenAI")),
            mapOf("openai" to entry("gpt-4o")),
        )
        assertEquals(1, flagged.size)
        assertEquals("gpt-9", flagged[0].id)
        assertEquals("OpenAI", flagged[0].providerLabel)
    }

    @Test
    fun `matching is case insensitive`() {
        val flagged = RetiredModelCheck.flagRetired(
            listOf(seed("GPT-4o", "OpenAI")),
            mapOf("openai" to entry("gpt-4o")),
        )
        assertTrue(flagged.isEmpty())
    }

    @Test
    fun `openrouter vendor slash ids match bare tails`() {
        val flagged = RetiredModelCheck.flagRetired(
            listOf(seed("openai/gpt-4o", "OpenRouter")),
            mapOf("openrouter" to entry("gpt-4o")),
        )
        assertTrue(flagged.isEmpty())
    }

    @Test
    fun `providers without catalog coverage are never flagged`() {
        val flagged = RetiredModelCheck.flagRetired(
            listOf(seed("moonshot-v1", "Kimi"), seed("gh-model", "GitHub Models")),
            mapOf("openai" to entry("gpt-4o")),
        )
        assertTrue(flagged.isEmpty())
    }

    @Test
    fun `empty registry flags nothing`() {
        val flagged = RetiredModelCheck.flagRetired(
            listOf(seed("gpt-9", "OpenAI")),
            emptyMap(),
        )
        assertTrue(flagged.isEmpty())
    }
}
