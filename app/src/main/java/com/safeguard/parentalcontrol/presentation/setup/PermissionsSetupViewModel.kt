package com.safeguard.parentalcontrol.presentation.setup

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.util.AccessibilityServiceHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * UI state for permissions setup screen.
 */
data class PermissionsSetupUiState(
    val isLoading: Boolean = true,
    val accessibilityEnabled: Boolean = false,
    val usageStatsEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val batteryOptimizationDisabled: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val phoneStatePermissionGranted: Boolean = false,
    val mediaPermissionGranted: Boolean = false,
    val isParent: Boolean = false,
    val allRequiredPermissionsGranted: Boolean = false
)

/**
 * Events that require Activity interaction
 */
sealed class PermissionsSetupEvent {
    data class RequestVpnPermission(val prepareIntent: Intent) : PermissionsSetupEvent()
    object RequestPhoneStatePermission : PermissionsSetupEvent()
    object RequestMediaPermission : PermissionsSetupEvent()
}

/**
 * ViewModel for managing permissions setup.
 *
 * This screen guides child device users through enabling the required
 * permissions for text monitoring and screen time tracking.
 */
@HiltViewModel
class PermissionsSetupViewModel @Inject constructor(
    application: Application,
    private val preferencesManager: PreferencesManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PermissionsSetupVM"
        private const val PERMISSION_CHECK_INTERVAL_MS = 1000L
    }

    private val _uiState = MutableStateFlow(PermissionsSetupUiState())
    val uiState: StateFlow<PermissionsSetupUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PermissionsSetupEvent>()
    val events: SharedFlow<PermissionsSetupEvent> = _events.asSharedFlow()

    private val context: Context
        get() = getApplication<Application>().applicationContext

    // Track if we've already attempted to auto-start VPN to avoid repeated attempts
    private var vpnAutoStartAttempted = false

    init {
        checkPermissions()
        startPermissionPolling()
    }

    /**
     * Start polling for permission changes.
     * This allows the UI to update automatically when the user grants a permission.
     */
    private fun startPermissionPolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(PERMISSION_CHECK_INTERVAL_MS)
                checkPermissions()
            }
        }
    }

    /**
     * Check all permission states and update UI.
     */
    fun checkPermissions() {
        val isParent = preferencesManager.isParent
        val accessibilityEnabled = AccessibilityServiceHelper.isServiceEnabled(context)
        val usageStatsEnabled = hasUsageStatsPermission()
        val overlayEnabled = Settings.canDrawOverlays(context)
        val notificationsEnabled = hasNotificationPermission()
        val batteryOptimizationDisabled = isBatteryOptimizationDisabled()
        val vpnPermissionGranted = hasVpnPermission()
        val phoneStatePermissionGranted = hasPhoneStatePermission()
        val mediaPermissionGranted = hasMediaPermission()

        // For child devices, required permissions are:
        // - Accessibility (for text monitoring)
        // - Usage Stats (for screen time tracking)
        // - Overlay (for lock screen on Android 10+)
        // - Battery optimization disabled (to prevent service being killed)
        // - VPN (for content filtering)
        // - Phone state (for allowing calls during bedtime) - required on API 31+
        // - Media (for image analysis/sexting prevention) - required on API 33+
        val allRequired = if (isParent) {
            true // Parents don't need special permissions
        } else {
            accessibilityEnabled && usageStatsEnabled && overlayEnabled &&
            batteryOptimizationDisabled && vpnPermissionGranted &&
            phoneStatePermissionGranted && mediaPermissionGranted
        }

        _uiState.update { state ->
            state.copy(
                isLoading = false,
                accessibilityEnabled = accessibilityEnabled,
                usageStatsEnabled = usageStatsEnabled,
                overlayEnabled = overlayEnabled,
                notificationsEnabled = notificationsEnabled,
                batteryOptimizationDisabled = batteryOptimizationDisabled,
                vpnPermissionGranted = vpnPermissionGranted,
                phoneStatePermissionGranted = phoneStatePermissionGranted,
                mediaPermissionGranted = mediaPermissionGranted,
                isParent = isParent,
                allRequiredPermissionsGranted = allRequired
            )
        }

        Timber.d("$TAG: Permissions checked - accessibility=$accessibilityEnabled, " +
                "usageStats=$usageStatsEnabled, overlay=$overlayEnabled, " +
                "notifications=$notificationsEnabled, batteryOpt=$batteryOptimizationDisabled, " +
                "vpn=$vpnPermissionGranted, phoneState=$phoneStatePermissionGranted, " +
                "media=$mediaPermissionGranted, allRequired=$allRequired")

        // Auto-start VPN if permission is granted but VPN is not running
        // This handles the case where the user previously granted VPN permission
        // but the app was restarted or the VPN service was stopped
        // Only attempt once to avoid repeatedly trying to start a crashing service
        if (!isParent && vpnPermissionGranted && preferencesManager.isContentFilteringEnabled && !vpnAutoStartAttempted) {
            val vpnRunning = ContentFilterVpnService.isVpnRunning(context)
            if (!vpnRunning) {
                vpnAutoStartAttempted = true
                Timber.d("$TAG: VPN permission granted but not running, auto-starting VPN service")
                startVpnService()
            }
        }
    }

    /**
     * Check if VPN permission is granted.
     * VpnService.prepare() returns null if permission is already granted.
     */
    private fun hasVpnPermission(): Boolean {
        return VpnService.prepare(context) == null
    }

    /**
     * Request VPN permission - emits event for Activity to handle
     */
    fun requestVpnPermission() {
        viewModelScope.launch {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                _events.emit(PermissionsSetupEvent.RequestVpnPermission(prepareIntent))
            } else {
                // Permission already granted, start VPN service
                startVpnService()
            }
        }
    }

    /**
     * Called when VPN permission result is received
     */
    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            startVpnService()
        }
        checkPermissions()
    }

    /**
     * Start VPN service after permission is granted
     */
    private fun startVpnService() {
        try {
            val intent = Intent(context, ContentFilterVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // Update preference
            preferencesManager.isContentFilteringEnabled = true
            Timber.d("$TAG: VPN service started")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start VPN service")
        }
    }

    /**
     * Check if usage stats permission is granted.
     */
    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking usage stats permission")
            false
        }
    }

    /**
     * Check if notification permission is granted (Android 13+).
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            notificationManager.areNotificationsEnabled()
        } else {
            true // Before Android 13, notifications are enabled by default
        }
    }

    /**
     * Check if battery optimization is disabled for this app.
     * When battery optimization is enabled, Android may kill the app and disable accessibility service.
     */
    private fun isBatteryOptimizationDisabled(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking battery optimization")
            false
        }
    }

    /**
     * Get description for accessibility permission.
     */
    fun getAccessibilityDescription(): String {
        return "Required for monitoring text content across apps to detect inappropriate " +
                "messages, cyberbullying, and potential predatory behavior."
    }

    /**
     * Get description for usage stats permission.
     */
    fun getUsageStatsDescription(): String {
        return "Required for tracking screen time and app usage to enforce daily limits " +
                "and bedtime restrictions."
    }

    /**
     * Get description for overlay permission.
     */
    fun getOverlayDescription(): String {
        return "Required for displaying the lock screen when screen time limits are exceeded " +
                "or during bedtime hours."
    }

    /**
     * Get description for notification permission.
     */
    fun getNotificationDescription(): String {
        return "Required for showing alerts and notifications about your child's device activity."
    }

    /**
     * Get description for battery optimization permission.
     */
    fun getBatteryOptimizationDescription(): String {
        return "Required to prevent Android from killing SafeGuard in the background. " +
                "Without this, text monitoring may stop working unexpectedly."
    }

    /**
     * Get description for VPN permission.
     */
    fun getVpnDescription(): String {
        return "Required for content filtering to block inappropriate websites. " +
                "SafeGuard uses a local VPN to filter web content - no data is sent to external servers."
    }

    /**
     * Check if READ_PHONE_STATE permission is granted.
     * This is needed to detect phone calls and allow them during bedtime.
     */
    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request phone state permission - emits event for Activity to handle
     */
    fun requestPhoneStatePermission() {
        viewModelScope.launch {
            _events.emit(PermissionsSetupEvent.RequestPhoneStatePermission)
        }
    }

    /**
     * Get description for phone state permission.
     */
    fun getPhoneStateDescription(): String {
        return "Required to allow phone calls during bedtime and screen time limits. " +
                "Your child will always be able to make and receive emergency calls."
    }

    /**
     * Check if media (photos) permission is granted.
     * We need MANAGE_EXTERNAL_STORAGE for full access (to blur images).
     * Falls back to READ_MEDIA_IMAGES for detection only.
     */
    private fun hasMediaPermission(): Boolean {
        // Check for full storage access (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager()
        }
        // Older versions - check read permission
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Request media permission - for Android 11+ opens Settings for MANAGE_EXTERNAL_STORAGE
     */
    fun requestMediaPermission() {
        viewModelScope.launch {
            _events.emit(PermissionsSetupEvent.RequestMediaPermission)
        }
    }

    /**
     * Get description for media permission.
     */
    fun getMediaDescription(): String {
        return "Required for sexting prevention. SafeGuard needs full storage access to " +
                "detect and blur inappropriate images, protecting your child from harmful content."
    }
}
