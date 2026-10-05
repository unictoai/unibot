package ai.unicto.unibot.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [UbColors] — the v1.3.5 UI optimization pass
 * centralized ~130 raw `Color(0x…)` literals into these tokens. These tests
 * pin the token VALUES so a future edit can't silently shift the app's
 * status/accent colors (every migrated call site was a value-identical
 * replacement, so the tokens must keep matching the legacy hexes).
 */
class UbColorsTest {

    private fun argb(color: Color): String = "%08X".format(color.toArgb())

    @Test
    fun success_matchesLegacySystemGreen() {
        assertEquals("FF34C759", argb(UbColors.success))
    }

    @Test
    fun warning_matchesLegacySystemOrange() {
        assertEquals("FFFF9500", argb(UbColors.warning))
    }

    @Test
    fun error_matchesLegacySystemRed() {
        assertEquals("FFFF3B30", argb(UbColors.error))
    }

    @Test
    fun systemGray_matchesLegacySystemGray() {
        assertEquals("FF8E8E93", argb(UbColors.systemGray))
    }

    @Test
    fun darkVariants_matchLegacyElevatedHexes() {
        assertEquals("FF30D158", argb(UbColors.successDark))
        assertEquals("FFFF9F0A", argb(UbColors.warningDark))
        assertEquals("FFFF453A", argb(UbColors.errorDark))
    }

    @Test
    fun brandViolet_resolvesThemeAware() {
        assertEquals("FF6D28D9", argb(UbColors.brandViolet(isDark = false)))
        assertEquals("FFA78BFA", argb(UbColors.brandViolet(isDark = true)))
    }

    @Test
    fun brandViolet_isDistinctPerTheme() {
        // A regression here would collapse the light/dark brand accent into
        // one value — the exact bug the theme-aware accessor exists to prevent.
        assert(UbColors.brandViolet(isDark = false) != UbColors.brandViolet(isDark = true))
    }
}
