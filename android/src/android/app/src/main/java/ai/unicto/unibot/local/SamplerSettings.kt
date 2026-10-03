package ai.unicto.unibot.local

import android.content.Context
import android.util.Log

/**
 * [v1.2 Batch F] Per-model sampling parameters for on-device generation.
 *
 * Persisted per [LlamaModel.id] in SharedPreferences — never uploaded, never
 * leaves the phone. [LocalLlamaBackend.generate] reads these on every turn
 * and passes them into [LlamaCpp.nativeGenerate], so the Sampler settings
 * screen's sliders genuinely steer generation.
 *
 * Defaults mirror common llama.cpp guidance: temperature falls back to the
 * model's own [LlamaModel.defaultTemperature].
 */
data class SamplerSettings(
    /** 0 = greedy/deterministic … 2 = very creative. */
    val temperature: Float = 0.7f,
    /** Nucleus sampling cutoff, 0.1 … 1.0 (1.0 = disabled). */
    val topP: Float = 0.9f,
    /** Only sample from the top-K tokens; 0 = disabled. */
    val topK: Int = 40,
    /** > 1.0 discourages repeating the same tokens. */
    val repeatPenalty: Float = 1.1f,
    /** Hard cap on reply length, in tokens. */
    val maxTokens: Int = 1024,
)

/** One-tap sampler preset (see the Sampler settings screen's preset cards). */
data class SamplerPreset(
    val id: String,
    val settings: SamplerSettings,
)

/**
 * [v1.2 Batch F] The three chat presets. Creative / Precise / Balanced set
 * the same per-model prefs the sliders edit, so one tap fully rewires
 * sampling for that model.
 */
object SamplerPresets {
    val Creative = SamplerPreset(
        id = "creative",
        settings = SamplerSettings(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.0f,
            maxTokens = 1024,
        ),
    )
    val Balanced = SamplerPreset(
        id = "balanced",
        settings = SamplerSettings(
            temperature = 0.7f,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.1f,
            maxTokens = 1024,
        ),
    )
    val Precise = SamplerPreset(
        id = "precise",
        settings = SamplerSettings(
            temperature = 0.2f,
            topP = 0.8f,
            topK = 20,
            repeatPenalty = 1.15f,
            maxTokens = 512,
        ),
    )

    val all: List<SamplerPreset> = listOf(Creative, Balanced, Precise)

    fun byId(id: String?): SamplerPreset? = all.firstOrNull { it.id == id }
}

/**
 * [v1.2 Batch F] Persists [SamplerSettings] per model. Pure on-device
 * SharedPreferences — the sampler values never leave the phone.
 */
object SamplerSettingsStore {
    private const val TAG = "SamplerSettings"
    private const val PREFS = "llama_sampler_prefs"

    private const val DEFAULT_TOP_P = 0.9f
    private const val DEFAULT_TOP_K = 40
    private const val DEFAULT_REPEAT_PENALTY = 1.1f
    private const val DEFAULT_MAX_TOKENS = 1024

    fun load(context: Context, model: LlamaModel): SamplerSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = model.id
        return SamplerSettings(
            temperature = p.getFloat("${id}_temperature", model.defaultTemperature),
            topP = p.getFloat("${id}_top_p", DEFAULT_TOP_P),
            topK = p.getInt("${id}_top_k", DEFAULT_TOP_K),
            repeatPenalty = p.getFloat("${id}_repeat_penalty", DEFAULT_REPEAT_PENALTY),
            maxTokens = p.getInt("${id}_max_tokens", DEFAULT_MAX_TOKENS),
        )
    }

    fun save(context: Context, model: LlamaModel, settings: SamplerSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("${model.id}_temperature", settings.temperature)
            .putFloat("${model.id}_top_p", settings.topP)
            .putInt("${model.id}_top_k", settings.topK)
            .putFloat("${model.id}_repeat_penalty", settings.repeatPenalty)
            .putInt("${model.id}_max_tokens", settings.maxTokens)
            .apply()
        Log.i(TAG, "sampler saved for ${model.id}")
    }

    /** Back to the model defaults (clears the per-model overrides). */
    fun reset(context: Context, model: LlamaModel) {
        val id = model.id
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("${id}_temperature")
            .remove("${id}_top_p")
            .remove("${id}_top_k")
            .remove("${id}_repeat_penalty")
            .remove("${id}_max_tokens")
            .apply()
        Log.i(TAG, "sampler reset for ${model.id}")
    }
}
