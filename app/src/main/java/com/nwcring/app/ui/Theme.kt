package com.nwcring.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5A623),
    onPrimary = Color(0xFF2B1A00),
    primaryContainer = Color(0xFF5C3F00),
    onPrimaryContainer = Color(0xFFFFDDAE),
    secondary = Color(0xFFD8C3A0),
    onSecondary = Color(0xFF3B2F15),
    background = Color(0xFF14161B),
    onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF14161B),
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFB9B4AA),
    surfaceContainer = Color(0xFF1D2027),
    surfaceContainerHigh = Color(0xFF262A33),
    surfaceContainerHighest = Color(0xFF30343E),
    outline = Color(0xFF8C877D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5200),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDAE),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Color(0xFF6C5C3F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFBF8F3),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF4E4639),
    surfaceContainer = Color(0xFFF3EEE5),
    surfaceContainerHigh = Color(0xFFEDE6DA),
    surfaceContainerHighest = Color(0xFFE6DFD2),
    outline = Color(0xFF80766A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun NwcRingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
