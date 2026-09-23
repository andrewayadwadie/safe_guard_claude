package com.safeguard.parentalcontrol.presentation.settings

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl

/**
 * Screen for configuring text monitoring settings.
 *
 * Allows parents to:
 * - Enable/disable text monitoring globally
 * - Toggle specific content categories
 * - Configure alert preferences
 * - View alert statistics per category
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextMonitoringSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToWordList: () -> Unit,
    viewModel: TextMonitoringSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.textmon_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.common_back), modifier = Modifier.mirrorInRtl())
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStats() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.alerts_cd_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Main toggle
                MainToggleCard(
                    isEnabled = uiState.isTextMonitoringEnabled,
                    onToggle = { viewModel.setTextMonitoringEnabled(it) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Categories section
                Text(
                    text = stringResource(R.string.textmon_categories_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.textmon_categories_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                uiState.categories.forEach { category ->
                    CategoryCard(
                        category = category,
                        enabled = uiState.isTextMonitoringEnabled,
                        onToggle = { enabled ->
                            viewModel.setCategoryEnabled(category.id, enabled)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom word list button
                OutlinedButton(
                    onClick = onNavigateToWordList,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.textmon_manage_wordlist))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Alert preferences section
                Text(
                    text = stringResource(R.string.textmon_alert_prefs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                PreferenceToggle(
                    title = stringResource(R.string.textmon_alert_sound_title),
                    description = stringResource(R.string.textmon_alert_sound_desc),
                    icon = Icons.Default.VolumeUp,
                    isEnabled = uiState.alertSoundEnabled,
                    onToggle = { viewModel.setAlertSoundEnabled(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                PreferenceToggle(
                    title = stringResource(R.string.textmon_vibration_title),
                    description = stringResource(R.string.textmon_vibration_desc),
                    icon = Icons.Default.Vibration,
                    isEnabled = uiState.vibrationEnabled,
                    onToggle = { viewModel.setVibrationEnabled(it) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Info card
                InfoCard()

                // Debug-only delivery test. Compiled out of release builds, and the repository
                // refuses the call there as well. Present because verifying the alert path
                // otherwise means waiting out a 60–300s category cooldown between attempts.
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { viewModel.fireTestAlert() },
                        enabled = !uiState.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send test alert (debug)")
                    }
                }
            }
        }
    }
}

@Composable
private fun MainToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.Shield else Icons.Default.ShieldMoon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.textmon_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isEnabled) {
                        stringResource(R.string.textmon_monitoring_active)
                    } else {
                        stringResource(R.string.textmon_monitoring_paused)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun CategoryCard(
    category: MonitoringCategory,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = category.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (category.isCritical) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.textmon_required_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = category.isEnabled,
                    onCheckedChange = onToggle,
                    enabled = enabled && !category.isCritical
                )
            }

            // Show alert stats if available
            category.alertStats?.let { stats ->
                if (stats.alertsToday > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.textmon_alerts_today, stats.alertsToday, stats.dailyLimit),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!stats.isCooldownPassed()) {
                            Text(
                                text = stringResource(R.string.textmon_cooldown, stats.getRemainingCooldownSeconds()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceToggle(
    title: String,
    description: String,
    icon: ImageVector,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = stringResource(R.string.textmon_privacy_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.textmon_privacy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TextMonitoringPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp)) { InfoCard() }
    }
}

@Preview(name = "TextMonitoring · Light", showBackground = true)
@Composable private fun TextMonitoringLightPreview() { SafeGuardTheme(darkTheme = false) { TextMonitoringPreviewContent() } }
@Preview(name = "TextMonitoring · Dark", showBackground = true)
@Composable private fun TextMonitoringDarkPreview() { SafeGuardTheme(darkTheme = true) { TextMonitoringPreviewContent() } }
