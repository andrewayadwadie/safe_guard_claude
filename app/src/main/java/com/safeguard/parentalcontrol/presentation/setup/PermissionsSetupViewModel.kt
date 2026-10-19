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
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import android.net.Uri
import com.safeguard.parentalcontrol.util.AccessibilityServiceHelper
import com.safeguard.parentalcontrol.util.BlurEnforcement
import com.safeguard.parentalcontrol.util.ImageProtectionStatus
import com.safeguard.parentalcontrol.util.ImageProtectionStatusResolver
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.MediaReadAccess
import com.safeguard.parentalcontrol.util.MediaWriteAccess
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
    val allRequiredPermissionsGranted: Boolean = false,
    /**
     * Image protection as the parent sees it: whether photos can be read at all, whether flagged
     * images are being blurred in place, and which picture folders still need granting.
     *
     * This is the single source of truth for the media row on this screen. It replaced a check that
     * asked whether the app was an all-files manager — a permission the app no longer declares,
     * because Google Play does not permit it for this app category.
     */
    val imageProtection: ImageProtectionStatus = ImageProtectionStatus(
        read = MediaReadAccess.DENIED,
        blur = BlurEnforcement.READ_BLOCKED
    ),
    /** Shown once: images in the Download folder cannot be blurred in place on modern Android. */
    val showDownloadBlurNotice: Boolean = false,
    val error: String? = null
)

/**
 * Events that require Activity interaction
 */
sealed class PermissionsSetupEvent {
    data class RequestVpnPermission(val prepareIntent: Intent) : PermissionsSetupEvent()
    object RequestPhoneStatePermission : PermissionsSetupEvent()

    /**
     * Ask for the narrow photo permission.
     *
     * [permission] is the runtime permission to request: the photo-specific one from Android 13, the
     * legacy read permission below that. Before feature 016 this event routed Android 11+ to the
     * all-files-access settings screen instead and **never requested either permission**, so on those
     * versions image reading worked only because all-files access had been granted.
     */
    data class RequestMediaPermission(val permission: String) : PermissionsSetupEvent()

    /**
     * Open the system folder picker so the parent can grant one picture folder.
     *
     * Needed because blurring a flagged image means modifying a file another app created, which
     * scoped storage does not otherwise allow.
     */
    data class RequestFolderAccess(
        val target: MediaWriteAccess.GrantTarget,
        val pickerIntent: Intent
    ) : PermissionsSetupEvent()
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
    private val preferencesManager: PreferencesManager,
    private val mediaWriteAccess: MediaWriteAccess
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
        val imageProtection = resolveImageProtection()
        // The media row is satisfied when photos can actually be read. Blurring is a separate,
        // opt-in capability and must never block setup completion — Maximum Protection ships off.
        val mediaPermissionGranted = imageProtection.detectionWorks

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
                allRequiredPermissionsGranted = allRequired,
                imageProtection = imageProtection,
                // Only worth saying once the parent has actually asked for blurring, and only once.
                showDownloadBlurNotice = !isParent &&
                    imageProtection.blur == BlurEnforcement.ACTIVE &&
                    !preferencesManager.downloadBlurNoticeShown
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
    fun getAccessibilityDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_accessibility_desc)

    /**
     * Get description for usage stats permission.
     */
    fun getUsageStatsDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_usage_stats_desc)

    /**
     * Get description for overlay permission.
     */
    fun getOverlayDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_overlay_desc)

    /**
     * Get description for notification permission.
     */
    fun getNotificationDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_notifications_desc)

    /**
     * Get description for battery optimization permission.
     */
    fun getBatteryOptimizationDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_battery_desc)

    /**
     * Get description for VPN permission.
     */
    fun getVpnDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_vpn_desc)

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
    fun getPhoneStateDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_phone_desc)

    /**
     * The runtime permission that lets the app read the child's photos on this Android version.
     *
     * An ordinary runtime permission, so it is requested with the normal permission dialog. The four
     * special permissions this screen also manages (accessibility, usage access, overlay, VPN) keep
     * their system-settings routing — this one never belonged in that group, and treating it as if it
     * did is what left it ungranted on every Android 11+ device.
     */
    private fun mediaReadPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * How much of the photo library the app may read.
     *
     * From Android 14 the permission prompt offers a middle option that grants a hand-picked set of
     * photos. That is detected and reported as [MediaReadAccess.PARTIAL] rather than treated as
     * success: a photo the child receives tomorrow was not in yesterday's selection, so partial
     * access cannot do this feature's job. The platform tracks the user-selected permission for any
     * app holding the photo permission, whether or not the app declares it, which is what makes the
     * distinction observable without declaring a permission the app would only ever reject.
     */
    private fun mediaReadAccess(): MediaReadAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                MediaReadAccess.FULL
            } else {
                MediaReadAccess.DENIED
            }
        }

        val userSelectedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(READ_MEDIA_VISUAL_USER_SELECTED)

        return when {
            userSelectedOnly -> MediaReadAccess.PARTIAL
            isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaReadAccess.FULL
            else -> MediaReadAccess.DENIED
        }
    }

    /**
     * The whole image-protection picture, derived fresh — never cached.
     *
     * Folder-grant state comes from the platform's persisted-permission list on every call, so a
     * grant the device owner revoked in settings is noticed rather than assumed to still hold.
     */
    private fun resolveImageProtection(): ImageProtectionStatus {
        val missingTargets = mediaWriteAccess.missingTargets()
        return ImageProtectionStatusResolver.resolve(
            read = mediaReadAccess(),
            maximumProtectionOn = preferencesManager.isMaximumProtectionEnabled,
            hasAnyFolderGrant = mediaWriteAccess.hasAnyGrant(),
            anyGrantRevoked = missingTargets.any {
                mediaWriteAccess.grantState(it) == MediaWriteAccess.GrantState.REVOKED
            },
            missingFolderTargets = missingTargets.map { it.name }
        )
    }

    /**
     * Request the narrow photo permission with the ordinary runtime prompt.
     */
    fun requestMediaPermission() {
        viewModelScope.launch {
            _events.emit(PermissionsSetupEvent.RequestMediaPermission(mediaReadPermission()))
        }
    }

    /**
     * Called when the photo permission dialog closes.
     *
     * A partial grant is reported back to the parent as not protected, with the reason, rather than
     * being quietly accepted.
     */
    fun onMediaPermissionResult() {
        checkPermissions()
        if (mediaReadAccess() == MediaReadAccess.PARTIAL) {
            _uiState.update {
                it.copy(
                    error = LocaleHelper.localizedContext(context)
                        .getString(R.string.permissions_media_partial_error)
                )
            }
        }
    }

    /**
     * Start the folder-grant flow for the next picture folder that still needs one.
     *
     * One picker per folder, each pre-pointed at the folder it is asking for. Does nothing when every
     * folder is already granted, so it is safe to call from a retry button.
     */
    fun requestNextFolderAccess() {
        viewModelScope.launch {
            val target = mediaWriteAccess.missingTargets().firstOrNull() ?: run {
                checkPermissions()
                return@launch
            }
            _events.emit(
                PermissionsSetupEvent.RequestFolderAccess(
                    target = target,
                    pickerIntent = mediaWriteAccess.pickerIntent(target)
                )
            )
        }
    }

    /**
     * Called with whatever the folder picker returned, which may be nothing (the parent cancelled) or
     * a folder other than the one that was asked for.
     *
     * A partial result is kept rather than discarded: a parent who granted the camera folder and
     * backed out of the pictures one should keep camera protection, and be able to come back for the
     * rest without starting setup again.
     */
    fun onFolderAccessResult(treeUri: Uri?) {
        if (treeUri == null) {
            Timber.d("$TAG: Folder picker cancelled; keeping any grants already held")
            checkPermissions()
            return
        }

        val covered = mediaWriteAccess.persistGrant(treeUri)
        if (covered.isEmpty()) {
            _uiState.update {
                it.copy(
                    error = LocaleHelper.localizedContext(context)
                        .getString(R.string.permissions_folder_wrong_choice)
                )
            }
        } else {
            Timber.i("$TAG: Folder access granted for ${covered.joinToString { t -> t.name }}")
        }
        checkPermissions()
    }

    /** Suppress the one-time Download-folder notice once the parent has seen it. */
    fun onDownloadBlurNoticeShown() {
        preferencesManager.downloadBlurNoticeShown = true
        _uiState.update { it.copy(showDownloadBlurNotice = false) }
    }

    /** Clear a surfaced error after it has been displayed. */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Get description for media permission.
     */
    fun getMediaDescription(): String =
        LocaleHelper.localizedContext(context).getString(R.string.permissions_media_desc)
}

/**
 * Declared as a literal because the app deliberately does not list this permission in its manifest.
 *
 * The platform tracks it for any app holding the photo permission, so its grant state is readable and
 * is the signal that the parent chose "Select photos" instead of full access. Declaring it would only
 * let the app enumerate a selection it is going to reject anyway.
 */
private const val READ_MEDIA_VISUAL_USER_SELECTED =
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
