package com.safeguard.parentalcontrol.presentation.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.data.model.Device
import com.safeguard.parentalcontrol.data.model.DeviceStatus
import com.safeguard.parentalcontrol.util.formatAsRelative

/**
 * Screen for managing monitored devices (parent view)
 * @param childId Optional - if provided, shows only this child's devices
 * @param childName Optional - the child's name for display in the title
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBlacklist: (deviceId: Int, deviceName: String) -> Unit = { _, _ -> },
    onNavigateToScreenTimeLimits: (deviceId: Int, deviceName: String) -> Unit = { _, _ -> },
    childId: Int? = null,
    childName: String? = null,
    viewModel: DevicesViewModel = hiltViewModel()
) {
    // Load devices filtered by childId if provided
    LaunchedEffect(childId) {
        viewModel.loadDevices(childId)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

    // Device details bottom sheet
    if (uiState.showDeviceDetails && uiState.selectedDevice != null) {
        DeviceDetailsSheet(
            device = uiState.selectedDevice!!,
            contentFilterEnabled = uiState.contentFilterEnabled,
            blockSocialMediaEnabled = uiState.blockSocialMediaEnabled,
            isLoadingContentFilter = uiState.isLoadingContentFilter,
            onDismiss = { viewModel.hideDeviceDetails() },
            onSuspend = { viewModel.suspendDevice(uiState.selectedDevice!!.id) },
            onActivate = { viewModel.activateDevice(uiState.selectedDevice!!.id) },
            onDelete = { viewModel.deleteDevice(uiState.selectedDevice!!.id, uiState.selectedDevice!!.deviceName) },
            onToggleContentFilter = { enabled ->
                viewModel.toggleContentFiltering(uiState.selectedDevice!!.id, enabled)
            },
            onToggleSocialMedia = { blocked ->
                viewModel.toggleSocialMediaBlocking(uiState.selectedDevice!!.id, blocked)
            },
            onNavigateToBlacklist = {
                viewModel.hideDeviceDetails()
                onNavigateToBlacklist(uiState.selectedDevice!!.id, uiState.selectedDevice!!.deviceName)
            },
            onNavigateToScreenTimeLimits = {
                viewModel.hideDeviceDetails()
                onNavigateToScreenTimeLimits(uiState.selectedDevice!!.id, uiState.selectedDevice!!.deviceName)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (childName != null) "$childName's Devices" else "Devices") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDevices(childId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.devices.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.devices.isEmpty() -> {
                    EmptyState(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "${uiState.devices.size} device${if (uiState.devices.size != 1) "s" else ""} registered",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(
                            items = uiState.devices,
                            key = { it.id }
                        ) { device ->
                            DeviceCard(
                                device = device,
                                onClick = { viewModel.showDeviceDetails(device) },
                                onNavigateToBlacklist = { onNavigateToBlacklist(device.id, device.deviceName) },
                                onNavigateToScreenTimeLimits = { onNavigateToScreenTimeLimits(device.id, device.deviceName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No devices found",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your children's devices will appear here once they install SafeGuard and link to your account",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToScreenTimeLimits: () -> Unit
) {
    // Online/Offline status based on lastSync time (15 min threshold)
    val connectivityColor = if (device.isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    val connectivityIcon = if (device.isOnline) Icons.Default.Wifi else Icons.Default.WifiOff

    // Parental control status (active/suspended/inactive)
    val statusColor = when (device.status) {
        DeviceStatus.ACTIVE -> Color(0xFF4CAF50)
        DeviceStatus.SUSPENDED -> Color(0xFFFF9800)
        DeviceStatus.INACTIVE -> Color(0xFF9E9E9E)
    }

    val statusText = when (device.status) {
        DeviceStatus.ACTIVE -> "Monitoring Active"
        DeviceStatus.SUSPENDED -> "Suspended"
        DeviceStatus.INACTIVE -> "Inactive"
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
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
                // Device icon
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Online/Offline indicator based on lastSync
                        Icon(
                            imageVector = connectivityIcon,
                            contentDescription = if (device.isOnline) "Online" else "Offline",
                            tint = connectivityColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (device.deviceModel != null) {
                        Text(
                            text = device.deviceModel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Show both status and last sync time
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                        device.lastSync?.let { lastSync ->
                            Text(
                                text = " • ${lastSync.formatAsRelative()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IconButton(onClick = onClick) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "View details")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToScreenTimeLimits,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Set Limits")
                }
                OutlinedButton(
                    onClick = onNavigateToBlacklist,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Blocked Sites")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailsSheet(
    device: Device,
    contentFilterEnabled: Boolean,
    blockSocialMediaEnabled: Boolean,
    isLoadingContentFilter: Boolean,
    onDismiss: () -> Unit,
    onSuspend: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onToggleContentFilter: (Boolean) -> Unit,
    onToggleSocialMedia: (Boolean) -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToScreenTimeLimits: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Remove ${device.deviceName}?") },
            text = {
                Text("This will remove the device and all its monitoring data. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    device.deviceModel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Device info
            DeviceInfoRow(
                icon = Icons.Default.Android,
                label = "Android Version",
                value = device.androidVersion ?: "Unknown"
            )
            DeviceInfoRow(
                icon = Icons.Default.AppSettingsAlt,
                label = "App Version",
                value = device.appVersion ?: "Unknown"
            )
            DeviceInfoRow(
                icon = Icons.Default.Circle,
                label = "Status",
                value = device.status.name.lowercase().replaceFirstChar { it.uppercase() },
                valueColor = when (device.status) {
                    DeviceStatus.ACTIVE -> Color(0xFF4CAF50)
                    DeviceStatus.SUSPENDED -> Color(0xFFFF9800)
                    DeviceStatus.INACTIVE -> Color(0xFF9E9E9E)
                }
            )
            device.lastSync?.let { lastSync ->
                DeviceInfoRow(
                    icon = Icons.Default.Sync,
                    label = "Last Sync",
                    value = lastSync.formatAsRelative()
                )
            }
            DeviceInfoRow(
                icon = Icons.Default.CalendarToday,
                label = "Registered",
                value = device.createdAt.formatAsRelative()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Content Filtering Control
            Text(
                text = "Content Filtering",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Block Adult Content toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (contentFilterEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Block Adult Content",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (contentFilterEnabled) {
                                    "VPN filtering is active on this device"
                                } else {
                                    "Enable to block inappropriate websites"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isLoadingContentFilter) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = contentFilterEnabled,
                                onCheckedChange = onToggleContentFilter
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

                    // Block Social Media toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = if (blockSocialMediaEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Block Social Media",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (blockSocialMediaEnabled) {
                                    "Social media sites are blocked"
                                } else {
                                    "Social media is allowed"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isLoadingContentFilter) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = blockSocialMediaEnabled,
                                onCheckedChange = onToggleSocialMedia
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Actions
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Suspend/Activate button
            if (device.status == DeviceStatus.ACTIVE) {
                OutlinedButton(
                    onClick = onSuspend,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF9800)
                    )
                ) {
                    Icon(Icons.Default.PauseCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Suspend Device")
                }
            } else if (device.status == DeviceStatus.SUSPENDED) {
                OutlinedButton(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Activate Device")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Screen time limits
            OutlinedButton(
                onClick = onNavigateToScreenTimeLimits,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Timer, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Screen Time Limits")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Blocked sites
            OutlinedButton(
                onClick = onNavigateToBlacklist,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Block, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Blocked Sites")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Delete button
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Remove Device")
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor
        )
    }
}
