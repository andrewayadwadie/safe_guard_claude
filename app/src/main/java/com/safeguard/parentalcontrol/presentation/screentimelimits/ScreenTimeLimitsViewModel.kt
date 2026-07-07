package com.safeguard.parentalcontrol.presentation.screentimelimits

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.ScreenTimeRule
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents an app installed on the child's device
 */
data class ChildApp(
    val packageName: String,
    val appName: String
)

/**
 * UI State for screen time limits management screen
 */
data class ScreenTimeLimitsUiState(
    val isLoading: Boolean = false,
    val rules: ScreenTimeRule? = null,
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitHours: Int = 2,
    val dailyLimitMinutes: Int = 0,
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    // Study Time
    val studyTimeEnabled: Boolean = false,
    val studyTimeStart: String = "14:00",
    val studyTimeEnd: String = "17:00",
    val studyTimeAllowedApps: List<String> = emptyList(),
    // Remote Lock
    val isDeviceLocked: Boolean = false,
    val deviceLockedMessage: String = "",
    val appLimits: Map<String, Int> = emptyMap(),
    val blockedApps: List<String> = emptyList(),
    val isActive: Boolean = true,
    val childApps: List<ChildApp> = emptyList(),
    val isLoadingApps: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

/**
 * ViewModel for managing screen time limits
 */
@HiltViewModel
class ScreenTimeLimitsViewModel @Inject constructor(
    private val screenTimeRulesRepository: ScreenTimeRulesRepository,
    private val screenTimeRepository: ScreenTimeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenTimeLimitsUiState())
    val uiState: StateFlow<ScreenTimeLimitsUiState> = _uiState.asStateFlow()

    private var currentDeviceId: Int = -1

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    /**
     * Load screen time rules for a device
     */
    fun loadRules(deviceId: Int) {
        if (deviceId <= 0) {
            _uiState.update { it.copy(error = getString(R.string.screentime_error_invalid_device)) }
            return
        }

        currentDeviceId = deviceId
        timber.log.Timber.i("PARENT CONFIG: Loading rules for deviceId=$deviceId")

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load rules and child apps in parallel
            launch { loadChildApps(deviceId) }

            timber.log.Timber.i("PARENT CONFIG: Fetching screen time rules for deviceId=$deviceId")
            when (val result = screenTimeRulesRepository.getScreenTimeRules(deviceId)) {
                is NetworkResult.Success -> {
                    val rules = result.data
                    if (rules != null) {
                        val dailyLimitSeconds = rules.dailyLimit ?: 0
                        val dailyLimitEnabled = rules.dailyLimit != null && rules.dailyLimit > 0

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                rules = rules,
                                dailyLimitEnabled = dailyLimitEnabled,
                                dailyLimitHours = dailyLimitSeconds / 3600,
                                dailyLimitMinutes = (dailyLimitSeconds % 3600) / 60,
                                bedtimeEnabled = rules.bedtimeEnabled,
                                bedtimeStart = rules.bedtimeStart ?: "21:00",
                                bedtimeEnd = rules.bedtimeEnd ?: "07:00",
                                studyTimeEnabled = rules.studyTimeEnabled,
                                studyTimeStart = rules.studyTimeStart ?: "14:00",
                                studyTimeEnd = rules.studyTimeEnd ?: "17:00",
                                studyTimeAllowedApps = rules.studyTimeAllowedApps ?: emptyList(),
                                isDeviceLocked = rules.isDeviceLocked,
                                deviceLockedMessage = rules.deviceLockedMessage ?: "",
                                appLimits = rules.appLimits ?: emptyMap(),
                                blockedApps = rules.blockedApps ?: emptyList(),
                                isActive = rules.isActive
                            )
                        }
                    } else {
                        // No rules configured yet - show default empty state
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                rules = null,
                                dailyLimitEnabled = false,
                                dailyLimitHours = 2,
                                dailyLimitMinutes = 0,
                                bedtimeEnabled = false,
                                bedtimeStart = "21:00",
                                bedtimeEnd = "07:00",
                                studyTimeEnabled = false,
                                studyTimeStart = "14:00",
                                studyTimeEnd = "17:00",
                                studyTimeAllowedApps = emptyList(),
                                isDeviceLocked = false,
                                deviceLockedMessage = "",
                                appLimits = emptyMap(),
                                blockedApps = emptyList(),
                                isActive = true
                            )
                        }
                    }
                }
                is NetworkResult.Error -> {
                    // Check if this is a 404 "no rules configured" - not a real error
                    if (result.message.contains("not found", ignoreCase = true) ||
                        result.message.contains("No screen time rules", ignoreCase = true) ||
                        result.code == 404) {
                        // No rules exist yet - show default empty state (not an error)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                rules = null,
                                error = null
                            )
                        }
                    } else {
                        // Actual error
                        _uiState.update {
                            it.copy(isLoading = false, error = result.message)
                        }
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Load apps from child's device via backend
     * Fetches app usage logs which contain all apps the child has used
     */
    private suspend fun loadChildApps(deviceId: Int) {
        _uiState.update { it.copy(isLoadingApps = true) }

        when (val result = screenTimeRepository.getAppUsageLogs(deviceId, days = 30)) {
            is NetworkResult.Success -> {
                // Extract unique apps from usage logs
                val apps = result.data
                    .distinctBy { it.packageName }
                    .map { log ->
                        ChildApp(
                            packageName = log.packageName,
                            appName = log.appName ?: log.packageName.split(".").lastOrNull() ?: log.packageName
                        )
                    }
                    .sortedBy { it.appName.lowercase() }

                _uiState.update {
                    it.copy(childApps = apps, isLoadingApps = false)
                }
            }
            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingApps = false) }
            }
            is NetworkResult.Loading -> {
                // Already handled
            }
        }
    }

    /**
     * Toggle daily limit on/off
     */
    fun setDailyLimitEnabled(enabled: Boolean) {
        _uiState.update { it.copy(dailyLimitEnabled = enabled) }
    }

    /**
     * Update daily limit value
     */
    fun setDailyLimit(hours: Int, minutes: Int) {
        _uiState.update {
            it.copy(
                dailyLimitHours = hours.coerceIn(0, 24),
                dailyLimitMinutes = minutes.coerceIn(0, 59)
            )
        }
    }

    /**
     * Toggle bedtime mode on/off
     */
    fun setBedtimeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(bedtimeEnabled = enabled) }
    }

    /**
     * Update bedtime start time
     */
    fun setBedtimeStart(time: String) {
        _uiState.update { it.copy(bedtimeStart = time) }
    }

    /**
     * Update bedtime end time
     */
    fun setBedtimeEnd(time: String) {
        _uiState.update { it.copy(bedtimeEnd = time) }
    }

    /**
     * Toggle rules active/inactive
     */
    fun setRulesActive(active: Boolean) {
        _uiState.update { it.copy(isActive = active) }
    }

    /**
     * Toggle study time mode on/off
     */
    fun setStudyTimeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(studyTimeEnabled = enabled) }
    }

    /**
     * Update study time start time
     */
    fun setStudyTimeStart(time: String) {
        _uiState.update { it.copy(studyTimeStart = time) }
    }

    /**
     * Update study time end time
     */
    fun setStudyTimeEnd(time: String) {
        _uiState.update { it.copy(studyTimeEnd = time) }
    }

    /**
     * Add an app to study time allowed list
     */
    fun addStudyTimeAllowedApp(packageName: String) {
        _uiState.update { state ->
            if (packageName !in state.studyTimeAllowedApps) {
                state.copy(studyTimeAllowedApps = state.studyTimeAllowedApps + packageName)
            } else {
                state
            }
        }
    }

    /**
     * Remove an app from study time allowed list
     */
    fun removeStudyTimeAllowedApp(packageName: String) {
        _uiState.update {
            it.copy(studyTimeAllowedApps = it.studyTimeAllowedApps - packageName)
        }
    }

    /**
     * Toggle device lock on/off
     */
    fun setDeviceLocked(locked: Boolean) {
        _uiState.update { it.copy(isDeviceLocked = locked) }
    }

    /**
     * Update device locked message
     */
    fun setDeviceLockedMessage(message: String) {
        _uiState.update { it.copy(deviceLockedMessage = message) }
    }

    /**
     * Add or update an app limit
     * Uses immutable map operations for predictable Compose state updates
     */
    fun addAppLimit(packageName: String, limitSeconds: Int) {
        _uiState.update {
            it.copy(appLimits = it.appLimits + (packageName to limitSeconds))
        }
    }

    /**
     * Remove an app limit
     */
    fun removeAppLimit(packageName: String) {
        _uiState.update {
            it.copy(appLimits = it.appLimits - packageName)
        }
    }

    /**
     * Add an app to blocked list
     */
    fun addBlockedApp(packageName: String) {
        _uiState.update { state ->
            if (packageName !in state.blockedApps) {
                state.copy(blockedApps = state.blockedApps + packageName)
            } else {
                state
            }
        }
    }

    /**
     * Remove an app from blocked list
     */
    fun removeBlockedApp(packageName: String) {
        _uiState.update {
            it.copy(blockedApps = it.blockedApps - packageName)
        }
    }

    /**
     * Save all current settings to server
     */
    fun saveRules() {
        if (currentDeviceId < 0) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val state = _uiState.value
            // When disabled, send 0 instead of null
            // Backend only updates fields that are explicitly provided (not null)
            // Gson omits null values, so we must send 0 to clear the limit
            val dailyLimitSeconds = if (state.dailyLimitEnabled) {
                state.dailyLimitHours * 3600 + state.dailyLimitMinutes * 60
            } else {
                0  // 0 means "no limit" - must send explicitly to clear existing limit
            }

            timber.log.Timber.i("PARENT CONFIG: Saving rules for deviceId=$currentDeviceId")
            timber.log.Timber.i("  - bedtimeEnabled=${state.bedtimeEnabled}")
            timber.log.Timber.i("  - bedtimeStart=${state.bedtimeStart}")
            timber.log.Timber.i("  - bedtimeEnd=${state.bedtimeEnd}")
            timber.log.Timber.i("  - studyTimeEnabled=${state.studyTimeEnabled}")
            timber.log.Timber.i("  - studyTimeStart=${state.studyTimeStart}")
            timber.log.Timber.i("  - studyTimeEnd=${state.studyTimeEnd}")
            timber.log.Timber.i("  - isDeviceLocked=${state.isDeviceLocked}")
            timber.log.Timber.i("  - dailyLimitSeconds=$dailyLimitSeconds")
            timber.log.Timber.i("  - isActive=${state.isActive}")

            val result = screenTimeRulesRepository.updateScreenTimeRules(
                deviceId = currentDeviceId,
                dailyLimit = dailyLimitSeconds,
                bedtimeEnabled = state.bedtimeEnabled,
                bedtimeStart = if (state.bedtimeEnabled) state.bedtimeStart else null,
                bedtimeEnd = if (state.bedtimeEnabled) state.bedtimeEnd else null,
                studyTimeEnabled = state.studyTimeEnabled,
                studyTimeStart = if (state.studyTimeEnabled) state.studyTimeStart else null,
                studyTimeEnd = if (state.studyTimeEnabled) state.studyTimeEnd else null,
                studyTimeAllowedApps = state.studyTimeAllowedApps.ifEmpty { null },
                isDeviceLocked = state.isDeviceLocked,
                deviceLockedMessage = state.deviceLockedMessage.ifEmpty { null },
                appLimits = state.appLimits.ifEmpty { null },
                blockedApps = state.blockedApps.ifEmpty { null },
                isActive = state.isActive
            )

            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            rules = result.data,
                            successMessage = getString(R.string.screentime_msg_saved)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Delete all rules for this device
     */
    fun deleteRules() {
        if (currentDeviceId < 0) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = screenTimeRulesRepository.deleteScreenTimeRules(currentDeviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        ScreenTimeLimitsUiState(
                            successMessage = getString(R.string.screentime_msg_deleted)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Clear error and success messages
     */
    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
