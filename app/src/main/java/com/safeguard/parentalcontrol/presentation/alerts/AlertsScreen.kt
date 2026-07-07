package com.safeguard.parentalcontrol.presentation.alerts

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import java.util.Date

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.presentation.theme.SemanticColors
import com.safeguard.parentalcontrol.presentation.theme.rememberScreenWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveContentWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveScreenPadding
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.shimmerEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.data.model.Alert
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.util.formatAsRelative

/**
 * Alerts screen showing all alerts for parent
 * Optionally filtered by device if deviceId is provided
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onNavigateBack: () -> Unit,
    deviceId: Int? = null,
    deviceName: String? = null,
    viewModel: AlertsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Set device filter when screen loads
    LaunchedEffect(deviceId, deviceName) {
        viewModel.setDevice(deviceId, deviceName)
    }

    // Show error snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    // Show success snackbar
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearSuccessMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.alerts_title))
                        uiState.deviceName?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.markAllAsRead() }) {
                        Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.alerts_cd_mark_all_read))
                    }
                    IconButton(onClick = { viewModel.loadAlerts() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.alerts_cd_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedFilter == AlertFilter.ALL,
                        onClick = { viewModel.setFilter(AlertFilter.ALL) },
                        label = { Text(stringResource(R.string.alerts_filter_all)) }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedFilter == AlertFilter.UNREAD,
                        onClick = { viewModel.setFilter(AlertFilter.UNREAD) },
                        label = { Text(stringResource(R.string.alerts_filter_unread)) },
                        leadingIcon = if (uiState.selectedFilter == AlertFilter.UNREAD) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedFilter == AlertFilter.CRITICAL,
                        onClick = { viewModel.setFilter(AlertFilter.CRITICAL) },
                        label = { Text(stringResource(R.string.alerts_filter_critical)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SemanticColors.severityCritical.copy(alpha = 0.2f)
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedFilter == AlertFilter.HIGH,
                        onClick = { viewModel.setFilter(AlertFilter.HIGH) },
                        label = { Text(stringResource(R.string.alerts_filter_high)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SemanticColors.severityHigh.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            when {
                uiState.isLoading && uiState.alerts.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        repeat(5) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(72.dp)
                                    .clip(SafeGuardShapes.large)
                                    .shimmerEffect()
                            )
                        }
                    }
                }
                uiState.alerts.isEmpty() -> {
                    EmptyAlertsState(
                        modifier = Modifier.fillMaxSize(),
                        filter = uiState.selectedFilter
                    )
                }
                else -> {
                    val screenWidth = rememberScreenWidth()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxHeight()
                            .responsiveContentWidth(screenWidth),
                        contentPadding = PaddingValues(responsiveScreenPadding(screenWidth)),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = uiState.alerts,
                            key = { it.id }
                        ) { alert ->
                            AlertCard(
                                alert = alert,
                                onMarkAsRead = { viewModel.markAsRead(alert.id) },
                                onDismiss = { viewModel.dismissAlert(alert.id) },
                                onDelete = { viewModel.deleteAlert(alert.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyAlertsState(
    modifier: Modifier = Modifier,
    filter: AlertFilter
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.NotificationsOff,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (filter) {
                AlertFilter.ALL -> stringResource(R.string.alerts_empty_all)
                AlertFilter.UNREAD -> stringResource(R.string.alerts_empty_unread)
                AlertFilter.CRITICAL -> stringResource(R.string.alerts_empty_critical)
                AlertFilter.HIGH -> stringResource(R.string.alerts_empty_high)
            },
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.alerts_caught_up),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertCard(
    alert: Alert,
    onMarkAsRead: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val (icon, color) = when (alert.severity) {
        AlertSeverity.CRITICAL -> Icons.Default.Error to SemanticColors.severityCritical
        AlertSeverity.HIGH -> Icons.Default.Warning to SemanticColors.severityHigh
        AlertSeverity.MEDIUM -> Icons.Default.Info to SemanticColors.severityMedium
        AlertSeverity.LOW -> Icons.Default.CheckCircle to SemanticColors.severityLow
    }

    val typeIcon = when (alert.alertType) {
        AlertType.CONTENT_BLOCK -> Icons.Default.Block
        AlertType.SCREEN_TIME_LIMIT -> Icons.Default.Timer
        AlertType.APP_BLOCKED -> Icons.Default.AppBlocking
        AlertType.INAPPROPRIATE_IMAGE -> Icons.Default.Image
        AlertType.INAPPROPRIATE_TEXT -> Icons.Default.TextFields
        AlertType.SCREENSHOT_CAPTURED -> Icons.Default.Screenshot
        AlertType.DEVICE_ADMIN_DISABLED -> Icons.Default.AdminPanelSettings
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (!alert.isRead) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Severity icon
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Title with unread indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = alert.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!alert.isRead) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(modifier = Modifier.size(8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Message
                    Text(
                        text = alert.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Type and time
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = alert.alertType.name.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "•",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = alert.createdAt.formatAsRelative(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.alerts_cd_more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (!alert.isRead) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.alerts_mark_read)) },
                                onClick = {
                                    showMenu = false
                                    onMarkAsRead()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Done, contentDescription = null)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.alerts_dismiss)) },
                            onClick = {
                                showMenu = false
                                onDismiss()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.alerts_delete)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun sampleAlert() = Alert(
    id = 1, userId = 1, deviceId = 1, alertType = AlertType.INAPPROPRIATE_TEXT,
    severity = AlertSeverity.HIGH, title = "Flagged message",
    message = "Detected risky language in a chat app.", metadata = null,
    isRead = false, isDismissed = false, createdAt = Date(), updatedAt = null, occurrenceCount = 3
)
@Composable
private fun AlertsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AlertCard(sampleAlert(), {}, {}, {})
        }
    }
}
@Preview(name = "Alerts · Light", showBackground = true)
@Composable private fun AlertsLightPreview() { SafeGuardTheme(darkTheme = false) { AlertsPreviewContent() } }
@Preview(name = "Alerts · Dark", showBackground = true)
@Composable private fun AlertsDarkPreview() { SafeGuardTheme(darkTheme = true) { AlertsPreviewContent() } }
