package com.matt.unfog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Fog = Color(0xFF1B1F2A)
val FogDeep = Color(0xFF12151D)
val Mist = Color(0xFFC9D1E0)
val Ember = Color(0xFFFF8A3D)

private val colors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF2A1100),
    secondary = Color(0xFF7FC8F8),
    background = FogDeep,
    onBackground = Mist,
    surface = Fog,
    onSurface = Mist,
    surfaceVariant = Color(0xFF262C3A),
    onSurfaceVariant = Color(0xFF9AA3B5),
    surfaceContainer = Color(0xFF20252F),
    surfaceContainerHigh = Color(0xFF282E3B),
)

@Composable
fun UnfogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
