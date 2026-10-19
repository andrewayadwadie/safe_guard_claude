package com.safeguard.parentalcontrol.presentation.children

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.account.AccountDeletionCoordinator
import com.safeguard.parentalcontrol.data.account.ChildReauth
import com.safeguard.parentalcontrol.data.account.DeletionState
import com.safeguard.parentalcontrol.data.model.AuthProvider
import com.safeguard.parentalcontrol.data.model.FamilyLink
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeletionError
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.GoogleSignInManager
import com.safeguard.parentalcontrol.util.GoogleSignInResult
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
    val successMessage: String? = null,
    // Permanent child-account deletion. Kept separate from the unlink ("stop monitoring") flow.
    /** The child whose delete dialog is open. */
    val deleteTarget: FamilyLink? = null,
    /** Must equal [FamilyLink.childName] exactly: no trimming, no case folding. */
    val deleteNameInput: String = "",
    /** The parent's password. In memory only: never persisted, never logged. */
    val deleteCredential: String = "",
    val parentIsGoogle: Boolean = false,
    val deleteCredentialError: String? = null,
    /** Rate-limit, network and unknown messages, shown inside the dialog (a Snackbar behind its scrim is hidden). */
    val deleteDialogNotice: String? = null,
    val deletingChildId: Int? = null,
    /** Incremented each time a child's account is deleted or found to be no longer linked. */
    val childrenChangedCount: Int = 0
) {
    val canDelete: Boolean
        get() = deleteTarget != null &&
            deleteNameInput == deleteTarget.childName &&
            (parentIsGoogle || deleteCredential.isNotEmpty()) &&
            deletingChildId == null

    override fun toString(): String =
        "ChildrenUiState(children=${children.size}, isLoading=$isLoading, deleteTarget=${deleteTarget != null}, " +
            "deleteCredential=<redacted>, deletingChildId=${deletingChildId != null})"
}

@HiltViewModel
class ChildrenViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val authRepository: AuthRepository,
    private val accountDeletionCoordinator: AccountDeletionCoordinator,
    private val googleSignInManager: GoogleSignInManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChildrenUiState())
    val uiState: StateFlow<ChildrenUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        loadChildren()
        loadParentProvider()
        observeChildDeletions()
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

    // ==================== Permanent child-account deletion ====================

    /** A Google parent confirms with a fresh Google sign-in instead of a password. Best effort. */
    private fun loadParentProvider() {
        viewModelScope.launch {
            val result = authRepository.getCurrentUser()
            if (result is NetworkResult.Success && result.data.authProvider == AuthProvider.GOOGLE) {
                _uiState.update { it.copy(parentIsGoogle = true) }
            }
        }
    }

    fun openDeleteDialog(link: FamilyLink) {
        _uiState.update {
            it.copy(
                deleteTarget = link,
                deleteNameInput = "",
                deleteCredential = "",
                deleteCredentialError = null,
                deleteDialogNotice = null
            )
        }
    }

    fun closeDeleteDialog() {
        if (_uiState.value.deletingChildId != null) return
        clearDeleteDialog()
    }

    fun onDeleteNameChange(value: String) {
        _uiState.update { it.copy(deleteNameInput = value) }
    }

    fun onDeleteCredentialChange(value: String) {
        _uiState.update { it.copy(deleteCredential = value, deleteCredentialError = null, deleteDialogNotice = null) }
    }

    /** Password parent: re-authenticate with the server, then delete the child's account. */
    fun confirmDeletePassword() {
        val state = _uiState.value
        val target = state.deleteTarget ?: return
        if (!state.canDelete || state.parentIsGoogle) return
        _uiState.update { it.copy(deletingChildId = target.childId, deleteDialogNotice = null) }
        accountDeletionCoordinator.deleteChildAccount(target.childId, ChildReauth.Password(state.deleteCredential))
    }

    /**
     * Google parent: a fresh Google account chooser is the re-authentication. Cancelling keeps the
     * dialog open. A different account is rejected by the repository (nothing is deleted).
     */
    fun confirmDeleteWithGoogle(activityContext: Context) {
        val state = _uiState.value
        val target = state.deleteTarget ?: return
        if (!state.canDelete) return

        viewModelScope.launch {
            when (val result = googleSignInManager.signIn(activityContext)) {
                is GoogleSignInResult.Success -> {
                    _uiState.update { it.copy(deletingChildId = target.childId, deleteDialogNotice = null) }
                    accountDeletionCoordinator.deleteChildAccount(target.childId, ChildReauth.Google(result.idToken))
                }
                GoogleSignInResult.Cancelled -> Unit
                else -> _uiState.update {
                    it.copy(deleteDialogNotice = getString(R.string.auth_error_google_signin_failed))
                }
            }
        }
    }

    private fun observeChildDeletions() {
        viewModelScope.launch {
            accountDeletionCoordinator.childStates.collect { states ->
                states.forEach { (childId, deletion) ->
                    when (deletion) {
                        DeletionState.Succeeded -> {
                            onChildDeleted(childId)
                            accountDeletionCoordinator.acknowledgeChild(childId)
                        }
                        is DeletionState.Failed -> {
                            onChildDeletionFailed(childId, deletion.error)
                            accountDeletionCoordinator.acknowledgeChild(childId)
                        }
                        DeletionState.InFlight, DeletionState.Idle -> Unit
                    }
                }
                val openId = _uiState.value.deleteTarget?.childId
                _uiState.update {
                    it.copy(deletingChildId = openId?.takeIf { id -> states[id] is DeletionState.InFlight })
                }
            }
        }
    }

    private fun nameOf(childId: Int): String =
        _uiState.value.children.firstOrNull { it.childId == childId }?.childName.orEmpty()

    private fun onChildDeleted(childId: Int) {
        val name = nameOf(childId)
        _uiState.update {
            it.copy(
                children = it.children.filter { child -> child.childId != childId },
                successMessage = getString(R.string.children_delete_success, name),
                childrenChangedCount = it.childrenChangedCount + 1
            )
        }
        closeDialogIfFor(childId)
    }

    private fun onChildDeletionFailed(childId: Int, error: DeletionError) {
        when (error) {
            DeletionError.ChildNotFound -> {
                val name = nameOf(childId)
                closeDialogIfFor(childId)
                loadChildren()
                _uiState.update {
                    it.copy(
                        successMessage = getString(R.string.children_delete_not_linked, name),
                        childrenChangedCount = it.childrenChangedCount + 1
                    )
                }
            }
            DeletionError.WrongPassword -> _uiState.update {
                it.copy(
                    deleteCredentialError = getString(R.string.delete_account_error_wrong_password),
                    deletingChildId = null
                )
            }
            DeletionError.AccountMismatch -> {
                val message = if (_uiState.value.parentIsGoogle) {
                    R.string.children_delete_google_mismatch
                } else {
                    R.string.children_delete_account_mismatch
                }
                closeDialogIfFor(childId)
                _uiState.update { it.copy(error = getString(message)) }
            }
            // The interceptor has already cleared the tokens and routes to Login.
            DeletionError.SessionOver -> closeDialogIfFor(childId)
            is DeletionError.RateLimited -> showDialogNotice(R.string.delete_account_error_rate_limited)
            DeletionError.Network -> showDialogNotice(R.string.delete_account_error_network)
            else -> showDialogNotice(R.string.delete_account_error_unknown)
        }
    }

    private fun showDialogNotice(resId: Int) {
        _uiState.update { it.copy(deleteDialogNotice = getString(resId), deletingChildId = null) }
    }

    private fun closeDialogIfFor(childId: Int) {
        if (_uiState.value.deleteTarget?.childId == childId) clearDeleteDialog()
    }

    private fun clearDeleteDialog() {
        _uiState.update {
            it.copy(
                deleteTarget = null,
                deleteNameInput = "",
                deleteCredential = "",
                deleteCredentialError = null,
                deleteDialogNotice = null,
                deletingChildId = null
            )
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
