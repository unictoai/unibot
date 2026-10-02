package ai.unicto.unibot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight // unibot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand accent: unibot violet.
//   light  #6D28D9
//   dark   #A78BFA (lifted for contrast on near-black surfaces)
//
// The violet containers/neutrals below are iOS-grouped values (page #F2F2F7 /
// card white on light; page #000 / card #1C1C1E on dark). The Material3
// `surfaceContainer*` slots are overridden to the neutral card colors so cards
// never pick up a violet tint from the primary color.
private val VioletPrimary = Color(0xFF6D28D9)
private val VioletOnPrimary = Color(0xFFFFFFFF)
private val VioletPrimaryContainer = Color(0xFFF0E6FF)
private val VioletOnPrimaryContainer = Color(0xFF3B0764)
private val VioletSecondary = Color(0xFF5B4F6E)
private val VioletOnSecondary = Color(0xFFFFFFFF)
private val VioletSecondaryContainer = Color(0xFFE9DEF8)
private val VioletOnSecondaryContainer = Color(0xFF1E1B2E)
private val VioletTertiary = Color(0xFF46617A)
private val VioletOnTertiary = Color(0xFFFFFFFF)
private val VioletTertiaryContainer = Color(0xFFCDE5FF)
private val VioletOnTertiaryContainer = Color(0xFF001D32)
private val VioletBackground = Color(0xFFF2F2F7)
private val VioletOnBackground = Color(0xFF1C1C1E)
private val VioletSurface = Color(0xFFF2F2F7)
private val VioletOnSurface = Color(0xFF1C1C1E)
private val VioletSurfaceVariant = Color(0xFFE5E5EA)
private val VioletOnSurfaceVariant = Color(0xFF3C3C43)
private val VioletOutline = Color(0xFF6E6E73)

private val VioletDarkPrimary = Color(0xFFA78BFA)
private val VioletDarkOnPrimary = Color(0xFF2E1065)
private val VioletDarkPrimaryContainer = Color(0xFF2E1A4A)
private val VioletDarkOnPrimaryContainer = Color(0xFFF0E6FF)
private val VioletDarkSecondary = Color(0xFFC4B5DA)
private val VioletDarkOnSecondary = Color(0xFF2A2340)
private val VioletDarkSecondaryContainer = Color(0xFF3B3359)
private val VioletDarkOnSecondaryContainer = Color(0xFFE9DEF8)
private val VioletDarkBackground = Color(0xFF000000)
private val VioletDarkOnBackground = Color(0xFFE5E5EA)
private val VioletDarkSurface = Color(0xFF000000)
private val VioletDarkOnSurface = Color(0xFFE5E5EA)
private val VioletDarkSurfaceVariant = Color(0xFF3C3C43)
private val VioletDarkOnSurfaceVariant = Color(0xFFC7C7CC)
private val VioletDarkOutline = Color(0xFF8E8E93)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// violet primary tint.
// Light: page = #F2F2F7 gray, card = white
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFF2F2F7)
private val NeutralGroupedCard = Color(0xFFFFFFFF)
private val NeutralGroupedCardElevated = Color(0xFFF7F7FA)
private val NeutralOutline = Color(0xFFD1D1D6)

private val NeutralDarkGroupedBg = Color(0xFF000000)
private val NeutralDarkGroupedCard = Color(0xFF1C1C1E)
private val NeutralDarkGroupedCardElevated = Color(0xFF2C2C2E)
private val NeutralDarkOutline = Color(0xFF38383A)

private val LightColorScheme = lightColorScheme(
    primary = VioletPrimary,
    onPrimary = VioletOnPrimary,
    primaryContainer = VioletPrimaryContainer,
    onPrimaryContainer = VioletOnPrimaryContainer,
    secondary = VioletSecondary,
    onSecondary = VioletOnSecondary,
    secondaryContainer = VioletSecondaryContainer,
    onSecondaryContainer = VioletOnSecondaryContainer,
    tertiary = VioletTertiary,
    onTertiary = VioletOnTertiary,
    tertiaryContainer = VioletTertiaryContainer,
    onTertiaryContainer = VioletOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = VioletOnBackground,
    surface = NeutralGroupedBg,
    onSurface = VioletOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = VioletOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = VioletDarkPrimary,
    onPrimary = VioletDarkOnPrimary,
    primaryContainer = VioletDarkPrimaryContainer,
    onPrimaryContainer = VioletDarkOnPrimaryContainer,
    secondary = VioletDarkSecondary,
    onSecondary = VioletDarkOnSecondary,
    secondaryContainer = VioletDarkSecondaryContainer,
    onSecondaryContainer = VioletDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = VioletDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = VioletDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = VioletDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun unibotFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val UnibotShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun UnibotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = scaledTypography(fontScale)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = UnibotShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(LocalChatPalette provides chatPalette, content = content)
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

// unibot: Muse's type, on the system face. Material's scale carries tracking
// (0.1–0.5sp) and small labels (11–12sp) that read as "Android"; Muse sets
// everything at zero tracking, runs body at 16 and its captions at 13, and
// weights titles SemiBold. The face stays the device's default so the app
// matches Muse on the same phone (MiSans, Roboto, …) and Chinese and Latin
// never come from two fonts.
private fun museTypography(): Typography {
    val base = Typography()
    fun TextStyle.muse(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        copy(fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.muse(56, 64, FontWeight.SemiBold),
        displayMedium = base.displayMedium.muse(44, 52, FontWeight.SemiBold),
        displaySmall = base.displaySmall.muse(36, 44, FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.muse(32, 40, FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.muse(28, 36, FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.muse(24, 32, FontWeight.SemiBold),
        titleLarge = base.titleLarge.muse(22, 28, FontWeight.SemiBold),
        titleMedium = base.titleMedium.muse(17, 22, FontWeight.SemiBold),
        titleSmall = base.titleSmall.muse(15, 20, FontWeight.Medium),
        bodyLarge = base.bodyLarge.muse(16, 24),
        bodyMedium = base.bodyMedium.muse(14, 20),
        bodySmall = base.bodySmall.muse(13, 18),
        labelLarge = base.labelLarge.muse(15, 20, FontWeight.Medium),
        labelMedium = base.labelMedium.muse(13, 18, FontWeight.Medium),
        labelSmall = base.labelSmall.muse(12, 16, FontWeight.Medium),
    )
}

private fun scaledTypography(factor: Float): Typography {
    val base = museTypography() // unibot: was Material's default scale
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
