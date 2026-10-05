package ai.unicto.unibot.ui.theme
import ai.unicto.unibot.ui.theme.UbColors

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
    val success = UbColors.success

    /** Warning / caution accent — iOS systemOrange. */
    val warning = UbColors.warning

    /** Error / destructive accent — iOS systemRed. */
    val error = UbColors.error

    /** Neutral secondary accent — iOS systemGray. */
    val systemGray = UbColors.systemGray

    /** Success on dark backgrounds (diff additions, dark charts). */
    val successDark = UbColors.successDark

    /** Warning on dark backgrounds (dark charts, dark badges). */
    val warningDark = UbColors.warningDark

    /** Error on dark backgrounds (diff deletions, dark charts). */
    val errorDark = UbColors.errorDark

    /**
     * Brand violet, theme-aware: #6D28D9 on light, #A78BFA (lifted for
     * contrast) on dark. Plain function (not Composable) so it stays
     * unit-testable and usable from non-Composable helpers that already
     * track `isDark` themselves.
     */
    fun brandViolet(isDark: Boolean): Color =
        UbColors.brandViolet(isDark)
}

/**
 * Brand violet for the current theme — drop-in replacement for the
 * repeated `UbColors.brandViolet(isDark)` pattern.
 */
val UbBrandViolet: Color
    @Composable
    @ReadOnlyComposable
    get() = UbColors.brandViolet(isSystemInDarkTheme())
