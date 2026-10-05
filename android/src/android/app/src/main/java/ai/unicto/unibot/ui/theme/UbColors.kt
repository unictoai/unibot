package ai.unicto.unibot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * App-wide semantic status/accent colors (v1.3.5 UI optimization pass).
 *
 * These are the iOS-system status colors the app already used as raw
 * `Color(0x…)` literals in ~130 places (tool chip accents, copy-success
 * tints, warning banners, error states, category colors). They are accent
 * colors, not surfaces, so they intentionally do NOT flip with the theme —
 * the same value reads correctly on both light and dark backgrounds, which
 * is exactly how the call sites already used them.
 *
 * The `*Dark` variants are the elevated-contrast versions for content drawn
 * on dark backgrounds (code diffs, charts); use them only there.
 *
 * Brand violet is theme-dependent (light #6D28D9 / dark #A78BFA), so it
 * resolves via [brandViolet]; the `UbBrandViolet` Composable reads the
 * current theme automatically.
 */
object UbColors {
    /** Success / positive accent — iOS systemGreen. */
    val success: Color = Color(0xFF34C759)

    /** Warning / caution accent — iOS systemOrange. */
    val warning: Color = Color(0xFFFF9500)

    /** Error / destructive accent — iOS systemRed. */
    val error: Color = Color(0xFFFF3B30)

    /** Neutral secondary accent — iOS systemGray. */
    val systemGray: Color = Color(0xFF8E8E93)

    /** Success on dark backgrounds (diff additions, dark charts). */
    val successDark: Color = Color(0xFF30D158)

    /** Warning on dark backgrounds (dark charts, dark badges). */
    val warningDark: Color = Color(0xFFFF9F0A)

    /** Error on dark backgrounds (diff deletions, dark charts). */
    val errorDark: Color = Color(0xFFFF453A)

    /**
     * Brand violet, theme-aware: #6D28D9 on light, #A78BFA (lifted for
     * contrast) on dark. Plain function (not Composable) so it stays
     * unit-testable and usable from non-Composable helpers that already
     * track `isDark` themselves.
     */
    fun brandViolet(isDark: Boolean): Color =
        if (isDark) Color(0xFFA78BFA) else Color(0xFF6D28D9)
}

/**
 * Brand violet for the current theme — drop-in replacement for the
 * repeated `UbColors.brandViolet(isDark)` pattern.
 */
val UbBrandViolet: Color
    @Composable
    @ReadOnlyComposable
    get() = UbColors.brandViolet(isSystemInDarkTheme())
