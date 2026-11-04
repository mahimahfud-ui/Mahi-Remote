package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MahiDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = DarkCharcoalBg,
    primaryContainer = Color(0xFF0C3547),
    onPrimaryContainer = CyanAccent,
    secondary = MintAccent,
    onSecondary = DarkCharcoalBg,
    secondaryContainer = Color(0xFF0F3B2E),
    onSecondaryContainer = MintAccent,
    tertiary = WarningAmber,
    onTertiary = DarkCharcoalBg,
    background = DarkCharcoalBg,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DarkSurfaceBorder,
    error = ErrorCoral,
    onError = DarkCharcoalBg
)

@Composable
fun MahiRemoteTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MahiDarkColorScheme,
        typography = Typography,
        content = content
    )
}

// Backward compatibility alias
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MahiRemoteTheme(content = content)
}
