package ai.unicto.unibot.swarm

import ai.unicto.unibot.ui.swarm.PARALLEL_WORKERS_ENABLED
import ai.unicto.unibot.ui.swarm.formatBudgetIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v1.5 swarm bugfix stream — UI-side helpers. Pure functions, no Compose,
 * unit-testable without Robolectric (same contract as SwarmUiHelpers).
 */
class SwarmUiHelpersTest {

    @Test
    fun `parallel workers stay hidden until a real batch executor exists`() {
        // Bug 3: the engine runs workers sequentially, so the UI must not
        // promise parallel workers.
        assertFalse(PARALLEL_WORKERS_ENABLED)
    }

    @Test
    fun `budget indicator shows usage against the budget`() {
        // Bug 5: compact muted "used / budget" indicator, one decimal.
        assertEquals("9.4k / 150k tokens", formatBudgetIndicator(9_400, 150_000))
        assertEquals("850 / 50k tokens", formatBudgetIndicator(850, 50_000))
        assertEquals("1.2M / 500k tokens", formatBudgetIndicator(1_250_000, 500_000))
    }

    @Test
    fun `budget indicator omits the budget when unlimited`() {
        assertEquals("9.4k tokens", formatBudgetIndicator(9_400, 0))
    }

    @Test
    fun `token budget default matches the settings default`() {
        assertEquals(150_000, SwarmLaunchOptions().tokenBudget)
        assertEquals(150_000, SwarmPrefs().tokenBudget)
    }
}
