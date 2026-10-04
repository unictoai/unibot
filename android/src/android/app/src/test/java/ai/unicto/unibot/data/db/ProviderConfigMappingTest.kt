package ai.unicto.unibot.data.db

import ai.unicto.unibot.data.model.FallbackStrategy
import ai.unicto.unibot.data.model.ProviderCredential
import ai.unicto.unibot.data.model.ProviderType
import ai.unicto.unibot.data.model.RoutingStrategy
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * [T-android-v128-enum-hardening] DB enum values must never throw on load.
 * A downgrade or cross-platform restore can carry enum names this build does
 * not know; the old `valueOf()` calls threw, wiping all providers/models/
 * groups from the UI and killing chat. Unknown values now fall back to safe
 * defaults (mirroring the earlier ThinkingLevel/ImageEndpointMode hardening).
 */
class ProviderConfigMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun snapshotWith(
        providerType: String,
        credentialType: String,
        strategy: String,
        fallbackStrategy: String,
    ) = ProviderConfigSnapshot(
        instances = listOf(
            ProviderInstanceEntity(
                id = "inst-1",
                label = "Test",
                providerType = providerType,
                credentialType = credentialType,
                createdAt = 1L,
            )
        ),
        entries = emptyList(),
        groups = listOf(
            ProviderModelGroupEntity(
                id = "grp-1",
                name = "Default",
                strategy = strategy,
                fallbackStrategy = fallbackStrategy,
                memberEntryIdsJson = "[]",
            )
        ),
        loopIds = emptyList(),
        meta = emptyList(),
    )

    @Test
    fun `unknown provider type falls back to unsupported instead of throwing`() {
        val config = snapshotWith(
            providerType = "quantumAI", // a newer build's type
            credentialType = "apiKey",
            strategy = "fallback",
            fallbackStrategy = "default",
        ).toProviderConfig(json)

        assertEquals(1, config.instances.size)
        assertEquals(ProviderType.unsupported, config.instances[0].providerType)
    }

    @Test
    fun `unknown credential type falls back to apiKey instead of throwing`() {
        val config = snapshotWith(
            providerType = "openAI",
            credentialType = "biometric",
            strategy = "fallback",
            fallbackStrategy = "default",
        ).toProviderConfig(json)

        assertEquals(ProviderCredential.apiKey, config.instances[0].credentialType)
    }

    @Test
    fun `unknown routing strategies fall back to defaults instead of throwing`() {
        val config = snapshotWith(
            providerType = "openAI",
            credentialType = "apiKey",
            strategy = "roundRobin",
            fallbackStrategy = "never",
        ).toProviderConfig(json)

        assertEquals(1, config.modelGroups.size)
        assertEquals(RoutingStrategy.fallback, config.modelGroups[0].strategy)
        assertEquals(FallbackStrategy.default, config.modelGroups[0].fallbackStrategy)
    }

    @Test
    fun `all enums unknown at once still loads`() {
        val config = snapshotWith(
            providerType = "nope",
            credentialType = "nope",
            strategy = "nope",
            fallbackStrategy = "nope",
        ).toProviderConfig(json)

        assertEquals(ProviderType.unsupported, config.instances[0].providerType)
        assertEquals(ProviderCredential.apiKey, config.instances[0].credentialType)
        assertEquals(RoutingStrategy.fallback, config.modelGroups[0].strategy)
        assertEquals(FallbackStrategy.default, config.modelGroups[0].fallbackStrategy)
    }

    @Test
    fun `known values still round-trip unchanged`() {
        val config = snapshotWith(
            providerType = "groq",
            credentialType = "oauth",
            strategy = "loadBalance",
            fallbackStrategy = "always",
        ).toProviderConfig(json)

        assertEquals(ProviderType.groq, config.instances[0].providerType)
        assertEquals(ProviderCredential.oauth, config.instances[0].credentialType)
        assertEquals(RoutingStrategy.loadBalance, config.modelGroups[0].strategy)
        assertEquals(FallbackStrategy.always, config.modelGroups[0].fallbackStrategy)
    }
}
