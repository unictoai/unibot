package ai.unicto.unibot.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v1.4.0 item 70 — "Unknown error: null" must never reach the user.
 * Exception messages are nullable and often blank; interpolating them raw
 * produced "Unknown error: null" / "Network error: null" in chat banners.
 */
class LLMErrorNullSafetyTest {

    @Test
    fun `Unknown with null cause renders bare label`() {
        assertEquals("Unknown error", LLMError.Unknown(null).message)
    }

    @Test
    fun `Unknown with null-message cause renders bare label`() {
        assertEquals("Unknown error", LLMError.Unknown(RuntimeException()).message)
    }

    @Test
    fun `Unknown with blank-message cause renders bare label`() {
        assertEquals("Unknown error", LLMError.Unknown(RuntimeException("   ")).message)
    }

    @Test
    fun `Unknown with real cause keeps the detail`() {
        assertEquals(
            "Unknown error: boom",
            LLMError.Unknown(RuntimeException("boom")).message,
        )
    }

    @Test
    fun `NetworkError with null-message cause renders bare label`() {
        assertEquals("Network error", LLMError.NetworkError(RuntimeException()).message)
    }

    @Test
    fun `NetworkError keeps real detail`() {
        assertEquals(
            "Network error: timeout",
            LLMError.NetworkError(RuntimeException("timeout")).message,
        )
    }

    @Test
    fun `DecodingError with null-message cause renders bare label`() {
        assertEquals("Decoding error", LLMError.DecodingError(RuntimeException()).message)
    }

    @Test
    fun `no LLMError message ever contains the literal null`() {
        val errors = listOf<LLMError>(
            LLMError.Unknown(null),
            LLMError.Unknown(RuntimeException()),
            LLMError.NetworkError(RuntimeException()),
            LLMError.DecodingError(RuntimeException()),
            LLMError.InvalidApiKey(),
            LLMError.RequestTooLarge(),
            LLMError.ModelNotFound(),
            LLMError.OutputLimitExceeded(),
            LLMError.RateLimited(),
            LLMError.TransientError("x"),
            LLMError.Cancelled,
        )
        errors.forEach { error ->
            val msg = error.message.orEmpty()
            assertFalse(
                "message renders a literal null: \"$msg\"",
                msg.contains("null", ignoreCase = true) && !msg.contains("nullable", ignoreCase = true),
            )
        }
    }

    @Test
    fun `readableMessage falls back on null and blank`() {
        val nullThrowable: Throwable? = null
        assertEquals("Unknown error", nullThrowable.readableMessage())
        assertEquals("Unknown error", RuntimeException().readableMessage())
        assertEquals("Unknown error", RuntimeException("  ").readableMessage())
        assertEquals("boom", RuntimeException("boom").readableMessage())
    }
}
