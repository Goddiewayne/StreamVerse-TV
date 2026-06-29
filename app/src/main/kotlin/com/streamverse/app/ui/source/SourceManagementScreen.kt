package com.streamverse.app.ui.source

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamverse.app.ui.theme.CoralRed
import com.streamverse.app.ui.theme.CyberCyan
import com.streamverse.app.ui.theme.ElectricViolet
import com.streamverse.app.ui.theme.LiveGreen
import com.streamverse.app.ui.theme.NavyCard
import com.streamverse.app.ui.theme.SpaceNavy
import com.streamverse.app.ui.theme.TextPrimary
import com.streamverse.app.ui.theme.TextSecondary
import com.streamverse.core.data.SourceProvider
import com.streamverse.core.data.source.LifecycleState
import com.streamverse.core.data.source.ProviderCapability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SourceManagementScreen(
    onBackClick: () -> Unit = {},
    viewModel: SourceManagementViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(onBackClick = onBackClick)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            SystemSummarySection(summary = uiState.systemSummary)

            Spacer(modifier = Modifier.height(24.dp))

            SourceProvidersSection(
                summaries = uiState.providerSummaries,
                expandedProviderId = uiState.expandedProviderId,
                onToggleProvider = { provider, enabled -> viewModel.toggleProvider(provider, enabled) },
                onExpandedChange = { id -> viewModel.setExpandedProvider(id) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            PlaybackPrioritySection(
                priorityOrder = uiState.priorityOrder,
                onMoveUp = { viewModel.movePriorityUp(it) },
                onMoveDown = { viewModel.movePriorityDown(it) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            HealthDiagnosticsSection(summaries = uiState.providerSummaries)

            Spacer(modifier = Modifier.height(24.dp))

            SynchronizationSection(
                activeOperations = uiState.activeOperations,
                onSyncAll = { viewModel.triggerSyncAll() },
                onRefreshMetadata = { viewModel.triggerRefreshMetadata() },
                onValidateStreams = { viewModel.triggerValidateStreams() },
                onRebuildIndex = { viewModel.triggerRebuildIndex() },
                onClearMetadataCache = { viewModel.triggerClearMetadataCache() },
                onClearStreamCache = { viewModel.triggerClearStreamCache() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            AdvancedSettingsSection()

            Spacer(modifier = Modifier.height(24.dp))

            LiveDiagnosticsSection(
                events = uiState.liveEvents,
                onClear = { viewModel.clearLiveEvents() },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (uiState.showImpactDialog && uiState.impactAnalysis != null) {
        ImpactAnalysisDialog(
            analysis = uiState.impactAnalysis!!,
            onConfirm = { providerName ->
                val provider = SourceProvider.entries.find { it.name == uiState.impactAnalysis!!.providerId }
                if (provider != null) viewModel.confirmDisableProvider(provider, false)
            },
            onDismiss = { viewModel.dismissImpactDialog() },
        )
    }
}

@Composable
private fun Header(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(CyberCyan.copy(alpha = 0.08f), ElectricViolet.copy(alpha = 0.08f)),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = CyberCyan,
            )
        }
        Column {
            Text(
                text = "Channel Sources",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            Text(
                text = "Broadcast Management Console",
                style = MaterialTheme.typography.labelSmall,
                color = CyberCyan,
            )
        }
    }
}

@Composable
private fun SystemSummarySection(summary: SystemSummary) {
    SectionHeader(title = "System Summary", subtitle = "Real-time platform overview")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Verified",
                value = "${summary.totalProviders}",
                color = ElectricViolet,
                accentColor = ElectricViolet.copy(alpha = 0.15f),
            )
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Enabled",
                value = "${summary.enabledProviders}",
                color = CyberCyan,
                accentColor = CyberCyan.copy(alpha = 0.15f),
            )
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Healthy",
                value = "${summary.healthyProviders}",
                color = LiveGreen,
                accentColor = LiveGreen.copy(alpha = 0.15f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Warnings",
                value = "${summary.warningProviders}",
                color = Color(0xFFFBBF24),
                accentColor = Color(0xFFFBBF24).copy(alpha = 0.15f),
            )
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Offline",
                value = "${summary.offlineProviders}",
                color = CoralRed,
                accentColor = CoralRed.copy(alpha = 0.15f),
            )
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Channels",
                value = formatLargeNumber(summary.totalLogicalChannels),
                color = TextPrimary,
                accentColor = TextPrimary.copy(alpha = 0.1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Streams",
                value = formatLargeNumber(summary.totalPhysicalStreams),
                color = ElectricViolet,
                accentColor = ElectricViolet.copy(alpha = 0.15f),
            )
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Multi-Source",
                value = "${summary.multiSourceChannels}",
                color = CyberCyan,
                accentColor = CyberCyan.copy(alpha = 0.15f),
            )
            DashboardCard(
                modifier = Modifier.weight(1f),
                label = "Last Sync",
                value = if (summary.lastSyncTimestamp > 0) formatTimestamp(summary.lastSyncTimestamp) else "--",
                color = TextSecondary,
                accentColor = TextSecondary.copy(alpha = 0.1f),
                small = true,
            )
        }
    }
}

@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color,
    accentColor: Color,
    small: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = color,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SourceProvidersSection(
    summaries: List<ProviderSummary>,
    expandedProviderId: String?,
    onToggleProvider: (SourceProvider, Boolean) -> Unit,
    onExpandedChange: (String?) -> Unit,
) {
    SectionHeader(title = "Source Providers", subtitle = "Verified content providers")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard),
    ) {
        summaries.forEachIndexed { idx, summary ->
            ProviderCard(
                summary = summary,
                isExpanded = expandedProviderId == summary.providerId,
                onToggle = { onToggleProvider(summary.provider, !summary.isEnabled) },
                onExpandToggle = {
                    onExpandedChange(
                        if (expandedProviderId == summary.providerId) null else summary.providerId
                    )
                },
            )
            if (idx < summaries.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    summary: ProviderSummary,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onExpandToggle: () -> Unit,
) {
    val cardAlpha = if (summary.isEnabled) 1f else 0.55f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpandToggle)
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMonogram(
                name = summary.displayName,
                isHealthy = summary.isHealthy,
                isEnabled = summary.isEnabled,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (summary.isEnabled) TextPrimary else TextSecondary,
                    )
                    if (summary.isEnabled && summary.isHealthy) {
                        Spacer(modifier = Modifier.width(6.dp))
                        VerifiedBadge()
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${summary.channelCount} channels · ${summary.streamCount} streams",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            ProviderStatusIndicator(
                lifecycle = summary.lifecycle,
                isHealthy = summary.isHealthy,
                isEnabled = summary.isEnabled,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Switch(
                checked = summary.isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = CyberCyan,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = NavyCard,
                ),
            )
            Icon(
                imageVector = if (isExpanded) Icons.Outlined.ArrowDropDown else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ProviderDetailPanel(summary = summary)
        }
    }
}

@Composable
private fun ProviderMonogram(name: String, isHealthy: Boolean, isEnabled: Boolean) {
    val initials = name.split(" ", "(", "&").firstOrNull()?.take(2)?.uppercase() ?: "?"
    val bgColor = when {
        !isEnabled -> SlateBlue
        isHealthy -> CyberCyan
        else -> CoralRed
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bgColor.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = bgColor,
        )
    }
}

@Composable
private fun VerifiedBadge() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LiveGreen.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = LiveGreen,
            modifier = Modifier.size(10.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "Verified",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = LiveGreen,
        )
    }
}

@Composable
private fun ProviderStatusIndicator(lifecycle: LifecycleState, isHealthy: Boolean, isEnabled: Boolean) {
    val (color, label) = when {
        !isEnabled -> SlateBlue to "Disabled"
        lifecycle == LifecycleState.FAILED -> CoralRed to "Failed"
        !isHealthy -> Color(0xFFFBBF24) to "Warning"
        lifecycle == LifecycleState.ACTIVE -> LiveGreen to "Active"
        lifecycle == LifecycleState.INITIALIZING -> CyberCyan to "Init"
        else -> TextSecondary to lifecycle.name
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun ProviderDetailPanel(summary: ProviderSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SpaceNavy.copy(alpha = 0.5f))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailColumn(title = "Coverage", items = summary.countries.take(3), modifier = Modifier.weight(1f))
            DetailColumn(title = "Languages", items = summary.languages.take(3), modifier = Modifier.weight(1f))
            DetailColumn(title = "Categories", items = summary.categories.take(3), modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatItem(label = "Channels", value = "${summary.channelCount}", modifier = Modifier.weight(1f))
            StatItem(label = "Streams", value = "${summary.streamCount}", modifier = Modifier.weight(1f))
            StatItem(label = "Reliability", value = "${summary.reliabilityPercent}%", modifier = Modifier.weight(1f))
            StatItem(label = "Avg Response", value = if (summary.avgResponseTimeMs > 0) "${summary.avgResponseTimeMs}ms" else "--", modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatItem(label = "Success Rate", value = "${(summary.successRate * 100).toInt()}%", modifier = Modifier.weight(1f))
            StatItem(label = "Failures", value = "${summary.consecutiveFailures}", modifier = Modifier.weight(1f))
            StatItem(label = "Last Sync", value = if (summary.lastSyncMs > 0) formatTimestamp(summary.lastSyncMs) else "--", modifier = Modifier.weight(1f))
            val capsLabel = summary.capabilities.joinToString(", ") { cap ->
                cap.name.take(3) + "..."
            }
            StatItem(label = "Capabilities", value = capsLabel, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(
                icon = Icons.Outlined.Refresh,
                label = "Refresh",
                onClick = { /* refresh */ },
            )
            ActionChip(
                icon = Icons.Outlined.PlayArrow,
                label = "Validate",
                onClick = { /* validate */ },
            )
            ActionChip(
                icon = Icons.Outlined.Info,
                label = "Metadata",
                onClick = { /* metadata */ },
            )
            ActionChip(
                icon = Icons.Outlined.Star,
                label = "View Channels",
                onClick = { /* view channels */ },
            )
        }

        if (summary.lastError != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(CoralRed.copy(alpha = 0.1f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = CoralRed,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = CoralRed,
                )
            }
        }
    }
}

@Composable
private fun DetailColumn(title: String, items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = CyberCyan,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (items.isEmpty()) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            items.forEach { item ->
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NavyCard.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CyberCyan.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CyberCyan,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = CyberCyan,
        )
    }
}

@Composable
private fun PlaybackPrioritySection(
    priorityOrder: List<SourceProvider>,
    onMoveUp: (SourceProvider) -> Unit,
    onMoveDown: (SourceProvider) -> Unit,
) {
    SectionHeader(
        title = "Playback Priority",
        subtitle = "Determines which provider is tried first for multi-source channels",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard),
    ) {
        Text(
            text = "Higher priority providers are attempted first during playback failover.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        priorityOrder.forEachIndexed { idx, provider ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#${idx + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = if (idx == 0) LiveGreen else if (idx < 3) CyberCyan else TextSecondary,
                    modifier = Modifier.width(32.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (idx > 0) {
                    IconButton(onClick = { onMoveUp(provider) }) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = CyberCyan,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
                if (idx < priorityOrder.lastIndex) {
                    IconButton(onClick = { onMoveDown(provider) }) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = CyberCyan,
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
            if (idx < priorityOrder.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun HealthDiagnosticsSection(summaries: List<ProviderSummary>) {
    SectionHeader(
        title = "Health & Diagnostics",
        subtitle = "Real-time provider health status",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        summaries.forEach { summary ->
            HealthRow(summary = summary)
        }
    }
}

@Composable
private fun HealthRow(summary: ProviderSummary) {
    val healthColor by animateColorAsState(
        targetValue = when {
            !summary.isEnabled -> SlateBlue
            !summary.isHealthy -> CoralRed
            summary.lifecycle != LifecycleState.ACTIVE -> Color(0xFFFBBF24)
            else -> LiveGreen
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NavyCard.copy(alpha = 0.3f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(healthColor),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = summary.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.width(140.dp),
        )
        HealthBar(value = summary.reliabilityPercent, color = healthColor, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "${summary.reliabilityPercent}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = healthColor,
            modifier = Modifier.width(40.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (summary.avgResponseTimeMs > 0) "${summary.avgResponseTimeMs}ms" else "--",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.width(50.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when (summary.lifecycle) {
                LifecycleState.ACTIVE -> "Online"
                LifecycleState.INITIALIZING -> "Starting"
                LifecycleState.FAILED -> "Failed"
                LifecycleState.DISABLED -> "Disabled"
                else -> summary.lifecycle.name
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = healthColor,
        )
    }
}

@Composable
private fun HealthBar(value: Int, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(SlateBlue.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = value / 100f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SynchronizationSection(
    activeOperations: Set<String>,
    onSyncAll: () -> Unit,
    onRefreshMetadata: () -> Unit,
    onValidateStreams: () -> Unit,
    onRebuildIndex: () -> Unit,
    onClearMetadataCache: () -> Unit,
    onClearStreamCache: () -> Unit,
) {
    SectionHeader(
        title = "Synchronization",
        subtitle = "Production-grade maintenance controls",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .padding(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncButton(
                label = "Sync All",
                isActive = activeOperations.contains("sync_all"),
                onClick = onSyncAll,
            )
            SyncButton(
                label = "Refresh Metadata",
                isActive = activeOperations.contains("refresh_metadata"),
                onClick = onRefreshMetadata,
            )
            SyncButton(
                label = "Validate Streams",
                isActive = activeOperations.contains("validate_streams"),
                onClick = onValidateStreams,
            )
            SyncButton(
                label = "Rebuild Index",
                isActive = activeOperations.contains("rebuild_index"),
                onClick = onRebuildIndex,
            )
            SyncButton(
                label = "Clear Metadata Cache",
                isActive = false,
                onClick = onClearMetadataCache,
            )
            SyncButton(
                label = "Clear Stream Cache",
                isActive = false,
                onClick = onClearStreamCache,
            )
        }

        if (activeOperations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(CyberCyan.copy(alpha = 0.1f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CyberCyan),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${activeOperations.size} operation(s) in progress...",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberCyan,
                )
            }
        }
    }
}

@Composable
private fun SyncButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) CyberCyan.copy(alpha = 0.25f) else NavyCard.copy(alpha = 0.5f),
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(CyberCyan),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) CyberCyan else TextPrimary,
        )
    }
}

@Composable
private fun AdvancedSettingsSection() {
    SectionHeader(
        title = "Advanced Configuration",
        subtitle = "Fine-tune source management behaviour",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard),
    ) {
        var autoSync by remember { mutableStateOf(true) }
        var autoFailover by remember { mutableStateOf(true) }
        var autoHealth by remember { mutableStateOf(true) }
        var autoValidate by remember { mutableStateOf(false) }
        var autoLogo by remember { mutableStateOf(true) }
        var autoEpg by remember { mutableStateOf(true) }

        AdvancedToggleRow(
            title = "Background Sync",
            subtitle = "Automatically synchronize providers in the background",
            checked = autoSync,
            onCheckedChange = { autoSync = it },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        AdvancedToggleRow(
            title = "Auto Failover",
            subtitle = "Automatically switch to alternate source when primary fails",
            checked = autoFailover,
            onCheckedChange = { autoFailover = it },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        AdvancedToggleRow(
            title = "Health Monitoring",
            subtitle = "Periodically probe stream health for live indicators",
            checked = autoHealth,
            onCheckedChange = { autoHealth = it },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        AdvancedToggleRow(
            title = "Stream Validation",
            subtitle = "Verify stream URLs are reachable before adding to catalogue",
            checked = autoValidate,
            onCheckedChange = { autoValidate = it },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        AdvancedToggleRow(
            title = "Auto Logo Updates",
            subtitle = "Automatically fetch highest-quality logos from providers",
            checked = autoLogo,
            onCheckedChange = { autoLogo = it },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )
        AdvancedToggleRow(
            title = "Auto EPG Updates",
            subtitle = "Keep electronic programme guide data current",
            checked = autoEpg,
            onCheckedChange = { autoEpg = it },
        )
    }
}

@Composable
private fun AdvancedToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CyberCyan,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = NavyCard,
            ),
        )
    }
}

@Composable
private fun LiveDiagnosticsSection(events: List<LiveEvent>, onClear: () -> Unit) {
    SectionHeader(
        title = "Live Diagnostics",
        subtitle = "Real-time platform activity feed",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${events.size} event(s)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (events.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        )

        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No events yet. Events appear as providers sync, failover, or change state.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        } else {
            events.take(10).forEach { event ->
                LiveEventRow(event = event)
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@Composable
private fun LiveEventRow(event: LiveEvent) {
    val iconColor = when (event.severity) {
        LiveEventSeverity.ERROR -> CoralRed
        LiveEventSeverity.WARNING -> Color(0xFFFBBF24)
        LiveEventSeverity.INFO -> CyberCyan
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(iconColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.providerName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = iconColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatTimestamp(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ImpactAnalysisDialog(
    analysis: ImpactAnalysis,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NavyCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Disable ${analysis.providerName}?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Impact Analysis",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = CyberCyan,
                )
                Spacer(modifier = Modifier.height(10.dp))

                ImpactStatRow(label = "Channels Affected", value = "${analysis.totalChannelsAffected}", color = Color(0xFFFBBF24))
                ImpactStatRow(label = "With Alternative Sources", value = "${analysis.channelsWithAlternatives}", color = LiveGreen)
                ImpactStatRow(label = "Will Become Unavailable", value = "${analysis.channelsUnavailable}", color = CoralRed)
                ImpactStatRow(label = "Favorites Affected", value = "${analysis.favoritesAffected}", color = if (analysis.favoritesAffected > 0) CoralRed else LiveGreen)

                if (analysis.affectedCategories.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Most Affected Categories",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    analysis.affectedCategories.forEach { (cat, count) ->
                        Text(
                            text = "$cat — $count channels",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }

                if (analysis.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    analysis.warnings.forEach { warning ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CoralRed.copy(alpha = 0.1f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = CoralRed,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = CoralRed,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            val canDisable = analysis.channelsUnavailable == 0
            Button(
                onClick = {
                    if (canDisable) onConfirm(analysis.providerId)
                },
                enabled = canDisable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canDisable) CoralRed else CoralRed.copy(alpha = 0.3f),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (canDisable) "Disable Provider" else "Cannot Disable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}

@Composable
private fun ImpactStatRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = CyberCyan,
            fontWeight = FontWeight.Bold,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

private fun formatLargeNumber(n: Int): String = when {
    n >= 10_000 -> "%.1fk".format(n / 1000.0)
    n >= 1_000 -> "%.1fk".format(n / 1000.0)
    else -> "$n"
}

private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}

private val SlateBlue = Color(0xFF475569)
