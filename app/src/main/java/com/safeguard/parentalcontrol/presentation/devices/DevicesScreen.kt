package com.safeguard.parentalcontrol.presentation.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.data.model.Device
import com.safeguard.parentalcontrol.data.model.DeviceStatus
import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.shimmerEffect
import androidx.compose.ui.draw.clip
import java.util.Date
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
                title = {
                    Text(
                        if (childName != null) {
                            stringResource(R.string.devices_title_child, childName)
                        } else {
                            stringResource(R.string.devices_title)
                        }
                    )
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
                    IconButton(onClick = { viewModel.loadDevices(childId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.alerts_cd_refresh))
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(SafeGuardDimens.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
                    ) {
                        repeat(4) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(88.dp)
                                    .clip(SafeGuardShapes.large)
                                    .shimmerEffect()
                            )
                        }
                    }
                }
                uiState.devices.isEmpty() -> {
                    EmptyState(modifier = Modifier.align(Alignment.Center))
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
                        item {
                            Text(
                                text = if (uiState.devices.size == 1) {
                                    stringResource(R.string.devices_count_one)
                                } else {
                                    stringResource(R.string.devices_count_other, uiState.devices.size)
                                },
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
            text = stringResource(R.string.devices_empty_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.devices_empty_subtitle),
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
    val connectivityColor = if (device.isOnline) SemanticColors.statusOnline else SemanticColors.statusOffline
    val connectivityIcon = if (device.isOnline) Icons.Default.Wifi else Icons.Default.WifiOff

    // Parental control status (active/suspended/inactive)
    val statusColor = when (device.status) {
        DeviceStatus.ACTIVE -> SemanticColors.statusOnline
        DeviceStatus.SUSPENDED -> SemanticColors.statusSuspended
        DeviceStatus.INACTIVE -> SemanticColors.statusOffline
    }

    val statusText = when (device.status) {
        DeviceStatus.ACTIVE -> stringResource(R.string.devices_status_active)
        DeviceStatus.SUSPENDED -> stringResource(R.string.devices_status_suspended)
        DeviceStatus.INACTIVE -> stringResource(R.string.devices_status_inactive)
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
                            contentDescription = if (device.isOnline) {
                                stringResource(R.string.dashboard_status_online)
                            } else {
                                stringResource(R.string.dashboard_status_offline)
                            },
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
                                text = " â€¢ ${lastSync.formatAsRelative()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                IconButton(onClick = onClick) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = stringResource(R.string.devices_cd_view_details),
                        modifier = Modifier.mirrorInRtl()
                    )
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
                    Text(stringResource(R.string.devices_set_limits))
                }
                OutlinedButton(
                    onClick = onNavigateToBlacklist,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.devices_blocked_sites))
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
            title = { Text(stringResource(R.string.devices_remove_title, device.deviceName)) },
            text = {
                Text(stringResource(R.string.devices_remove_message))
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
                    Text(stringResource(R.string.devices_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
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
                // Sheet content is taller than one screen (info + content filtering +
                // actions incl. Remove Device); without this the bottom buttons get
                // clipped off-screen with no way to reach them.
                .verticalScroll(rememberScrollState())
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
                label = stringResource(R.string.devices_android_version),
                value = device.androidVersion ?: stringResource(R.string.devices_unknown)
            )
            DeviceInfoRow(
                icon = Icons.Default.AppSettingsAlt,
                label = stringResource(R.string.devices_app_version),
                value = device.appVersion ?: stringResource(R.string.devices_unknown)
            )
            DeviceInfoRow(
                icon = Icons.Default.Circle,
                label = stringResource(R.string.devices_status_label),
                value = when (device.status) {
                    DeviceStatus.ACTIVE -> stringResource(R.string.devices_status_plain_active)
                    DeviceStatus.SUSPENDED -> stringResource(R.string.devices_status_suspended)
                    DeviceStatus.INACTIVE -> stringResource(R.string.devices_status_inactive)
                },
                valueColor = when (device.status) {
                    DeviceStatus.ACTIVE -> SemanticColors.statusOnline
                    DeviceStatus.SUSPENDED -> SemanticColors.statusSuspended
                    DeviceStatus.INACTIVE -> SemanticColors.statusOffline
                }
            )
            device.lastSync?.let { lastSync ->
                DeviceInfoRow(
                    icon = Icons.Default.Sync,
                    label = stringResource(R.string.devices_last_sync),
                    value = lastSync.formatAsRelative()
                )
            }
            DeviceInfoRow(
                icon = Icons.Default.CalendarToday,
                label = stringResource(R.string.devices_registered_label),
                value = device.createdAt.formatAsRelative()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Content Filtering Control
            Text(
                text = stringResource(R.string.devices_content_filtering),
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
                                text = stringResource(R.string.devices_block_adult),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (contentFilterEnabled) {
                                    stringResource(R.string.devices_vpn_active)
                                } else {
                                    stringResource(R.string.devices_enable_block)
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
                                text = stringResource(R.string.devices_block_social),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (blockSocialMediaEnabled) {
                                    stringResource(R.string.devices_social_blocked)
                                } else {
                                    stringResource(R.string.devices_social_allowed)
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
                text = stringResource(R.string.devices_actions),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Suspend/Activate button
            if (device.status == DeviceStatus.ACTIVE) {
                OutlinedButton(
                    onClick = onSuspend,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.statusSuspended
                    )
                ) {
                    Icon(Icons.Default.PauseCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.devices_suspend))
                }
            } else if (device.status == DeviceStatus.SUSPENDED) {
                OutlinedButton(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticColors.success
                    )
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.devices_activate))
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
                Text(stringResource(R.string.devices_screen_time_limits))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Blocked sites
            OutlinedButton(
                onClick = onNavigateToBlacklist,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Block, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.devices_manage_blocked))
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
                Text(stringResource(R.string.devices_remove_device))
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

private fun sampleDevice(status: DeviceStatus) = Device(
    id = 1, userId = 1, deviceId = "dev-1", deviceName = "Alex's Phone",
    deviceModel = "Pixel 7", androidVersion = "14", appVersion = "1.1.2",
    status = status, lastSync = Date(), createdAt = Date()
)

@Composable
private fun DevicesPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
        ) {
            DeviceCard(sampleDevice(DeviceStatus.ACTIVE), {}, {}, {})
            DeviceCard(sampleDevice(DeviceStatus.SUSPENDED), {}, {}, {})
        }
    }
}

@Preview(name = "Devices · Light", showBackground = true)
@Composable
private fun DevicesScreenLightPreview() {
    SafeGuardTheme(darkTheme = false) { DevicesPreviewContent() }
}

@Preview(name = "Devices · Dark", showBackground = true)
@Composable
private fun DevicesScreenDarkPreview() {
    SafeGuardTheme(darkTheme = true) { DevicesPreviewContent() }
}
