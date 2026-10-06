package ai.unicto.unibot.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 item 72 — the "show once per version bump" rule.
 */
class WhatsNewStoreTest {

    @Test
    fun `same version does not show`() {
        assertFalse(WhatsNewStore.shouldShow("1.4.0", "1.4.0"))
    }

    @Test
    fun `bumped version shows`() {
        assertTrue(WhatsNewStore.shouldShow("1.4.0", "1.3.5"))
    }

    @Test
    fun `fresh install does not pop the dialog`() {
        // null = never recorded: the version is recorded silently so the
        // NEXT bump triggers.
        assertFalse(WhatsNewStore.shouldShow("1.4.0", null))
    }

    @Test
    fun `blank current version never shows`() {
        assertFalse(WhatsNewStore.shouldShow("", "1.3.5"))
        assertFalse(WhatsNewStore.shouldShow("   ", null))
    }
}
