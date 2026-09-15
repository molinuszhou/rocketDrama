package com.dramaflow.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF171717)
val Surface = Color(0xFFFFFFFF)
val SurfaceHigh = Color(0xFFFFF3ED)
val Lavender = Color(0xFFFF7A45)
val LavenderLight = Color(0xFFFFE3D4)
val Coral = Color(0xFFFF4F69)
val Mint = Color(0xFF38B89E)
val TextPrimary = Color(0xFF171717)
val TextSecondary = Color(0xFF8B8B8B)

private val DramaFlowColors = lightColorScheme(
    primary = Lavender,
    onPrimary = Ink,
    secondary = Coral,
    tertiary = Mint,
    background = Color(0xFFF8F8F8),
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFFFF3ED),
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFE8E8E8)
)

@Composable
fun DramaFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DramaFlowColors,
        typography = DramaFlowTypography,
        content = content
    )
}
