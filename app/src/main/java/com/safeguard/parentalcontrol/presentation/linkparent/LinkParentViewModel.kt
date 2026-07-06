package com.safeguard.parentalcontrol.presentation.linkparent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

data class LinkParentUiState(
    val isLoading: Boolean = false,
    val code: String? = null,
    val expiresAt: Date? = null,
    val error: String? = null
)

/**
 * Child-side: generate a short-lived pairing code that a parent enters on their
 * own device to link. Proves the parent physically holds this child's phone.
 */
@HiltViewModel
class LinkParentViewModel @Inject constructor(
    private val familyRepository: FamilyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkParentUiState())
    val uiState: StateFlow<LinkParentUiState> = _uiState.asStateFlow()

    fun generateCode() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = familyRepository.generatePairingCode()) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            code = result.data.code,
                            expiresAt = result.data.expiresAt,
                            error = null
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                else -> {}
            }
        }
    }
}
