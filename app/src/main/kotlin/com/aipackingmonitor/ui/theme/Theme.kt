package com.aipackingmonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PackingMonitorColors = lightColorScheme(
    primary = Color(0xFF1F7A5C),
    onPrimary = Color.White,
    secondary = Color(0xFF355C7D),
    onSecondary = Color.White,
    tertiary = Color(0xFF8B5E34),
    error = Color(0xFFB3261E),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF1E2329),
    surface = Color.White,
    onSurface = Color(0xFF1E2329),
    surfaceVariant = Color(0xFFE6EAEE),
    onSurfaceVariant = Color(0xFF46515C),
)

@Composable
fun PackingMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PackingMonitorColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
