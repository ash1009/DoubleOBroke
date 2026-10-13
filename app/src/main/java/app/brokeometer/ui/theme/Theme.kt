package app.brokeometer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import app.brokeometer.data.Prefs

private val BrokeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** [themeSetting] is one of the Prefs.THEME_ values. Dark is the default. */
@Composable
fun BrokeTheme(themeSetting: String, content: @Composable () -> Unit) {
    val dark = when (themeSetting) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_SYSTEM -> isSystemInDarkTheme()
        else -> true
    }
    CompositionLocalProvider(LocalBrand provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = BrokeTypography,
            shapes = BrokeShapes,
            content = content,
        )
    }
}

/** True when the theme being shown is the dark one. */
@Composable
fun isDarkTheme(themeSetting: String): Boolean = when (themeSetting) {
    Prefs.THEME_LIGHT -> false
    Prefs.THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}
