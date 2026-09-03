package com.dramaflow.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0E0B14)
val Surface = Color(0xFF181321)
val SurfaceHigh = Color(0xFF231C30)
val Lavender = Color(0xFF9E7BFF)
val LavenderLight = Color(0xFFE7DDFF)
val Coral = Color(0xFFFF709B)
val Mint = Color(0xFF58D4C1)
val TextPrimary = Color(0xFFF8F5FC)
val TextSecondary = Color(0xFFBFB7CB)

private val DramaFlowColors = darkColorScheme(
    primary = Lavender,
    onPrimary = Ink,
    secondary = Coral,
    tertiary = Mint,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFF51485E)
)

@Composable
fun DramaFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DramaFlowColors,
        typography = DramaFlowTypography,
        content = content
    )
}
