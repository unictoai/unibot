package ai.unicto.unibot.ui.chat

import org.json.JSONObject

/**
 * v1.4.0 item 16 — pure token-accounting helpers. The recorded `token_usage`
 * JSON payloads are MEASURED values (written per turn by the providers), so
 * they need no clamp; anything derived from stored model LIMITS must go
 * through [ai.unicto.unibot.provider.LLMProvider.effectiveMaxOutputTokens]
 * (see [ChatViewModel.effectiveCurrentMaxOutputTokens]).
 */

/**
 * Parse a `token_usage` JSON payload into (input, output) tokens. Null when
 * the payload is absent, blank, malformed, or records zero usage.
 */
internal fun parseTokenUsage(json: String?): Pair<Long, Long>? {
    if (json.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(json)
        val input = obj.optLong("inputTokens", 0L)
        val output = obj.optLong("outputTokens", 0L)
        if (input == 0L && output == 0L) null else input to output
    } catch (_: Exception) {
        null
    }
}
