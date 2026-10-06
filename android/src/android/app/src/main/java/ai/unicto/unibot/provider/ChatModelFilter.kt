package ai.unicto.unibot.provider

import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.ModelEntry

/**
 * [T-android-v125-model-filter] Single source of truth for "is this a CHAT
 * model?" — used by every chat model picker (provider detail list, chat
 * model sheet, unified picker) and by the selection-validity check in
 * ChatViewModel.
 *
 * Why this exists: providers' live /v1/models endpoints list EVERYTHING.
 * Groq returns whisper (STT), orpheus (TTS), prompt-guard / safeguard
 * (moderation) next to chat models, and retired IDs can linger in the
 * listing. Showing those in a chat picker ends in 404s the user cannot fix
 * (the user's own words: "everything is mess"). Voice Services reads the
 * same modelEntries for its ASR/TTS rows, so the filter lives at the picker
 * layer, never in the repository: the stored data stays complete, chat UI
 * only ever shows chat models.
 */
object ChatModelFilter {
    /** Case-insensitive id substrings that mark a non-chat model. */
    private val NON_CHAT_PATTERNS = listOf(
        "whisper",
        "orpheus",
        "safeguard",
        "guard",
        "tts",
        "stt",
        "transcribe",
        "embedding",
        // [T-android-v128-filter-gaps] Embedding / rerank / OCR / moderation /
        // image-generation ids that providers list next to chat models.
        // NOTE: no bare "image" — "gpt-image-2" is a specially-routed chat
        // surface (Codex image-gen) and is carved out explicitly below.
        "embed",
        "rerank",
        "ocr",
        "moderat",
        "dall",
        "imagen",
        "flux",
        "sora",
        // v1.4.0 item 83 — audio / realtime voice models (gpt-4o-audio-preview,
        // gpt-realtime, …) are listed next to chat models by several providers
        // but cannot take a chat turn.
        "audio",
        "realtime",
    )

    /**
     * Model IDs a provider has retired, hard-excluded even if /v1/models
     * still lists them. The value documents the retirement date and the
     * replacement to pick instead.
     */
    val RETIRED_MODEL_IDS: Map<String, String> = mapOf(
        // Groq decommissioned both 2026-08-16, migrated to the gpt-oss family.
        "llama-3.3-70b-versatile" to "retired 2026-08-16 — use openai/gpt-oss-120b",
        "llama-3.1-8b-instant" to "retired 2026-08-16 — use openai/gpt-oss-20b",
        // Groq retired qwen3.6-27b ~2026-09-14, renamed to qwen3.8-27b
        // (confirmed: live /v1/models 2026-10-04 lists 3.8, not 3.6).
        "qwen/qwen3.6-27b" to "retired ~2026-09-14 — use qwen/qwen3.8-27b",
    )

    /**
     * Image-generation model that IS a valid chat-surface pick: Codex routes
     * it through the image_generation tool rather than the chat pipeline, so
     * it must survive the non-chat filter. Matched before [NON_CHAT_PATTERNS].
     */
    private fun isSpecialRoutedImageModel(id: String): Boolean =
        id.lowercase().contains("gpt-image-2")

    /** True when [id] may be offered as a chat model. */
    fun isChatModel(id: String): Boolean {
        val lower = id.lowercase()
        if (lower in RETIRED_MODEL_IDS) return false
        if (isSpecialRoutedImageModel(lower)) return true
        return NON_CHAT_PATTERNS.none { lower.contains(it) }
    }

    /** True when [id] is a known-retired model id (still listed upstream). */
    fun isRetired(id: String): Boolean = id.lowercase() in RETIRED_MODEL_IDS

    fun filterChatModels(models: List<LLMModel>): List<LLMModel> =
        models.filter { isChatModel(it.id) }

    fun filterEntries(entries: List<ModelEntry>): List<ModelEntry> =
        entries.filter { isChatModel(it.model.id) }

    /**
     * v1.4.0 item 83 — the default-model choke point. Anywhere the app must
     * pick a model WITHOUT asking the user (chat import, trace samples,
     * first-run fallbacks) goes through here so a TTS / embedding / retired
     * id can never become the default. Null when no entry is a chat model.
     */
    fun firstChatModelId(entries: List<ModelEntry>): String? =
        entries.firstOrNull { isChatModel(it.model.id) }?.model?.id
}
