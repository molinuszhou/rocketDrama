package com.dramaflow.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Ink = Color(0xFF171717)
val Surface = Color(0xFFFFFFFF)
val SurfaceHigh = Color(0xFFFFF1EA)
val Lavender = Color(0xFFFF7043)
val LavenderLight = Color(0xFFFFE1D3)
val Coral = Color(0xFFFF5C63)
val Mint = Color(0xFF22A98A)
val TextPrimary = Color(0xFF171717)
val TextSecondary = Color(0xFF8B8B8B)

private val DramaFlowColors = lightColorScheme(
    primary = Lavender,
    onPrimary = Ink,
    secondary = Coral,
    tertiary = Mint,
    background = Color(0xFFF7F8FA),
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFE8E8E8)
)

@Composable
fun DramaFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DramaFlowColors,
        typography = DramaFlowTypography,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
