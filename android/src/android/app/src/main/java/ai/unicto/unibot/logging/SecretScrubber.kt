package ai.unicto.unibot.logging

/**
 * v1.4.0 item 88 — privacy-safe crash/log export.
 *
 * Crash reports are opt-in (the user picks Share / Save / Dismiss in the
 * crash dialog; Settings → Logs → share is equally explicit — nothing is
 * ever sent automatically). This scrubber is the second half of the
 * guarantee: whatever the user DOES choose to send never contains API
 * keys, even if a key leaked into a stack trace, an HTTP error body, or
 * a log line on disk.
 *
 * Applied at the export boundary — [ai.unicto.unibot.crash.CrashFrequencyDetector]'s
 * zip bundling and `LogManagementScreen.shareLogFile` — not at write time,
 * so on-device diagnostics keep their full fidelity and only the bytes
 * that leave the phone are scrubbed.
 *
 * Pure Kotlin, zero Android imports: safe to call from the crash
 * reporter's process and from unit tests.
 */
object SecretScrubber {

    /**
     * Files larger than this are zipped/shared unscrubbed rather than
     * risking an OOM reading them into memory. 8 MB is far above any
     * crash report or daily log the app produces.
     */
    const val MAX_SCRUB_BYTES: Long = 8L * 1024 * 1024

    /** JSON credential fields: "api_key": "<value>" → "api_key": "***". */
    private val JSON_CREDENTIAL_FIELD = Regex(
        "\"(api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret" +
            "|private[_-]?key|authorization|secret[_-]?key|session[_-]?key)\"\\s*:\\s*\"([^\"\\\\]{4,})\"",
        RegexOption.IGNORE_CASE,
    )

    /** Authorization: Bearer <token> / "authorization":"Bearer <token>". */
    private val BEARER_TOKEN = Regex(
        "(?i)\\bbearer\\s+([A-Za-z0-9\\-._~+/=]{12,})",
    )

    /** Provider token shapes (OpenAI, Anthropic, xAI, Groq, Google). */
    private val TOKEN_SHAPES = listOf(
        Regex("\\bsk-ant-[A-Za-z0-9\\-_]{16,}"),
        Regex("\\bsk-(?:proj-)?[A-Za-z0-9\\-_]{16,}"),
        Regex("\\bxai-[A-Za-z0-9\\-_]{16,}"),
        Regex("\\bgsk_[A-Za-z0-9\\-_]{16,}"),
        Regex("\\bAIza[A-Za-z0-9\\-_]{16,}"),
    )

    /** URL query params: ?api_key=<secret> / &token=<secret>. */
    private val QUERY_KEY_PARAM = Regex(
        "(?i)([?&](?:api[_-]?key|key|token|access[_-]?token)=)([^&\\s\"']{6,})",
    )

    /**
     * Returns [text] with key-like values replaced by "***".
     * Idempotent; never throws — on any internal failure the input is
     * returned unchanged (a scrubber must not break the export).
     */
    fun scrub(text: String): String {
        return try {
            var out = text
            out = JSON_CREDENTIAL_FIELD.replace(out) { m ->
                // Rebuild the match with only the value masked so the
                // field name stays visible for diagnostics.
                val full = m.value
                val value = m.groupValues[2]
                full.replace(value, "***")
            }
            out = BEARER_TOKEN.replace(out, "Bearer ***")
            for (shape in TOKEN_SHAPES) {
                out = shape.replace(out, "***")
            }
            out = QUERY_KEY_PARAM.replace(out) { m -> m.groupValues[1] + "***" }
            out
        } catch (_: Throwable) {
            text
        }
    }

    /** True when [text] contains anything [scrub] would mask. */
    fun containsSecrets(text: String): Boolean {
        return try {
            JSON_CREDENTIAL_FIELD.containsMatchIn(text) ||
                BEARER_TOKEN.containsMatchIn(text) ||
                TOKEN_SHAPES.any { it.containsMatchIn(text) } ||
                QUERY_KEY_PARAM.containsMatchIn(text)
        } catch (_: Throwable) {
            false
        }
    }
}
