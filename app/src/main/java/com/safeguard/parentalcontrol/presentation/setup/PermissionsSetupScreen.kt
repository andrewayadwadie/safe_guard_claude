package com.safeguard.parentalcontrol.presentation.setup

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.util.AccessibilityServiceHelper
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber

/**
 * Screen for guiding users through required permissions setup.
 *
 * This screen is shown to child device users after login/registration
 * to help them enable all necessary permissions for monitoring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSetupScreen(
    onNavigateBack: () -> Unit,
    onAllPermissionsGranted: () -> Unit,
    isFromSetupFlow: Boolean = false, // True when coming from device registration, false when from Settings
    viewModel: PermissionsSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    // Phone state permission launcher
    val phoneStatePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.checkPermissions()
        if (isGranted) {
            Timber.d("Phone state permission granted")
        } else {
            Timber.d("Phone state permission denied")
        }
    }

    // Media permission launcher (for sexting prevention)
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.checkPermissions()
        if (isGranted) {
            Timber.d("Media permission granted")
        } else {
            Timber.d("Media permission denied")
        }
    }

    // Collect permission events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is PermissionsSetupEvent.RequestVpnPermission -> {
                    vpnPermissionLauncher.launch(event.prepareIntent)
                }
                is PermissionsSetupEvent.RequestPhoneStatePermission -> {
                    phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
                is PermissionsSetupEvent.RequestMediaPermission -> {
                    // For Android 11+ we need MANAGE_EXTERNAL_STORAGE which requires Settings
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to open all files access settings")
                            // Fallback to general storage settings
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                Timber.e(e2, "Failed to open storage settings fallback")
                            }
                        }
                    } else {
                        // For older versions, use runtime permission
                        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
                        mediaPermissionLauncher.launch(permission)
                    }
                }
            }
        }
    }

    // Only auto-navigate during initial setup flow, not when accessed from Settings
    LaunchedEffect(uiState.allRequiredPermissionsGranted, isFromSetupFlow) {
        if (isFromSetupFlow && uiState.allRequiredPermissionsGranted && !uiState.isParent && !uiState.isLoading) {
            Timber.d("All permissions granted during setup flow, navigating to dashboard")
            // Small delay for user to see the success state
            kotlinx.coroutines.delay(500)
            onAllPermissionsGranted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permissions_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.permissions_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Progress indicator
            if (!uiState.isParent) {
                val grantedCount = listOf(
                    uiState.accessibilityEnabled,
                    uiState.usageStatsEnabled,
                    uiState.overlayEnabled,
                    uiState.batteryOptimizationDisabled,
                    uiState.vpnPermissionGranted,
                    uiState.phoneStatePermissionGranted,
                    uiState.mediaPermissionGranted
                ).count { it }

                LinearProgressIndicator(
                    progress = grantedCount / 7f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.permissions_granted_count, grantedCount, 7),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Permission cards
            PermissionCard(
                title = stringResource(R.string.permissions_accessibility_title),
                description = viewModel.getAccessibilityDescription(),
                icon = Icons.Default.Accessibility,
                isGranted = uiState.accessibilityEnabled,
                isRequired = true,
                onRequestPermission = {
                    AccessibilityServiceHelper.openAccessibilitySettings(context)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                title = stringResource(R.string.permissions_usage_stats_title),
                description = viewModel.getUsageStatsDescription(),
                icon = Icons.Default.BarChart,
                isGranted = uiState.usageStatsEnabled,
                isRequired = true,
                onRequestPermission = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to open usage access settings")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionCard(
                title = stringResource(R.string.permissions_overlay_title),
                description = viewModel.getOverlayDescription(),
                icon = Icons.Default.Layers,
                isGranted = uiState.overlayEnabled,
                isRequired = true,
                onRequestPermission = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to open overlay permission settings")
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Battery optimization (required to prevent service being killed)
            PermissionCard(
                title = stringResource(R.string.permissions_battery_title),
                description = viewModel.getBatteryOptimizationDescription(),
                icon = Icons.Default.BatteryChargingFull,
                isGranted = uiState.batteryOptimizationDisabled,
                isRequired = true,
                onRequestPermission = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to open battery optimization settings")
                        // Fallback to general battery settings
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        } catch (e2: Exception) {
                            Timber.e(e2, "Failed to open battery settings fallback")
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // VPN permission (required for content filtering)
            PermissionCard(
                title = stringResource(R.string.permissions_vpn_title),
                description = viewModel.getVpnDescription(),
                icon = Icons.Default.VpnKey,
                isGranted = uiState.vpnPermissionGranted,
                isRequired = true,
                onRequestPermission = {
                    viewModel.requestVpnPermission()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Phone state permission (required for allowing calls during bedtime)
            PermissionCard(
                title = stringResource(R.string.permissions_phone_title),
                description = viewModel.getPhoneStateDescription(),
                icon = Icons.Default.Phone,
                isGranted = uiState.phoneStatePermissionGranted,
                isRequired = true,
                onRequestPermission = {
                    viewModel.requestPhoneStatePermission()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Media permission (for sexting prevention)
            // On Android 11+ this requires "All files access" permission from Settings
            PermissionCard(
                title = stringResource(R.string.permissions_media_title),
                description = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    stringResource(R.string.permissions_media_desc_r_plus)
                } else {
                    viewModel.getMediaDescription()
                },
                icon = Icons.Default.Image,
                isGranted = uiState.mediaPermissionGranted,
                isRequired = true,
                onRequestPermission = {
                    viewModel.requestMediaPermission()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Optional notification permission
            PermissionCard(
                title = stringResource(R.string.permissions_notifications_title),
                description = viewModel.getNotificationDescription(),
                icon = Icons.Default.Notifications,
                isGranted = uiState.notificationsEnabled,
                isRequired = false,
                onRequestPermission = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to open notification settings")
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Continue button
            if (uiState.allRequiredPermissionsGranted) {
                Button(
                    onClick = onAllPermissionsGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.permissions_continue_dashboard))
                }
            } else {
                OutlinedButton(
                    onClick = onAllPermissionsGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.permissions_skip_for_now))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.permissions_incomplete_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Card displaying a single permission with its status and action button.
 */
@Composable
private fun PermissionCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    isRequired: Boolean,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with status indicator
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (isGranted) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (isRequired) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.permissions_required_marker),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status / Action
            if (isGranted) {
                // Even when granted, allow clicking to re-trigger (e.g., to start VPN if it's not running)
                FilledTonalButton(
                    onClick = onRequestPermission,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.permissions_active), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                FilledTonalButton(
                    onClick = onRequestPermission,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.permissions_enable), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PermissionsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermissionCard("Accessibility Service", "Monitor app text for safety", Icons.Default.Lock, false, true, {})
            PermissionCard("Usage Access", "Track screen time", Icons.Default.CheckCircle, true, false, {})
        }
    }
}

@Preview(name = "Permissions · Light", showBackground = true)
@Composable private fun PermissionsLightPreview() { SafeGuardTheme(darkTheme = false) { PermissionsPreviewContent() } }
@Preview(name = "Permissions · Dark", showBackground = true)
@Composable private fun PermissionsDarkPreview() { SafeGuardTheme(darkTheme = true) { PermissionsPreviewContent() } }
