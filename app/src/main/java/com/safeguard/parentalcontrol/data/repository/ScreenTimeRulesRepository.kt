package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for screen time rules management
 *
 * Handles:
 * - CRUD operations for screen time rules
 * - Local caching for child device enforcement
 * - Helper methods to check limits and bedtime
 */
@Singleton
class ScreenTimeRulesRepository @Inject constructor(
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager
) {
    // Cached rules for the current device (for child enforcement)
    private val _cachedRules = MutableStateFlow<ScreenTimeRule?>(null)
    val cachedRules: StateFlow<ScreenTimeRule?> = _cachedRules.asStateFlow()

    // Cache timestamp for expiration check
    private var cacheTimestamp: Long = 0
    private val cacheExpirationMs = 5 * 60 * 1000L // 5 minutes

    // Mutex to prevent concurrent cache refresh operations
    private val cacheMutex = Mutex()

    /**
     * Get screen time rules for a device
     * Returns null if no rules configured (404 is treated as "no rules", not an error)
     */
    suspend fun getScreenTimeRules(deviceId: Int): NetworkResult<ScreenTimeRule?> =
        withContext(Dispatchers.IO) {
            Timber.d("=== FETCHING SCREEN TIME RULES for device $deviceId ===")
            try {
                val result = safeApiCall { apiService.getScreenTimeRules(deviceId) }
                Timber.d("API call completed for device $deviceId, result type: ${result::class.simpleName}")

                when (result) {
                    is NetworkResult.Success -> {
                        // Cache the rules
                        _cachedRules.value = result.data
                        cacheTimestamp = System.currentTimeMillis()
                        Timber.d("Rules loaded for device $deviceId: isActive=${result.data.isActive}, bedtime=${result.data.bedtimeEnabled}(${result.data.bedtimeStart}-${result.data.bedtimeEnd}), blockedApps=${result.data.blockedApps}, studyTime=${result.data.studyTimeEnabled}(${result.data.studyTimeStart}-${result.data.studyTimeEnd}), studyTimeAllowedApps=${result.data.studyTimeAllowedApps}, isDeviceLocked=${result.data.isDeviceLocked}")
                        NetworkResult.Success(result.data)
                    }
                    is NetworkResult.Error -> {
                        if (result.code == 404) {
                            // 404 means no rules configured - this is expected, not an error
                            Timber.d("No rules configured for device $deviceId (404)")
                            _cachedRules.value = null
                            cacheTimestamp = System.currentTimeMillis()
                            NetworkResult.Success(null)
                        } else {
                            Timber.e("Rules fetch FAILED for device $deviceId: code=${result.code}, message=${result.message}")
                            result
                        }
                    }
                    is NetworkResult.Loading -> {
                        Timber.d("Rules fetch still loading for device $deviceId")
                        result
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "EXCEPTION during rules fetch for device $deviceId")
                NetworkResult.Error(e.message ?: "Unknown error", -1)
            }
        }

    /**
     * Get cached rules, fetching from server if cache is expired
     * Uses mutex to prevent concurrent API calls when multiple callers request rules
     */
    suspend fun getCachedRules(deviceId: Int, forceRefresh: Boolean = false): ScreenTimeRule? {
        return cacheMutex.withLock {
            val now = System.currentTimeMillis()
            val cacheExpired = now - cacheTimestamp > cacheExpirationMs
            val cacheAge = now - cacheTimestamp

            Timber.d("getCachedRules: deviceId=$deviceId, forceRefresh=$forceRefresh, " +
                "cacheExpired=$cacheExpired (age=${cacheAge}ms), hasCachedRules=${_cachedRules.value != null}")

            if (forceRefresh || cacheExpired || _cachedRules.value == null) {
                Timber.d("getCachedRules: Fetching fresh rules from API")
                val result = getScreenTimeRules(deviceId)
                if (result is NetworkResult.Success) {
                    Timber.d("getCachedRules: Successfully fetched rules")
                    return@withLock result.data
                } else {
                    Timber.w("getCachedRules: Failed to fetch rules, returning cached value")
                }
            }

            _cachedRules.value
        }
    }

    /**
     * Create screen time rules for a device (parent only)
     */
    suspend fun createScreenTimeRules(
        deviceId: Int,
        dailyLimit: Int? = null,
        bedtimeEnabled: Boolean = false,
        bedtimeStart: String? = null,
        bedtimeEnd: String? = null,
        appLimits: Map<String, Int>? = null,
        blockedApps: List<String>? = null
    ): NetworkResult<ScreenTimeRule> = withContext(Dispatchers.IO) {
        val request = ScreenTimeRuleCreateRequest(
            deviceId = deviceId,
            dailyLimit = dailyLimit,
            bedtimeEnabled = bedtimeEnabled,
            bedtimeStart = bedtimeStart,
            bedtimeEnd = bedtimeEnd,
            appLimits = appLimits,
            blockedApps = blockedApps
        )

        val result = safeApiCall { apiService.createScreenTimeRules(deviceId, request) }

        result.onSuccess { rules ->
            _cachedRules.value = rules
            cacheTimestamp = System.currentTimeMillis()
            Timber.d("Screen time rules created for device $deviceId")
        }

        result
    }

    /**
     * Update screen time rules (parent only)
     */
    suspend fun updateScreenTimeRules(
        deviceId: Int,
        dailyLimit: Int? = null,
        bedtimeEnabled: Boolean? = null,
        bedtimeStart: String? = null,
        bedtimeEnd: String? = null,
        studyTimeEnabled: Boolean? = null,
        studyTimeStart: String? = null,
        studyTimeEnd: String? = null,
        studyTimeAllowedApps: List<String>? = null,
        isDeviceLocked: Boolean? = null,
        deviceLockedMessage: String? = null,
        appLimits: Map<String, Int>? = null,
        blockedApps: List<String>? = null,
        isActive: Boolean? = null
    ): NetworkResult<ScreenTimeRule> = withContext(Dispatchers.IO) {
        val request = ScreenTimeRuleUpdateRequest(
            dailyLimit = dailyLimit,
            bedtimeEnabled = bedtimeEnabled,
            bedtimeStart = bedtimeStart,
            bedtimeEnd = bedtimeEnd,
            studyTimeEnabled = studyTimeEnabled,
            studyTimeStart = studyTimeStart,
            studyTimeEnd = studyTimeEnd,
            studyTimeAllowedApps = studyTimeAllowedApps,
            isDeviceLocked = isDeviceLocked,
            deviceLockedMessage = deviceLockedMessage,
            appLimits = appLimits,
            blockedApps = blockedApps,
            isActive = isActive
        )

        val result = safeApiCall { apiService.updateScreenTimeRules(deviceId, request) }

        result.onSuccess { rules ->
            _cachedRules.value = rules
            cacheTimestamp = System.currentTimeMillis()
            Timber.d("Screen time rules updated for device $deviceId")
        }

        result
    }

    /**
     * Delete screen time rules (parent only)
     */
    suspend fun deleteScreenTimeRules(deviceId: Int): NetworkResult<MessageResponse> =
        withContext(Dispatchers.IO) {
            val result = safeApiCall { apiService.deleteScreenTimeRules(deviceId) }

            result.onSuccess {
                _cachedRules.value = null
                cacheTimestamp = 0
                Timber.d("Screen time rules deleted for device $deviceId")
            }

            result
        }

    /**
     * Add or update a per-app time limit (parent only)
     */
    suspend fun addAppLimit(
        deviceId: Int,
        packageName: String,
        limitSeconds: Int
    ): NetworkResult<ScreenTimeRule> = withContext(Dispatchers.IO) {
        val result = safeApiCall {
            apiService.addAppLimit(deviceId, packageName, limitSeconds)
        }

        result.onSuccess { rules ->
            _cachedRules.value = rules
            cacheTimestamp = System.currentTimeMillis()
            Timber.d("App limit set for $packageName on device $deviceId: ${limitSeconds}s")
        }

        result
    }

    /**
     * Remove a per-app time limit (parent only)
     */
    suspend fun removeAppLimit(
        deviceId: Int,
        packageName: String
    ): NetworkResult<ScreenTimeRule> = withContext(Dispatchers.IO) {
        val result = safeApiCall {
            apiService.removeAppLimit(deviceId, packageName)
        }

        result.onSuccess { rules ->
            _cachedRules.value = rules
            cacheTimestamp = System.currentTimeMillis()
            Timber.d("App limit removed for $packageName on device $deviceId")
        }

        result
    }

    /**
     * Block an app (parent only)
     */
    suspend fun addBlockedApp(
        deviceId: Int,
        packageName: String
    ): NetworkResult<ScreenTimeRule> = withContext(Dispatchers.IO) {
        val result = safeApiCall {
            apiService.addBlockedApp(deviceId, packageName)
        }

        result.onSuccess { rules ->
            _cachedRules.value = rules
            cacheTimestamp = System.currentTimeMillis()
            Timber.d("App $packageName blocked on device $deviceId")
        }

        result
    }

    /**
     * Unblock an app (parent only)
     */
    suspend fun removeBlockedApp(
        deviceId: Int,
        packageName: String
    ): NetworkResult<ScreenTimeRule> = withContext(Dispatchers.IO) {
        val result = safeApiCall {
            apiService.removeBlockedApp(deviceId, packageName)
        }

        result.onSuccess { rules ->
            _cachedRules.value = rules
            cacheTimestamp = System.currentTimeMillis()
            Timber.d("App $packageName unblocked on device $deviceId")
        }

        result
    }

    // ==================== Enforcement Helper Methods ====================

    /**
     * Check if daily screen time limit is exceeded
     * Uses cached rules for efficiency
     *
     * @param usedTimeSeconds total screen time used today in seconds
     * @return true if limit is set (> 0) and exceeded. Limit of 0 or null means "no limit".
     */
    fun isDailyLimitExceeded(usedTimeSeconds: Int): Boolean {
        val rules = _cachedRules.value ?: run {
            Timber.d("isDailyLimitExceeded: No cached rules")
            return false
        }
        val result = rules.isDailyLimitExceeded(usedTimeSeconds)
        val limit = rules.dailyLimit
        when {
            limit == null -> Timber.d("isDailyLimitExceeded: No daily limit set (null)")
            limit <= 0 -> Timber.d("isDailyLimitExceeded: Daily limit is $limit (treated as no limit)")
            else -> Timber.d("isDailyLimitExceeded: used=${usedTimeSeconds}s, limit=${limit}s, " +
                "isActive=${rules.isActive}, exceeded=$result")
        }
        return result
    }

    /**
     * Check if app-specific time limit is exceeded
     *
     * @param packageName the app package name
     * @param usedTimeSeconds time used for this app today in seconds
     * @return true if app has a limit (> 0) and it's exceeded. Limit of 0 or null means "no limit".
     */
    fun isAppLimitExceeded(packageName: String, usedTimeSeconds: Int): Boolean {
        val rules = _cachedRules.value ?: run {
            Timber.d("isAppLimitExceeded: No cached rules")
            return false
        }
        val limit = rules.appLimits?.get(packageName)
        val result = rules.isAppLimitExceeded(packageName, usedTimeSeconds)
        when {
            limit == null -> { /* No limit for this app, don't log */ }
            limit <= 0 -> Timber.d("isAppLimitExceeded: app=$packageName has limit=$limit (treated as no limit)")
            else -> Timber.d("isAppLimitExceeded: app=$packageName, used=${usedTimeSeconds}s, limit=${limit}s, " +
                "isActive=${rules.isActive}, exceeded=$result")
        }
        return result
    }

    /**
     * Check if an app is completely blocked
     *
     * @param packageName the app package name
     * @return true if app is in the blocked list
     */
    fun isAppBlocked(packageName: String): Boolean {
        val rules = _cachedRules.value ?: return false
        return rules.isAppBlocked(packageName)
    }

    /**
     * Check if current time is within bedtime hours
     *
     * @return true if bedtime is enabled and current time is within bedtime range
     */
    fun isInBedtime(): Boolean {
        val rules = _cachedRules.value ?: return false
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return rules.isInBedtime(currentTime)
    }

    /**
     * Get remaining daily time in seconds
     *
     * @param usedTimeSeconds time already used today
     * @return remaining time in seconds, or null if no limit set
     */
    fun getRemainingDailyTime(usedTimeSeconds: Int): Int? {
        val rules = _cachedRules.value ?: return null
        return rules.getRemainingDailyTime(usedTimeSeconds)
    }

    /**
     * Get remaining time for a specific app in seconds
     *
     * @param packageName the app package name
     * @param usedTimeSeconds time already used for this app
     * @return remaining time in seconds, or null if no limit for this app
     */
    fun getRemainingAppTime(packageName: String, usedTimeSeconds: Int): Int? {
        val rules = _cachedRules.value ?: return null
        return rules.getRemainingAppTime(packageName, usedTimeSeconds)
    }

    /**
     * Check if rules enforcement is currently active
     */
    fun isEnforcementActive(): Boolean {
        val rules = _cachedRules.value
        val result = rules?.isActive == true
        Timber.d("isEnforcementActive: rules=${rules != null}, isActive=${rules?.isActive}, result=$result")
        return result
    }

    /**
     * Get the daily limit in seconds (if set)
     */
    fun getDailyLimit(): Int? {
        return _cachedRules.value?.dailyLimit
    }

    /**
     * Get app limit for a specific package (if set)
     */
    fun getAppLimit(packageName: String): Int? {
        return _cachedRules.value?.appLimits?.get(packageName)
    }

    /**
     * Get list of all blocked apps
     */
    fun getBlockedApps(): List<String> {
        return _cachedRules.value?.blockedApps ?: emptyList()
    }

    /**
     * Clear local cache (e.g., on logout)
     */
    fun clearCache() {
        _cachedRules.value = null
        cacheTimestamp = 0
    }
}
