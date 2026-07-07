package com.safeguard.parentalcontrol.presentation.dashboard

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import java.util.Date
import com.safeguard.parentalcontrol.service.MonitoringService
import timber.log.Timber
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.presentation.components.*
import com.safeguard.parentalcontrol.presentation.theme.*
import com.safeguard.parentalcontrol.presentation.designsystem.HarisGradientHeader
import com.safeguard.parentalcontrol.presentation.designsystem.HarisLogo
import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.util.formatAsHoursMinutes
import com.safeguard.parentalcontrol.util.formatAsRelative
import kotlinx.coroutines.delay

/**
 * Main dashboard screen with modern, professional UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToChildren: () -> Unit = {},
    onNavigateToScreenTimeLimits: (deviceId: Int, deviceName: String) -> Unit = { _, _ -> },
    onNavigateToPermissionsSetup: () -> Unit = {},
    onNavigateToLinkParent: () -> Unit = {},
    onLogout: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    // Re-check permissions and start service when returning from settings or first showing
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkPermissions()
                if (!uiState.isParent && viewModel.checkUsageStatsPermission()) {
                    viewModel.loadDashboard()
                }

                // Ensure MonitoringService is running for enforcement
                // This handles the case where the user just registered their device
                try {
                    val serviceIntent = Intent(context, MonitoringService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Timber.d("DashboardScreen: MonitoringService start command sent on resume")
                } catch (e: Exception) {
                    Timber.e(e, "DashboardScreen: Failed to start MonitoringService")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Menu and dialog state
    var showMenu by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = {
                showLogoutDialog = false
                viewModel.logout()
                onLogout()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                unreadAlertCount = uiState.unreadAlertCount,
                isRefreshing = uiState.isRefreshing,
                isParent = uiState.isParent,
                showMenu = showMenu,
                onRefresh = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.refreshAll()
                },
                onNavigateToAlerts = onNavigateToAlerts,
                onMenuToggle = { showMenu = it },
                onNavigateToChildren = onNavigateToChildren,
                onNavigateToDevices = onNavigateToDevices,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToLinkParent = onNavigateToLinkParent,
                onLogoutClick = { showLogoutDialog = true }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // Pull to refresh wrapper
        SafeGuardPullToRefresh(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier.padding(padding)
        ) {
            // Show loading skeleton during initial load
            if (uiState.isLoading && uiState.topApps.isEmpty()) {
                SkeletonDashboard(isParent = uiState.isParent)
            } else {
                DashboardContent(
                    uiState = uiState,
                    isParent = uiState.isParent,
                    onDeviceSelected = { viewModel.selectDevice(it) },
                    onNavigateToAlerts = onNavigateToAlerts,
                    onNavigateToScreenTimeLimits = onNavigateToScreenTimeLimits,
                    onNavigateToPermissionsSetup = onNavigateToPermissionsSetup,
                    onRequestUsageStats = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    },
                    onToggleContentFilter = { viewModel.toggleContentFiltering(it) },
                    onToggleSocialMediaSubMenu = { viewModel.toggleSocialMediaSubMenu() },
                    onTogglePlatform = { platform, blocked -> viewModel.toggleSocialMediaPlatform(platform, blocked) },
                    onBlockAllSocialMedia = { viewModel.blockAllSocialMedia() },
                    onUnblockAllSocialMedia = { viewModel.unblockAllSocialMedia() },
                    onMarkAlertAsRead = { viewModel.markAlertAsRead(it) }
                )
            }
        }
    }
}

// ============================================================================
// TOP BAR
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    unreadAlertCount: Int,
    isRefreshing: Boolean,
    isParent: Boolean,
    showMenu: Boolean,
    onRefresh: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onMenuToggle: (Boolean) -> Unit,
    onNavigateToChildren: () -> Unit,
    onNavigateToDevices: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLinkParent: () -> Unit,
    onLogoutClick: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App logo/icon — Haris brand mark, shown as-is (no box, no tint)
                HarisLogo(size = 36.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isParent) stringResource(R.string.dashboard_role_parent) else stringResource(R.string.dashboard_role_child),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = {
            // Refresh button with animation
            val refreshCd = stringResource(R.string.dashboard_cd_refresh)
            IconButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                modifier = Modifier.semantics {
                    contentDescription = refreshCd
                }
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.dashboard_refresh)
                    )
                }
            }

            // Alerts badge
            BadgedBox(
                badge = {
                    if (unreadAlertCount > 0) {
                        Badge(
                            containerColor = SemanticColors.error
                        ) {
                            Text(
                                text = if (unreadAlertCount > 99) stringResource(R.string.dashboard_alert_count_max) else unreadAlertCount.toString(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            ) {
                val alertsCd = stringResource(R.string.dashboard_cd_alerts, unreadAlertCount)
                IconButton(
                    onClick = onNavigateToAlerts,
                    modifier = Modifier.semantics {
                        contentDescription = alertsCd
                    }
                ) {
                    Icon(
                        imageVector = if (unreadAlertCount > 0) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                        contentDescription = stringResource(R.string.alerts_title)
                    )
                }
            }

            // Overflow menu
            Box {
                IconButton(onClick = { onMenuToggle(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.dashboard_cd_more_options))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { onMenuToggle(false) }
                ) {
                    if (isParent) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dashboard_menu_children)) },
                            onClick = {
                                onMenuToggle(false)
                                onNavigateToChildren()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.People, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.devices_title)) },
                            onClick = {
                                onMenuToggle(false)
                                onNavigateToDevices()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Devices, contentDescription = null)
                            }
                        )
                        Divider()
                    }
                    if (!isParent) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dashboard_menu_link_parent)) },
                            onClick = {
                                onMenuToggle(false)
                                onNavigateToLinkParent()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Link, contentDescription = null)
                            }
                        )
                        Divider()
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_title)) },
                        onClick = {
                            onMenuToggle(false)
                            onNavigateToSettings()
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Settings, contentDescription = null)
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.logout), color = SemanticColors.error) },
                        onClick = {
                            onMenuToggle(false)
                            onLogoutClick()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Logout,
                                contentDescription = null,
                                tint = SemanticColors.error
                            )
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ============================================================================
// DASHBOARD CONTENT
// ============================================================================

@Composable
private fun DashboardContent(
    uiState: DashboardUiState,
    isParent: Boolean,
    onDeviceSelected: (Int) -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToScreenTimeLimits: (deviceId: Int, deviceName: String) -> Unit,
    onNavigateToPermissionsSetup: () -> Unit,
    onRequestUsageStats: () -> Unit,
    onToggleContentFilter: (Boolean) -> Unit,
    onToggleSocialMediaSubMenu: () -> Unit,
    onTogglePlatform: (SocialMediaPlatform, Boolean) -> Unit,
    onBlockAllSocialMedia: () -> Unit,
    onUnblockAllSocialMedia: () -> Unit,
    onMarkAlertAsRead: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // Derive computed values to avoid recomposition
    val topAppsToShow by remember(uiState.topApps) {
        derivedStateOf { uiState.topApps.take(5) }
    }

    val recentAlertsToShow by remember(uiState.recentAlerts) {
        derivedStateOf { uiState.recentAlerts.take(3) }
    }

    val hasAppsToShow by remember(uiState.topApps) {
        derivedStateOf { uiState.topApps.isNotEmpty() }
    }

    val hasAlertsToShow by remember(uiState.recentAlerts) {
        derivedStateOf { uiState.recentAlerts.isNotEmpty() }
    }

    // Staggered animation state
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        showContent = true
    }

    val screenWidth = rememberScreenWidth()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxHeight()
            .responsiveContentWidth(screenWidth),
        contentPadding = PaddingValues(
            horizontal = responsiveScreenPadding(screenWidth),
            vertical = SafeGuardDimens.spacingMd
        ),
        verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingLg)
    ) {
        // Brand gradient summary banner
        item {
            HarisGradientHeader(modifier = Modifier.fadeScaleIn { showContent }) {
                Column {
                    Text(
                        text = stringResource(R.string.dashboard_welcome),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.dashboard_family_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
            }
        }

        // Monitoring offline warning (parents). A child device that was reporting has
        // gone silent — the only way to surface a force-stop / uninstall / OEM kill,
        // since a dead app can't report its own death. Server-side this is
        // indistinguishable from "powered off / no network", so the copy covers all three.
        if (isParent) {
            val now = System.currentTimeMillis()
            // Loud banner only after 30 min of silence (2 missed 15-min sync cycles) so
            // it doesn't flap; the per-device status dot still flips at the 15-min mark.
            val offlineDevices = uiState.devices.filter { d ->
                d.status == DeviceStatus.ACTIVE &&
                    (d.lastSync?.let { now - it.time > 30 * 60 * 1000L } ?: false)
            }
            if (offlineDevices.isNotEmpty()) {
                item(key = "monitoring_offline_banner") {
                    AnimatedCard(visible = showContent) {
                        val first = offlineDevices.first()
                        val fallbackTime = stringResource(R.string.dashboard_offline_fallback_time)
                        val message = if (offlineDevices.size == 1) {
                            stringResource(
                                R.string.dashboard_offline_single,
                                first.deviceName,
                                first.lastSync?.formatAsRelative() ?: fallbackTime
                            )
                        } else {
                            stringResource(R.string.dashboard_offline_multiple, offlineDevices.size)
                        }
                        InfoBanner(
                            message = message,
                            type = BannerType.WARNING,
                            icon = Icons.Default.CloudOff,
                            actionLabel = stringResource(R.string.common_view),
                            onAction = { onDeviceSelected(first.id) }
                        )
                    }
                }
            }
        }

        // Permission setup banner for child devices
        if (uiState.showPermissionBanner && !isParent) {
            item(key = "permission_banner") {
                AnimatedCard(visible = showContent) {
                    PermissionSetupBanner(
                        hasAccessibility = uiState.hasAccessibilityPermission,
                        hasUsageStats = uiState.hasUsageStatsPermission,
                        hasOverlay = uiState.hasOverlayPermission,
                        hasBatteryOptimization = uiState.hasBatteryOptimizationDisabled,
                        onSetupClick = onNavigateToPermissionsSetup
                    )
                }
            }
        }

        // Device selector for parents
        if (isParent && uiState.devices.isNotEmpty()) {
            item(key = "device_selector") {
                AnimatedCard(visible = showContent) {
                    EnhancedDeviceSelectorSection(
                        devices = uiState.devices,
                        selectedDeviceId = uiState.selectedDeviceId,
                        onDeviceSelected = { deviceId ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDeviceSelected(deviceId)
                        }
                    )
                }
            }
        }

        // Usage Stats Permission Warning (for child devices)
        if (!uiState.hasUsageStatsPermission && !isParent) {
            item(key = "usage_stats_warning") {
                AnimatedCard(visible = showContent) {
                    UsageStatsPermissionCard(onRequestPermission = onRequestUsageStats)
                }
            }
        }

        // Screen Time Hero Card - Different for parent vs child
        item(key = "screen_time_hero") {
            AnimatedCard(
                visible = showContent,
                enterTransition = fadeIn(tween(300)) + scaleIn(initialScale = 0.95f)
            ) {
                if (isParent) {
                    EnhancedScreenTimeHeroCard(
                        usedSeconds = uiState.todayScreenTime,
                        limitSeconds = uiState.dailyLimit,
                        unlockCount = uiState.todayUnlocks,
                        isParent = true,
                        isStale = !uiState.hasTodayScreenTimeData,
                        lastSync = uiState.currentDevice?.lastSync,
                        onManageLimits = uiState.currentDevice?.let { device ->
                            { onNavigateToScreenTimeLimits(device.id, device.deviceName) }
                        }
                    )
                } else {
                    // Use child-friendly display for child devices
                    ChildScreenTimeCard(
                        usedSeconds = uiState.todayScreenTime,
                        limitSeconds = uiState.dailyLimit,
                        unlockCount = uiState.todayUnlocks
                    )
                }
            }
        }

        // Quick Actions Row for Parents
        uiState.currentDevice?.let { device ->
            if (isParent) {
                item(key = "quick_actions") {
                    AnimatedCard(visible = showContent) {
                        QuickActionsRow(
                            onManageLimits = {
                                onNavigateToScreenTimeLimits(device.id, device.deviceName)
                            },
                        onViewAlerts = onNavigateToAlerts,
                        unreadAlertCount = uiState.unreadAlertCount
                    )
                }
            }
        }
        }

        // Content Filtering Card (for parents with a selected device)
        if (isParent && uiState.currentDevice != null) {
            item(key = "content_filtering") {
                AnimatedCard(visible = showContent) {
                    ContentFilteringSection(
                        contentFilterEnabled = uiState.contentFilterEnabled,
                        blockedPlatforms = uiState.blockedSocialMediaPlatforms,
                        showSubMenu = uiState.showSocialMediaSubMenu,
                        isLoading = uiState.isLoadingContentFilter,
                        onToggleContentFilter = onToggleContentFilter,
                        onToggleSocialMediaSubMenu = onToggleSocialMediaSubMenu,
                        onTogglePlatform = onTogglePlatform,
                        onBlockAll = onBlockAllSocialMedia,
                        onUnblockAll = onUnblockAllSocialMedia
                    )
                }
            }
        }

        // Top Apps Section
        if (hasAppsToShow) {
            item(key = "top_apps_header") {
                AnimatedCard(visible = showContent) {
                    SectionHeader(
                        title = stringResource(R.string.top_apps),
                        subtitle = if (uiState.topApps.size == 1) {
                            stringResource(R.string.dashboard_apps_used_one)
                        } else {
                            stringResource(R.string.dashboard_apps_used_other, uiState.topApps.size)
                        },
                        modifier = Modifier.semantics { heading() }
                    )
                }
            }

            itemsIndexed(
                items = topAppsToShow,
                key = { _, app -> "app_${app.id}" }
            ) { index, app ->
                StaggeredListItem(
                    index = index,
                    visible = showContent
                ) {
                    if (isParent) {
                        EnhancedAppUsageCard(
                            appName = app.appName ?: app.packageName.substringAfterLast('.'),
                            packageName = app.packageName,
                            usageMinutes = app.usageTime / 60,
                            rank = index + 1,
                            totalUsageMinutes = uiState.topApps.sumOf { it.usageTime / 60 }
                        )
                    } else {
                        ChildAppUsageCard(
                            appName = app.appName ?: app.packageName.substringAfterLast('.'),
                            usageMinutes = app.usageTime / 60,
                            rank = index + 1
                        )
                    }
                }
            }
        } else if (uiState.hasUsageStatsPermission || isParent) {
            item(key = "empty_apps") {
                AnimatedCard(visible = showContent) {
                    InlineEmptyState(
                        icon = Icons.Outlined.Apps,
                        message = stringResource(R.string.dashboard_no_apps_message)
                    )
                }
            }
        }

        // Recent Alerts Section
        if (hasAlertsToShow) {
            item(key = "alerts_header") {
                AnimatedCard(visible = showContent) {
                    SectionHeader(
                        title = stringResource(R.string.recent_alerts),
                        subtitle = stringResource(R.string.dashboard_unread_count, uiState.unreadAlertCount),
                        actionLabel = stringResource(R.string.see_all),
                        onAction = onNavigateToAlerts,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            }

            itemsIndexed(
                items = recentAlertsToShow,
                key = { _, alert -> "alert_${alert.id}" }
            ) { index, alert ->
                StaggeredListItem(
                    index = index,
                    visible = showContent
                ) {
                    EnhancedAlertCard(
                        title = alert.title,
                        message = alert.message,
                        timestamp = alert.createdAt.formatAsRelative(),
                        severity = when (alert.severity) {
                            AlertSeverity.CRITICAL -> AlertSeverityLevel.CRITICAL
                            AlertSeverity.HIGH -> AlertSeverityLevel.HIGH
                            AlertSeverity.MEDIUM -> AlertSeverityLevel.MEDIUM
                            AlertSeverity.LOW -> AlertSeverityLevel.LOW
                        },
                        isRead = alert.isRead,
                        occurrenceCount = alert.occurrenceCount,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMarkAlertAsRead(alert.id)
                        }
                    )
                }
            }
        } else {
            item(key = "empty_alerts") {
                AnimatedCard(visible = showContent) {
                    if (isParent) {
                        InlineEmptyState(
                            icon = Icons.Outlined.NotificationsNone,
                            message = stringResource(R.string.dashboard_no_alerts_parent)
                        )
                    } else {
                        EncouragementBanner(
                            message = stringResource(R.string.dashboard_no_alerts_child),
                            icon = Icons.Default.EmojiEvents
                        )
                    }
                }
            }
        }

        // Bottom spacing
        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))
        }
    }
}

// ============================================================================
// DEVICE SELECTOR SECTION
// ============================================================================

@Composable
private fun DeviceSelectorSection(
    devices: List<Device>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.dashboard_select_device),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = SafeGuardDimens.spacingSm)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingSm)
        ) {
            items(
                items = devices,
                key = { "device_chip_${it.id}" }
            ) { device ->
                val isSelected = device.id == selectedDeviceId
                // Use isOnline for connectivity status (based on lastSync time threshold)
                val connectivityColor = if (device.isOnline) SemanticColors.statusOnline else SemanticColors.statusOffline
                val connectivityText = if (device.isOnline) stringResource(R.string.dashboard_status_online) else stringResource(R.string.dashboard_status_offline)

                FilterChip(
                    selected = isSelected,
                    onClick = { onDeviceSelected(device.id) },
                    label = { Text(device.deviceName) },
                    leadingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(connectivityColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = when {
                                    device.status == DeviceStatus.SUSPENDED -> Icons.Default.Block
                                    device.isOnline -> Icons.Default.PhoneAndroid
                                    else -> Icons.Default.PhonelinkOff
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "${device.deviceName}, $connectivityText"
                    }
                )
            }
        }
    }
}

// ============================================================================
// PERMISSION CARDS
// ============================================================================

@Composable
private fun UsageStatsPermissionCard(
    onRequestPermission: () -> Unit
) {
    InfoBanner(
        message = stringResource(R.string.dashboard_usage_stats_grant_msg),
        type = BannerType.WARNING,
        icon = Icons.Default.Timer,
        actionLabel = stringResource(R.string.common_open_settings),
        onAction = onRequestPermission
    )
}

@Composable
private fun PermissionSetupBanner(
    hasAccessibility: Boolean,
    hasUsageStats: Boolean,
    hasOverlay: Boolean,
    hasBatteryOptimization: Boolean,
    onSetupClick: () -> Unit
) {
    val missingCount = listOf(
        !hasAccessibility,
        !hasUsageStats,
        !hasOverlay,
        !hasBatteryOptimization
    ).count { it }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.warningContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.paddingCard)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SemanticColors.warning.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = SemanticColors.warning,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dashboard_setup_required),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (missingCount == 1) {
                            stringResource(R.string.dashboard_permissions_needed_one)
                        } else {
                            stringResource(R.string.dashboard_permissions_needed_other, missingCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingMd))

            // Permission status indicators
            Column(
                verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingXs)
            ) {
                PermissionStatusItem(stringResource(R.string.permissions_accessibility_title), hasAccessibility)
                PermissionStatusItem(stringResource(R.string.permissions_usage_stats_title), hasUsageStats)
                PermissionStatusItem(stringResource(R.string.permissions_overlay_title), hasOverlay)
                PermissionStatusItem(stringResource(R.string.permissions_battery_title), hasBatteryOptimization)
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingMd))

            SafeGuardButton(
                text = stringResource(R.string.dashboard_complete_setup),
                onClick = onSetupClick,
                icon = Icons.Default.ArrowForward,
                iconPosition = IconPosition.END,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PermissionStatusItem(
    name: String,
    isGranted: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isGranted) SemanticColors.success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = if (isGranted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

// ============================================================================
// CONTENT FILTERING SECTION
// ============================================================================

@Composable
private fun ContentFilteringSection(
    contentFilterEnabled: Boolean,
    blockedPlatforms: Set<SocialMediaPlatform>,
    showSubMenu: Boolean,
    isLoading: Boolean,
    onToggleContentFilter: (Boolean) -> Unit,
    onToggleSocialMediaSubMenu: () -> Unit,
    onTogglePlatform: (SocialMediaPlatform, Boolean) -> Unit,
    onBlockAll: () -> Unit,
    onUnblockAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.paddingCard)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
                Text(
                    text = stringResource(R.string.settings_content_filtering),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

            // Adult Content Toggle
            StatusCard(
                title = stringResource(R.string.dashboard_block_adult_content),
                status = if (contentFilterEnabled) stringResource(R.string.dashboard_vpn_active) else stringResource(R.string.dashboard_filter_disabled),
                isActive = contentFilterEnabled,
                icon = Icons.Default.Shield,
                showToggle = true,
                onToggle = onToggleContentFilter,
                isLoading = isLoading
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingMd))

            // Social Media Section
            Card(
                onClick = onToggleSocialMediaSubMenu,
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(SafeGuardDimens.paddingCardSmall)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dashboard_social_media),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (blockedPlatforms.isEmpty()) {
                                    stringResource(R.string.dashboard_all_platforms_allowed)
                                } else if (blockedPlatforms.size == 1) {
                                    stringResource(R.string.dashboard_platforms_blocked_one)
                                } else {
                                    stringResource(R.string.dashboard_platforms_blocked_other, blockedPlatforms.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (showSubMenu) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showSubMenu) stringResource(R.string.dashboard_collapse) else stringResource(R.string.dashboard_expand),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Expandable platform list
                    AnimatedVisibility(
                        visible = showSubMenu,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = SafeGuardDimens.spacingMd)
                        ) {
                            Divider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(bottom = SafeGuardDimens.spacingMd)
                            )

                            // Quick actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingSm)
                            ) {
                                SafeGuardOutlinedButton(
                                    text = stringResource(R.string.dashboard_block_all),
                                    onClick = onBlockAll,
                                    enabled = !isLoading,
                                    modifier = Modifier.weight(1f),
                                    size = ButtonSize.SMALL
                                )
                                SafeGuardOutlinedButton(
                                    text = stringResource(R.string.dashboard_allow_all),
                                    onClick = onUnblockAll,
                                    enabled = !isLoading,
                                    modifier = Modifier.weight(1f),
                                    size = ButtonSize.SMALL
                                )
                            }

                            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingMd))

                            // Individual platform toggles
                            SocialMediaPlatform.values().forEach { platform ->
                                val isBlocked = blockedPlatforms.contains(platform)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = SafeGuardDimens.spacingXs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = platform.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Switch(
                                            checked = isBlocked,
                                            onCheckedChange = { onTogglePlatform(platform, it) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// ENHANCED COMPONENTS
// ============================================================================

/**
 * Enhanced device selector with better visual feedback
 */
@Composable
private fun EnhancedDeviceSelectorSection(
    devices: List<Device>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.dashboard_select_device),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = SafeGuardDimens.spacingSm)
                .semantics { heading() }
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingMd)
        ) {
            items(
                items = devices,
                key = { "device_chip_${it.id}" }
            ) { device ->
                val isSelected = device.id == selectedDeviceId
                // Use isOnline for connectivity status (based on lastSync time threshold)
                val connectivityColor = if (device.isOnline) SemanticColors.statusOnline else SemanticColors.statusOffline
                val connectivityText = if (device.isOnline) stringResource(R.string.dashboard_status_online) else stringResource(R.string.dashboard_status_offline)

                // Animated selection
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.02f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessHigh),
                    label = "device_scale"
                )

                PressableCard(
                    onClick = { onDeviceSelected(device.id) },
                    modifier = Modifier.scale(scale),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentDescription = "${device.deviceName}, $connectivityText"
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = SafeGuardDimens.spacingMd,
                            vertical = SafeGuardDimens.spacingSm
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pulsing indicator for online devices
                        if (device.isOnline) {
                            PulsingIndicator(
                                color = connectivityColor,
                                size = 10.dp,
                                isPulsing = true
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(connectivityColor)
                            )
                        }

                        Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))

                        Icon(
                            imageVector = when {
                                device.status == DeviceStatus.SUSPENDED -> Icons.Default.Block
                                device.isOnline -> Icons.Default.PhoneAndroid
                                else -> Icons.Default.PhonelinkOff
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))

                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        if (isSelected) {
                            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingXs))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.dashboard_cd_selected),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Enhanced screen time hero card with gradient and animations
 */
@Composable
private fun EnhancedScreenTimeHeroCard(
    usedSeconds: Int,
    limitSeconds: Int?,
    unlockCount: Int,
    isParent: Boolean,
    onManageLimits: (() -> Unit)?,
    // When the phone hasn't reported today, the shown time is 0 and this flags the
    // card to display a "last seen" note (from lastSync) instead of the usual subtitle.
    isStale: Boolean = false,
    lastSync: Date? = null
) {
    val usedMinutes = usedSeconds / 60
    val limitMinutes = limitSeconds?.let { it / 60 }

    val progress = if (limitMinutes != null && limitMinutes > 0) {
        (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f)
    } else {
        0f
    }

    val gradientColors = getScreenTimeGradient(usedMinutes, limitMinutes)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = SafeGuardDimens.elevationMd)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with gradient accent
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.screen_time_today),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isStale) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = lastSync?.let {
                                    stringResource(
                                        R.string.dashboard_last_active,
                                        DateUtils.getRelativeTimeSpanString(
                                            it.time,
                                            System.currentTimeMillis(),
                                            DateUtils.MINUTE_IN_MILLIS
                                        )
                                    )
                                } ?: stringResource(R.string.dashboard_no_activity_yet),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = if (progress >= 1f) stringResource(R.string.dashboard_limit_reached) else stringResource(R.string.dashboard_keep_balanced),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status indicator
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            brush = Brush.horizontalGradient(gradientColors.map { it.copy(alpha = 0.2f) })
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = when {
                            progress >= 1f -> stringResource(R.string.dashboard_status_over_limit)
                            progress >= 0.8f -> stringResource(R.string.dashboard_status_warning)
                            else -> stringResource(R.string.dashboard_status_on_track)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = gradientColors.first()
                    )
                }
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

            // Gradient circular progress
            GradientCircularProgress(
                progress = progress,
                size = 160.dp,
                strokeWidth = 14.dp,
                gradientColors = gradientColors,
                animate = true
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatSecondsToTime(usedSeconds),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = gradientColors.first()
                    )
                    limitMinutes?.let {
                        Text(
                            text = stringResource(R.string.dashboard_of_limit, formatMinutesToTime(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

            // Stats row with animated values
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AnimatedStatDisplay(
                    value = unlockCount,
                    label = stringResource(R.string.unlocks),
                    icon = Icons.Default.LockOpen,
                    iconColor = MaterialTheme.colorScheme.primary
                )

                limitMinutes?.let {
                    val remaining = (it - usedMinutes).coerceAtLeast(0)
                    AnimatedStatDisplay(
                        value = remaining,
                        label = stringResource(R.string.dashboard_remaining),
                        icon = Icons.Default.Timer,
                        iconColor = gradientColors.first(),
                        formatValue = { mins -> formatMinutesToTime(mins) }
                    )
                }
            }

            // Manage limits button for parents
            if (isParent && onManageLimits != null) {
                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

                SafeGuardOutlinedButton(
                    text = stringResource(R.string.dashboard_manage_time_limits),
                    onClick = onManageLimits,
                    icon = Icons.Default.Settings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Quick actions row for parent dashboard
 */
@Composable
private fun QuickActionsRow(
    onManageLimits: () -> Unit,
    onViewAlerts: () -> Unit,
    unreadAlertCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton(
            icon = Icons.Default.Timer,
            label = stringResource(R.string.dashboard_time_limits_label),
            onClick = onManageLimits,
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )

        QuickActionButton(
            icon = if (unreadAlertCount > 0) Icons.Filled.Notifications else Icons.Outlined.Notifications,
            label = stringResource(R.string.alerts_title),
            onClick = onViewAlerts,
            containerColor = if (unreadAlertCount > 0) {
                SemanticColors.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (unreadAlertCount > 0) {
                SemanticColors.error
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )

        QuickActionButton(
            icon = Icons.Default.FilterList,
            label = stringResource(R.string.dashboard_filters_label),
            onClick = { /* Scroll to filters */ },
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

/**
 * Enhanced app usage card with progress visualization
 */
@Composable
private fun EnhancedAppUsageCard(
    appName: String,
    @Suppress("UNUSED_PARAMETER") packageName: String,
    usageMinutes: Int,
    rank: Int,
    totalUsageMinutes: Int
) {
    val progress = if (totalUsageMinutes > 0) {
        (usageMinutes.toFloat() / totalUsageMinutes).coerceIn(0f, 1f)
    } else {
        0f
    }

    val rankColor = when (rank) {
        1 -> SemanticColors.rankGold
        2 -> SemanticColors.rankSilver
        3 -> SemanticColors.rankBronze
        else -> MaterialTheme.colorScheme.primary
    }

    PressableCard(
        onClick = { /* Navigate to app details */ },
        shape = RoundedCornerShape(14.dp),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCardSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(rankColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = rankColor
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Usage progress bar
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = rankColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            // Usage time
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = formatMinutesToTime(usageMinutes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = rankColor
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Enhanced alert card with better visual hierarchy
 * @param occurrenceCount Number of times this alert has occurred (for stacked alerts)
 */
@Composable
private fun EnhancedAlertCard(
    title: String,
    message: String,
    timestamp: String,
    severity: AlertSeverityLevel,
    isRead: Boolean,
    occurrenceCount: Int = 1,
    onClick: () -> Unit
) {
    val severityColor = when (severity) {
        AlertSeverityLevel.CRITICAL -> SemanticColors.severityCritical
        AlertSeverityLevel.HIGH -> SemanticColors.severityHigh
        AlertSeverityLevel.MEDIUM -> SemanticColors.severityMedium
        AlertSeverityLevel.LOW -> SemanticColors.severityLow
    }

    val severityContainerColor = when (severity) {
        AlertSeverityLevel.CRITICAL -> SemanticColors.severityCriticalContainer
        AlertSeverityLevel.HIGH -> SemanticColors.severityHighContainer
        AlertSeverityLevel.MEDIUM -> SemanticColors.severityMediumContainer
        AlertSeverityLevel.LOW -> SemanticColors.severityLowContainer
    }

    val severityIcon = when (severity) {
        AlertSeverityLevel.CRITICAL -> Icons.Filled.Error
        AlertSeverityLevel.HIGH -> Icons.Filled.Warning
        AlertSeverityLevel.MEDIUM -> Icons.Filled.Info
        AlertSeverityLevel.LOW -> Icons.Filled.CheckCircle
    }

    PressableCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = if (!isRead) {
            severityContainerColor.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        elevation = if (!isRead) SafeGuardDimens.elevationSm else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.Top
        ) {
            // Severity icon with animated indicator
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(severityColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = severityIcon,
                    contentDescription = null,
                    tint = severityColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (!isRead) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Show occurrence count badge for stacked alerts
                    if (occurrenceCount > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = severityColor.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "×$occurrenceCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = severityColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!isRead) {
                        Spacer(modifier = Modifier.width(8.dp))
                        PulsingIndicator(
                            color = severityColor,
                            size = 8.dp,
                            isPulsing = severity == AlertSeverityLevel.CRITICAL
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ============================================================================
// DIALOGS
// ============================================================================

@Composable
private fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.dashboard_sign_out),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dashboard_signout_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            DangerButton(
                text = stringResource(R.string.dashboard_sign_out),
                onClick = onConfirm,
                size = ButtonSize.SMALL
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun DashboardPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
        ) {
            HarisGradientHeader {
                Column {
                    Text("Welcome back", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Text("Family Dashboard", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                }
            }
            EnhancedScreenTimeHeroCard(
                usedSeconds = 7200, limitSeconds = 14400, unlockCount = 5,
                isParent = true, onManageLimits = {}, isStale = false, lastSync = java.util.Date()
            )
        }
    }
}

@Preview(name = "Dashboard · Light", showBackground = true)
@Composable
private fun DashboardLightPreview() { SafeGuardTheme(darkTheme = false) { DashboardPreviewContent() } }

@Preview(name = "Dashboard · Dark", showBackground = true)
@Composable
private fun DashboardDarkPreview() { SafeGuardTheme(darkTheme = true) { DashboardPreviewContent() } }
