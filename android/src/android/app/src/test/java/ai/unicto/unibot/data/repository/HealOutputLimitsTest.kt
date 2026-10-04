package ai.unicto.unibot.data.repository

import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.ModelEntry
import ai.unicto.unibot.data.model.ModelOverrides
import ai.unicto.unibot.data.model.ProviderConfig
import ai.unicto.unibot.data.model.ProviderCredential
import ai.unicto.unibot.data.model.ProviderInstance
import ai.unicto.unibot.data.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v127-heal-output-limits] One-time migration that heals saved
 * model entries poisoned by the pre-v1.2.6 enrichment bug (Groq's
 * qwen3.6-27b saved with aiand's 65536 instead of Groq's 16384 → HTTP
 * 400/413). Installing v1.2.7 must fix the data with no "Refresh models"
 * tap from the user.
 */
class HealOutputLimitsTest {

    private fun groqInstance() = ProviderInstance(
        id = "groq-1",
        label = "Groq",
        providerType = ProviderType.groq,
        credentialType = ProviderCredential.apiKey,
    )

    private fun openAIInstance() = ProviderInstance(
        id = "openai-1",
        label = "OpenAI",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    private fun poisonedEntry(instanceId: String = "groq-1") = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(
            id = "qwen/qwen3.6-27b",
            displayName = "Qwen3.6 27B",
            provider = "Custom",
            maxOutputTokens = 65536, // poisoned by the pre-v1.2.6 scan
        ),
    )

    /** Fake enrichment mimicking the FIXED lookup: Groq's real 16384. */
    private val fixedEnrich: (LLMModel, String?) -> LLMModel = { model, hint ->
        if (hint == "groq" && model.id == "qwen/qwen3.6-27b") model.copy(maxOutputTokens = 16384)
        else model
    }

    private inner class Harness(config: ProviderConfig) {
        var healedFlag = false
        var saves = 0
        var enrichCalls = 0
        val liveConfig = config

        fun run(enrich: (LLMModel, String?) -> LLMModel = { m, h ->
            enrichCalls++
            fixedEnrich(m, h)
        }): Int = healPoisonedOutputLimitsPass(
            isAlreadyHealed = { healedFlag },
            markHealed = { healedFlag = true },
            snapshot = { liveConfig },
            save = { saves++ },
            enrich = enrich,
        )
    }

    // ── Core: poisoned 65536 → 16384 ──────────────────────────────────

    @Test
    fun `poisoned entry is healed to the serving provider limit`() {
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(poisonedEntry()),
        ))
        assertEquals(1, h.run())
        assertTrue(h.healedFlag)
        assertEquals(1, h.saves)
        assertEquals(16384, h.liveConfig.modelEntries.single().baseModel.maxOutputTokens)
        // Nothing else about the entry changed.
        assertEquals("qwen/qwen3.6-27b", h.liveConfig.modelEntries.single().baseModel.id)
        assertEquals("Qwen3.6 27B", h.liveConfig.modelEntries.single().baseModel.displayName)
    }

    @Test
    fun `migration runs once — second run is a no-op`() {
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(poisonedEntry()),
        ))
        assertEquals(1, h.run())
        assertEquals(0, h.run())
        assertEquals(1, h.saves)
        assertEquals(1, h.enrichCalls)
    }

    // ── Safety: never touch user intent ───────────────────────────────

    @Test
    fun `user-modified custom entry is never touched`() {
        val custom = poisonedEntry().copy(isCustom = true)
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(custom),
        ))
        assertEquals(0, h.run())
        assertEquals(0, h.saves)
        assertEquals(65536, h.liveConfig.modelEntries.single().baseModel.maxOutputTokens)
    }

    @Test
    fun `entry with user overrides is never touched`() {
        val overridden = poisonedEntry().copy(overrides = ModelOverrides(displayName = "My Qwen"))
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(overridden),
        ))
        assertEquals(0, h.run())
        assertEquals(0, h.saves)
    }

    @Test
    fun `entry already at the correct limit is not rewritten`() {
        val healthy = poisonedEntry().copy(
            baseModel = poisonedEntry().baseModel.copy(maxOutputTokens = 16384),
        )
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(healthy),
        ))
        assertEquals(0, h.run())
        assertEquals(0, h.saves) // no pointless write
    }

    @Test
    fun `non-free-tier instance entries are out of scope`() {
        val h = Harness(ProviderConfig(
            instances = mutableListOf(openAIInstance()),
            modelEntries = mutableListOf(poisonedEntry(instanceId = "openai-1")),
        ))
        assertEquals(0, h.run())
        assertEquals(0, h.saves)
        assertEquals(0, h.enrichCalls)
    }

    @Test
    fun `enrichment returning null limit heals nothing`() {
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(poisonedEntry()),
        ))
        assertEquals(0, h.run(enrich = { m, _ -> m.copy(maxOutputTokens = null) }))
        assertEquals(0, h.saves)
    }

    @Test
    fun `list order and size are preserved`() {
        val other = ModelEntry(
            providerInstanceId = "groq-1",
            baseModel = LLMModel("openai/gpt-oss-20b", "GPT OSS 20B", "Custom", maxOutputTokens = 65536),
        )
        val h = Harness(ProviderConfig(
            instances = mutableListOf(groqInstance()),
            modelEntries = mutableListOf(other, poisonedEntry()),
        ))
        assertEquals(1, h.run())
        assertEquals(2, h.liveConfig.modelEntries.size)
        assertEquals("openai/gpt-oss-20b", h.liveConfig.modelEntries[0].baseModel.id)
        assertEquals("qwen/qwen3.6-27b", h.liveConfig.modelEntries[1].baseModel.id)
    }

    // ── [T-android-v129-custom-instance-hint] Second heal pass ──────────

    /** Groq added as a *custom* OpenAI-type instance — the user's setup. */
    private fun customGroqInstance() = ProviderInstance(
        id = "custom-1",
        label = "My Groq",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://api.groq.com/openai/v1",
    )

    @Test
    fun `v127 resolver skips custom instances, v129 host-aware resolver covers them`() {
        val custom = customGroqInstance()
        assertNull(defaultHealHintResolver(custom))
        assertEquals("groq", hostAwareHealHintResolver(custom))
        // Built-in free-tier instances keep working under both resolvers.
        assertEquals("groq", defaultHealHintResolver(groqInstance()))
        assertEquals("groq", hostAwareHealHintResolver(groqInstance()))
        // Official types were never at risk: no hint under either policy.
        assertNull(defaultHealHintResolver(openAIInstance()))
        assertNull(hostAwareHealHintResolver(openAIInstance()))
    }

    @Test
    fun `v129 pass heals custom-instance entries exactly once`() {
        var healedFlag = false
        var saves = 0
        val config = ProviderConfig(
            instances = mutableListOf(customGroqInstance()),
            modelEntries = mutableListOf(poisonedEntry("custom-1")),
        )
        fun run(): Int = healPoisonedOutputLimitsPass(
            isAlreadyHealed = { healedFlag },
            markHealed = { healedFlag = true },
            snapshot = { config },
            save = { saves++ },
            enrich = fixedEnrich,
            resolveHint = ::hostAwareHealHintResolver,
        )
        assertEquals(1, run())
        assertTrue(healedFlag)
        assertEquals(1, saves)
        assertEquals(16384, config.modelEntries.first().baseModel.maxOutputTokens)
        // Second run is a no-op via the guard.
        assertEquals(0, run())
        assertEquals(1, saves)
    }

    @Test
    fun `v129 pass still skips user-modified entries`() {
        val custom = poisonedEntry("custom-1").copy(
            baseModel = poisonedEntry("custom-1").baseModel.copy(maxOutputTokens = 65536),
        )
        val userModified = custom.copy(
            // Mark user-modified via overrides (see ModelEntry.isUserModified).
            overrides = ai.unicto.unibot.data.model.ModelOverrides(maxOutputTokens = 65536),
        )
        var healedFlag = false
        val config = ProviderConfig(
            instances = mutableListOf(customGroqInstance()),
            modelEntries = mutableListOf(userModified),
        )
        val healed = healPoisonedOutputLimitsPass(
            isAlreadyHealed = { healedFlag },
            markHealed = { healedFlag = true },
            snapshot = { config },
            save = { },
            enrich = fixedEnrich,
            resolveHint = ::hostAwareHealHintResolver,
        )
        assertEquals(0, healed)
        assertEquals(65536, config.modelEntries.first().baseModel.maxOutputTokens)
    }
}
