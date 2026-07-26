package com.safeguard.parentalcontrol.presentation.devicesetup

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.util.FcmTokenProvider
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

data class DeviceSetupUiState(
    val isLoading: Boolean = false,
    val isRegistered: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DeviceSetupViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val fcmTokenProvider: FcmTokenProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceSetupUiState())
    val uiState: StateFlow<DeviceSetupUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        // Check if already registered
        if (deviceRepository.isDeviceRegistered()) {
            _uiState.update { it.copy(isRegistered = true) }
        }
    }

    /**
     * Register this device with the backend
     */
    fun registerDevice(deviceName: String) {
        if (deviceName.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.devicesetup_error_empty_name)) }
            return
        }

        if (deviceName.length < 2) {
            _uiState.update { it.copy(error = getString(R.string.devicesetup_error_short_name)) }
            return
        }

        viewModelScope.launch {
            Log.d("DeviceSetup", "Registering device: $deviceName")
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Carry the push token in the registration itself so the backend can reach this
            // child device (for silent sync commands) from the moment it exists. A null token
            // is tolerated — PushTokenSyncWorker publishes it later once Firebase supplies one.
            val fcmToken = fcmTokenProvider.currentToken()

            when (val result = deviceRepository.registerDevice(deviceName, fcmToken)) {
                is NetworkResult.Success -> {
                    Log.d("DeviceSetup", "Device registered successfully: ${result.data.id}")
                    Timber.d("Device registered: ${result.data.deviceName} (ID: ${result.data.id})")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRegistered = true
                        )
                    }
                }
                is NetworkResult.Error -> {
                    Log.e("DeviceSetup", "Device registration failed: ${result.message}")
                    Timber.e("Device registration failed: ${result.message}")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
