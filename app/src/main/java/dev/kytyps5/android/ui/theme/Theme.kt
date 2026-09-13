package dev.kytyps5.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// PlayStation-inspired M3 palette
private val Blue = Color(0xFF5B8CFF)
private val BlueDark = Color(0xFF9DBCFF)
private val IndigoContainer = Color(0xFF1B2A4A)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF001C3B),
    primaryContainer = Color(0xFF1E3A66),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF9FBCFF),
    onSecondary = Color(0xFF002F67),
    secondaryContainer = IndigoContainer,
    onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF7FD9C0),
    onTertiary = Color(0xFF00382C),
    background = Color(0xFF101218),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF16171D),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF262833),
    onSurfaceVariant = Color(0xFFC3C5D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2159CD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color.White,
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
)

private val KytyTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    ),
)

@Composable
fun KytyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KytyTypography,
        content = content,
    )
}
