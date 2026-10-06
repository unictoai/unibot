package ai.unicto.unibot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 13 — every mapped HTTP status gets a plain-language card with
 * exactly one fix action; the raw code never leaks into the body text.
 */
class ChatErrorCardsTest {

    @Test
    fun `all item-13 codes have cards`() {
        for (code in listOf(400, 402, 408, 409, 410, 422, 451)) {
            val card = httpStatusCard(code, "gpt-x", "Groq")
            assertEquals(code, card.code)
            assertTrue("code $code: headline blank", card.headline.isNotBlank())
            assertTrue("code $code: body blank", card.body.isNotBlank())
            // The numeric code is not primary text — it stays in long-press copy only.
            assertFalse("code $code: raw code leaked into body", card.body.contains(code.toString()))
        }
    }

    @Test
    fun `402 offers provider settings, no retry`() {
        val card = httpStatusCard(402, "m", "Groq")
        assertEquals(HttpStatusFix.PROVIDER_SETTINGS, card.fix)
        assertFalse(card.allowRetry)
    }

    @Test
    fun `408 and 409 keep an honest retry`() {
        assertTrue(httpStatusCard(408, "m", "p").allowRetry)
        assertTrue(httpStatusCard(409, "m", "p").allowRetry)
        assertEquals(HttpStatusFix.RETRY, httpStatusCard(408, "m", "p").fix)
    }

    @Test
    fun `410 422 400 offer change model without retry`() {
        for (code in listOf(400, 410, 422)) {
            val card = httpStatusCard(code, "m", "p")
            assertEquals(HttpStatusFix.CHANGE_MODEL, card.fix)
            assertFalse("code $code should not retry", card.allowRetry)
        }
    }

    @Test
    fun `451 is explanation-only`() {
        val card = httpStatusCard(451, "m", "p")
        assertEquals(HttpStatusFix.NONE, card.fix)
        assertTrue(card.fixLabel.isEmpty())
        assertFalse(card.allowRetry)
    }

    @Test
    fun `blank names degrade gracefully`() {
        val card = httpStatusCard(402, "", "")
        assertTrue(card.body.contains("the provider"))
    }

    @Test
    fun `kind mapping round-trips`() {
        assertEquals("http_402", errorKindForHttpStatus(402))
        assertTrue(errorKindIsHttpStatus("http_402"))
        assertFalse(errorKindIsHttpStatus("http_500"))
        assertFalse(errorKindIsHttpStatus("request_too_large"))
        assertFalse(errorKindIsHttpStatus(null))
    }

    @Test
    fun `invalid-key and oauth copy never suggest retry`() {
        assertFalse(friendlyInvalidKeyText("Groq").contains("try again", ignoreCase = true))
        assertTrue(friendlyOAuthExpiredText("Google").contains("Sign in again"))
    }
}
