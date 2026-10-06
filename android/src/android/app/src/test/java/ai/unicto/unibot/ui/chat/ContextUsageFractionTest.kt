package ai.unicto.unibot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.4.0 item 76 — the context-usage glow's fraction math.
 *
 * The estimate is deliberately cheap: (history chars + draft chars) / 4.
 * These tests pin the pure function so the composable stays a thin view.
 */
class ContextUsageFractionTest {

    @Test
    fun `empty conversation and empty draft is zero`() {
        assertEquals(0f, ContextUsage.fraction(0, 0, 128_000))
    }

    @Test
    fun `unknown window hides the glow`() {
        assertEquals(0f, ContextUsage.fraction(10_000, 500, 0))
        assertEquals(0f, ContextUsage.fraction(10_000, 500, -1))
    }

    @Test
    fun `fraction scales with usage`() {
        // 4_000 chars ≈ 1_000 tokens of a 100_000 window → 1%.
        assertEquals(0.01f, ContextUsage.fraction(4_000, 0, 100_000), 0.0001f)
        // Draft counts too: 4_000 history + 4_000 draft → 2%.
        assertEquals(0.02f, ContextUsage.fraction(4_000, 4_000, 100_000), 0.0001f)
    }

    @Test
    fun `fraction clamps at full`() {
        assertEquals(1f, ContextUsage.fraction(1_000_000, 0, 128_000))
    }

    @Test
    fun `negative inputs clamp to zero`() {
        assertEquals(0f, ContextUsage.fraction(-100, -50, 128_000))
    }
}
