package com.streamverse.app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.streamverse.core.domain.model.Channel
import com.streamverse.core.util.ChannelLogoResolver
import kotlin.math.abs

/**
 * Immersive channel logo that *fills* its box like a Netflix tile:
 *  • a brand-gradient fill so the box is never empty,
 *  • a soft, blurred full-bleed copy of the logo behind it (API 31+) — the logo's own colours
 *    spread edge-to-edge, exactly the "poster fills the frame" feel,
 *  • the crisp logo centred and readable on top,
 *  • a generated gradient monogram when no logo loads — so logo-less channels look *designed*.
 *
 * Bulletproof: there is always something full and beautiful to show.
 */
@Composable
fun ChannelLogo(
    channel: Channel,
    modifier: Modifier = Modifier,
    logoPadding: Dp = 8.dp,
) {
    val (c1, c2) = remember(channel.id) { accentColors(channel.displayName) }
    var loaded by remember(channel.id) { mutableStateOf(false) }
    val model = remember(channel.id) { ChannelLogoResolver.model(channel) }
    val context = LocalContext.current
    // Crossfade the crisp logo in so it never hard-pops over the gradient/monogram.
    val logoRequest = remember(model) {
        ImageRequest.Builder(context).data(model).crossfade(220).build()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 1. Brand-gradient fill — the box is full of colour even before/without a logo.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.55f), c2.copy(alpha = 0.55f)))),
        )

        // 2. Blurred full-bleed logo backdrop (API 31+) — fills the frame Netflix-style.
        if (loaded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(26.dp)
                    .alpha(0.55f),
                contentScale = ContentScale.Crop,
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        }

        // 3. Crisp content: the monogram until a logo loads, then the logo over it.
        if (!loaded) Monogram(name = channel.displayName, c1 = c1, c2 = c2)

        AsyncImage(
            model = logoRequest,
            contentDescription = channel.displayName,
            modifier = Modifier.fillMaxSize().padding(logoPadding),
            contentScale = ContentScale.Fit,
            onState = { state -> loaded = state is AsyncImagePainter.State.Success },
        )
    }
}

/** Generated gradient monogram — the channel's initials on a brand-derived diagonal gradient. */
@Composable
private fun Monogram(name: String, c1: Color, c2: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun initials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].first().toString() + words[1].first().toString()).uppercase()
    }
}

/** Deterministic two-colour brand gradient derived from the channel name. */
fun accentColors(name: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF0EA5E9) to Color(0xFF6366F1), // sky → indigo
        Color(0xFF22D3EE) to Color(0xFF0891B2), // cyan → teal
        Color(0xFF818CF8) to Color(0xFF4F46E5), // violet → deep violet
        Color(0xFFF43F5E) to Color(0xFF9333EA), // rose → purple
        Color(0xFF10B981) to Color(0xFF0891B2), // emerald → teal
        Color(0xFFF59E0B) to Color(0xFFEA580C), // amber → orange
        Color(0xFF06B6D4) to Color(0xFF3B82F6), // cyan → blue
    )
    return palettes[abs(name.hashCode()) % palettes.size]
}
