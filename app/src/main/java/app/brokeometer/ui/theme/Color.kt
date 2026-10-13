package app.brokeometer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// The palette comes from an instrument panel at night: an ink-blue dial, ivory markings,
// an amber backlight, and a needle that runs from mint (fine) to red (broke).
val Ink = Color(0xFF0F1326)
val InkPanel = Color(0xFF181D36)
val InkRaised = Color(0xFF222849)
val Ivory = Color(0xFFF2EEE3)
val Amber = Color(0xFFFFB42E)
val Mint = Color(0xFF45D9B5)
val NeedleRed = Color(0xFFFF5C45)

val Paper = Color(0xFFF4F5FA)
val InkText = Color(0xFF141936)

val DarkScheme: ColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF241800),
    primaryContainer = Color(0xFF3B2F12),
    onPrimaryContainer = Color(0xFFFFE0A6),
    secondary = Mint,
    onSecondary = Color(0xFF00241B),
    secondaryContainer = Color(0xFF123A31),
    onSecondaryContainer = Color(0xFFB8F3E3),
    tertiary = NeedleRed,
    onTertiary = Color(0xFF2B0700),
    tertiaryContainer = Color(0xFF4A1A12),
    onTertiaryContainer = Color(0xFFFFD2CA),
    error = Color(0xFFFF8A75),
    onError = Color(0xFF2B0700),
    errorContainer = Color(0xFF4A1A12),
    onErrorContainer = Color(0xFFFFD2CA),
    background = Ink,
    onBackground = Ivory,
    surface = Ink,
    onSurface = Ivory,
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFA9AFCE),
    surfaceTint = Amber,
    inverseSurface = Ivory,
    inverseOnSurface = Ink,
    inversePrimary = Color(0xFF7A5200),
    outline = Color(0xFF545C8C),
    outlineVariant = Color(0xFF2C335A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3158),
    surfaceDim = Color(0xFF0B0E1D),
    surfaceContainerLowest = Color(0xFF0B0E1D),
    surfaceContainerLow = Color(0xFF141930),
    surfaceContainer = InkPanel,
    surfaceContainerHigh = Color(0xFF1F2545),
    surfaceContainerHighest = Color(0xFF282F56),
)

val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF8A5300),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Amber,
    onPrimaryContainer = Color(0xFF2A1A00),
    secondary = Color(0xFF0B7F66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9F3E7),
    onSecondaryContainer = Color(0xFF00382B),
    tertiary = Color(0xFFC6331D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD3),
    onTertiaryContainer = Color(0xFF410800),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF410800),
    background = Paper,
    onBackground = InkText,
    surface = Paper,
    onSurface = InkText,
    surfaceVariant = Color(0xFFE3E6F3),
    onSurfaceVariant = Color(0xFF545B80),
    surfaceTint = Color(0xFF8A5300),
    inverseSurface = InkText,
    inverseOnSurface = Paper,
    inversePrimary = Amber,
    outline = Color(0xFF8288AA),
    outlineVariant = Color(0xFFD3D7E8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE0EE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECEEF7),
    surfaceContainerHighest = Color(0xFFE3E6F3),
)

/** Colours that belong to the meter and the categories, outside the Material roles. */
@Immutable
data class BrandColors(
    val isDark: Boolean,
    /** Fill for the main action: amber in both themes, with ink text on top. */
    val action: Color,
    val onAction: Color,
    val fine: Color,
    val careful: Color,
    val broke: Color,
    val needle: Color,
    val track: Color,
    val categories: List<Color>,
) {
    fun category(index: Int): Color = categories[((index % categories.size) + categories.size) % categories.size]
}

private val categoryHues = listOf(
    Color(0xFFFF9F43), // Food
    Color(0xFF7BD88F), // Grocery
    Color(0xFFFF6B8B), // Health
    Color(0xFF6CA8FF), // Bills
    Color(0xFF3DD6C6), // Travel
    Color(0xFFC29BFF), // Personal Care
    Color(0xFFFF7AD9), // Entertainment
    Color(0xFFFFD84D), // Fashion
    Color(0xFFB4E05A), // Bike
    Color(0xFF5CC8FF), // Electronics
    Color(0xFF9AA0BF), // Other
)

val DarkBrand = BrandColors(
    isDark = true,
    action = Amber,
    onAction = Color(0xFF241800),
    fine = Mint,
    careful = Amber,
    broke = NeedleRed,
    needle = Ivory,
    track = Color(0xFF2A3157),
    categories = categoryHues,
)

val LightBrand = BrandColors(
    isDark = false,
    action = Amber,
    onAction = Color(0xFF2A1A00),
    fine = Color(0xFF12A383),
    careful = Color(0xFFE39A0B),
    broke = Color(0xFFD9412B),
    needle = InkText,
    track = Color(0xFFDADDEC),
    categories = categoryHues.map { lerp(it, InkText, 0.22f) },
)

val LocalBrand = staticCompositionLocalOf { DarkBrand }
