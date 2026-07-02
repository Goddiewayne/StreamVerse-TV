package com.streamverse.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamverse.app.ui.player.LocalMiniPlayerInset
import com.streamverse.core.data.CacheTier
import com.streamverse.core.data.VideoResizeMode

private val IosGreen = Color(0xFF34C759)
private val IosGray = Color(0xFF8E8E93)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onManageSources: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var appVersion by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion = info.versionName ?: "1.0.0"
        } catch (_: Exception) {
            appVersion = "1.0.0"
        }
    }

    val sections = listOf(
        SettingsSection.PLAYBACK,
        SettingsSection.CHANNELS_AND_SOURCES,
        SettingsSection.NO_SIGNAL,
        SettingsSection.STORAGE,
        SettingsSection.ADVANCED,
        SettingsSection.DIAGNOSTICS,
        SettingsSection.ABOUT,
    )

    val query = state.searchQuery.lowercase().trim()
    val filteredSections = if (query.isEmpty()) sections else sections.filter { section ->
        val label = when (section) {
            SettingsSection.PLAYBACK -> "Playback"
            SettingsSection.CHANNELS_AND_SOURCES -> "Channels & Sources"
            SettingsSection.NO_SIGNAL -> "No-Signal Screen"
            SettingsSection.STORAGE -> "Storage"
            SettingsSection.ADVANCED -> "Advanced"
            SettingsSection.DIAGNOSTICS -> "Diagnostics"
            SettingsSection.ABOUT -> "About"
        }
        label.lowercase().contains(query) ||
        when (section) {
            SettingsSection.PLAYBACK -> listOfNotNull(
                if (state.keepScreenOn) "keep screen on" else null,
                if (state.backgroundAudio) "background audio" else null,
                if (state.dataSaver) "data saver" else null,
                state.videoScaling.displayName,
            ).any { it.lowercase().contains(query) }
            SettingsSection.CHANNELS_AND_SOURCES -> state.sourceProviders.any { p ->
                p.displayName.lowercase().contains(query) || p.isEnabled.toString().contains(query)
            }
            SettingsSection.NO_SIGNAL -> listOf(state.staticIntensity, "static", "snow", "tuning", "hiss").any { it.contains(query) }
            SettingsSection.STORAGE -> listOf("cache", "clear", "storage").any { it.contains(query) }
            SettingsSection.ADVANCED -> listOf("index", "metadata", "validate", "rebuild").any { it.contains(query) }
            SettingsSection.DIAGNOSTICS -> listOf("version", "channels", "sources", "updated").any { it.contains(query) }
            SettingsSection.ABOUT -> listOf("version", "about", "copyright").any { it.contains(query) }
            else -> false
        }
    }

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
            Spacer(modifier = Modifier.height(16.dp))

            // Search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = {
                    Text("Search settings…", color = IosGray)
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = IosGray)
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear", tint = IosGray)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* no-op */ }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                    focusedBorderColor = com.streamverse.app.ui.theme.CyberCyan,
                    unfocusedBorderColor = Color(0xFF38383A),
                    focusedContainerColor = Color(0xFF1C1C1E),
                    unfocusedContainerColor = Color(0xFF1C1C1E),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quick actions
            if (query.isEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionChip(
                        label = "Refresh Catalogue",
                        isActive = state.isRefreshingCatalogue,
                        onClick = { viewModel.refreshCatalogue() },
                    )
                    ActionChip(
                        label = "Clear All Cache",
                        isActive = false,
                        onClick = { viewModel.clearAllCache() },
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Section cards
            var sectionIndex = 0
            for (section in sections) {
                if (section !in filteredSections) continue
                when (section) {
                    SettingsSection.PLAYBACK -> PlaybackSection(state, viewModel)
                    SettingsSection.CHANNELS_AND_SOURCES -> ChannelsAndSourcesSection(state, viewModel, onManageSources)
                    SettingsSection.NO_SIGNAL -> NoSignalSection(state, viewModel)
                    SettingsSection.STORAGE -> StorageSection(state, viewModel)
                    SettingsSection.ADVANCED -> AdvancedSection(state, viewModel)
                    SettingsSection.DIAGNOSTICS -> DiagnosticsSection(state)
                    SettingsSection.ABOUT -> AboutSection(appVersion)
                }
                if (sectionIndex < filteredSections.lastIndex) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                sectionIndex++
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExpandableSectionCard(
    title: String,
    subtitle: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    accentColor: Color = com.streamverse.app.ui.theme.CyberCyan,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1E)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = IosGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            val rotation by animateFloatAsState(
                targetValue = if (isExpanded) 90f else 0f,
                animationSpec = tween(200),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = accentColor,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color(0xFF2C2C2E),
                    thickness = 0.5.dp,
                )
                content()
            }
        }
    }
}

@Composable
private fun StaticSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1C1C1E)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = IosGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = Color(0xFF2C2C2E),
            thickness = 0.5.dp,
        )
        content()
    }
}

// ── Playback Section ──────────────────────────────────────────────────

@Composable
private fun PlaybackSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val subs = buildList {
        if (state.keepScreenOn) add("Keep screen on")
        if (state.backgroundAudio) add("Background audio")
        add(state.videoScaling.displayName)
    }
    ExpandableSectionCard(
        title = "Playback",
        subtitle = subs.joinToString(" · "),
        isExpanded = state.expandedSections.contains(SettingsSection.PLAYBACK),
        onToggle = { viewModel.toggleSection(SettingsSection.PLAYBACK) },
        accentColor = com.streamverse.app.ui.theme.CyberCyan,
    ) {
        ToggleRow("Keep Screen On", "Prevent display from sleeping during live streams", state.keepScreenOn) {
            viewModel.setKeepScreenOn(it)
        }
        InsetDivider()
        ToggleRow("Background Audio", "Continue audio when you leave the app", state.backgroundAudio) {
            viewModel.setBackgroundAudio(it)
        }
        InsetDivider()
        ToggleRow("Data Saver", "Reduce data usage: lower quality, smaller buffers", state.dataSaver) {
            viewModel.setDataSaver(it)
        }
        InsetDivider()
        PickerRow(
            title = "Video Scaling",
            subtitle = state.videoScaling.displayName,
            options = VideoResizeMode.entries.map { it.displayName },
            selectedIndex = VideoResizeMode.entries.indexOf(state.videoScaling).coerceAtLeast(0),
        ) { viewModel.setVideoScaling(VideoResizeMode.entries[it]) }
    }
}

// ── Channels & Sources Section ─────────────────────────────────────────

@Composable
private fun ChannelsAndSourcesSection(state: SettingsUiState, viewModel: SettingsViewModel, onManageSources: () -> Unit) {
    val enabledCount = state.sourceProviders.count { it.isEnabled }
    val sub = "$enabledCount sources · ${state.totalChannelCount} channels"
    ExpandableSectionCard(
        title = "Channels & Sources",
        subtitle = sub,
        isExpanded = state.expandedSections.contains(SettingsSection.CHANNELS_AND_SOURCES),
        onToggle = { viewModel.toggleSection(SettingsSection.CHANNELS_AND_SOURCES) },
        accentColor = com.streamverse.app.ui.theme.ElectricViolet,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManageSources)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Manage Sources",
                style = MaterialTheme.typography.bodyLarge,
                color = com.streamverse.app.ui.theme.CyberCyan,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = com.streamverse.app.ui.theme.CyberCyan,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ── No-Signal Section ─────────────────────────────────────────────────

@Composable
private fun NoSignalSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val subs = listOf(
        state.staticIntensity.replaceFirstChar { it.uppercase() } + " static",
        if (state.staticChannelBurst) "Tuning burst" else null,
        if (state.staticSound) "Static sound" else null,
    ).filterNotNull()
    ExpandableSectionCard(
        title = "No-Signal Screen",
        subtitle = subs.joinToString(" · "),
        isExpanded = state.expandedSections.contains(SettingsSection.NO_SIGNAL),
        onToggle = { viewModel.toggleSection(SettingsSection.NO_SIGNAL) },
        accentColor = com.streamverse.app.ui.theme.LiveGreen,
    ) {
        PickerRow(
            title = "Static Intensity",
            subtitle = listOf("Low", "Medium", "High")[listOf("low", "medium", "high").indexOf(state.staticIntensity).coerceAtLeast(0)],
            options = listOf("Low", "Medium", "High"),
            selectedIndex = listOf("low", "medium", "high").indexOf(state.staticIntensity).coerceAtLeast(0),
        ) { viewModel.setStaticIntensity(listOf("low", "medium", "high")[it]) }
        InsetDivider()
        ToggleRow("Channel Burst", "Brief snow flash when changing channels", state.staticChannelBurst) {
            viewModel.setStaticChannelBurst(it)
        }
        InsetDivider()
        ToggleRow("Static Sound", "Faint analogue hiss while there's no signal", state.staticSound) {
            viewModel.setStaticSound(it)
        }
    }
}

// ── Storage Section ───────────────────────────────────────────────────

@Composable
private fun StorageSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val cacheLabels = mapOf(
        CacheTier.HOT to "Active Streams",
        CacheTier.WARM to "Catalogue & EPG",
        CacheTier.COLD to "Search Index",
    )
    ExpandableSectionCard(
        title = "Storage",
        subtitle = state.totalCacheSize,
        isExpanded = state.expandedSections.contains(SettingsSection.STORAGE),
        onToggle = { viewModel.toggleSection(SettingsSection.STORAGE) },
        accentColor = com.streamverse.app.ui.theme.CoralRed,
    ) {
        state.cacheStats.forEach { stat ->
            DetailRow(
                label = cacheLabels[stat.tier] ?: stat.tier.label,
                detail = formatCacheBytes(stat.sizeBytes),
                actionLabel = "Clear",
                actionTint = Color(0xFFFF453A),
                onAction = { viewModel.clearCacheTier(stat.tier) },
            )
        }
        InsetDivider()
        DetailRow(
            label = "Clear All Cache",
            detail = state.totalCacheSize,
            actionLabel = "Clear All",
            actionTint = Color(0xFFFF453A),
            onAction = { viewModel.clearAllCache() },
        )
    }
}

// ── Advanced Section ──────────────────────────────────────────────────

@Composable
private fun AdvancedSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    ExpandableSectionCard(
        title = "Advanced",
        subtitle = "Maintenance and developer tools",
        isExpanded = state.expandedSections.contains(SettingsSection.ADVANCED),
        onToggle = { viewModel.toggleSection(SettingsSection.ADVANCED) },
        accentColor = IosGray,
    ) {
        ActionRow("Rebuild Search Index", "Recreate the channel search index from scratch") {
            viewModel.rebuildSearchIndex()
        }
        InsetDivider()
        ActionRow("Refresh Metadata", "Re-fetch channel metadata from all providers") {
            viewModel.refreshMetadata()
        }
        InsetDivider()
        ActionRow("Validate Streams", "Check stream URLs for reachability") {
            viewModel.validateStreams()
        }
    }
}

// ── Diagnostics Section ───────────────────────────────────────────────

@Composable
private fun DiagnosticsSection(state: SettingsUiState) {
    StaticSectionCard(
        title = "Diagnostics",
        subtitle = "Catalogue ${state.diagnostics.catalogueVersion} · ${state.diagnostics.channelCount} channels",
    ) {
        StatRow("Catalogue Version", state.diagnostics.catalogueVersion)
        InsetDivider()
        StatRow("Total Channels", "${state.diagnostics.channelCount}")
        InsetDivider()
        StatRow("Sources", "${state.diagnostics.enabledSourceCount} of ${state.diagnostics.totalSourceCount} enabled")
        InsetDivider()
        StatRow("Last Updated", state.diagnostics.lastUpdated)
        InsetDivider()
        StatRow("Cache Used", state.totalCacheSize)
    }
}

// ── About Section ─────────────────────────────────────────────────────

@Composable
private fun AboutSection(version: String) {
    StaticSectionCard(
        title = "About",
        subtitle = "StreamVerse TV v$version",
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "StreamVerse TV aggregates live channels from multiple free and publicly available streaming sources. No subscription required.",
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Built by Captain Global Technologies — a software studio specialising in digital television, streaming infrastructure, and open-source media tools.",
                style = MaterialTheme.typography.bodySmall,
                color = IosGray,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Version $version",
                style = MaterialTheme.typography.labelSmall,
                color = IosGray.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "© 2026 Captain Global Technologies. All rights reserved.",
                style = MaterialTheme.typography.labelSmall,
                color = IosGray.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Shared Components ─────────────────────────────────────────────────

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = IosGray)
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

@Composable
private fun PickerRow(title: String, subtitle: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = IosGray)
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

@Composable
private fun DetailRow(label: String, detail: String, actionLabel: String? = null, actionTint: Color = IosGray, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = IosGray)
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

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = IosGray)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = IosGray,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = IosGray,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

@Composable
private fun ActionChip(label: String, isActive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) com.streamverse.app.ui.theme.CyberCyan.copy(alpha = 0.2f) else Color(0xFF2C2C2E))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(com.streamverse.app.ui.theme.CyberCyan),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) com.streamverse.app.ui.theme.CyberCyan else Color.White,
        )
    }
}

@Composable
private fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = Color(0xFF38383A),
        thickness = 0.5.dp,
    )
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
