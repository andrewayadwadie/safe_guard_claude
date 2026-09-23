package com.safeguard.parentalcontrol.data.repository

import android.content.Context
import android.os.Build
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.FcmTokenProvider
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val preferencesManager: PreferencesManager,
    private val fcmTokenProvider: FcmTokenProvider
) {
    /** Serialises token recovery so a burst of alerts cannot re-register the device N times. */
    private val deviceTokenMutex = Mutex()

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
            preferencesManager.saveDeviceInfo(
                deviceId = deviceId,
                deviceDbId = device.id,
                deviceName = deviceName,
                // The credential every device-authenticated call needs. Captured here because
                // this response is the only place the backend hands it over.
                deviceToken = device.deviceToken
            )
            Timber.i("DEVICE REGISTRATION: SUCCESS!")
            Timber.i("  - Backend deviceDbId: ${device.id}")
            Timber.i("  - Device name: ${device.deviceName}")
            Timber.i("  - Stored deviceUuid: $deviceId")
            Timber.i("  - Stored deviceDbId: ${device.id}")
            // Presence only — the token itself is a live credential and never goes to logcat.
            Timber.i("  - device_token: ${if (device.deviceToken.isNullOrBlank()) "MISSING (device-auth calls will fail)" else "stored"}")
        }

        result.onError { message, code ->
            Timber.e("DEVICE REGISTRATION: FAILED!")
            Timber.e("  - Error code: $code")
            Timber.e("  - Error message: $message")
        }

        result
    }

    /**
     * The `device_token` for this device, recovering it from the backend when it is missing.
     *
     * Callers of the device-authenticated endpoints (`POST /alerts`, `GET /word-lists/sync`)
     * go through here rather than reading preferences directly, because the token can be
     * absent on a device that registered before it was persisted at all: that install has a
     * valid device record and a signed-in user, but nothing to put in `X-Device-Token`, and
     * every alert it raised was rejected. Recovery order:
     *
     * 1. the stored token;
     * 2. `GET /devices/{id}` — cheap, and does not touch any other device field;
     * 3. re-registering the same `device_id`, which the backend upserts and answers with the
     *    existing record plus its token. The current FCM token travels with that request so
     *    re-registration cannot blank out the device's push registration.
     *
     * @return null when the device is not registered or the backend never supplied a token —
     *   the caller reports that rather than sending a request that is certain to be rejected.
     */
    suspend fun ensureDeviceToken(): String? = deviceTokenMutex.withLock {
        preferencesManager.deviceToken?.takeIf { it.isNotBlank() }?.let { return@withLock it }

        if (!preferencesManager.isDeviceRegistered) {
            Timber.w("DEVICE TOKEN: no token and device is not registered")
            return@withLock null
        }

        val dbId = preferencesManager.deviceDbId
        if (dbId != -1) {
            val fetched = (safeApiCall { apiService.getDevice(dbId) } as? NetworkResult.Success)
                ?.data?.deviceToken?.takeIf { it.isNotBlank() }
            if (fetched != null) {
                preferencesManager.deviceToken = fetched
                Timber.i("DEVICE TOKEN: recovered from GET /devices/$dbId")
                return@withLock fetched
            }
        }

        // GET did not carry the token; re-register to have the backend re-issue it.
        val deviceName = preferencesManager.deviceName ?: Build.MODEL
        val result = registerDevice(deviceName, fcmTokenProvider.currentToken())
        val recovered = (result as? NetworkResult.Success)?.data?.deviceToken?.takeIf { it.isNotBlank() }

        if (recovered == null) {
            Timber.e("DEVICE TOKEN: recovery failed — device-authenticated calls cannot be made")
        } else {
            Timber.i("DEVICE TOKEN: recovered by re-registering device")
        }
        recovered
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
                // The credential dies with the record it authenticated.
                preferencesManager.deviceToken = null
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
