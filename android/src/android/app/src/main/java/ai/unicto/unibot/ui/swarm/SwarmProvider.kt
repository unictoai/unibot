package ai.unicto.unibot.ui.swarm

import android.content.Context
import ai.unicto.unibot.data.repository.ProviderRepository
import ai.unicto.unibot.provider.LLMProvider
import ai.unicto.unibot.provider.ProviderFactory

/**
 * [v1.3.0-swarm] Resolves the LLM provider a swarm run bills to.
 *
 * The swarm runs on the user's own key, like everything else in unibot:
 * the first agent-loop model entry (the models the user enabled for
 * agentic work), falling back to the first text-capable, non-hidden
 * entry. Returns null when nothing usable exists — the destination then
 * shows [SwarmNoProviderScreen] instead of crashing the engine's
 * ViewModel factory (which requires a provider).
 */
internal fun resolveSwarmLlmProvider(
    providerRepository: ProviderRepository,
    context: Context,
): LLMProvider? {
    val config = providerRepository.config.value
    val loopIds = config.agentLoopModelEntryIds.toSet()
    val textEntries = config.modelEntries.filter { !it.isHidden && it.model.isTextOutput }
    val entry = textEntries.firstOrNull { it.id in loopIds }
        ?: textEntries.firstOrNull()
        ?: return null
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
    // usableApiKey (not loadApiKey): a keyless local endpoint is valid.
    val apiKey = providerRepository.usableApiKey(instance) ?: return null
    return ProviderFactory.create(instance, apiKey, entry.model, context)
}
