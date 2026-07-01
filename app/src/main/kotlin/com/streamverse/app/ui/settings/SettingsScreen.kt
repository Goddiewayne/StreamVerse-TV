package com.streamverse.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamverse.core.data.CacheTier
import com.streamverse.core.data.SourceHealthState
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.VideoResizeMode
import com.streamverse.core.data.repository.ProviderLoadingPhase

private val IosGreen = Color(0xFF34C759)
private val IosGray = Color(0xFF8E8E93)
private val CardBg = Color(0xFF1C1C1E)

@Composable
fun SettingsScreen(
    onManageSources: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val enabledSources by viewModel.enabledSources.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val backgroundPlayback by viewModel.backgroundPlayback.collectAsStateWithLifecycle()
    val dataSaver by viewModel.dataSaver.collectAsStateWithLifecycle()
    val resizeMode by viewModel.resizeMode.collectAsStateWithLifecycle()
    val staticIntensity by viewModel.staticIntensity.collectAsStateWithLifecycle()
    val staticAudio by viewModel.staticAudio.collectAsStateWithLifecycle()
    val staticChannelBurst by viewModel.staticChannelBurst.collectAsStateWithLifecycle()
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val totalCacheSize by viewModel.totalCacheSize.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp)
                .padding(bottom = com.streamverse.app.ui.player.LocalMiniPlayerInset.current + 20.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ── Channel Sources ──────────────────────────────────────────
            SectionHeader("Channel Sources")
            SettingsCard {
                val providerHealth by viewModel.providerHealthSummaries.collectAsStateWithLifecycle()
                val channelCounts by viewModel.sourceChannelCounts.collectAsStateWithLifecycle()
                val loadingProgress by viewModel.providerLoadingProgress.collectAsStateWithLifecycle()
                val enabledCount = enabledSources.count { it.value }
                val orderedProviders = SourceProvider.entries.sortedByDescending { channelCounts[it] ?: 0 }
                for ((idx, provider) in orderedProviders.withIndex()) {
                    if (idx > 0) InsetDivider()
                    val summary = providerHealth[provider]
                    val phase = loadingProgress[provider] ?: ProviderLoadingPhase.PENDING
                    SettingsProviderHealthRow(
                        provider = provider,
                        channelCount = channelCounts[provider] ?: 0,
                        isEnabled = enabledSources[provider] ?: true,
                        loadingPhase = phase,
                        summary = summary,
                    )
                }
                InsetDivider()
                SettingsNavRow(
                    title = "Manage Sources",
                    subtitle = "$enabledCount of ${SourceProvider.entries.size} enabled",
                    onClick = onManageSources,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Storage ───────────────────────────────────────────────────
            SectionHeader("Storage")
            SettingsCard {
                cacheStats.forEachIndexed { idx, stat ->
                    if (idx > 0) InsetDivider()
                    SettingsDetailRow(
                        label = stat.tier.label,
                        detail = formatCacheBytes(stat.sizeBytes),
                        actionLabel = "Clear",
                        actionTint = Color(0xFFFF3B30),
                        onAction = { viewModel.clearCacheTier(stat.tier) },
                    )
                }
                InsetDivider()
                SettingsDetailRow(
                    label = "Clear All Cache",
                    detail = totalCacheSize,
                    actionLabel = "Clear",
                    actionTint = Color(0xFFFF3B30),
                    onAction = { viewModel.clearAllCache() },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Playback ──────────────────────────────────────────────────
            SectionHeader("Playback")
            SettingsCard {
                SettingsToggleRow(
                    title = "Keep Screen On",
                    subtitle = "Prevent screen from turning off while watching",
                    checked = keepScreenOn,
                    onCheckedChange = { viewModel.toggleKeepScreenOn(it) },
                )
                InsetDivider()
                SettingsToggleRow(
                    title = "Background Audio",
                    subtitle = "Continue playing when you leave the app",
                    checked = backgroundPlayback,
                    onCheckedChange = { viewModel.toggleBackgroundPlayback(it) },
                )
                InsetDivider()
                SettingsToggleRow(
                    title = "Data Saver",
                    subtitle = "Lower quality, smaller buffers, less data usage",
                    checked = dataSaver,
                    onCheckedChange = { viewModel.toggleDataSaver(it) },
                )
                InsetDivider()
                SettingsPickerRow(
                    title = "Video Scaling",
                    subtitle = resizeMode.displayName,
                    options = VideoResizeMode.entries.map { it.displayName },
                    selectedIndex = VideoResizeMode.entries.indexOf(resizeMode).coerceAtLeast(0),
                    onSelect = { viewModel.setResizeMode(VideoResizeMode.entries[it]) },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── No-Signal Screen ─────────────────────────────────────────
            SectionHeader("No-Signal Screen")
            SettingsCard {
                val intensityOptions = listOf("Low", "Medium", "High")
                val intensityKeys = listOf("low", "medium", "high")
                SettingsPickerRow(
                    title = "Static Intensity",
                    subtitle = intensityOptions[intensityKeys.indexOf(staticIntensity).coerceAtLeast(0)],
                    options = intensityOptions,
                    selectedIndex = intensityKeys.indexOf(staticIntensity).coerceAtLeast(0),
                    onSelect = { viewModel.setStaticIntensity(intensityKeys[it]) },
                )
                InsetDivider()
                SettingsToggleRow(
                    title = "Channel Burst",
                    subtitle = "Brief snow flash when changing channels",
                    checked = staticChannelBurst,
                    onCheckedChange = { viewModel.toggleStaticChannelBurst(it) },
                )
                InsetDivider()
                SettingsToggleRow(
                    title = "Static Sound",
                    subtitle = "Faint analogue hiss while there's no signal",
                    checked = staticAudio,
                    onCheckedChange = { viewModel.toggleStaticAudio(it) },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── About ─────────────────────────────────────────────────────
            SectionHeader("About")
            SettingsCard {
                SettingsInfoRow(
                    title = "StreamVerse TV",
                    subtitle = "by Captain Global Technologies",
                    detail = "Version 1.0.0",
                )
                InsetDivider()
                Text(
                    text = "StreamVerse TV aggregates live channels from multiple free and publicly available streaming sources. No subscription required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = IosGray,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                InsetDivider()
                Text(
                    text = "© 2026 Captain Global Technologies",
                    style = MaterialTheme.typography.bodySmall,
                    color = IosGray,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Shared components ────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = IosGray,
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg),
        content = content,
    )
}

@Composable
private fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = Color(0xFF38383A),
        thickness = 0.5.dp,
    )
}

// ── Row types ────────────────────────────────────────────────────────

/** Toggle row: title + subtitle + iOS green switch on the right. */
@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = IosGreen,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF39393D),
            ),
        )
    }
}

/** Navigation row: title + subtitle + chevron on the right. */
@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = IosGray,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Detail row: label on left, value + action button on right. */
@Composable
private fun SettingsDetailRow(
    label: String,
    detail: String,
    actionLabel: String? = null,
    actionTint: Color = IosGray,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = actionTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Picker row: title + subtitle + horizontally scrollable chip picker. */
@Composable
private fun SettingsPickerRow(
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = IosGray,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) IosGreen else Color(0xFF2C2C2E))
                        .clickable { onSelect(idx) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color.White else IosGray,
                    )
                }
            }
        }
    }
}

/** Info row: title + subtitle on left, detail on right. */
@Composable
private fun SettingsInfoRow(
    title: String,
    subtitle: String,
    detail: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = IosGray,
        )
    }
}

/** Provider health row: colored dot + name + loading/diagnostics + health summary. */
@Composable
private fun SettingsProviderHealthRow(
    provider: SourceProvider,
    channelCount: Int,
    isEnabled: Boolean,
    loadingPhase: ProviderLoadingPhase,
    summary: ProviderHealthSummary?,
) {
    val dotColor = when {
        loadingPhase == ProviderLoadingPhase.LOADING -> Color(0xFFFFD60A)
        !isEnabled -> Color(0xFF3A3A3C)
        else -> when (summary?.overallState) {
            SourceHealthState.AVAILABLE -> IosGreen
            SourceHealthState.VERIFYING -> Color(0xFFFFD60A)
            SourceHealthState.UNAVAILABLE -> Color(0xFFFF3B30)
            SourceHealthState.UNKNOWN, null -> Color(0xFF3A3A3C)
        }
    }
    val healthText = when {
        loadingPhase == ProviderLoadingPhase.LOADING -> if (channelCount > 0) "Loading $channelCount channels…" else "Loading…"
        loadingPhase == ProviderLoadingPhase.PENDING -> "Waiting…"
        loadingPhase == ProviderLoadingPhase.FAILED -> "Failed to load"
        loadingPhase == ProviderLoadingPhase.SKIPPED -> "Skipped"
        !isEnabled -> "Disabled"
        summary == null || summary.totalChannels == 0 -> "No data"
        summary.overallState == SourceHealthState.AVAILABLE -> if (channelCount > 0) "All $channelCount available" else "No channels"
        else -> "${summary.healthyChannels}/$channelCount available"
    }
    val showLoading = loadingPhase == ProviderLoadingPhase.LOADING

    val lifecycleLabel = when (summary?.lifecycle) {
        com.streamverse.core.data.source.LifecycleState.ACTIVE -> "Online"
        com.streamverse.core.data.source.LifecycleState.INITIALIZING -> "Starting"
        com.streamverse.core.data.source.LifecycleState.FAILED -> "Failed"
        com.streamverse.core.data.source.LifecycleState.DISABLED -> "Disabled"
        com.streamverse.core.data.source.LifecycleState.REGISTERED -> "Idle"
        else -> null
    }
    val lifecycleColor = when (summary?.lifecycle) {
        com.streamverse.core.data.source.LifecycleState.ACTIVE -> IosGreen
        com.streamverse.core.data.source.LifecycleState.FAILED -> Color(0xFFFF3B30)
        com.streamverse.core.data.source.LifecycleState.INITIALIZING -> Color(0xFFFFD60A)
        else -> IosGray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEnabled) Color.White else Color(0xFF3A3A3C),
            )
            Text(
                text = healthText,
                style = MaterialTheme.typography.bodySmall,
                color = if (showLoading) Color(0xFFFFD60A) else IosGray,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (showLoading) {
            LoadingSpinner()
            Spacer(modifier = Modifier.width(8.dp))
        }
        val reliability = summary?.reliabilityPercent ?: 0
        if (reliability > 0 && isEnabled && !showLoading) {
            Text(
                text = "${reliability}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (reliability >= 80) IosGreen else if (reliability >= 50) Color(0xFFFFD60A) else Color(0xFFFF3B30),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        val respTime = summary?.avgResponseTimeMs ?: -1
        if (respTime > 0 && isEnabled && !showLoading) {
            Text(
                text = "${respTime}ms",
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (lifecycleLabel != null && isEnabled && !showLoading) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(lifecycleColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = lifecycleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = lifecycleColor,
                )
            }
        }
    }
}

@Composable
private fun LoadingSpinner() {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
    val rotation: Float by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
        ), label = "rotation",
    )
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "Loading",
        tint = Color(0xFFFFD60A),
        modifier = Modifier
            .size(16.dp)
            .graphicsLayer { rotationZ = rotation },
    )
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
