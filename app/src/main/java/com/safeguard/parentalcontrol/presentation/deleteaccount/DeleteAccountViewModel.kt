package com.safeguard.parentalcontrol.presentation.deleteaccount

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.account.AccountDeletionCoordinator
import com.safeguard.parentalcontrol.data.account.DeletionState
import com.safeguard.parentalcontrol.data.model.AuthProvider
import com.safeguard.parentalcontrol.data.model.DeleteAccountRequest
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeletionError
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.ImageBlurManager
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the delete-account screen. The request itself is owned by [AccountDeletionCoordinator]
 * (application scope), so leaving the screen or rotating never cancels it. The credential lives in
 * [DeleteAccountUiState] memory only: it is never put in SavedStateHandle and never logged.
 */
@HiltViewModel
class DeleteAccountViewModel internal constructor(
    private val coordinator: AccountDeletionCoordinator,
    private val authRepository: AuthRepository,
    private val familyRepository: FamilyRepository,
    private val preferencesManager: PreferencesManager,
    private val imageBlurManager: ImageBlurManager,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    @Inject
    constructor(
        coordinator: AccountDeletionCoordinator,
        authRepository: AuthRepository,
        familyRepository: FamilyRepository,
        preferencesManager: PreferencesManager,
        imageBlurManager: ImageBlurManager,
        @ApplicationContext context: Context
    ) : this(
        coordinator, authRepository, familyRepository, preferencesManager, imageBlurManager,
        context, Dispatchers.IO
    )

    private val _uiState = MutableStateFlow(DeleteAccountUiState())
    val uiState: StateFlow<DeleteAccountUiState> = _uiState.asStateFlow()

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    init {
        // A finished outcome left over from an earlier deletion in this process must not
        // navigate someone who has since signed in again.
        coordinator.attachSelf()

        val isParent = preferencesManager.isParent
        _uiState.update { it.copy(role = if (isParent) DeleteRole.PARENT else DeleteRole.CHILD) }

        loadAccountDetails(isParent)
        observeCoordinator()
    }

    private fun loadAccountDetails(isParent: Boolean) {
        // Pre-selects DELETE mode for a Google account. Best effort: the manual switch link is
        // always available, and the server stays the only judge of the credential.
        viewModelScope.launch {
            val result = authRepository.getCurrentUser()
            if (result is NetworkResult.Success && result.data.authProvider == AuthProvider.GOOGLE) {
                _uiState.update { if (it.credential.isEmpty()) it.copy(mode = ConfirmMode.GOOGLE_DELETE) else it }
            }
        }

        if (isParent) {
            viewModelScope.launch {
                val result = familyRepository.getLinkedChildren()
                if (result is NetworkResult.Success) {
                    _uiState.update { it.copy(linkedChildCount = result.data.size) }
                }
            }
        } else {
            viewModelScope.launch {
                val hasOriginals = withContext(ioDispatcher) {
                    runCatching { imageBlurManager.getPendingReviews().isNotEmpty() }.getOrDefault(false)
                }
                _uiState.update { it.copy(hasReviewOriginals = hasOriginals) }
            }
        }
    }

    private fun observeCoordinator() {
        viewModelScope.launch {
            coordinator.selfState.collect { state ->
                when (state) {
                    DeletionState.Idle -> _uiState.update { it.copy(isLoading = false) }
                    DeletionState.InFlight ->
                        _uiState.update { it.copy(isLoading = true, showFinalConfirm = false) }
                    DeletionState.Succeeded -> {
                        _uiState.update { it.copy(isLoading = false, deleted = true) }
                        coordinator.acknowledgeSelf()
                    }
                    is DeletionState.Failed -> {
                        applyFailure(state.error)
                        coordinator.acknowledgeSelf()
                    }
                }
            }
        }
    }

    private fun applyFailure(error: DeletionError) {
        when (error) {
            // The only case that may say "wrong password", and it goes inline on the field.
            DeletionError.WrongPassword -> {
                val resId = if (_uiState.value.mode == ConfirmMode.PASSWORD) {
                    R.string.delete_account_error_wrong_password
                } else {
                    R.string.delete_account_error_wrong_confirmation
                }
                _uiState.update { it.copy(isLoading = false, credentialError = getString(resId), error = null) }
            }
            DeletionError.SupervisedChild -> {
                val message = getString(R.string.delete_account_error_supervised)
                _uiState.update {
                    it.copy(isLoading = false, error = message, errorRetryable = false, blockingNotice = message)
                }
            }
            is DeletionError.RateLimited -> {
                val seconds = error.retryAfterSeconds
                val message = if (seconds != null) {
                    getString(R.string.delete_account_error_rate_limited_seconds, seconds)
                } else {
                    getString(R.string.delete_account_error_rate_limited)
                }
                _uiState.update {
                    it.copy(isLoading = false, error = message, errorRetryable = false, blockingNotice = message)
                }
            }
            DeletionError.Network -> _uiState.update {
                it.copy(isLoading = false, error = getString(R.string.delete_account_error_network), errorRetryable = true)
            }
            // The interceptor has already cleared the tokens and routes to Login. Never claim "deleted".
            DeletionError.SessionOver -> _uiState.update {
                it.copy(isLoading = false, error = getString(R.string.login_session_ended), errorRetryable = false)
            }
            else -> _uiState.update {
                it.copy(isLoading = false, error = getString(R.string.delete_account_error_unknown), errorRetryable = true)
            }
        }
    }

    fun onCredentialChange(value: String) {
        _uiState.update { it.copy(credential = value, credentialError = null, blockingNotice = null) }
    }

    fun toggleVisibility() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun switchMode() {
        _uiState.update {
            it.copy(
                mode = if (it.mode == ConfirmMode.PASSWORD) ConfirmMode.GOOGLE_DELETE else ConfirmMode.PASSWORD,
                credential = "",
                credentialError = null,
                blockingNotice = null,
                passwordVisible = false
            )
        }
    }

    /** First step: opens the final confirmation. Nothing is sent yet. */
    fun onDeleteClicked() {
        if (!_uiState.value.canSubmit) return
        _uiState.update { it.copy(showFinalConfirm = true) }
    }

    fun dismissFinalConfirm() {
        _uiState.update { it.copy(showFinalConfirm = false) }
    }

    /** Second step: the only place a request is started. Re-checks the rules (FR-007). */
    fun onFinalConfirm() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(showFinalConfirm = false) }
        send(state)
    }

    /** Sends again after a network or unknown error, without asking a second time. */
    fun retry() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(error = null, errorRetryable = false) }
        send(state)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, errorRetryable = false) }
    }

    private fun send(state: DeleteAccountUiState) {
        _uiState.update { it.copy(isLoading = true) }
        val request = when (state.mode) {
            ConfirmMode.PASSWORD -> DeleteAccountRequest.forPassword(state.credential)
            ConfirmMode.GOOGLE_DELETE -> DeleteAccountRequest.forGoogle()
        }
        coordinator.deleteOwnAccount(request)
    }
}
