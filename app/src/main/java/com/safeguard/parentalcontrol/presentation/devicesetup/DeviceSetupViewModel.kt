package com.safeguard.parentalcontrol.presentation.devicesetup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceSetupUiState())
    val uiState: StateFlow<DeviceSetupUiState> = _uiState.asStateFlow()

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
            _uiState.update { it.copy(error = "Please enter a device name") }
            return
        }

        if (deviceName.length < 2) {
            _uiState.update { it.copy(error = "Device name is too short") }
            return
        }

        viewModelScope.launch {
            Log.d("DeviceSetup", "Registering device: $deviceName")
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = deviceRepository.registerDevice(deviceName)) {
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
