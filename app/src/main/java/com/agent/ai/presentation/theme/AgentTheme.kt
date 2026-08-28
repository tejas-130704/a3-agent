package com.agent.ai.presentation.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SkyDeep = Color(0xFF0A0E1A)
val SkyMid = Color(0xFF141B2D)
val SkyCard = Color(0xFF1E2740)
val AccentViolet = Color(0xFF7C6CF0)
val AccentCyan = Color(0xFF4FD1C5)
val AccentPink = Color(0xFFE879A9)
val TextPrimary = Color(0xFFF0F2FF)
val TextMuted = Color(0xFF8B93B0)

private val AgentDarkScheme = darkColorScheme(
    primary = AccentViolet,
    onPrimary = Color.White,
    secondary = AccentCyan,
    tertiary = AccentPink,
    background = SkyDeep,
    surface = SkyMid,
    surfaceVariant = SkyCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AgentDarkScheme, content = content)
}
