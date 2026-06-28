package com.streamverse.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        Color(0xFF1A1A1A),
        Color(0xFF2A2A2A),
        Color(0xFF333333),
        Color(0xFF2A2A2A),
        Color(0xFF1A1A1A),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_x",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(progress - 400f, 0f),
        end = Offset(progress, 400f),
    )
}

@Composable
fun ShimmerBox(modifier: Modifier = Modifier, cornerRadius: Int = 12, brush: Brush = shimmerBrush()) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(brush),
    )
}

@Composable
fun HomeShimmer() {
    // One shared shimmer animation drives every placeholder — in phase, and far cheaper than
    // one infinite transition per box.
    val brush = shimmerBrush()
    Column(modifier = Modifier.fillMaxWidth()) {
        // Hero shimmer
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .aspectRatio(16f / 9f),
            cornerRadius = 16,
            brush = brush,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Section shimmers
        repeat(3) {
            ShimmerBox(
                modifier = Modifier
                    .width(140.dp)
                    .height(20.dp)
                    .padding(start = 16.dp),
                cornerRadius = 6,
                brush = brush,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(5) {
                    Column {
                        ShimmerBox(
                            modifier = Modifier
                                .width(140.dp)
                                .aspectRatio(16f / 9f),
                            cornerRadius = 12,
                            brush = brush,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ShimmerBox(
                            modifier = Modifier
                                .width(100.dp)
                                .height(12.dp),
                            cornerRadius = 4,
                            brush = brush,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun GridShimmer() {
    val brush = shimmerBrush()
    val cols = 3
    repeat(3) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(cols) {
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        cornerRadius = 12,
                        brush = brush,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(10.dp),
                        cornerRadius = 4,
                        brush = brush,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}
