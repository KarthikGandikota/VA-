package com.example.va.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0E141B)
private val Slate = Color(0xFF1B2733)
private val Mist = Color(0xFFE8EEF4)
private val Accent = Color(0xFF7C9CFF)
private val AccentDim = Color(0xFF3D5A99)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = AccentDim,
    onPrimaryContainer = Mist,
    background = Ink,
    onBackground = Mist,
    surface = Slate,
    onSurface = Mist,
    surfaceVariant = Color(0xFF243140),
    onSurfaceVariant = Color(0xFFB7C4D4),
    outline = Color(0xFF3A4A5C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F4F9A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E0FF),
    onPrimaryContainer = Color(0xFF0E1A33),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF15202B),
    surface = Color.White,
    onSurface = Color(0xFF15202B),
    surfaceVariant = Color(0xFFE7EDF5),
    onSurfaceVariant = Color(0xFF3D4A58),
    outline = Color(0xFFC5D0DC),
)

@Composable
fun VaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
