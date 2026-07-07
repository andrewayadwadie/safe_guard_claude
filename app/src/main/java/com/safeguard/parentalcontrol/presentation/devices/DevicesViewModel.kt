package com.safeguard.parentalcontrol.presentation.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.ContentFilter
import com.safeguard.parentalcontrol.data.model.ContentFilterUpdate
import com.safeguard.parentalcontrol.data.model.Device
import com.safeguard.parentalcontrol.data.model.DeviceStatus
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.ContentFilterRepository
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

data class DevicesUiState(
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val selectedDevice: Device? = null,
    val showDeviceDetails: Boolean = false,
    // Content filtering state per device
    val contentFilterEnabled: Boolean = false,
    val blockSocialMediaEnabled: Boolean = false,  // Social media blocking (default: allowed)
    val isLoadingContentFilter: Boolean = false
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val contentFilterRepository: ContentFilterRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        loadDevices()
    }

    /**
     * Load all devices from the server
     * @param childId Optional - if provided, filters devices to only show this child's devices
     */
    fun loadDevices(childId: Int? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            Timber.i("PARENT DEVICES: Loading devices list... childId=$childId")
            when (val result = if (childId != null) {
                deviceRepository.getChildDevices(childId)
            } else {
                deviceRepository.getDevices()
            }) {
                is NetworkResult.Success -> {
                    Timber.i("PARENT DEVICES: Found ${result.data.size} devices:")
                    result.data.forEach { device ->
                        Timber.i("  - Device ID: ${device.id}, Name: '${device.deviceName}', Model: '${device.deviceModel}', Status: ${device.status}")
                    }
                    _uiState.update {
                        it.copy(
                            devices = result.data,
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
     * Show device details and load content filter settings
     */
    fun showDeviceDetails(device: Device) {
        _uiState.update { it.copy(selectedDevice = device, showDeviceDetails = true) }
        loadContentFilterSettings(device.id)
    }

    /**
     * Hide device details
     */
    fun hideDeviceDetails() {
        _uiState.update { it.copy(selectedDevice = null, showDeviceDetails = false, contentFilterEnabled = false, blockSocialMediaEnabled = false) }
    }

    /**
     * Load content filter settings for a device
     */
    private fun loadContentFilterSettings(deviceId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            when (val result = contentFilterRepository.getContentFilter(deviceId)) {
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
                    // If 404, content filter doesn't exist yet - default to enabled for new setups
                    if (result.code == 404) {
                        _uiState.update {
                            it.copy(
                                contentFilterEnabled = true, // Default to enabled
                                blockSocialMediaEnabled = false, // Default: social media allowed
                                isLoadingContentFilter = false
                            )
                        }
                    } else {
                        Timber.e("Failed to load content filter: ${result.message}")
                        _uiState.update {
                            it.copy(isLoadingContentFilter = false)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Toggle content filtering for a device (parent control)
     * This enables/disables VPN content filtering on the child's device
     */
    fun toggleContentFiltering(deviceId: Int, enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            val update = ContentFilterUpdate(
                isActive = enabled,
                // Enable all content blocking categories by default when enabling
                blockAdult = if (enabled) true else null,
                blockViolence = if (enabled) true else null,
                blockGambling = if (enabled) true else null,
                blockDrugs = if (enabled) true else null
            )

            // Try to update first, if it fails with 404, create new filter
            when (val result = contentFilterRepository.updateContentFilter(deviceId, update)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            contentFilterEnabled = result.data.isActive,
                            isLoadingContentFilter = false,
                            successMessage = if (enabled) getString(R.string.devices_msg_filter_enabled) else getString(R.string.devices_msg_filter_disabled)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404) {
                        // Content filter doesn't exist, create it
                        createContentFilter(deviceId, enabled)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoadingContentFilter = false,
                                error = getString(R.string.devices_error_update_filter, result.message ?: "")
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Toggle social media blocking for a device (parent control)
     * By default, social media is ALLOWED unless the parent chooses to block it.
     */
    fun toggleSocialMediaBlocking(deviceId: Int, blocked: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContentFilter = true) }

            val update = ContentFilterUpdate(
                blockSocialMedia = blocked
            )

            // Try to update first, if it fails with 404, create new filter
            when (val result = contentFilterRepository.updateContentFilter(deviceId, update)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            blockSocialMediaEnabled = result.data.blockSocialMedia,
                            isLoadingContentFilter = false,
                            successMessage = if (blocked) getString(R.string.devices_msg_social_blocked) else getString(R.string.devices_msg_social_allowed)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404) {
                        // Content filter doesn't exist, create it with social media setting
                        createContentFilterWithSocialMedia(deviceId, blocked)
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoadingContentFilter = false,
                                error = getString(R.string.devices_error_update_social, result.message ?: "")
                            )
                        }
                    }
                }
                else -> {}
            }
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
                        isLoadingContentFilter = false,
                        successMessage = if (blockSocialMedia) getString(R.string.devices_msg_social_blocked) else getString(R.string.devices_msg_social_allowed)
                    )
                }
            }
            is NetworkResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoadingContentFilter = false,
                        error = getString(R.string.devices_error_create_filter, result.message ?: "")
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Create content filter settings for a device
     */
    private suspend fun createContentFilter(deviceId: Int, enabled: Boolean) {
        val update = ContentFilterUpdate(
            isActive = enabled,
            blockAdult = true,
            blockViolence = true,
            blockGambling = true,
            blockDrugs = true
        )

        when (val result = contentFilterRepository.createContentFilter(deviceId, update)) {
            is NetworkResult.Success -> {
                _uiState.update {
                    it.copy(
                        contentFilterEnabled = result.data.isActive,
                        isLoadingContentFilter = false,
                        successMessage = if (enabled) getString(R.string.devices_msg_filter_enabled) else getString(R.string.devices_msg_filter_disabled)
                    )
                }
            }
            is NetworkResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoadingContentFilter = false,
                        error = getString(R.string.devices_error_create_filter, result.message ?: "")
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Suspend a device
     */
    fun suspendDevice(deviceId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            when (val result = deviceRepository.suspendDevice(deviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            devices = state.devices.map { device ->
                                if (device.id == deviceId) result.data else device
                            },
                            selectedDevice = if (state.selectedDevice?.id == deviceId) result.data else state.selectedDevice,
                            isLoading = false,
                            successMessage = getString(R.string.devices_msg_suspended)
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
     * Activate a device
     */
    fun activateDevice(deviceId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            when (val result = deviceRepository.activateDevice(deviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            devices = state.devices.map { device ->
                                if (device.id == deviceId) result.data else device
                            },
                            selectedDevice = if (state.selectedDevice?.id == deviceId) result.data else state.selectedDevice,
                            isLoading = false,
                            successMessage = getString(R.string.devices_msg_activated)
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
     * Delete a device
     */
    fun deleteDevice(deviceId: Int, deviceName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            when (val result = deviceRepository.deleteDevice(deviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            devices = state.devices.filter { it.id != deviceId },
                            selectedDevice = null,
                            showDeviceDetails = false,
                            isLoading = false,
                            successMessage = "$deviceName has been removed"
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
