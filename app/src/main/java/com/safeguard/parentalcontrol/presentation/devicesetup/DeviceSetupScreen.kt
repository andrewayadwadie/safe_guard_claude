package com.safeguard.parentalcontrol.presentation.devicesetup

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Device Setup Screen
 * Shown to child users who haven't registered their device yet.
 * Device registration is required for monitoring and content filtering to work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: DeviceSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Track if we're showing the permission step
    var showPermissionStep by remember { mutableStateOf(false) }

    // Default device name based on device model
    var deviceName by remember {
        mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".take(50))
    }

    // When device is registered, show permission step instead of completing immediately
    LaunchedEffect(uiState.isRegistered) {
        if (uiState.isRegistered) {
            showPermissionStep = true
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showPermissionStep) {
                // Permission Setup Step
                PermissionSetupContent(
                    onOpenUsageSettings = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    },
                    onContinue = onSetupComplete
                )
            } else {
                // Device Registration Step
                DeviceRegistrationContent(
                    deviceName = deviceName,
                    onDeviceNameChange = { deviceName = it.take(50) },
                    isLoading = uiState.isLoading,
                    onRegister = {
                        focusManager.clearFocus()
                        viewModel.registerDevice(deviceName.trim())
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceRegistrationContent(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    isLoading: Boolean,
    onRegister: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    // Shield Icon
    Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Title
    Text(
        text = "Set Up This Device",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Subtitle
    Text(
        text = "Register this device to enable Haris protection and allow your parent to monitor your online activity.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Device Name Input
    OutlinedTextField(
        value = deviceName,
        onValueChange = onDeviceNameChange,
        label = { Text("Device Name") },
        placeholder = { Text("e.g., John's Phone") },
        leadingIcon = {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                if (deviceName.isNotBlank()) {
                    onRegister()
                }
            }
        ),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Helper text
    Text(
        text = "This name will help your parent identify this device",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Register Button
    Button(
        onClick = onRegister,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = deviceName.isNotBlank() && !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text("Register Device")
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Info Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "What happens next?",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "â€¢ Your parent will be able to see your screen time\n" +
                        "â€¢ Content filtering will block inappropriate websites\n" +
                        "â€¢ You'll need to grant usage access permission",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun PermissionSetupContent(
    onOpenUsageSettings: () -> Unit,
    onContinue: () -> Unit
) {
    // Success Icon
    Icon(
        imageVector = Icons.Default.Shield,
        contentDescription = null,
        modifier = Modifier.size(80.dp),
        tint = MaterialTheme.colorScheme.primary
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Title
    Text(
        text = "Device Registered!",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Subtitle
    Text(
        text = "Now let's enable screen time tracking by granting usage access permission.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    // Permission Warning Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "Usage Access Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "To track your screen time, Haris needs permission to access usage data. " +
                        "Tap the button below, find 'Haris' in the list, and enable access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Open Settings Button
    Button(
        onClick = onOpenUsageSettings,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Open Usage Access Settings")
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Skip/Continue Button
    OutlinedButton(
        onClick = onContinue,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Text("Continue to Dashboard")
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "You can enable this permission later from the dashboard",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun DeviceSetupPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            DeviceRegistrationContent("Alex's Phone", {}, false, {})
        }
    }
}
@Preview(name = "DeviceSetup · Light", showBackground = true)
@Composable private fun DeviceSetupLightPreview() { SafeGuardTheme(darkTheme = false) { DeviceSetupPreviewContent() } }
@Preview(name = "DeviceSetup · Dark", showBackground = true)
@Composable private fun DeviceSetupDarkPreview() { SafeGuardTheme(darkTheme = true) { DeviceSetupPreviewContent() } }
