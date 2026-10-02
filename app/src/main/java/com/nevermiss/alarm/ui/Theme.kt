package com.nevermiss.alarm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF1B1200),
    primaryContainer = Color(0xFF3A2E00),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF8FB4FF),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C232B),
    surfaceContainer = Color(0xFF1C232B),
    surfaceContainerHigh = Color(0xFF232B35),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF4A1515),
)

@Composable
fun NeverMissTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
