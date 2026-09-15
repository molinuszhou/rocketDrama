package com.dramaflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dramaflow.app.data.Drama
import com.dramaflow.app.ui.theme.TextSecondary

@Composable
fun DramaPoster(
    drama: Drama,
    modifier: Modifier = Modifier,
    showPlay: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val interaction = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(
        modifier = modifier
            .aspectRatio(0.70f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(drama.accent.copy(alpha = 0.95f), Color(0xFF130F1B))))
            .then(interaction)
            .padding(12.dp)
    ) {
        Text(
            text = drama.coverSymbol,
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White.copy(alpha = 0.90f),
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Column(modifier = Modifier.align(Alignment.BottomStart)) {
            Text(drama.subtitle, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
            Text(drama.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (showPlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.PlayArrow, null, tint = Color.White)
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onAction))
        }
    }
}

@Composable
fun CompactProgress(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(6.dp).clip(RoundedCornerShape(8.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MetaChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = TextSecondary
        ),
        border = null
    )
}

@Composable
fun EmptyState(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 76.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("◌", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.height(18.dp))
        Text(action, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onAction))
    }
}

fun formatCash(cents: Int): String = "USD %.2f".format(cents / 100.0)
