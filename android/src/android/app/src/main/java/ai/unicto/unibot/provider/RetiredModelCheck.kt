package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap verification of the bundled seed model lineup (backlog item 100).
 *
 * The static lists in [LLMModel] (allAnthropic, allGemini, allOpenAI, …) are
 * the fallback models the app offers before a provider's live list arrives.
 * Providers retire ids silently, and a retired id picked from the list fails
 * in confusing ways. This compares every seed id against the live
 * models.dev catalog ([ModelsDevApi.registrySnapshot]) and flags ids the
 * catalog no longer carries.
 *
 * A flagged id is "probably retired", not certain: the catalog lags for
 * niche providers, and ids missing from the catalog are reported — never
 * auto-removed. New code only; wire implementations untouched.
 */
data class RetiredModelFlag(
    val id: String,
    val displayName: String,
    val providerLabel: String,
)

data class RetiredModelReport(
    /** Seed ids checked. */
    val checked: Int,
    /** Ids absent from the live catalog. */
    val flagged: List<RetiredModelFlag>,
    /** True when the catalog could not be loaded at all (offline). */
    val catalogUnavailable: Boolean,
)

object RetiredModelCheck {
    /**
     * Seed provider label → models.dev registry key. Null = no public
     * catalog coverage (GitHub Models, Kimi Code); those seeds are skipped,
     * never flagged.
     */
    internal val seedLabelToRegistryKey: Map<String, String?> = mapOf(
        "Anthropic" to "anthropic",
        "Google" to "google",
        "OpenAI" to "openai",
        "OpenRouter" to "openrouter",
        "xAI" to "xai",
        "Kimi" to null,
        "Groq" to "groq",
        "Cerebras" to "cerebras",
        "Mistral" to "mistral",
        "GitHub Models" to null,
        "SambaNova" to null,
        "NVIDIA NIM" to "nvidia",
        "DeepSeek" to "deepseek",
        "Z.AI" to "zai",
        "Nebius" to "nebius",
        "Chutes" to "chutes",
    )

    /**
     * Pure comparison: which [seeds] are absent from the live [registry].
     * Ids match case-insensitively, and OpenRouter-style "vendor/id" tails
     * match the bare id too (the catalog lists both forms).
     */
    fun flagRetired(
        seeds: List<LLMModel>,
        registry: Map<String, ModelsDevApi.ProviderEntry>,
    ): List<RetiredModelFlag> {
        if (registry.isEmpty()) return emptyList()
        // registry key (lowercased) → set of lowercased model ids it carries.
        val catalogIds: Map<String, Set<String>> = registry.mapValues { (_, entry) ->
            entry.models.keys.map { it.lowercase() }.toSet()
        }
        return seeds.mapNotNull { seed ->
            val key = seedLabelToRegistryKey[seed.provider] ?: return@mapNotNull null
            val ids = catalogIds[key] ?: return@mapNotNull null
            val id = seed.id.lowercase()
            val tail = id.substringAfterLast('/')
            val present = id in ids || tail in ids ||
                ids.any { it.substringAfterLast('/') == tail }
            if (present) null
            else RetiredModelFlag(seed.id, seed.displayName, seed.provider)
        }
    }

    /** Loads the catalog snapshot and flags the bundled lineup. Never throws. */
    suspend fun run(): RetiredModelReport = withContext(Dispatchers.IO) {
        val registry = try {
            ModelsDevApi.registrySnapshot()
        } catch (_: Exception) {
            emptyMap()
        }
        val seeds = LLMModel.allModels
        if (registry.isEmpty()) {
            return@withContext RetiredModelReport(seeds.size, emptyList(), catalogUnavailable = true)
        }
        RetiredModelReport(seeds.size, flagRetired(seeds, registry), catalogUnavailable = false)
    }
}
