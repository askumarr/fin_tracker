package com.fintracker.app.ui.theme

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

private val Teal = Color(0xFF0F5C5C)
private val TealDark = Color(0xFF083F3F)
private val Saffron = Color(0xFFE07A2F)
private val Sand = Color(0xFFF3EFE6)
private val Ink = Color(0xFF1A2421)
private val Mist = Color(0xFFE8F1F0)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Mist,
    onPrimaryContainer = TealDark,
    secondary = Saffron,
    onSecondary = Color.White,
    background = Sand,
    onBackground = Ink,
    surface = Color(0xFFFFFBF5),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE4EDE9),
    onSurfaceVariant = Color(0xFF3D4A46),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EC8C0),
    onPrimary = TealDark,
    secondary = Color(0xFFF0A56A),
    background = Color(0xFF101816),
    onBackground = Color(0xFFE8F1F0),
    surface = Color(0xFF16201D),
    onSurface = Color(0xFFE8F1F0)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    )
)

@Composable
fun FinTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
