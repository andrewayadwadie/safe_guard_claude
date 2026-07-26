package com.safeguard.parentalcontrol.presentation.alerts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.Alert
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class AlertsUiState(
    val alerts: List<Alert> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedFilter: AlertFilter = AlertFilter.ALL,
    val successMessage: String? = null,
    val deviceId: Int? = null,
    val deviceName: String? = null
)

enum class AlertFilter {
    ALL, UNREAD, CRITICAL, HIGH
}

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val deviceRepository: DeviceRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertsUiState())
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        loadAlerts()
    }

    /**
     * Set the device to filter alerts by.
     * Call this when navigating from device details to alerts screen.
     *
     * A device that has since been unlinked or removed from the family would otherwise leave
     * the parent staring at a permanently empty list with no explanation — most likely after
     * tapping an older violation notification. In that case fall back to the unfiltered list
     * rather than a broken target.
     */
    fun setDevice(deviceId: Int?, deviceName: String?) {
        _uiState.update { it.copy(deviceId = deviceId, deviceName = deviceName) }

        if (deviceId == null) {
            loadAlerts()
            return
        }

        viewModelScope.launch {
            if (!deviceStillExists(deviceId)) {
                Timber.d("Alerts target device $deviceId is no longer linked; showing all alerts")
                _uiState.update { it.copy(deviceId = null, deviceName = null) }
            }
            loadAlerts()
        }
    }

    /**
     * Refresh whenever the screen resumes, so the list is current after arriving from a
     * notification and after returning from any other screen.
     */
    fun onResume() {
        loadAlerts()
    }

    /**
     * @return false only when the backend positively reports the device is gone. A network
     *   failure leaves the filter in place — losing connectivity is not evidence that the
     *   device was unlinked.
     */
    private suspend fun deviceStillExists(deviceId: Int): Boolean =
        when (val result = deviceRepository.getDevice(deviceId)) {
            is NetworkResult.Success -> true
            is NetworkResult.Error -> result.code != 404
            else -> true
        }

    /**
     * Load alerts from the server
     * Filters by deviceId if set
     */
    fun loadAlerts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val filter = _uiState.value.selectedFilter
            val deviceId = _uiState.value.deviceId
            val isRead = when (filter) {
                AlertFilter.UNREAD -> false
                else -> null
            }
            val severity = when (filter) {
                AlertFilter.CRITICAL -> AlertSeverity.CRITICAL
                AlertFilter.HIGH -> AlertSeverity.HIGH
                else -> null
            }

            when (val result = alertRepository.getAlerts(
                deviceId = deviceId,
                isRead = isRead,
                severity = severity,
                days = 30,
                limit = 100
            )) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            alerts = result.data,
                            isLoading = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Set filter and reload
     */
    fun setFilter(filter: AlertFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        loadAlerts()
    }

    /**
     * Mark alert as read
     */
    fun markAsRead(alertId: Int) {
        viewModelScope.launch {
            when (val result = alertRepository.markAsRead(alertId)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            alerts = state.alerts.map { alert ->
                                if (alert.id == alertId) result.data else alert
                            }
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Dismiss alert
     */
    fun dismissAlert(alertId: Int) {
        viewModelScope.launch {
            when (val result = alertRepository.dismissAlert(alertId)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            alerts = state.alerts.filter { it.id != alertId },
                            successMessage = getString(R.string.alerts_msg_dismissed)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Mark all alerts as read
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            when (val result = alertRepository.markAllAsRead()) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            alerts = state.alerts.map { it.copy(isRead = true) },
                            successMessage = getString(R.string.alerts_msg_all_read)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Delete alert
     */
    fun deleteAlert(alertId: Int) {
        viewModelScope.launch {
            when (val result = alertRepository.deleteAlert(alertId)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            alerts = state.alerts.filter { it.id != alertId },
                            successMessage = getString(R.string.alerts_msg_deleted)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                else -> {}
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear success message
     */
    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
