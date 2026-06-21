package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for content filter and blacklist operations
 */
@Singleton
class ContentFilterRepository @Inject constructor(
    private val apiService: ApiService
) {
    /**
     * Get content filter settings for a device
     */
    suspend fun getContentFilter(deviceId: Int): NetworkResult<ContentFilter> =
        withContext(Dispatchers.IO) {
            safeApiCall { apiService.getContentFilter(deviceId) }
        }

    /**
     * Create content filter settings for a device
     */
    suspend fun createContentFilter(
        deviceId: Int,
        update: ContentFilterUpdate
    ): NetworkResult<ContentFilter> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.createContentFilter(deviceId, update) }

        result.onSuccess {
            Timber.d("Content filter created for device $deviceId")
        }

        result
    }

    /**
     * Update content filter settings
     */
    suspend fun updateContentFilter(
        deviceId: Int,
        update: ContentFilterUpdate
    ): NetworkResult<ContentFilter> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.updateContentFilter(deviceId, update) }

        result.onSuccess {
            Timber.d("Content filter updated for device $deviceId")
        }

        result
    }

    /**
     * Get blacklist for a device
     */
    suspend fun getBlacklist(deviceId: Int): NetworkResult<BlacklistResponse> =
        withContext(Dispatchers.IO) {
            safeApiCall { apiService.getBlacklist(deviceId) }
        }

    /**
     * Add domain to blacklist
     */
    suspend fun addToBlacklist(
        deviceId: Int,
        domain: String
    ): NetworkResult<BlacklistResponse> = withContext(Dispatchers.IO) {
        val request = BlacklistDomainRequest(domain = domain.lowercase().trim())
        val result = safeApiCall { apiService.addToBlacklist(deviceId, request) }

        result.onSuccess {
            Timber.d("Domain '$domain' added to blacklist for device $deviceId")
        }

        result
    }

    /**
     * Remove domain from blacklist
     */
    suspend fun removeFromBlacklist(
        deviceId: Int,
        domain: String
    ): NetworkResult<BlacklistResponse> = withContext(Dispatchers.IO) {
        val request = BlacklistDomainRequest(domain = domain.lowercase().trim())
        val result = safeApiCall { apiService.removeFromBlacklist(deviceId, request) }

        result.onSuccess {
            Timber.d("Domain '$domain' removed from blacklist for device $deviceId")
        }

        result
    }

    /**
     * Clear entire blacklist
     */
    suspend fun clearBlacklist(deviceId: Int): NetworkResult<MessageResponse> =
        withContext(Dispatchers.IO) {
            val result = safeApiCall { apiService.clearBlacklist(deviceId) }

            result.onSuccess {
                Timber.d("Blacklist cleared for device $deviceId")
            }

            result
        }
}
