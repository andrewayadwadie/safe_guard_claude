package com.safeguard.parentalcontrol.presentation.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.util.ImageBlurManager
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.ProtectionStatusHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SettingsUiState(
    val userName: String = "",
    val userEmail: String = "",
    val userRole: UserRole = UserRole.CHILD,
    val isContentFilteringEnabled: Boolean = false,
    val isMaximumProtectionEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val logoutSuccess: Boolean = false,
    val error: String? = null,
    val currentLanguage: String = LocaleHelper.LANGUAGE_ENGLISH
)

/**
 * Events that require Activity interaction (e.g., VPN permission)
 */
sealed class SettingsEvent {
    data class RequestVpnPermission(val prepareIntent: Intent) : SettingsEvent()
    object VpnPermissionGranted : SettingsEvent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferencesManager: PreferencesManager,
    private val imageBlurManager: ImageBlurManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    /**
     * Broadcast receiver for VPN state changes
     */
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ContentFilterVpnService.ACTION_VPN_STATE_CHANGED) {
                val isRunning = intent.getBooleanExtra(ContentFilterVpnService.EXTRA_VPN_RUNNING, false)
                Log.d("SafeGuardSettings", "Received VPN state broadcast: isRunning=$isRunning")

                _uiState.update { it.copy(isContentFilteringEnabled = isRunning) }

                if (!isRunning) {
                    // Show error when VPN fails to start
                    _uiState.update {
                        it.copy(error = "Failed to start content filtering. VPN permission may have been revoked.")
                    }
                }
            }
        }
    }

    init {
        loadUserInfo()
        loadContentFilteringState()
        refreshMaximumProtectionState()
        refreshNotificationStatus()
        registerVpnStateReceiver()
        _uiState.update { it.copy(currentLanguage = LocaleHelper.getLanguage(context)) }
    }

    /**
     * Load the Maximum Protection flag into UI state. Called on init and on resume so the
     * toggle always reflects the stored value.
     */
    fun refreshMaximumProtectionState() {
        _uiState.update {
            it.copy(isMaximumProtectionEnabled = preferencesManager.isMaximumProtectionEnabled)
        }
    }

    /**
     * Persist a PIN-approved Maximum Protection value. MUST be called only after successful
     * parent-PIN verification (the screen gates this behind [ParentPinDialog]).
     *
     * When enabling, kick off a retroactive blur pass so images flagged copy-only while the
     * setting was OFF get blurred now. The pass is idempotent and runs off the main thread.
     */
    fun setMaximumProtection(enabled: Boolean) {
        preferencesManager.isMaximumProtectionEnabled = enabled
        _uiState.update { it.copy(isMaximumProtectionEnabled = enabled) }

        if (enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                val count = imageBlurManager.applyBlurToUnblurredBackups()
                Timber.d("Maximum Protection enabled; retroactively blurred $count image(s)")
            }
        }
    }

    /** Persists the chosen language. Caller (SettingsScreen) is responsible for `Activity.recreate()`. */
    fun setLanguage(lang: String) {
        LocaleHelper.setLanguage(context, lang)
        _uiState.update { it.copy(currentLanguage = lang) }
    }

    private fun registerVpnStateReceiver() {
        try {
            val filter = IntentFilter(ContentFilterVpnService.ACTION_VPN_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(vpnStateReceiver, filter)
            }
            Log.d("SafeGuardSettings", "VPN state receiver registered")
        } catch (e: Exception) {
            Log.e("SafeGuardSettings", "Failed to register VPN state receiver", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(vpnStateReceiver)
            Log.d("SafeGuardSettings", "VPN state receiver unregistered")
        } catch (e: Exception) {
            // Receiver may not have been registered
        }
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            when (val result = authRepository.getCurrentUser()) {
                is NetworkResult.Success -> {
                    val user = result.data
                    _uiState.update {
                        it.copy(
                            userName = user.fullName,
                            userEmail = user.email,
                            userRole = user.role
                        )
                    }
                }
                is NetworkResult.Error -> {
                    // Silent fail - user info is optional for settings
                }
                else -> {}
            }
        }
    }

    private fun loadContentFilteringState() {
        // Check both saved preference AND actual VPN running state
        val savedPreference = preferencesManager.isContentFilteringEnabled
        val actuallyRunning = ContentFilterVpnService.isVpnRunning(context)
        val isChild = preferencesManager.userRole == "child"

        Log.d("SafeGuardSettings", "Content filtering state: savedPref=$savedPreference, actuallyRunning=$actuallyRunning, isChild=$isChild")

        // For child accounts: show the parent's setting (savedPreference), not VPN running state
        // The preference is synced from backend by SyncWorker
        // For parent accounts: this setting isn't relevant (they control it per child device)
        if (isChild) {
            _uiState.update {
                it.copy(isContentFilteringEnabled = savedPreference)
            }

            // NOTE: Do NOT auto-start VPN here just because settings screen is opened.
            // VPN should be started explicitly from Permissions Setup screen or by SyncWorker.
            // Auto-starting here causes unexpected VPN popup when user just wants to view settings.
            if (savedPreference && !actuallyRunning) {
                Log.d("SafeGuardSettings", "Parent enabled filtering but VPN not running. User should go to Permissions Setup to enable it.")
            }
        } else {
            // For parents, just show actual VPN state (though this is rarely relevant)
            _uiState.update {
                it.copy(isContentFilteringEnabled = actuallyRunning)
            }
        }
    }

    /**
     * Try to start VPN for child device when parent has enabled content filtering
     */
    private fun tryStartVpnForChild() {
        viewModelScope.launch {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                // VPN permission needed - emit event to request permission
                Log.d("SafeGuardSettings", "VPN permission needed for child content filtering")
                _events.emit(SettingsEvent.RequestVpnPermission(prepareIntent))
            } else {
                // Permission already granted, start VPN
                Log.d("SafeGuardSettings", "VPN permission granted, starting VPN for child")
                enableContentFiltering()
            }
        }
    }

    /**
     * Refresh content filtering state - called when returning to Settings screen
     */
    fun refreshContentFilteringState() {
        loadContentFilteringState()
    }

    /**
     * Refresh notification permission state - called on init and when returning to Settings screen
     */
    fun refreshNotificationStatus() {
        _uiState.update {
            it.copy(notificationsEnabled = ProtectionStatusHelper.isNotificationsEnabled(context))
        }
    }

    /**
     * Toggle content filtering (VPN-based domain blocking)
     * This requires VPN permission from the user
     */
    fun toggleContentFiltering(enabled: Boolean) {
        Log.d("SafeGuardSettings", "toggleContentFiltering called: enabled=$enabled")
        viewModelScope.launch {
            if (enabled) {
                // Check if VPN permission is needed
                val prepareIntent = VpnService.prepare(context)
                Log.d("SafeGuardSettings", "VpnService.prepare() returned: ${if (prepareIntent == null) "null (permission granted)" else "Intent (need permission)"}")
                if (prepareIntent != null) {
                    // Need to request VPN permission from Activity
                    Log.d("SafeGuardSettings", "Emitting VPN permission request event")
                    _events.emit(SettingsEvent.RequestVpnPermission(prepareIntent))
                } else {
                    // Permission already granted, start VPN
                    Log.d("SafeGuardSettings", "VPN permission already granted, enabling content filtering")
                    enableContentFiltering()
                }
            } else {
                disableContentFiltering()
            }
        }
    }

    /**
     * Called when VPN permission is granted by the user
     */
    fun onVpnPermissionResult(granted: Boolean) {
        Log.d("SafeGuardSettings", "onVpnPermissionResult: granted=$granted")
        viewModelScope.launch {
            if (granted) {
                Log.d("SafeGuardSettings", "VPN permission granted, enabling content filtering")
                enableContentFiltering()
                _events.emit(SettingsEvent.VpnPermissionGranted)
            } else {
                Log.w("SafeGuardSettings", "VPN permission denied")
                _uiState.update {
                    it.copy(error = "VPN permission is required for content filtering")
                }
            }
        }
    }

    private fun enableContentFiltering() {
        Log.d("SafeGuardSettings", "enableContentFiltering() called")
        try {
            // Save preference
            preferencesManager.isContentFilteringEnabled = true
            _uiState.update { it.copy(isContentFilteringEnabled = true) }
            Log.d("SafeGuardSettings", "Preference saved, UI state updated")

            // Start VPN service
            val intent = Intent(context, ContentFilterVpnService::class.java)
            Log.d("SafeGuardSettings", "Starting VPN service with intent: $intent")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d("SafeGuardSettings", "Using startForegroundService (Android O+)")
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.d("SafeGuardSettings", "VPN service start command sent successfully")
            Timber.d("Content filtering enabled, VPN service started")
        } catch (e: Exception) {
            Log.e("SafeGuardSettings", "Failed to start VPN service", e)
            Timber.e(e, "Failed to start content filter VPN service")
            _uiState.update {
                it.copy(error = "Failed to enable content filtering: ${e.message}")
            }
        }
    }

    private fun disableContentFiltering() {
        try {
            // Save preference
            preferencesManager.isContentFilteringEnabled = false
            _uiState.update { it.copy(isContentFilteringEnabled = false) }

            // Stop VPN service
            val intent = Intent(context, ContentFilterVpnService::class.java).apply {
                action = ContentFilterVpnService.ACTION_STOP
            }
            context.startService(intent)

            Timber.d("Content filtering disabled, VPN service stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop content filter VPN service")
        }
    }

    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Disable content filtering before logout
            if (preferencesManager.isContentFilteringEnabled) {
                disableContentFiltering()
            }

            when (authRepository.logout()) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            logoutSuccess = true
                        )
                    }
                }
                is NetworkResult.Error -> {
                    // Still navigate to login even if API call fails
                    // Local tokens are cleared regardless
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            logoutSuccess = true
                        )
                    }
                }
                else -> {}
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        /**
         * Start VPN service from any context (e.g., BootReceiver)
         */
        fun startContentFilteringService(context: Context) {
            try {
                val intent = Intent(context, ContentFilterVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Timber.d("Content filtering service started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start content filtering service")
            }
        }
    }
}
