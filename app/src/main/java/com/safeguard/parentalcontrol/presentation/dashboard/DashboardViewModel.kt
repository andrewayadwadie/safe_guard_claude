package com.safeguard.parentalcontrol.presentation.dashboard

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.ContentFilterRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRepository
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.util.AccessibilityServiceHelper
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.inject.Inject

/**
 * UI State for dashboard screen
 */
data class DashboardUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false, // For manual refresh indicator
    val isParent: Boolean = false,
    val todayScreenTime: Int = 0, // seconds
    val todayUnlocks: Int = 0,
    // True only when the latest screen-time log is actually from today. When false,
    // the phone hasn't reported today (offline/stale) and todayScreenTime is 0; the
    // UI shows a "last seen" note from currentDevice.lastSync instead.
    val hasTodayScreenTimeData: Boolean = false,
    val dailyLimit: Int? = null, // seconds, null if no limit
    val topApps: List<AppUsageLog> = emptyList(),
    val recentAlerts: List<Alert> = emptyList(),
    val unreadAlertCount: Int = 0,
    val devices: List<Device> = emptyList(),
    val currentDevice: Device? = null,
    val selectedDeviceId: Int? = null,
    val error: String? = null,
    val hasUsageStatsPermission: Boolean = true, // For screen time tracking
    val hasAccessibilityPermission: Boolean = true, // For text monitoring
    val hasOverlayPermission: Boolean = true, // For lock screen
    val hasBatteryOptimizationDisabled: Boolean = true, // For keeping services alive
    val showPermissionBanner: Boolean = false, // Show permission setup prompt
    // Content filtering state (for parent dashboard)
    val contentFilterEnabled: Boolean = true,
    val blockSocialMediaEnabled: Boolean = false,
    val blockedSocialMediaPlatforms: Set<SocialMediaPlatform> = emptySet(),
    val showSocialMediaSubMenu: Boolean = false,
    val isLoadingContentFilter: Boolean = false,
    // Parent reachability: with notifications off, violation alerts cannot be shown at all.
    // Surfaced as a dismissible informational banner — never a blocker, and never an error.
    val notificationsEnabled: Boolean = true,
    val notificationBannerDismissed: Boolean = false
) {
    /** Show the "alerts are off" banner only to a parent who has not dismissed it. */
    val showNotificationBanner: Boolean
        get() = isParent && !notificationsEnabled && !notificationBannerDismissed
}

/**
 * ViewModel for the dashboard screen
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    application: Application,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val screenTimeRepository: ScreenTimeRepository,
    private val alertRepository: AlertRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val preferencesManager: PreferencesManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(getApplication<Application>().applicationContext)
            .getString(resId, *args)

    init {
        _uiState.update { it.copy(isParent = authRepository.isParent()) }
        checkPermissions()
        loadDashboard()
    }

    /**
     * Re-read whether the OS will let this app post notifications. Called on every dashboard
     * resume, so granting the permission (or toggling it in system settings) clears the
     * banner without a restart.
     */
    fun refreshNotificationState() {
        val enabled = NotificationManagerCompat
            .from(getApplication<Application>().applicationContext)
            .areNotificationsEnabled()

        _uiState.update {
            it.copy(
                notificationsEnabled = enabled,
                notificationBannerDismissed = preferencesManager.notificationBannerDismissed
            )
        }
    }

    /**
     * Hide the notifications-disabled banner for good. Purely informational — nothing about
     * the app becomes unavailable either way.
     */
    fun dismissNotificationBanner() {
        preferencesManager.notificationBannerDismissed = true
        _uiState.update { it.copy(notificationBannerDismissed = true) }
    }

    /**
     * Check all required permissions for child devices.
     * This determines whether to show the permission setup banner.
     */
    fun checkPermissions() {
        // Notification state matters on both roles' devices, but only the parent is told
        // about it — a child has no violation alerts to miss.
        refreshNotificationState()

        // Only check for child devices
        if (authRepository.isParent()) {
            _uiState.update { it.copy(showPermissionBanner = false) }
            return
        }

        val context = getApplication<Application>().applicationContext
        val hasAccessibility = AccessibilityServiceHelper.isServiceEnabled(context)
        val hasOverlay = Settings.canDrawOverlays(context)
        val hasUsageStats = screenTimeRepository.hasUsageStatsPermission()
        val hasBatteryOptDisabled = try {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        // Show banner if ANY required permission is missing
        val showBanner = !hasAccessibility || !hasOverlay || !hasUsageStats || !hasBatteryOptDisabled || !hasPhoneState

        _uiState.update {
            it.copy(
                hasAccessibilityPermission = hasAccessibility,
                hasOverlayPermission = hasOverlay,
                hasUsageStatsPermission = hasUsageStats,
                hasBatteryOptimizationDisabled = hasBatteryOptDisabled,
                showPermissionBanner = showBanner
            )
        }

        Timber.d("Permission check - accessibility=$hasAccessibility, overlay=$hasOverlay, usageStats=$hasUsageStats, batteryOpt=$hasBatteryOptDisabled, phoneState=$hasPhoneState, showBanner=$showBanner")

        // If accessibility is "enabled" but service was never created, log diagnostics
        // This helps identify when Android hasn't actually started our service
        if (hasAccessibility) {
            val serviceCreated = AccessibilityServiceHelper.wasServiceEverCreated()
            if (!serviceCreated) {
                Timber.w("ACCESSIBILITY ISSUE: Service enabled in settings but NEVER CREATED by Android!")
                Timber.w("Diagnostic info:\n${AccessibilityServiceHelper.getDiagnosticInfo(context)}")
            } else {
                Timber.d("Accessibility service was created by Android - monitoring should be active")
            }
        }
    }

    /**
     * Load all dashboard data
     */
    fun loadDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            if (authRepository.isParent()) {
                loadParentDashboard()
            } else {
                loadChildDashboard()
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Manually refresh all data - triggered by user pressing refresh button.
     * Forces sync with backend and refreshes all dashboard data.
     */
    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            try {
                // Sync device with backend first
                deviceRepository.syncDevice()

                // Reload all dashboard data
                if (authRepository.isParent()) {
                    loadParentDashboard()
                } else {
                    loadChildDashboard()
                }

                Timber.d("Manual refresh completed successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error during manual refresh")
                _uiState.update { it.copy(error = getString(R.string.dashboard_error_refresh, e.message ?: "")) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * Load parent dashboard data
     */
    private suspend fun loadParentDashboard() {
        // Load all devices
        when (val devicesResult = deviceRepository.getDevices()) {
            is NetworkResult.Success -> {
                _uiState.update { it.copy(devices = devicesResult.data) }

                // Select first device if none selected
                val deviceId = _uiState.value.selectedDeviceId
                    ?: devicesResult.data.firstOrNull()?.id

                if (deviceId != null) {
                    selectDevice(deviceId)
                }
            }
            is NetworkResult.Error -> {
                _uiState.update { it.copy(error = devicesResult.message) }
            }
            else -> {}
        }

        // Load alerts
        loadAlerts()
    }

    /**
     * Load child dashboard data (current device)
     */
    private suspend fun loadChildDashboard() {
        val deviceId = preferencesManager.deviceDbId
        if (deviceId == -1) {
            _uiState.update { it.copy(error = getString(R.string.dashboard_error_device_not_registered)) }
            return
        }

        // Check usage stats permission first
        val hasPermission = screenTimeRepository.hasUsageStatsPermission()
        _uiState.update { it.copy(hasUsageStatsPermission = hasPermission, selectedDeviceId = deviceId) }

        if (hasPermission) {
            // Get local screen time data only if we have permission
            val (screenTime, unlocks) = screenTimeRepository.getTodayScreenTimeData()
            _uiState.update {
                it.copy(
                    todayScreenTime = screenTime,
                    todayUnlocks = unlocks
                )
            }

            // Load today's app usage
            loadTodayAppUsage(deviceId)

            // Sync data to server
            syncScreenTime()
        }
    }

    /**
     * Check if usage stats permission is granted
     */
    fun checkUsageStatsPermission(): Boolean {
        val hasPermission = screenTimeRepository.hasUsageStatsPermission()
        _uiState.update { it.copy(hasUsageStatsPermission = hasPermission) }
        return hasPermission
    }

    /**
     * Select a device (for parent view)
     */
    fun selectDevice(deviceId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(selectedDeviceId = deviceId, isLoading = true) }

            // Load device details
            when (val deviceResult = deviceRepository.getDevice(deviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(currentDevice = deviceResult.data) }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = deviceResult.message) }
                }
                else -> {}
            }

            // Load today's screen time. The backend returns the most recent log
            // regardless of date, so a phone that last synced days ago would return a
            // stale record. Only surface it as "today" if its date is actually today;
            // otherwise show 0 (the UI then shows a "last seen" note from lastSync).
            when (val logsResult = screenTimeRepository.getScreenTimeLogs(deviceId, 1)) {
                is NetworkResult.Success -> {
                    val latestLog = logsResult.data.firstOrNull()
                    val isToday = isLoggedToday(latestLog?.date)
                    _uiState.update {
                        it.copy(
                            todayScreenTime = if (isToday) latestLog!!.totalScreenTime else 0,
                            todayUnlocks = if (isToday) latestLog!!.unlocksCount else 0,
                            hasTodayScreenTimeData = isToday
                        )
                    }
                    Timber.d("Parent dashboard: latest log date=${latestLog?.date}, isToday=$isToday, screenTime=${if (isToday) latestLog!!.totalScreenTime else 0}s")
                }
                is NetworkResult.Error -> {
                    Timber.e("Failed to load screen time logs: ${logsResult.message}")
                }
                else -> {}
            }

            // Load today's app usage
            loadTodayAppUsage(deviceId)

            // Load alerts for device
            loadAlerts(deviceId)

            // Load content filter settings for device
            loadContentFilterSettings(deviceId)

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Whether a screen-time log's date is today.
     *
     * The backend stores the log date as midnight UTC of the child's local calendar
     * date, so we recover that calendar date by reading the instant in UTC and compare
     * it to the viewer's local "today". A phone offline for days will never match.
     */
    private fun isLoggedToday(logDate: Date?): Boolean {
        if (logDate == null) return false
        val logCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = logDate }
        val today = Calendar.getInstance()
        return logCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            logCal.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
            logCal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * Load content filter settings for a device (parent only)
     */
    private suspend fun loadContentFilterSettings(deviceId: Int) {
        when (val result = contentFilterRepository.getContentFilter(deviceId)) {
            is NetworkResult.Success -> {
                val blockedPlatforms = SocialMediaPlatform.getBlockedPlatforms(result.data.blockedDomains)
                _uiState.update {
                    it.copy(
                        contentFilterEnabled = result.data.isActive,
                        blockSocialMediaEnabled = result.data.blockSocialMedia || blockedPlatforms.isNotEmpty(),
                        blockedSocialMediaPlatforms = blockedPlatforms
                    )
                }
            }
            is NetworkResult.Error -> {
                // If 404, content filter doesn't exist yet - default values
                if (result.code == 404) {
                    _uiState.update {
                        it.copy(
                            contentFilterEnabled = true,
                            blockSocialMediaEnabled = false,
                            blockedSocialMediaPlatforms = emptySet()
                        )
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Toggle content filtering for selected device (parent only)
     */
    fun toggleContentFiltering(enabled: Boolean) {
        val deviceId = _uiState.value.selectedDeviceId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            val update = ContentFilterUpdate(
                isActive = enabled,
                blockAdult = if (enabled) true else null,
                blockViolence = if (enabled) true else null,
                blockGambling = if (enabled) true else null,
                blockDrugs = if (enabled) true else null
            )

            when (val result = contentFilterRepository.updateContentFilter(deviceId, update)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            contentFilterEnabled = result.data.isActive,
                            isLoadingContentFilter = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404) {
                        createContentFilter(deviceId, enabled)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoadingContentFilter = false,
                                error = getString(R.string.dashboard_error_update_filter)
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Toggle social media sub-menu visibility
     */
    fun toggleSocialMediaSubMenu() {
        _uiState.update { it.copy(showSocialMediaSubMenu = !it.showSocialMediaSubMenu) }
    }

    /**
     * Toggle blocking for a specific social media platform
     */
    fun toggleSocialMediaPlatform(platform: SocialMediaPlatform, blocked: Boolean) {
        val deviceId = _uiState.value.selectedDeviceId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            // Get current blocked platforms and update
            val currentPlatforms = _uiState.value.blockedSocialMediaPlatforms.toMutableSet()
            if (blocked) {
                currentPlatforms.add(platform)
            } else {
                currentPlatforms.remove(platform)
            }

            // Get all domains for the updated platforms
            val allBlockedDomains = SocialMediaPlatform.getDomainsForPlatforms(currentPlatforms.toList())

            val update = ContentFilterUpdate(
                blockSocialMedia = currentPlatforms.isNotEmpty(),
                blockedDomains = allBlockedDomains.ifEmpty { null }
            )

            when (val result = contentFilterRepository.updateContentFilter(deviceId, update)) {
                is NetworkResult.Success -> {
                    val blockedPlatforms = SocialMediaPlatform.getBlockedPlatforms(result.data.blockedDomains)
                    _uiState.update {
                        it.copy(
                            blockSocialMediaEnabled = result.data.blockSocialMedia || blockedPlatforms.isNotEmpty(),
                            blockedSocialMediaPlatforms = blockedPlatforms,
                            isLoadingContentFilter = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404) {
                        createContentFilterWithPlatforms(deviceId, currentPlatforms)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoadingContentFilter = false,
                                error = getString(R.string.dashboard_error_update_social)
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Block all social media platforms at once
     */
    fun blockAllSocialMedia() {
        val deviceId = _uiState.value.selectedDeviceId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            val allPlatforms = SocialMediaPlatform.values().toSet()
            val allDomains = SocialMediaPlatform.getDomainsForPlatforms(allPlatforms.toList())

            val update = ContentFilterUpdate(
                blockSocialMedia = true,
                blockedDomains = allDomains
            )

            when (val result = contentFilterRepository.updateContentFilter(deviceId, update)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            blockSocialMediaEnabled = true,
                            blockedSocialMediaPlatforms = allPlatforms,
                            isLoadingContentFilter = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404) {
                        createContentFilterWithPlatforms(deviceId, allPlatforms)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoadingContentFilter = false,
                                error = getString(R.string.dashboard_error_block_social)
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Unblock all social media platforms at once
     */
    fun unblockAllSocialMedia() {
        val deviceId = _uiState.value.selectedDeviceId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            val update = ContentFilterUpdate(
                blockSocialMedia = false,
                blockedDomains = emptyList()
            )

            when (val result = contentFilterRepository.updateContentFilter(deviceId, update)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            blockSocialMediaEnabled = false,
                            blockedSocialMediaPlatforms = emptySet(),
                            isLoadingContentFilter = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingContentFilter = false,
                            error = getString(R.string.dashboard_error_unblock_social)
                        )
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Create content filter with specific platforms blocked
     */
    private suspend fun createContentFilterWithPlatforms(deviceId: Int, platforms: Set<SocialMediaPlatform>) {
        val domains = SocialMediaPlatform.getDomainsForPlatforms(platforms.toList())

        val update = ContentFilterUpdate(
            isActive = true,
            blockAdult = true,
            blockViolence = true,
            blockGambling = true,
            blockDrugs = true,
            blockSocialMedia = platforms.isNotEmpty(),
            blockedDomains = domains.ifEmpty { null }
        )

        when (val result = contentFilterRepository.createContentFilter(deviceId, update)) {
            is NetworkResult.Success -> {
                val blockedPlatforms = SocialMediaPlatform.getBlockedPlatforms(result.data.blockedDomains)
                _uiState.update {
                    it.copy(
                        contentFilterEnabled = result.data.isActive,
                        blockSocialMediaEnabled = result.data.blockSocialMedia || blockedPlatforms.isNotEmpty(),
                        blockedSocialMediaPlatforms = blockedPlatforms,
                        isLoadingContentFilter = false
                    )
                }
            }
            is NetworkResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoadingContentFilter = false,
                        error = getString(R.string.dashboard_error_create_filter)
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Toggle social media blocking for selected device (parent only)
     * @deprecated Use toggleSocialMediaPlatform for individual control
     */
    fun toggleSocialMediaBlocking(blocked: Boolean) {
        if (blocked) {
            // Show sub-menu to select platforms
            _uiState.update { it.copy(showSocialMediaSubMenu = true) }
        } else {
            // Unblock all
            unblockAllSocialMedia()
        }
    }

    /**
     * Create content filter for a device
     */
    private suspend fun createContentFilter(deviceId: Int, enabled: Boolean) {
        val update = ContentFilterUpdate(
            isActive = enabled,
            blockAdult = true,
            blockViolence = true,
            blockGambling = true,
            blockDrugs = true,
            blockSocialMedia = false
        )

        when (val result = contentFilterRepository.createContentFilter(deviceId, update)) {
            is NetworkResult.Success -> {
                _uiState.update {
                    it.copy(
                        contentFilterEnabled = result.data.isActive,
                        blockSocialMediaEnabled = result.data.blockSocialMedia,
                        isLoadingContentFilter = false
                    )
                }
            }
            is NetworkResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoadingContentFilter = false,
                        error = getString(R.string.dashboard_error_create_filter)
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Create content filter with social media setting
     */
    private suspend fun createContentFilterWithSocialMedia(deviceId: Int, blockSocialMedia: Boolean) {
        val update = ContentFilterUpdate(
            isActive = true,
            blockAdult = true,
            blockViolence = true,
            blockGambling = true,
            blockDrugs = true,
            blockSocialMedia = blockSocialMedia
        )

        when (val result = contentFilterRepository.createContentFilter(deviceId, update)) {
            is NetworkResult.Success -> {
                _uiState.update {
                    it.copy(
                        contentFilterEnabled = result.data.isActive,
                        blockSocialMediaEnabled = result.data.blockSocialMedia,
                        isLoadingContentFilter = false
                    )
                }
            }
            is NetworkResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoadingContentFilter = false,
                        error = getString(R.string.dashboard_error_create_filter)
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Load today's app usage
     */
    private suspend fun loadTodayAppUsage(deviceId: Int) {
        when (val result = screenTimeRepository.getTodayAppUsage(deviceId)) {
            is NetworkResult.Success -> {
                _uiState.update { it.copy(topApps = result.data.take(10)) }
            }
            else -> {}
        }
    }

    /**
     * Load alerts (past 7 days, max 10)
     * Note: The empty state message says "No recent alerts" to match this 7-day window.
     */
    private suspend fun loadAlerts(deviceId: Int? = null) {
        when (val result = alertRepository.getAlerts(deviceId = deviceId, days = 7, limit = 10)) {
            is NetworkResult.Success -> {
                _uiState.update {
                    it.copy(
                        recentAlerts = result.data,
                        unreadAlertCount = result.data.count { alert -> !alert.isRead }
                    )
                }
            }
            is NetworkResult.Error -> {
                // Log the error but don't clear existing alerts
                // This prevents showing "no alerts" when there's a network error
                Timber.w("Failed to load alerts: ${result.message}")
            }
            else -> {
                // Loading state - keep existing alerts
            }
        }
    }

    /**
     * Sync screen time to server (CHILD device only)
     * Parent devices should NOT sync their own screen time
     */
    fun syncScreenTime() {
        // Only child devices should sync their screen time
        if (authRepository.isParent()) {
            Timber.d("syncScreenTime: Skipping for PARENT device")
            return
        }

        viewModelScope.launch {
            Timber.d("syncScreenTime: Syncing CHILD device data to server")
            screenTimeRepository.syncScreenTimeFromDevice()
            screenTimeRepository.syncAppUsageFromDevice()
            preferencesManager.updateLastSyncTime()
        }
    }

    /**
     * Mark alert as read
     */
    fun markAlertAsRead(alertId: Int) {
        viewModelScope.launch {
            alertRepository.markAsRead(alertId)
            // Refresh alerts
            loadAlerts(_uiState.value.selectedDeviceId)
        }
    }

    /**
     * Suspend device (parent only)
     */
    fun suspendDevice(deviceId: Int) {
        viewModelScope.launch {
            when (val result = deviceRepository.suspendDevice(deviceId)) {
                is NetworkResult.Success -> {
                    // Refresh device list
                    loadDashboard()
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Activate device (parent only)
     */
    fun activateDevice(deviceId: Int) {
        viewModelScope.launch {
            when (val result = deviceRepository.activateDevice(deviceId)) {
                is NetworkResult.Success -> {
                    // Refresh device list
                    loadDashboard()
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Clear error
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Logout
     */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
