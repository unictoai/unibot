package ai.unicto.unibot.ui.chat

import ai.unicto.unibot.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.0 item 24 — quota state thresholds and the free-tier limit table. */
class QuotaTrackerTest {

    @Test
    fun `ok below 80 percent`() {
        assertEquals(QuotaState.OK, quotaStateFor(0, 100))
        assertEquals(QuotaState.OK, quotaStateFor(79, 100))
    }

    @Test
    fun `near limit at 80 percent`() {
        assertEquals(QuotaState.NEAR_LIMIT, quotaStateFor(80, 100))
        assertEquals(QuotaState.NEAR_LIMIT, quotaStateFor(99, 100))
        // Groq-scale values.
        assertEquals(QuotaState.NEAR_LIMIT, quotaStateFor(11_520, 14_400))
    }

    @Test
    fun `exhausted at the limit`() {
        assertEquals(QuotaState.EXHAUSTED, quotaStateFor(100, 100))
        assertEquals(QuotaState.EXHAUSTED, quotaStateFor(150, 100))
    }

    @Test
    fun `non-positive limit is ok`() {
        assertEquals(QuotaState.OK, quotaStateFor(10, 0))
        assertEquals(QuotaState.OK, quotaStateFor(10, -5))
    }

    @Test
    fun `tracked free tiers have documented limits`() {
        assertEquals(14_400, FreeTierLimits.dailyRequests(ProviderType.groq))
        assertEquals(1_500, FreeTierLimits.dailyRequests(ProviderType.gemini))
        assertEquals(200, FreeTierLimits.dailyRequests(ProviderType.openRouter))
    }

    @Test
    fun `paid-only providers are untracked`() {
        assertNull(FreeTierLimits.dailyRequests(ProviderType.openAI))
        assertNull(FreeTierLimits.dailyRequests(ProviderType.anthropic))
        assertNull(FreeTierLimits.dailyRequests(ProviderType.xAI))
        assertNull(FreeTierLimits.dailyRequests(ProviderType.deepSeek))
    }

    @Test
    fun `today key is a calendar date`() {
        assertTrue(todayKey().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}
