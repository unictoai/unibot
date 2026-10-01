package ai.unicto.unibot.cloud

import ai.unicto.unibot.data.model.ProviderType
import ai.unicto.unibot.data.model.VoiceProviderTemplate

/**
 * "Use your own key": the vendors the app can pre-fill the provider form for, so that the
 * way on from a spent allowance is one tap plus a paste. Only the public endpoints are
 * written here; the key is the person's own.
 *
 * The form's pre-fill hook is the voice-template slot (name, protocol, base URL, `/v1`), so a
 * preset is expressed as one — with no models of its own, the vendor's list is fetched live.
 */
object OwnKeyPresets {
    /** Alibaba Cloud Bailian (阿里云百炼): OpenAI-compatible, one key for chat, pictures and video. */
    const val BAILIAN = "bailian"

    /** Where a key is made (the console opens on the API-key page). */
    const val BAILIAN_KEY_URL = "https://bailian.console.aliyun.com/?apiKey=1"

    fun template(preset: String?): VoiceProviderTemplate? = when (preset?.trim()?.lowercase()) {
        BAILIAN -> VoiceProviderTemplate(
            id = BAILIAN,
            name = "阿里云百炼 Bailian",
            providerType = ProviderType.openAI,
            baseURL = "https://dashscope.aliyuncs.com/compatible-mode",
            appendV1 = true,
            capability = VoiceProviderTemplate.Capability.BOTH,
            baseURLMarkers = emptyList(),
            mockModels = emptyList(),
        )
        else -> null
    }
}
