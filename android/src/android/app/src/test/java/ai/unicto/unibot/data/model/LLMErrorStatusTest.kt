package ai.unicto.unibot.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 79 — structured HTTP status tracking.
 *
 * Numeric status codes travel end-to-end on LLMError (set by each
 * provider's mapHttpError) instead of the old text-regex 5xx detection
 * (`detail.contains(Regex("[5][0-9]{2}"))`). These tests pin:
 *  - httpStatus is carried on every HTTP-mappable error class,
 *  - isServerError is numeric-only: a detail that merely MENTIONS "500"
 *    must not count, and a 503 with clean text must,
 *  - pre-existing behavior is unchanged (defaults null, messages intact).
 *
 * v1.4.0 item 82 — friendly auto-retry labels.
 */
class LLMErrorStatusTest {

    // ─── httpStatus carriage ──────────────────────────────────────────

    @Test
    fun `httpStatus defaults to null`() {
        assertNull(LLMError.ProviderError("boom").httpStatus)
        assertNull(LLMError.TransientError("boom").httpStatus)
        assertNull(LLMError.RateLimited().httpStatus)
        assertNull(LLMError.InvalidApiKey().httpStatus)
        assertNull(LLMError.NetworkError(java.io.IOException("x")).httpStatus)
        assertNull(LLMError.Unknown(null).httpStatus)
    }

    @Test
    fun `httpStatus is carried through every HTTP-mappable class`() {
        assertEquals(503, LLMError.ProviderError("d", httpStatus = 503).httpStatus)
        assertEquals(503, LLMError.TransientError("d", httpStatus = 503).httpStatus)
        assertEquals(429, LLMError.RateLimited(httpStatus = 429).httpStatus)
        assertEquals(401, LLMError.InvalidApiKey(httpStatus = 401).httpStatus)
        assertEquals(413, LLMError.RequestTooLarge("d", httpStatus = 413).httpStatus)
        assertEquals(404, LLMError.ModelNotFound("m", "d", httpStatus = 404).httpStatus)
        assertEquals(400, LLMError.OutputLimitExceeded(1, "d", httpStatus = 400).httpStatus)
    }

    @Test
    fun `messages are unchanged when status is attached`() {
        assertEquals("Provider error: boom", LLMError.ProviderError("boom", httpStatus = 500).message)
        assertEquals("Rate limited — please try again later", LLMError.RateLimited(httpStatus = 429).message)
    }

    // ─── isServerError: numeric, never text ───────────────────────────

    @Test
    fun `isServerError is true for 5xx regardless of detail text`() {
        // The old regex needed "500" IN the text; the numeric path must not.
        val e = LLMError.ProviderError("upstream exploded, no code in text", httpStatus = 503)
        assertTrue(e.isServerError)
        assertTrue(LLMError.TransientError("x", httpStatus = 500).isServerError)
        assertTrue(LLMError.TransientError("x", httpStatus = 599).isServerError)
    }

    @Test
    fun `isServerError is false when text mentions 500 but status is absent or 4xx`() {
        // Regression: the old regex matched ANY "500" in the detail —
        // e.g. "limit is 500 tokens" — and mistook it for a server error.
        assertFalse(LLMError.ProviderError("limit is 500 tokens per request").isServerError)
        assertFalse(LLMError.ProviderError("error 500 in upstream payload", httpStatus = 400).isServerError)
        assertFalse(LLMError.ProviderError("x", httpStatus = 200).isServerError)
        assertFalse(LLMError.RateLimited(httpStatus = 429).isServerError)
        assertFalse(LLMError.NetworkError(java.io.IOException("x")).isServerError)
    }

    @Test
    fun `isClientError covers 4xx only`() {
        assertTrue(LLMError.RateLimited(httpStatus = 429).isClientError)
        assertTrue(LLMError.InvalidApiKey(httpStatus = 401).isClientError)
        assertFalse(LLMError.ProviderError("x", httpStatus = 500).isClientError)
        assertFalse(LLMError.ProviderError("x").isClientError)
    }

    // ─── item 82: friendly retry labels ───────────────────────────────

    @Test
    fun `friendlyRetryLabel never leaks provider text`() {
        assertEquals(
            "Connection hiccup",
            LLMError.NetworkError(java.io.IOException("raw: socket timeout after 30000ms")).friendlyRetryLabel,
        )
        assertEquals(
            "Server hiccup",
            LLMError.TransientError("raw: [503] upstream exploded").friendlyRetryLabel,
        )
        assertEquals(
            "Server hiccup",
            LLMError.ProviderError("raw json blob", httpStatus = 502).friendlyRetryLabel,
        )
        assertEquals("Rate limited", LLMError.RateLimited().friendlyRetryLabel)
    }

    @Test
    fun `retryCountdownText formats the countdown line`() {
        val e = LLMError.NetworkError(java.io.IOException("x"))
        assertEquals("Connection hiccup — retrying (1/3)…", e.retryCountdownText(1, 3))
        assertEquals("Connection hiccup — retrying (3/3)…", e.retryCountdownText(3, 3))
    }

    // ─── pre-existing behavior preserved ─────────────────────────────

    @Test
    fun `isRetryable and isFallbackable are unchanged`() {
        assertTrue(LLMError.NetworkError(java.io.IOException("x")).isRetryable)
        assertTrue(LLMError.TransientError("x", httpStatus = 503).isRetryable)
        assertFalse(LLMError.ProviderError("x", httpStatus = 503).isRetryable)
        assertTrue(LLMError.RateLimited(httpStatus = 429).isFallbackable)
    }
}
