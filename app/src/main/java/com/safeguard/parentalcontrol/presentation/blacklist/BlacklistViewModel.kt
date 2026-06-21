package com.safeguard.parentalcontrol.presentation.blacklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.ContentFilterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for blacklist management screen
 */
data class BlacklistUiState(
    val isLoading: Boolean = false,
    val domains: List<String> = emptyList(),
    val error: String? = null,
    val successMessage: String? = null
)

/**
 * ViewModel for managing website blacklist
 */
@HiltViewModel
class BlacklistViewModel @Inject constructor(
    private val contentFilterRepository: ContentFilterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BlacklistUiState())
    val uiState: StateFlow<BlacklistUiState> = _uiState.asStateFlow()

    /**
     * Load blacklist for a device
     */
    fun loadBlacklist(deviceId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = contentFilterRepository.getBlacklist(deviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            domains = result.data.blockedDomains
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Add domain to blacklist
     */
    fun addDomain(deviceId: Int, domain: String) {
        val trimmedDomain = domain.trim()

        // Validate domain
        if (trimmedDomain.isBlank()) {
            _uiState.update { it.copy(error = "Domain cannot be empty") }
            return
        }

        if (trimmedDomain.length < 3) {
            _uiState.update { it.copy(error = "Domain is too short") }
            return
        }

        // Check if already in list
        if (_uiState.value.domains.any { it.equals(trimmedDomain, ignoreCase = true) }) {
            _uiState.update { it.copy(error = "Domain already in blacklist") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = contentFilterRepository.addToBlacklist(deviceId, trimmedDomain)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            domains = result.data.blockedDomains,
                            successMessage = "Website added to blacklist"
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Remove domain from blacklist
     */
    fun removeDomain(deviceId: Int, domain: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = contentFilterRepository.removeFromBlacklist(deviceId, domain)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            domains = result.data.blockedDomains,
                            successMessage = "Website removed from blacklist"
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Clear all domains from blacklist
     */
    fun clearBlacklist(deviceId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = contentFilterRepository.clearBlacklist(deviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            domains = emptyList(),
                            successMessage = "Blacklist cleared"
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Clear error and success messages
     */
    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
