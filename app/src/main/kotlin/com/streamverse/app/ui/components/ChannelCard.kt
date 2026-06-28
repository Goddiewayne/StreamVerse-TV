package com.streamverse.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamverse.app.ui.theme.CyberCyan
import com.streamverse.app.ui.theme.NavyCard
import com.streamverse.core.data.sourceProviderCount
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.domain.model.Quality

/**
 * The current Live Availability Index (verified-available channel ids), provided once at the nav
 * root from [com.streamverse.core.data.ChannelHealthEngine]. Every [ChannelCard] reads it so LIVE
 * badges appear consistently across Home, Search, Favorites, Recent and the channel list without
 * threading the set through every call site.
 */
val LocalLiveChannels = androidx.compose.runtime.staticCompositionLocalOf { emptySet<String>() }

@Composable
fun ChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isFocused -> 1.05f
            else -> 1f
        },
        animationSpec = tween(150),
        label = "card_scale",
    )

    val overlayAlpha by animateFloatAsState(
        targetValue = if (isFocused || isPressed) 1f else 0f,
        animationSpec = tween(200),
        label = "overlay_alpha",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(NavyCard)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF0A0A0A)),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(
                channel = channel,
                modifier = Modifier.matchParentSize(),
            )

            if (overlayAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.3f * overlayAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.9f * overlayAlpha), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // LIVE badge (top-left, like broadcast) — only when the Channel Health Engine has
            // verified the channel is currently available.
            val isLive = LocalLiveChannels.current.contains(channel.id)
            if (isLive) {
                LiveBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
            }

            // Bottom-left: quality + multi-source indicator.
            val sourceCount = remember(channel.sources) { channel.sourceProviderCount() }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                channel.quality?.let { QualityBadge(quality = it) }
                if (sourceCount > 1) SourceCountBadge(count = sourceCount)
            }

            if (onToggleFavorite != null) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove favourite" else "Add favourite",
                        tint = if (isFavorite) Color(0xFFF43F5E) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Text(
            text = channel.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFCCCCCC),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SourceCountBadge(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(CyberCyan, CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = CyberCyan,
            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f,
        )
    }
}

@Composable
fun QualityBadge(quality: Quality?, modifier: Modifier = Modifier) {
    if (quality == null) return
    val (label, bgColor) = when (quality) {
        Quality._4K -> "4K" to Color(0xFF22D3EE)
        Quality.FHD -> "FHD" to Color(0xFF818CF8)
        Quality.HD  -> "HD"  to Color(0xFF0891B2)
        Quality.SD  -> "SD"  to Color(0xFF475569)
    }
    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f,
        )
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(Color(0xFF4ADE80), CircleShape),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4ADE80),
            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f,
        )
    }
}