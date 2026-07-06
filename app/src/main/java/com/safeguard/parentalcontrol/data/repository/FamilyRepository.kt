package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.model.FamilyLink
import com.safeguard.parentalcontrol.data.model.FamilyLinkCreateRequest
import com.safeguard.parentalcontrol.data.model.LinkedParent
import com.safeguard.parentalcontrol.data.model.PairingCodeResponse
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.PreferencesManager
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
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager
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

    /** Returns the child's linked parents. */
    suspend fun getLinkedParents(): NetworkResult<List<LinkedParent>> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getLinkedParents() }
    }

    /**
     * Refreshes the cached hasLinkedParent flag from the backend.
     * - success + non-empty -> flag = true
     * - success + empty     -> flag = false (confirmed unpair)
     * - error               -> flag unchanged (tamper-resilient)
     * Returns the effective cached flag after the attempt.
     */
    suspend fun refreshLinkedParentStatus(): Boolean = withContext(Dispatchers.IO) {
        when (val result = getLinkedParents()) {
            is NetworkResult.Success -> {
                val paired = result.data.isNotEmpty()
                preferencesManager.hasLinkedParent = paired
                Timber.d("refreshLinkedParentStatus: parents=${result.data.size}, paired=$paired")
                paired
            }
            is NetworkResult.Error -> {
                Timber.w("refreshLinkedParentStatus: network error, keeping cached=${preferencesManager.hasLinkedParent}")
                preferencesManager.hasLinkedParent
            }
            else -> preferencesManager.hasLinkedParent
        }
    }

    /**
     * Link a child account by redeeming the pairing code shown on the child's device.
     */
    suspend fun addChild(pairingCode: String): NetworkResult<FamilyLink> = withContext(Dispatchers.IO) {
        val request = FamilyLinkCreateRequest(pairingCode = pairingCode.trim().uppercase())

        val result = safeApiCall { apiService.createFamilyLink(request) }

        result.onSuccess { link ->
            Timber.d("Successfully linked child: ${link.childName}")
        }

        result
    }

    /**
     * Generate a pairing code for THIS device's child account (child only).
     * The returned code is displayed for a parent to enter on their device.
     */
    suspend fun generatePairingCode(): NetworkResult<PairingCodeResponse> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.createPairingCode() }

        result.onSuccess {
            Timber.d("Pairing code generated (expires ${it.expiresAt})")
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
