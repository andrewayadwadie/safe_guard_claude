package com.safeguard.parentalcontrol.presentation.settings

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.safeguard.parentalcontrol.presentation.theme.rememberScreenWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.util.Constants
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber

/**
 * Settings screen with account info and app settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToWordList: () -> Unit,
    onNavigateToTextMonitoringSettings: () -> Unit = {},
    onNavigateToPermissionsSetup: () -> Unit = {},
    onNavigateToImageReview: () -> Unit = {},
    onNavigateToTextReview: () -> Unit = {},
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Timber.e(e, "Failed to open URL: %s", url)
        }
    }

    // Refresh VPN state when returning to this screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContentFilteringState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    // Handle events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is SettingsEvent.RequestVpnPermission -> {
                    vpnPermissionLauncher.launch(event.prepareIntent)
                }
                is SettingsEvent.VpnPermissionGranted -> {
                    snackbarHostState.showSnackbar(
                        message = "Content filtering enabled",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    // Handle logout success
    LaunchedEffect(uiState.logoutSuccess) {
        if (uiState.logoutSuccess) {
            onLogout()
        }
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

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Default.Logout, contentDescription = null) },
            title = { Text("Log Out?") },
            text = { Text("Are you sure you want to log out of your account?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Log Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val screenWidth = rememberScreenWidth()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .responsiveContentWidth(screenWidth)
                .verticalScroll(rememberScrollState())
        ) {
            // User profile section
            if (uiState.userName.isNotEmpty()) {
                ProfileSection(
                    userName = uiState.userName,
                    userEmail = uiState.userEmail,
                    userRole = uiState.userRole
                )
            }

            // Content Filtering section (parent only - child's content filtering is controlled by parent)
            if (uiState.userRole == UserRole.PARENT) {
                SettingsSection(title = "Content Filtering") {
                    // Text Monitoring Settings
                    SettingsItem(
                        icon = Icons.Default.Message,
                        title = "Text Monitoring",
                        subtitle = "Configure text analysis and alert categories",
                        onClick = onNavigateToTextMonitoringSettings
                    )

                    // Custom Word Lists
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.TextFields,
                        title = "Custom Word Lists",
                        subtitle = "Add words to whitelist or blacklist",
                        onClick = onNavigateToWordList
                    )
                }
            }

            // Child-specific section
            if (uiState.userRole == UserRole.CHILD) {
                SettingsSection(title = "Device Setup") {
                    // Permissions Setup (child only)
                    SettingsItem(
                        icon = Icons.Default.Settings,
                        title = "Permissions Setup",
                        subtitle = "Configure required permissions for monitoring",
                        onClick = onNavigateToPermissionsSetup
                    )

                    // Content filtering status (read-only for child)
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.Shield,
                        title = "Content Filtering",
                        subtitle = if (uiState.isContentFilteringEnabled) {
                            "Active - Controlled by parent"
                        } else {
                            "Inactive - Controlled by parent"
                        },
                        onClick = { },
                        trailing = {
                            Icon(
                                imageVector = if (uiState.isContentFilteringEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (uiState.isContentFilteringEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    )
                }

                // Parent Review section (parent, on the child's device, behind a PIN).
                // Flagged photos and text never leave the device; the PIN keeps the child
                // from opening these surfaces.
                SettingsSection(title = "Parent Review") {
                    SettingsItem(
                        icon = Icons.Default.Image,
                        title = "Review Flagged Images",
                        subtitle = "Parent: Review and approve/reject blurred images",
                        onClick = onNavigateToImageReview
                    )

                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.Message,
                        title = "Review Flagged Text",
                        subtitle = "Parent: See phrases flagged on this device",
                        onClick = onNavigateToTextReview
                    )
                }
            }

            // Notifications section
            SettingsSection(title = "Notifications") {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "Push Notifications",
                    subtitle = "Manage notification preferences",
                    onClick = { /* TODO */ },
                    trailing = {
                        Text(
                            text = "Coming soon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            // Account section
            SettingsSection(title = "Account") {
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "Change Password",
                    subtitle = "Update your account password",
                    onClick = { /* TODO */ },
                    trailing = {
                        Text(
                            text = "Coming soon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Logout,
                    title = "Log Out",
                    subtitle = "Sign out of your account",
                    onClick = { showLogoutDialog = true },
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error
                )
            }

            // About section
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "App Version",
                    subtitle = "1.0.0",
                    onClick = { }
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Policy,
                    title = "Privacy Policy",
                    subtitle = "View our privacy policy",
                    onClick = { openUrl(Constants.PRIVACY_POLICY_URL) }
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Description,
                    title = "Terms of Service",
                    subtitle = "View our terms of service",
                    onClick = { openUrl(Constants.TERMS_OF_SERVICE_URL) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Loading overlay
    if (uiState.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ProfileSection(
    userName: String,
    userEmail: String,
    userRole: UserRole
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = userName.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            text = if (userRole == UserRole.PARENT) "Parent Account" else "Child Account"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (userRole == UserRole.PARENT) Icons.Default.SupervisedUserCircle else Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
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
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Settings item with a toggle switch
 */
@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth()
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
                tint = if (checked) iconTint else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SettingsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp)) {
            SettingsItem(Icons.Default.Person, "Account", "parent@haris.app", {})
            SettingsItem(Icons.Default.Notifications, "Notifications", "Alerts and reminders", {})
            SettingsItem(Icons.Default.Logout, "Log out", "Sign out of Haris", {}, iconTint = MaterialTheme.colorScheme.error, titleColor = MaterialTheme.colorScheme.error)
        }
    }
}

@Preview(name = "Settings · Light", showBackground = true)
@Composable
private fun SettingsScreenLightPreview() { SafeGuardTheme(darkTheme = false) { SettingsPreviewContent() } }

@Preview(name = "Settings · Dark", showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() { SafeGuardTheme(darkTheme = true) { SettingsPreviewContent() } }
