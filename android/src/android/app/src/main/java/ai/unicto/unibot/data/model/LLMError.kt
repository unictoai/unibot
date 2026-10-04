package ai.unicto.unibot.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")
    /**
     * [T-android-v124-413] HTTP 413 — the request (usually a long agent
     * history with thinking blocks + tool calls) exceeds the model's quota.
     * Kept as its own class (NOT a ProviderError) so the chat can render a
     * friendly card with recovery actions instead of the raw "[413]" text,
     * and so group-fallback never silently switches models on it — the user
     * picks "New chat" or "Change model" explicitly.
     */
    class RequestTooLarge(val detail: String = "") :
        LLMError(if (detail.isBlank()) "Request too large" else "Request too large: $detail")
    /**
     * [T-android-v125-model-filter] HTTP 404 where the body says the MODEL is
     * gone ("does not exist", "model_not_found", …) — e.g. a retired model id
     * like llama-3.3-70b-versatile. Kept as its own class (NOT a ProviderError)
     * so the chat renders a friendly card ("Change model" / "Refresh models")
     * instead of a bare Retry pill — retrying a dead model 404s again, and
     * group-fallback must not silently switch models on it either.
     */
    class ModelNotFound(val modelId: String = "", val detail: String = "") :
        LLMError(if (detail.isBlank()) "Model not available" else "Model not available: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited : LLMError("Rate limited — please try again later")
    class TransientError(val detail: String) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /** Should immediately fall back to the next model in the group — same model won't help. */
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is ProviderError -> "Provider error"
            is RequestTooLarge -> "Request too large"
            is ModelNotFound -> "Model not available"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}

/**
 * [T-android-v125-model-filter] True when an HTTP 404 body says the MODEL is
 * gone (as opposed to a wrong endpoint): "does not exist", "model_not_found",
 * "unknown model", or "not found" next to "model". A bare endpoint 404
 * ("Not found: /v1/...") must NOT match — that stays a ProviderError.
 */
internal fun isModelNotFoundBody(body: String): Boolean {
    val lower = body.lowercase()
    return lower.contains("does not exist") ||
        lower.contains("model_not_found") ||
        lower.contains("unknown model") ||
        (lower.contains("not found") && lower.contains("model"))
}

/**
 * [T-android-v125-model-filter] Best-effort model-id extraction from a 404
 * body: the first 'quoted' token, e.g. "The model 'llama-3.3-70b-versatile'
 * does not exist…". Empty string when nothing quoted is found.
 */
internal fun extractModelId(body: String): String {
    val match = Regex("'([^']{2,120})'").find(body)
    return match?.groupValues?.getOrNull(1).orEmpty()
}
