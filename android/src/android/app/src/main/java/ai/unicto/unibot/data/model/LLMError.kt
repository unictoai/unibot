package ai.unicto.unibot.data.model

/**
 * v1.4.0 item 70 — never render a literal "null" (or a blank) inside a
 * user-facing error string. Exception messages are nullable AND often blank
 * (e.g. `IOException()` with no detail); interpolating them raw produced
 * "Unknown error: null" / "Network error: null" in the chat banners.
 * [readableMessage] is the single choke point every error class below uses.
 */
internal fun Throwable?.readableMessage(): String =
    this?.message?.takeIf { it.isNotBlank() } ?: "Unknown error"

/**
 * Appends the cause's detail only when it actually says something —
 * otherwise the empty string, so prefixed errors degrade to the bare
 * label ("Network error") instead of "Network error: null".
 */
internal fun Throwable?.detailSuffix(): String =
    this?.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * v1.4.0 item 79 — numeric HTTP status when this error came from an HTTP
     * response; null for transport / decoding / cancellation errors that
     * never saw a status line. Numeric end-to-end: the provider health
     * dashboard (v1.4.0 item 23) and the auto-retry gate read this instead
     * of matching 5xx text out of the detail string.
     */
    open val httpStatus: Int? = null

    /** True for 5xx statuses — numeric, never text-matched. */
    val isServerError: Boolean get() = httpStatus != null && httpStatus in 500..599

    /** True for 4xx statuses. */
    val isClientError: Boolean get() = httpStatus != null && httpStatus in 400..499

    /**
     * v1.4.0 item 82 — short human label for the auto-retry countdown, e.g.
     * "Connection hiccup". Never leaks raw provider text.
     */
    val friendlyRetryLabel: String
        get() = when {
            this is NetworkError -> "Connection hiccup"
            this is TransientError || isServerError -> "Server hiccup"
            this is RateLimited -> "Rate limited"
            else -> "Something went wrong"
        }

    /**
     * v1.4.0 item 82 — the full countdown line, e.g.
     * "Connection hiccup — retrying (1/3)…".
     */
    fun retryCountdownText(attempt: Int, totalAttempts: Int): String =
        "$friendlyRetryLabel — retrying ($attempt/$totalAttempts)…"

    class InvalidApiKey(val detail: String = "", override val httpStatus: Int? = null) : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    /**
     * v1.4.0 item 15 — the provider's OAuth sign-in expired (refresh token
     * revoked/expired, or no usable access token). Kept as its own class
     * (NOT InvalidApiKey) so the chat renders "Sign in again" instead of
     * "Invalid API key" — the fix is re-authentication, not a new key.
     */
    class OAuthExpired(val detail: String = "", override val httpStatus: Int? = null) :
        LLMError(if (detail.isBlank()) "Sign-in expired" else "Sign-in expired: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error${cause.detailSuffix()}", cause)
    class ProviderError(val detail: String, override val httpStatus: Int? = null) : LLMError("Provider error: $detail")
    /**
     * [T-android-v124-413] HTTP 413 — the request (usually a long agent
     * history with thinking blocks + tool calls) exceeds the model's quota.
     * Kept as its own class (NOT a ProviderError) so the chat can render a
     * friendly card with recovery actions instead of the raw "[413]" text,
     * and so group-fallback never silently switches models on it — the user
     * picks "New chat" or "Change model" explicitly.
     */
    class RequestTooLarge(val detail: String = "", override val httpStatus: Int? = null) :
        LLMError(if (detail.isBlank()) "Request too large" else "Request too large: $detail")
    /**
     * [T-android-v125-model-filter] HTTP 404 where the body says the MODEL is
     * gone ("does not exist", "model_not_found", …) — e.g. a retired model id
     * like llama-3.3-70b-versatile. Kept as its own class (NOT a ProviderError)
     * so the chat renders a friendly card ("Change model" / "Refresh models")
     * instead of a bare Retry pill — retrying a dead model 404s again, and
     * group-fallback must not silently switch models on it either.
     */
    class ModelNotFound(val modelId: String = "", val detail: String = "", override val httpStatus: Int? = null) :
        LLMError(if (detail.isBlank()) "Model not available" else "Model not available: $detail")
    /**
     * [T-android-v126-maxtokens] HTTP 400 where the body says the requested
     * max_tokens/max_completion_tokens exceeds the model's output cap (e.g.
     * Groq: "'max_completion_tokens' must be less than or equal to '16384'").
     * Kept as its own class (NOT a ProviderError) so the chat renders a
     * friendly card instead of raw JSON — retrying the same oversized value
     * would 400 again, so the card offers Change model / Refresh models.
     * With the request-builder clamp this should be unreachable; if it ever
     * fires, the model's declared limit itself is wrong.
     */
    class OutputLimitExceeded(val limit: Int = 0, val detail: String = "", override val httpStatus: Int? = null) :
        LLMError(if (detail.isBlank()) "Output limit exceeded" else "Output limit exceeded: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error${cause.detailSuffix()}", cause)
    class RateLimited(override val httpStatus: Int? = null) : LLMError("Rate limited — please try again later")
    class TransientError(val detail: String, override val httpStatus: Int? = null) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error${cause.detailSuffix()}", cause)

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
            is OAuthExpired -> "Sign-in expired"
            is ProviderError -> "Provider error"
            is RequestTooLarge -> "Request too large"
            is ModelNotFound -> "Model not available"
            is OutputLimitExceeded -> "Output limit exceeded"
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

/**
 * [T-android-v126-maxtokens] True when an HTTP 400 body says the requested
 * output-token budget exceeds the model's cap: "max_completion_tokens /
 * max_tokens / max_output_tokens … must be less than or equal to 'N'".
 * Other 400s (bad fields, bad history shape) stay ProviderError.
 */
internal fun isOutputLimitBody(body: String): Boolean {
    val lower = body.lowercase()
    val namesField = lower.contains("max_completion_tokens") ||
        lower.contains("max_output_tokens") ||
        (lower.contains("max_tokens") && !lower.contains("max_completion_tokens"))
    return namesField && (lower.contains("less than or equal to") || lower.contains("must be <="))
}

/**
 * [T-android-v126-maxtokens] Best-effort extraction of the provider's stated
 * cap from an output-limit 400 body: the number after "less than or equal
 * to", e.g. '16384' in "'max_completion_tokens' must be less than or equal
 * to '16384'". 0 when not found.
 */
internal fun extractOutputLimit(body: String): Int {
    val match = Regex("less than or equal to '?(\\d+)").find(body.lowercase())
    return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
