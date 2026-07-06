package com.safeguard.parentalcontrol.data.repository

import android.content.Context
import android.os.Build
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for device management operations
 */
@Singleton
class DeviceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager
) {
    /**
     * Register this device with the backend
     */
    suspend fun registerDevice(
        deviceName: String,
        fcmToken: String? = null
    ): NetworkResult<Device> = withContext(Dispatchers.IO) {
        val deviceId = preferencesManager.getOrCreateDeviceId()

        Timber.i("DEVICE REGISTRATION: Starting registration...")
        Timber.i("  - deviceUuid (local): $deviceId")
        Timber.i("  - deviceName: $deviceName")
        Timber.i("  - deviceModel: ${Build.MANUFACTURER} ${Build.MODEL}")

        val request = DeviceRegisterRequest(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME,
            deviceInfo = mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "build" to Build.DISPLAY,
                "sdk_version" to Build.VERSION.SDK_INT.toString()
            ),
            fcmToken = fcmToken
        )

        val result = safeApiCall { apiService.registerDevice(request) }

        result.onSuccess { device ->
            // saveDeviceInfo also enables content filtering for child devices
            preferencesManager.saveDeviceInfo(deviceId, device.id)
            Timber.i("DEVICE REGISTRATION: SUCCESS!")
            Timber.i("  - Backend deviceDbId: ${device.id}")
            Timber.i("  - Device name: ${device.deviceName}")
            Timber.i("  - Stored deviceUuid: $deviceId")
            Timber.i("  - Stored deviceDbId: ${device.id}")
        }

        result.onError { message, code ->
            Timber.e("DEVICE REGISTRATION: FAILED!")
            Timber.e("  - Error code: $code")
            Timber.e("  - Error message: $message")
        }

        result
    }

    /**
     * Get all devices (for parent users)
     */
    suspend fun getDevices(): NetworkResult<List<Device>> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getDevices() }
    }

    /**
     * Get devices for a specific child (for parent users)
     */
    suspend fun getChildDevices(childId: Int): NetworkResult<List<Device>> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getDevices(childId = childId) }
    }

    /**
     * Get specific device details
     */
    suspend fun getDevice(deviceId: Int): NetworkResult<Device> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getDevice(deviceId) }
    }

    /**
     * Update device information
     */
    suspend fun updateDevice(
        deviceId: Int,
        deviceName: String? = null,
        fcmToken: String? = null,
        appVersion: String? = null
    ): NetworkResult<Device> = withContext(Dispatchers.IO) {
        val request = DeviceUpdateRequest(
            deviceName = deviceName,
            fcmToken = fcmToken,
            appVersion = appVersion
        )
        safeApiCall { apiService.updateDevice(deviceId, request) }
    }

    /**
     * Delete device
     */
    suspend fun deleteDevice(deviceId: Int): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.deleteDevice(deviceId) }

        result.onSuccess {
            // Clear local device info if this is the current device
            if (deviceId == preferencesManager.deviceDbId) {
                preferencesManager.deviceId = null
                preferencesManager.deviceDbId = -1
                preferencesManager.isDeviceRegistered = false
            }
        }

        result
    }

    /**
     * Sync device (update last_sync timestamp)
     */
    suspend fun syncDevice(): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val deviceId = preferencesManager.deviceDbId
        if (deviceId == -1) {
            return@withContext NetworkResult.Error("Device not registered")
        }

        // Report the device's current UTC offset on every heartbeat so the backend's
        // per-device offset stays fresh even for children who don't upload app usage
        // (usage access revoked, tampering). Without this the offset only refreshed on
        // app-usage batches and could drift stale, skewing daily-limit day rollover.
        val offsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val result = safeApiCall { apiService.syncDevice(deviceId, offsetMinutes) }

        result.onSuccess {
            preferencesManager.updateLastSyncTime()
            Timber.d("Device synced successfully (utcOffsetMinutes=$offsetMinutes)")
        }

        result
    }

    /**
     * Suspend device (parent only)
     */
    suspend fun suspendDevice(deviceId: Int): NetworkResult<Device> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.suspendDevice(deviceId) }
    }

    /**
     * Activate device (parent only)
     */
    suspend fun activateDevice(deviceId: Int): NetworkResult<Device> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.activateDevice(deviceId) }
    }

    /**
     * Check if device is registered
     */
    fun isDeviceRegistered(): Boolean {
        return preferencesManager.isDeviceRegistered
    }

    /**
     * Get current device database ID
     */
    fun getCurrentDeviceId(): Int {
        return preferencesManager.deviceDbId
    }

    /**
     * Get current device unique ID
     */
    fun getDeviceUniqueId(): String {
        return preferencesManager.getOrCreateDeviceId()
    }

    /**
     * Update FCM token for current device
     */
    suspend fun updateFcmToken(token: String): NetworkResult<Device> = withContext(Dispatchers.IO) {
        val deviceId = preferencesManager.deviceDbId
        if (deviceId == -1) {
            return@withContext NetworkResult.Error("Device not registered")
        }
        updateDevice(deviceId, fcmToken = token)
    }
}
