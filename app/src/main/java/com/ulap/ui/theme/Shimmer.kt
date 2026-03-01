package com.ulap.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme

/**
 * A simple shimmer placeholder that fills [modifier] with an animated gradient.
 * Uses theme surfaceVariant so it respects dark/light mode.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val base = colorScheme.surfaceVariant
    val highlight = base.copy(alpha = 0.5f)
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_500),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )
    Canvas(modifier = modifier) {
        val w = size.width
        if (w <= 0f) return@Canvas
        val startX = w * (offset * 1.4f - 0.2f)
        val endX = startX + w * 0.4f
        val brush = Brush.linearGradient(
            0f to base,
            0.2f to base,
            0.5f to highlight,
            0.8f to base,
            1f to base,
            start = Offset(startX, 0f),
            end = Offset(endX, 0f)
        )
        drawRect(brush = brush)
    }
}
