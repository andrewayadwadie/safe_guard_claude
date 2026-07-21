package com.safeguard.parentalcontrol.presentation.settings

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.presentation.components.ParentPinDialog
import com.safeguard.parentalcontrol.presentation.components.ParentPinViewModel
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.util.LocaleHelper
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
    onNavigateToChangePassword: () -> Unit = {},
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTerms: () -> Unit = {},
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    // Desired Maximum Protection value awaiting parent-PIN verification (null = no pending
    // change). Deliberately transient: a process death mid-dialog abandons the change, which
    // is the safe direction (no change without a correct PIN).
    var pendingMaxProtectionChange by remember { mutableStateOf<Boolean?>(null) }
    val pinViewModel: ParentPinViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val contentFilteringEnabledMessage = stringResource(R.string.settings_content_filtering_enabled_snackbar)

    // Refresh VPN state when returning to this screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContentFilteringState()
                viewModel.refreshMaximumProtectionState()
                viewModel.refreshNotificationStatus()
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
                        message = contentFilteringEnabledMessage,
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
            title = { Text(stringResource(R.string.settings_logout_dialog_title)) },
            text = { Text(stringResource(R.string.logout_confirmation)) },
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
                    Text(stringResource(R.string.logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Language selection dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_language_dialog_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    val options = listOf(
                        LocaleHelper.LANGUAGE_ENGLISH to stringResource(R.string.settings_language_english),
                        LocaleHelper.LANGUAGE_ARABIC to stringResource(R.string.settings_language_arabic)
                    )
                    options.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = uiState.currentLanguage == code,
                                    onClick = {
                                        showLanguageDialog = false
                                        if (uiState.currentLanguage != code) {
                                            viewModel.setLanguage(code)
                                            (context as? Activity)?.recreate()
                                        }
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.currentLanguage == code,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    // Parent-PIN gate for the Maximum Protection toggle. Shown while a change is pending;
    // applies it only on a correct PIN (or first-time PIN creation via the dialog's create
    // mode). Dismiss/wrong PIN clears the pending value and leaves the switch untouched.
    pendingMaxProtectionChange?.let { desired ->
        ParentPinDialog(
            hasPin = pinViewModel.hasParentPin,
            onVerify = pinViewModel::verifyParentPin,
            onCreate = pinViewModel::setParentPin,
            onSuccess = {
                viewModel.setMaximumProtection(desired)
                pendingMaxProtectionChange = null
            },
            onDismiss = { pendingMaxProtectionChange = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                SettingsSection(title = stringResource(R.string.settings_content_filtering)) {
                    // Text Monitoring Settings
                    SettingsItem(
                        icon = Icons.Default.Message,
                        title = stringResource(R.string.settings_text_monitoring),
                        subtitle = stringResource(R.string.settings_text_monitoring_desc),
                        onClick = onNavigateToTextMonitoringSettings
                    )

                    // Custom Word Lists
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.TextFields,
                        title = stringResource(R.string.settings_custom_word_lists),
                        subtitle = stringResource(R.string.settings_custom_word_lists_desc),
                        onClick = onNavigateToWordList
                    )
                }
            }

            // Child-specific section
            if (uiState.userRole == UserRole.CHILD) {
                SettingsSection(title = stringResource(R.string.settings_device_setup)) {
                    // Permissions Setup (child only)
                    SettingsItem(
                        icon = Icons.Default.Settings,
                        title = stringResource(R.string.settings_permissions_setup),
                        subtitle = stringResource(R.string.settings_permissions_setup_desc),
                        onClick = onNavigateToPermissionsSetup
                    )

                    // Content filtering status (read-only for child)
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.settings_content_filtering),
                        subtitle = if (uiState.isContentFilteringEnabled) {
                            stringResource(R.string.settings_content_filtering_active)
                        } else {
                            stringResource(R.string.settings_content_filtering_inactive)
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
                SettingsSection(title = stringResource(R.string.settings_parent_review)) {
                    SettingsItem(
                        icon = Icons.Default.Image,
                        title = stringResource(R.string.settings_review_images),
                        subtitle = stringResource(R.string.settings_review_images_desc),
                        onClick = onNavigateToImageReview
                    )

                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Default.Message,
                        title = stringResource(R.string.settings_review_text),
                        subtitle = stringResource(R.string.settings_review_text_desc),
                        onClick = onNavigateToTextReview
                    )

                    // Maximum Protection toggle. Tapping does NOT flip the switch directly:
                    // it records the desired value and opens the parent-PIN dialog. Only a
                    // correct PIN applies the change (via viewModel.setMaximumProtection). The
                    // Switch binds solely to uiState, so a wrong/cancelled PIN leaves it as-is.
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsToggleItem(
                        icon = Icons.Default.Security,
                        title = stringResource(R.string.settings_maximum_protection),
                        subtitle = stringResource(R.string.settings_maximum_protection_desc),
                        checked = uiState.isMaximumProtectionEnabled,
                        onCheckedChange = { desired -> pendingMaxProtectionChange = desired }
                    )
                }
            }

            // Language section
            SettingsSection(title = stringResource(R.string.settings_language_title)) {
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_language_title),
                    subtitle = if (uiState.currentLanguage == LocaleHelper.LANGUAGE_ARABIC) {
                        stringResource(R.string.settings_language_arabic)
                    } else {
                        stringResource(R.string.settings_language_english)
                    },
                    onClick = { showLanguageDialog = true }
                )
            }

            // Notifications section
            SettingsSection(title = stringResource(R.string.settings_notifications)) {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.settings_notifications),
                    subtitle = if (uiState.notificationsEnabled) {
                        stringResource(R.string.settings_notifications_on)
                    } else {
                        stringResource(R.string.settings_notifications_off_tap)
                    },
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to open notification settings")
                        }
                    },
                    trailing = {
                        Text(
                            text = if (uiState.notificationsEnabled) {
                                stringResource(R.string.settings_notifications_on)
                            } else {
                                stringResource(R.string.settings_notifications_off)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.notificationsEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                )
                // Divider(modifier = Modifier.padding(horizontal = 16.dp))
                // SettingsItem(
                //     icon = Icons.Default.Notifications,
                //     title = "Push Notifications",
                //     subtitle = "Manage notification preferences",
                //     onClick = { /* TODO */ },
                //     trailing = {
                //         Text(
                //             text = "Coming soon",
                //             style = MaterialTheme.typography.bodySmall,
                //             color = MaterialTheme.colorScheme.onSurfaceVariant
                //         )
                //     }
                // )
            }

            // Account section
            SettingsSection(title = stringResource(R.string.settings_account)) {
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.settings_change_password),
                    subtitle = stringResource(R.string.settings_change_password_desc),
                    onClick = onNavigateToChangePassword
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Logout,
                    title = stringResource(R.string.logout),
                    subtitle = stringResource(R.string.settings_logout_item_desc),
                    onClick = { showLogoutDialog = true },
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error
                )
            }

            // About section
            SettingsSection(title = stringResource(R.string.settings_about)) {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.settings_app_version),
                    subtitle = stringResource(R.string.settings_app_version_number),
                    onClick = { }
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Policy,
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = stringResource(R.string.settings_privacy_policy_desc),
                    onClick = onNavigateToPrivacyPolicy
                )
                Divider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Default.Description,
                    title = stringResource(R.string.settings_terms),
                    subtitle = stringResource(R.string.settings_terms_desc),
                    onClick = onNavigateToTerms
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
                            text = if (userRole == UserRole.PARENT) {
                                stringResource(R.string.settings_profile_parent_account)
                            } else {
                                stringResource(R.string.settings_profile_child_account)
                            }
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
