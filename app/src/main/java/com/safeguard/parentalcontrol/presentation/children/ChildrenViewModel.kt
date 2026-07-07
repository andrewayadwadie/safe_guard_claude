package com.safeguard.parentalcontrol.presentation.children

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.FamilyLink
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChildrenUiState(
    val children: List<FamilyLink> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val isAddingChild: Boolean = false,
    val addChildError: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class ChildrenViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChildrenUiState())
    val uiState: StateFlow<ChildrenUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        loadChildren()
    }

    /**
     * Load linked children from the server
     */
    fun loadChildren() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = familyRepository.getLinkedChildren()) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            children = result.data,
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
     * Show the add child dialog
     */
    fun showAddChildDialog() {
        _uiState.update { it.copy(showAddDialog = true, addChildError = null) }
    }

    /**
     * Hide the add child dialog
     */
    fun hideAddChildDialog() {
        _uiState.update { it.copy(showAddDialog = false, addChildError = null) }
    }

    /**
     * Add a child by redeeming the pairing code shown on the child's device.
     */
    fun addChild(pairingCode: String) {
        val code = pairingCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(addChildError = getString(R.string.children_error_enter_code)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAddingChild = true, addChildError = null) }

            when (val result = familyRepository.addChild(code)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            children = it.children + result.data,
                            isAddingChild = false,
                            showAddDialog = false,
                            successMessage = getString(R.string.children_msg_linked, result.data.childName)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAddingChild = false,
                            addChildError = result.message
                        )
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Remove a child link
     */
    fun removeChild(childId: Int, childName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            when (val result = familyRepository.removeChild(childId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            children = it.children.filter { child -> child.childId != childId },
                            isLoading = false,
                            successMessage = getString(R.string.children_msg_removed, childName)
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
