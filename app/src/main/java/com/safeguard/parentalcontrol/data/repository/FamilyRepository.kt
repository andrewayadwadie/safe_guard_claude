package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.model.FamilyLink
import com.safeguard.parentalcontrol.data.model.FamilyLinkCreateRequest
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for family link operations (parent-child relationships)
 */
@Singleton
class FamilyRepository @Inject constructor(
    private val apiService: ApiService
) {
    /**
     * Get all children linked to this parent
     */
    suspend fun getLinkedChildren(): NetworkResult<List<FamilyLink>> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.getLinkedChildren() }

        result.onSuccess { children ->
            Timber.d("Loaded ${children.size} linked children")
        }

        result
    }

    /**
     * Link a child account by email
     */
    suspend fun addChild(childEmail: String): NetworkResult<FamilyLink> = withContext(Dispatchers.IO) {
        val request = FamilyLinkCreateRequest(childEmail = childEmail)

        val result = safeApiCall { apiService.createFamilyLink(request) }

        result.onSuccess { link ->
            Timber.d("Successfully linked child: ${link.childName}")
        }

        result
    }

    /**
     * Remove a family link with a child
     */
    suspend fun removeChild(childId: Int): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.removeFamilyLink(childId) }

        result.onSuccess {
            Timber.d("Successfully removed child link: $childId")
        }

        result.map { }
    }

    /**
     * Get details of a specific family link
     */
    suspend fun getFamilyLink(childId: Int): NetworkResult<FamilyLink> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getFamilyLink(childId) }
    }
}
