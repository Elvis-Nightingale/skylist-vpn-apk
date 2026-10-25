package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SkylistDarkColorScheme = darkColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = CyanAccent,
    onSecondary = CyanOnAccent,
    secondaryContainer = CyanAccentContainer,
    onSecondaryContainer = CyanOnAccentContainer,
    tertiary = IceBlue,
    onTertiary = BlueOnPrimary,
    tertiaryContainer = IceBlueContainer,
    onTertiaryContainer = IceBlueOnContainer,
    background = NavyBackground,
    onBackground = TextPrimary,
    surface = NavySurface,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = NavySurfaceHighlight,
    outlineVariant = Color(0xFF1E3550)
)

private val SkylistLightColorScheme = lightColorScheme(
    primary = Color(0xFF0072C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCE4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF00838F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF002022),
    tertiary = Color(0xFF0288D1),
    background = Color(0xFFF4F8FC),
    onBackground = Color(0xFF0B192E),
    surface = Color.White,
    onSurface = Color(0xFF0B192E),
    surfaceVariant = Color(0xFFE1EAF2),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek midnight cyber theme for VPN
    dynamicColor: Boolean = false, // Keep consistent blue theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) SkylistDarkColorScheme else SkylistLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
